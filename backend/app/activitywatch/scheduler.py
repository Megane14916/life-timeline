"""FastAPI-lifespan scheduler and safe collector status for ActivityWatch."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal, Protocol

from sqlalchemy.orm import Session, sessionmaker

from app.activitywatch.client import ActivityWatchClient
from app.activitywatch.errors import ActivityWatchError
from app.activitywatch.importer import (
    ActivityWatchImporter,
    ActivityWatchImportError,
    ActivityWatchImportResult,
    ActivityWatchImportSettings,
)
from app.repositories import ActivityWatchRepository

ACTIVITYWATCH_ENABLED_ENV = "LIFE_TIMELINE_ACTIVITYWATCH_ENABLED"
ACTIVITYWATCH_PRIVACY_MODE_ENV = "LIFE_TIMELINE_ACTIVITYWATCH_PRIVACY_MODE"
SCHEDULER_INITIAL_DELAY_SECONDS = 30
SCHEDULER_INTERVAL_SECONDS = 15 * 60
TRANSIENT_BACKOFF_INITIAL_SECONDS = 60
TRANSIENT_BACKOFF_MAX_SECONDS = 15 * 60
logger = logging.getLogger(__name__)

CollectorState = Literal["disabled", "idle", "queued", "running", "needs_attention"]


@dataclass(frozen=True, slots=True)
class ActivityWatchRuntimeConfig:
    enabled: bool
    privacy_mode: str = "app_only"
    configuration_error: str | None = None


@dataclass(frozen=True, slots=True)
class ActivityWatchCollectorStatus:
    enabled: bool
    detail_mode: str
    state: CollectorState
    last_result: str | None
    last_attempt_at: str | None
    last_success_at: str | None
    completed_through: str | None
    next_attempt_at: str | None
    web_details_available: bool


class ActivityWatchRun(Protocol):
    """Minimal run surface used by the scheduler and fake-clock tests."""

    def run(self) -> ActivityWatchImportResult: ...


def load_runtime_config(environ: Mapping[str, str] | None = None) -> ActivityWatchRuntimeConfig:
    """Load opt-in settings without making a network request."""

    values = os.environ if environ is None else environ
    enabled_value = values.get(ACTIVITYWATCH_ENABLED_ENV, "false").strip().casefold()
    if enabled_value not in {"true", "false"}:
        return ActivityWatchRuntimeConfig(False, "app_only", "invalid_config")
    privacy_mode = values.get(ACTIVITYWATCH_PRIVACY_MODE_ENV, "app_only").strip()
    if privacy_mode not in {"app_only", "titles", "web"}:
        return ActivityWatchRuntimeConfig(enabled_value == "true", "app_only", "invalid_config")
    return ActivityWatchRuntimeConfig(enabled_value == "true", privacy_mode)


def _format_timestamp(timestamp_ms: int | None) -> str | None:
    if timestamp_ms is None:
        return None
    return (
        datetime.fromtimestamp(timestamp_ms / 1000, tz=UTC)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


class ActivityWatchImportScheduler:
    """Run the importer once at startup and then on a bounded interval."""

    def __init__(
        self,
        *,
        importer_factory: Callable[[], ActivityWatchRun] | None = None,
        session_factory: sessionmaker[Session] | None = None,
        enabled: bool = False,
        detail_mode: str = "app_only",
        configuration_error: str | None = None,
        client: ActivityWatchClient | None = None,
        clock_ms: Callable[[], int] | None = None,
        initial_delay_seconds: float = SCHEDULER_INITIAL_DELAY_SECONDS,
        interval_seconds: float = SCHEDULER_INTERVAL_SECONDS,
    ) -> None:
        if initial_delay_seconds < 0 or interval_seconds <= 0:
            raise ValueError("scheduler delays must be non-negative and positive.")
        self._importer_factory = importer_factory
        self._session_factory = session_factory
        self._enabled = enabled
        self._detail_mode = detail_mode
        self._configuration_error = configuration_error
        self._client = client
        self._clock_ms = clock_ms or (lambda: int(time.time() * 1000))
        self._initial_delay_seconds = initial_delay_seconds
        self._interval_seconds = interval_seconds
        self._loop_task: asyncio.Task[None] | None = None
        self._active_task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()
        self._stopping = False
        self._failure_count = 0
        self._needs_attention = configuration_error is not None
        self._last_result: str | None = configuration_error
        self._last_attempt_at_ms: int | None = None
        self._last_success_at_ms: int | None = None
        self._next_attempt_at_ms: int | None = None
        self._web_details_available = False

    @classmethod
    def from_defaults(
        cls,
        session_factory: sessionmaker[Session],
        *,
        runtime: ActivityWatchRuntimeConfig,
    ) -> ActivityWatchImportScheduler:
        client = (
            ActivityWatchClient()
            if runtime.enabled and runtime.configuration_error is None
            else None
        )
        importer_factory: Callable[[], ActivityWatchRun] | None
        if client is None:
            importer_factory = None
        else:

            def make_importer() -> ActivityWatchRun:
                return ActivityWatchImporter(
                    client,
                    session_factory,
                    settings=ActivityWatchImportSettings(privacy_mode=runtime.privacy_mode),
                )

            importer_factory = make_importer
        return cls(
            importer_factory=importer_factory,
            session_factory=session_factory,
            enabled=runtime.enabled and runtime.configuration_error is None,
            detail_mode=runtime.privacy_mode,
            configuration_error=runtime.configuration_error,
            client=client,
        )

    @property
    def enabled(self) -> bool:
        return self._enabled

    async def start(self) -> None:
        if not self._enabled or self._loop_task is not None:
            return
        self._stopping = False
        self._stop_event.clear()
        self._loop_task = asyncio.create_task(self._loop(), name="activitywatch-import-scheduler")

    async def stop(self) -> None:
        self._stopping = True
        self._stop_event.set()
        if self._loop_task is not None:
            self._loop_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._loop_task
            self._loop_task = None
        if self._active_task is not None:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._active_task
            self._active_task = None
        if self._client is not None:
            self._client.close()
            self._client = None

    async def trigger_manual(self) -> bool:
        """Queue one unique manual run and return whether it was accepted."""

        if not self._enabled or self._stopping or self._importer_factory is None:
            return False
        if self._active_task is not None and not self._active_task.done():
            return False
        self._active_task = asyncio.create_task(
            self._execute_once(), name="activitywatch-manual-import"
        )
        return True

    async def run_once(self) -> None:
        """Run one scheduled import while preserving the unique-run boundary."""

        if not self._enabled or self._stopping or self._importer_factory is None:
            return
        if self._active_task is not None and not self._active_task.done():
            return
        self._active_task = asyncio.create_task(
            self._execute_once(), name="activitywatch-scheduled-import"
        )
        await asyncio.shield(self._active_task)

    def status(self) -> ActivityWatchCollectorStatus:
        state_model = None
        if self._session_factory is not None:
            try:
                with self._session_factory() as session:
                    state_model = ActivityWatchRepository(session).get_latest_import_state()
            except Exception:
                if self._enabled:
                    self._needs_attention = True
                    self._last_result = "invalid_config"
        if not self._enabled:
            state: CollectorState = "needs_attention" if self._configuration_error else "disabled"
        elif self._active_task is not None and not self._active_task.done():
            state = "running"
        elif self._needs_attention:
            state = "needs_attention"
        else:
            state = "idle"
        last_result = state_model.last_result_code if state_model is not None else self._last_result
        last_attempt = (
            state_model.last_attempt_at_ms if state_model is not None else self._last_attempt_at_ms
        )
        last_success = (
            state_model.last_success_at_ms if state_model is not None else self._last_success_at_ms
        )
        completed = state_model.completed_through_ms if state_model is not None else None
        next_attempt = (
            self._next_attempt_at_ms
            if self._next_attempt_at_ms is not None
            else state_model.next_eligible_at_ms
            if state_model is not None
            else None
        )
        detail_mode = state_model.privacy_mode if state_model is not None else self._detail_mode
        return ActivityWatchCollectorStatus(
            self._enabled,
            detail_mode,
            state,
            last_result,
            _format_timestamp(last_attempt),
            _format_timestamp(last_success),
            _format_timestamp(completed),
            _format_timestamp(next_attempt),
            self._web_details_available,
        )

    async def _loop(self) -> None:
        await self._sleep_or_stop(self._initial_delay_seconds)
        while not self._stopping:
            await self.run_once()
            delay = self._next_delay_seconds()
            await self._sleep_or_stop(delay)

    async def _sleep_or_stop(self, seconds: float) -> None:
        if self._stop_event.is_set():
            return
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(self._stop_event.wait(), timeout=seconds)

    async def _execute_once(self) -> None:
        if self._importer_factory is None:
            return
        self._last_attempt_at_ms = self._clock_ms()
        self._needs_attention = False
        try:
            result = await asyncio.to_thread(self._importer_factory().run)
        except ActivityWatchImportError as error:
            logger.warning(
                "ActivityWatch import failed: result=%s retryable=%s",
                error.result_code,
                error.retryable,
            )
            self._handle_failure(error.result_code, error.retryable)
        except ActivityWatchError as error:
            logger.warning(
                "ActivityWatch import failed: result=%s retryable=%s",
                error.code,
                error.retryable,
            )
            self._handle_failure(error.code, error.retryable)
        except Exception:
            logger.warning("ActivityWatch import failed: result=protocol_error retryable=false")
            self._handle_failure("protocol_error", False)
        else:
            logger.info("ActivityWatch import succeeded: result=success")
            self._handle_success(result)

    def _handle_success(self, result: ActivityWatchImportResult) -> None:
        self._failure_count = 0
        self._needs_attention = False
        self._last_result = "success"
        self._last_success_at_ms = self._clock_ms()
        self._next_attempt_at_ms = None
        self._web_details_available = result.web_details_available

    def _handle_failure(self, result_code: str, retryable: bool) -> None:
        self._failure_count = self._failure_count + 1 if retryable else 0
        self._last_result = result_code
        if result_code == "lease_busy":
            self._needs_attention = False
            self._next_attempt_at_ms = None
            return
        self._needs_attention = not retryable
        exponent = max(self._failure_count - 1, 0)
        delay = min(
            TRANSIENT_BACKOFF_MAX_SECONDS,
            TRANSIENT_BACKOFF_INITIAL_SECONDS * 2**exponent,
        )
        self._next_attempt_at_ms = self._clock_ms() + int(delay * 1000)

    def _next_delay_seconds(self) -> float:
        if self._failure_count <= 0:
            return self._interval_seconds
        exponent = max(self._failure_count - 1, 0)
        return float(
            min(
                self._interval_seconds,
                TRANSIENT_BACKOFF_MAX_SECONDS,
                TRANSIENT_BACKOFF_INITIAL_SECONDS * 2**exponent,
            )
        )


__all__ = [
    "ACTIVITYWATCH_ENABLED_ENV",
    "ACTIVITYWATCH_PRIVACY_MODE_ENV",
    "SCHEDULER_INITIAL_DELAY_SECONDS",
    "SCHEDULER_INTERVAL_SECONDS",
    "TRANSIENT_BACKOFF_INITIAL_SECONDS",
    "TRANSIENT_BACKOFF_MAX_SECONDS",
    "ActivityWatchCollectorStatus",
    "ActivityWatchImportScheduler",
    "ActivityWatchRun",
    "ActivityWatchRuntimeConfig",
    "CollectorState",
    "load_runtime_config",
]
