"""Bounded UTC-day import orchestration for ActivityWatch."""

from __future__ import annotations

import json
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime
from typing import Protocol
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy.orm import Session, sessionmaker

from app.activitywatch.client import ACTIVITYWATCH_STABLE_RELEASE, EVENT_LIMIT
from app.activitywatch.discovery import ActivityWatchDiscovery
from app.activitywatch.errors import ActivityWatchError
from app.activitywatch.normalizer import (
    ActivityWatchBucketEvent,
    NormalizationDiagnostics,
    NormalizedActivityWatch,
    normalize_activitywatch,
)
from app.activitywatch.privacy import validate_privacy_mode
from app.activitywatch.schemas import ActivityWatchBucket, ActivityWatchEvent
from app.db import session_scope
from app.repositories import (
    ActivityWatchLeaseLostError,
    ActivityWatchReplaceResult,
    ActivityWatchRepository,
    ActivityWatchRepositoryError,
    source_key_for,
)

UTC_DAY_MS = 86_400_000
CONTEXT_MS = 5 * 60 * 1000
MIN_SPLIT_MS = CONTEXT_MS
SETTLEMENT_LAG_MS = 2 * 60 * 1000
INITIAL_LOOKBACK_DAYS = 7
MAX_RUN_DAYS = 8
MAX_RUN_MS = 8 * 60 * 1000
DEFAULT_TIMEZONE = "UTC"


class ActivityWatchEventClient(Protocol):
    """The minimal client surface required by bounded event fetching."""

    def get_events(
        self,
        bucket_id: str,
        *,
        start: datetime | str,
        end: datetime | str,
        limit: int = EVENT_LIMIT,
    ) -> tuple[ActivityWatchEvent, ...]: ...


class ActivityWatchImportClient(ActivityWatchEventClient, Protocol):
    """The read-only client surface required by the importer."""

    def discover(self, *, expected_hostname: str | None = None) -> ActivityWatchDiscovery: ...


class ActivityWatchImportError(RuntimeError):
    """Safe import failure with a stable result code."""

    def __init__(self, result_code: str, *, retryable: bool = False) -> None:
        self.result_code = result_code
        self.retryable = retryable
        super().__init__(f"ActivityWatch import failed: {result_code}.")


class TooManyEventsError(ActivityWatchImportError):
    """Raised when a minimum-sized API interval is still too dense."""

    def __init__(self) -> None:
        super().__init__("too_many_events")


class ImportRangeError(ValueError):
    """Raised for invalid or too-large backfill ranges."""


@dataclass(frozen=True, slots=True)
class ActivityWatchImportSettings:
    """Safety and scheduling limits fixed by the Phase 6 contract."""

    event_limit: int = EVENT_LIMIT
    context_ms: int = CONTEXT_MS
    min_split_ms: int = MIN_SPLIT_MS
    settlement_lag_ms: int = SETTLEMENT_LAG_MS
    initial_lookback_days: int = INITIAL_LOOKBACK_DAYS
    max_run_days: int = MAX_RUN_DAYS
    max_run_ms: int = MAX_RUN_MS
    lease_ttl_ms: int = 15 * 60 * 1000
    privacy_mode: str = "app_only"

    def __post_init__(self) -> None:
        if self.event_limit != EVENT_LIMIT or self.event_limit <= 0:
            raise ValueError("event_limit must be the fixed ActivityWatch limit.")
        if self.context_ms <= 0 or self.min_split_ms < self.context_ms:
            raise ValueError("context and minimum split limits are invalid.")
        if self.settlement_lag_ms < 0 or self.initial_lookback_days < 1:
            raise ValueError("settlement and initial lookback limits are invalid.")
        if self.max_run_days < 1 or self.max_run_ms <= 0 or self.lease_ttl_ms <= 0:
            raise ValueError("run and lease limits must be positive.")
        validate_privacy_mode(self.privacy_mode)


@dataclass(frozen=True, slots=True)
class ActivityWatchImportChunk:
    """One completed or previewed UTC day."""

    day_start_ms: int
    day_end_ms: int
    closed_day: bool
    session_count: int
    total_duration_ms: int
    app_count: int
    diagnostics: NormalizationDiagnostics
    replaced: bool


@dataclass(frozen=True, slots=True)
class ActivityWatchImportResult:
    """Safe aggregate result for a run; raw payloads are never retained."""

    source_key: str
    device_id: str
    chunks: tuple[ActivityWatchImportChunk, ...]
    dry_run: bool
    partial: bool
    web_details_available: bool = False

    @property
    def session_count(self) -> int:
        return sum(chunk.session_count for chunk in self.chunks)

    @property
    def total_duration_ms(self) -> int:
        return sum(chunk.total_duration_ms for chunk in self.chunks)

    @property
    def invalid_event_count(self) -> int:
        return sum(chunk.diagnostics.invalid_event_count for chunk in self.chunks)

    @property
    def redacted_detail_count(self) -> int:
        return sum(chunk.diagnostics.redacted_detail_count for chunk in self.chunks)

    @property
    def truncated_title_count(self) -> int:
        return sum(chunk.diagnostics.truncated_title_count for chunk in self.chunks)


def utc_day_start_ms(timestamp_ms: int) -> int:
    """Return the UTC midnight containing an epoch-millisecond timestamp."""

    if isinstance(timestamp_ms, bool) or not isinstance(timestamp_ms, int):
        raise ValueError("timestamp_ms must be an integer.")
    return timestamp_ms // UTC_DAY_MS * UTC_DAY_MS


def utc_day_end_ms(day_start_ms: int) -> int:
    start = utc_day_start_ms(day_start_ms)
    return start + UTC_DAY_MS


def iter_utc_days(start_ms: int, end_ms: int) -> tuple[tuple[int, int], ...]:
    """Expand a half-open range to the UTC days it overlaps."""

    if end_ms <= start_ms:
        raise ImportRangeError("the import range must be non-empty.")
    first = utc_day_start_ms(start_ms)
    last = utc_day_start_ms(end_ms - 1)
    return tuple(
        (day_start, day_start + UTC_DAY_MS)
        for day_start in range(first, last + UTC_DAY_MS, UTC_DAY_MS)
    )


def local_date_range_to_utc(
    from_date: date, to_date: date, timezone_name: str = DEFAULT_TIMEZONE
) -> tuple[int, int]:
    """Convert an exclusive local-date range to the overlapping UTC range."""

    if to_date <= from_date:
        raise ImportRangeError("--to must be after --from.")
    try:
        timezone = ZoneInfo(timezone_name)
    except ZoneInfoNotFoundError as error:
        raise ImportRangeError("timezone is not available.") from error
    start = datetime.combine(from_date, datetime.min.time(), tzinfo=timezone)
    end = datetime.combine(to_date, datetime.min.time(), tzinfo=timezone)
    return int(start.astimezone(UTC).timestamp() * 1000), int(
        end.astimezone(UTC).timestamp() * 1000
    )


def _datetime_from_ms(timestamp_ms: int) -> datetime:
    return datetime.fromtimestamp(timestamp_ms / 1000, tz=UTC)


def _event_key(bucket_id: str, event: ActivityWatchEvent) -> str:
    identity = event.id
    if identity is None:
        identity = json.dumps(
            {
                "timestamp": event.timestamp.isoformat(),
                "duration": event.duration_seconds,
                "data": event.data,
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    return f"{bucket_id}\0{identity}"


def _bucket_created_ms(bucket: ActivityWatchBucket) -> int:
    return int(bucket.created.timestamp() * 1000) if bucket.created is not None else 0


def fetch_bucket_events(
    client: ActivityWatchEventClient,
    bucket: ActivityWatchBucket,
    *,
    start_ms: int,
    end_ms: int,
    event_limit: int = EVENT_LIMIT,
    min_split_ms: int = MIN_SPLIT_MS,
) -> tuple[ActivityWatchBucketEvent, ...]:
    """Fetch a bounded interval, splitting exact-limit responses recursively."""

    if end_ms <= start_ms:
        raise ImportRangeError("event fetch range must be non-empty.")
    if event_limit != EVENT_LIMIT:
        raise ValueError("event_limit must be 10000.")
    if min_split_ms <= 0:
        raise ValueError("min_split_ms must be positive.")

    def fetch_interval(interval_start_ms: int, interval_end_ms: int) -> list[ActivityWatchEvent]:
        events = client.get_events(
            bucket.id,
            start=_datetime_from_ms(interval_start_ms),
            end=_datetime_from_ms(interval_end_ms),
            limit=event_limit,
        )
        if len(events) < event_limit:
            return list(events)
        if interval_end_ms - interval_start_ms <= min_split_ms:
            raise TooManyEventsError()
        midpoint = interval_start_ms + (interval_end_ms - interval_start_ms) // 2
        if midpoint <= interval_start_ms or midpoint >= interval_end_ms:
            raise TooManyEventsError()
        return fetch_interval(interval_start_ms, midpoint) + fetch_interval(
            midpoint, interval_end_ms
        )

    unique: dict[str, ActivityWatchEvent] = {}
    for event in fetch_interval(start_ms, end_ms):
        unique[_event_key(bucket.id, event)] = event
    return tuple(
        ActivityWatchBucketEvent(bucket.id, event, _bucket_created_ms(bucket))
        for event in sorted(unique.values(), key=lambda item: (item.timestamp, item.id or ""))
    )


def _safe_error_code(error: BaseException) -> tuple[str, bool]:
    if isinstance(error, TooManyEventsError):
        return error.result_code, False
    if isinstance(error, ActivityWatchError):
        return error.code, error.retryable
    if isinstance(error, (ActivityWatchRepositoryError, ActivityWatchLeaseLostError)):
        return "protocol_error", False
    if isinstance(error, (ValueError, TypeError, KeyError)):
        return "incompatible_api", False
    return "protocol_error", False


class ActivityWatchImporter:
    """Coordinate safe fetch → normalize → UTC-day replace operations."""

    def __init__(
        self,
        client: ActivityWatchImportClient,
        session_factory: sessionmaker[Session] | None = None,
        *,
        expected_hostname: str | None = None,
        settings: ActivityWatchImportSettings | None = None,
        clock_ms: Callable[[], int] | None = None,
        monotonic: Callable[[], float] | None = None,
    ) -> None:
        self.client = client
        self.session_factory = session_factory
        self.expected_hostname = expected_hostname
        self.settings = settings or ActivityWatchImportSettings()
        self.clock_ms = clock_ms or (lambda: int(time.time() * 1000))
        self.monotonic = monotonic or time.monotonic

    def run(
        self,
        *,
        dry_run: bool = False,
        from_ms: int | None = None,
        to_ms: int | None = None,
    ) -> ActivityWatchImportResult:
        """Run initial/cursor/rolling import or an explicit UTC range."""

        if dry_run and self.session_factory is None:
            state = None
        elif self.session_factory is None:
            raise ValueError("session_factory is required for a writing import.")
        else:
            state = None
        now_ms = self.clock_ms()
        discovery = self.client.discover(expected_hostname=self.expected_hostname)
        if not discovery.can_import_app_sessions:
            raise ActivityWatchImportError(discovery.result_code)
        source_key = source_key_for(discovery.hostname, ACTIVITYWATCH_STABLE_RELEASE)
        empty = normalize_activitywatch(
            hostname=discovery.hostname,
            window_events=(),
            afk_events=(),
            privacy_mode=self.settings.privacy_mode,
            created_at_ms=now_ms,
        )
        if self.session_factory is not None and not dry_run:
            with session_scope(self.session_factory) as session:
                state = ActivityWatchRepository(session).get_import_state(source_key)
        days = self._plan_days(
            now_ms=now_ms,
            state=state,
            from_ms=from_ms,
            to_ms=to_ms,
        )
        if from_ms is not None and to_ms is not None:
            requested_days = iter_utc_days(from_ms, to_ms)
            if len(requested_days) > 31:
                raise ImportRangeError("backfill range may cover at most 31 UTC days.")
        if dry_run:
            return self._run_dry(
                discovery=discovery,
                source_key=source_key,
                device_id=empty.device.id,
                days=days,
                now_ms=now_ms,
            )
        return self._run_writing(
            discovery=discovery,
            source_key=source_key,
            device=empty,
            days=days,
            now_ms=now_ms,
            allow_privacy_mode_change=from_ms is not None,
        )

    def _plan_days(
        self,
        *,
        now_ms: int,
        state: object,
        from_ms: int | None,
        to_ms: int | None,
    ) -> tuple[tuple[int, int], ...]:
        if (from_ms is None) != (to_ms is None):
            raise ImportRangeError("from_ms and to_ms must be supplied together.")
        if from_ms is not None and to_ms is not None:
            return iter_utc_days(from_ms, to_ms)
        current_day = utc_day_start_ms(now_ms)
        previous_day = current_day - UTC_DAY_MS
        completed = getattr(state, "completed_through_ms", None) if state is not None else None
        if completed is None:
            first = current_day - (self.settings.initial_lookback_days - 1) * UTC_DAY_MS
        else:
            first = utc_day_start_ms(completed)
        days: list[tuple[int, int]] = []
        if first < current_day:
            days.extend(iter_utc_days(first, current_day))
        if completed is None:
            days.append((current_day, current_day + UTC_DAY_MS))
        else:
            days.extend(((previous_day, current_day), (current_day, current_day + UTC_DAY_MS)))
        unique = {(start, end): None for start, end in days}
        return tuple(sorted(unique))

    def _run_dry(
        self,
        *,
        discovery: ActivityWatchDiscovery,
        source_key: str,
        device_id: str,
        days: Sequence[tuple[int, int]],
        now_ms: int,
    ) -> ActivityWatchImportResult:
        started = self.monotonic()
        chunks: list[ActivityWatchImportChunk] = []
        selected = self._bounded_days(days, started)
        for day_start, day_end in selected:
            normalized = self._fetch_and_normalize(
                discovery=discovery,
                day_start_ms=day_start,
                day_end_ms=day_end,
                now_ms=now_ms,
            )
            chunks.append(
                self._chunk_result(
                    day_start,
                    day_end,
                    normalized,
                    False,
                    day_end <= utc_day_start_ms(now_ms),
                )
            )
        return ActivityWatchImportResult(
            source_key,
            device_id,
            tuple(chunks),
            True,
            len(selected) < len(days),
            discovery.web_bucket is not None,
        )

    def _run_writing(
        self,
        *,
        discovery: ActivityWatchDiscovery,
        source_key: str,
        device: NormalizedActivityWatch,
        days: Sequence[tuple[int, int]],
        now_ms: int,
        allow_privacy_mode_change: bool,
    ) -> ActivityWatchImportResult:
        if self.session_factory is None:
            raise ValueError("session_factory is required for a writing import.")
        with session_scope(self.session_factory) as session:
            repository = ActivityWatchRepository(session)
            token = repository.acquire_lease(
                source_key=source_key,
                device_id=device.device.id,
                privacy_mode=self.settings.privacy_mode,
                now_ms=now_ms,
                ttl_ms=self.settings.lease_ttl_ms,
                allow_privacy_mode_change=allow_privacy_mode_change,
            )
            if token is None:
                raise ActivityWatchImportError("lease_busy")
        started = self.monotonic()
        chunks: list[ActivityWatchImportChunk] = []
        selected = self._bounded_days(days, started)
        try:
            for day_start, day_end in selected:
                normalized = self._fetch_and_normalize(
                    discovery=discovery,
                    day_start_ms=day_start,
                    day_end_ms=day_end,
                    now_ms=now_ms,
                )
                closed_day = day_end <= utc_day_start_ms(now_ms)
                with session_scope(self.session_factory) as session:
                    repository = ActivityWatchRepository(session)
                    replacement = repository.replace_utc_day(
                        source_key=source_key,
                        token=token,
                        device=normalized.device,
                        apps=normalized.apps,
                        sessions=normalized.sessions,
                        details=normalized.details,
                        day_start_ms=day_start,
                        day_end_ms=day_end,
                        now_ms=now_ms,
                        closed_day=closed_day,
                        privacy_mode=self.settings.privacy_mode,
                        ttl_ms=self.settings.lease_ttl_ms,
                    )
                chunks.append(
                    self._chunk_result(
                        day_start,
                        day_end,
                        normalized,
                        replacement,
                        closed_day,
                    )
                )
            partial = len(selected) < len(days)
            return ActivityWatchImportResult(
                source_key,
                device.device.id,
                tuple(chunks),
                False,
                partial,
                discovery.web_bucket is not None,
            )
        except Exception as error:
            code, retryable = _safe_error_code(error)
            with session_scope(self.session_factory) as session:
                ActivityWatchRepository(session).record_failure(
                    source_key=source_key,
                    token=token,
                    now_ms=self.clock_ms(),
                    result_code=code,
                )
            if isinstance(error, ActivityWatchImportError):
                raise
            raise ActivityWatchImportError(code, retryable=retryable) from error
        finally:
            with session_scope(self.session_factory) as session:
                ActivityWatchRepository(session).release_lease(
                    source_key=source_key, token=token, now_ms=self.clock_ms()
                )

    def _bounded_days(
        self, days: Sequence[tuple[int, int]], started: float
    ) -> tuple[tuple[int, int], ...]:
        selected: list[tuple[int, int]] = []
        for day in days:
            if len(selected) >= self.settings.max_run_days:
                break
            if selected and (self.monotonic() - started) * 1000 >= self.settings.max_run_ms:
                break
            selected.append(day)
        return tuple(selected)

    def _fetch_and_normalize(
        self,
        *,
        discovery: ActivityWatchDiscovery,
        day_start_ms: int,
        day_end_ms: int,
        now_ms: int,
    ) -> NormalizedActivityWatch:
        fetch_start = max(0, day_start_ms - self.settings.context_ms)
        fetch_end = day_end_ms + self.settings.context_ms
        window_bucket = discovery.window_bucket
        afk_bucket = discovery.afk_bucket
        if window_bucket is None or afk_bucket is None:
            raise ActivityWatchImportError("missing_window_bucket")
        window_events = fetch_bucket_events(
            self.client,
            window_bucket,
            start_ms=fetch_start,
            end_ms=fetch_end,
            event_limit=self.settings.event_limit,
            min_split_ms=self.settings.min_split_ms,
        )
        afk_events = fetch_bucket_events(
            self.client,
            afk_bucket,
            start_ms=fetch_start,
            end_ms=fetch_end,
            event_limit=self.settings.event_limit,
            min_split_ms=self.settings.min_split_ms,
        )
        web_events: tuple[ActivityWatchBucketEvent, ...] = ()
        if discovery.web_bucket is not None:
            web_events = fetch_bucket_events(
                self.client,
                discovery.web_bucket,
                start_ms=fetch_start,
                end_ms=fetch_end,
                event_limit=self.settings.event_limit,
                min_split_ms=self.settings.min_split_ms,
            )
        watermark_ms = now_ms - self.settings.settlement_lag_ms
        effective_end = min(day_end_ms, watermark_ms)
        if effective_end <= day_start_ms:
            effective_end = day_start_ms + 1
        return normalize_activitywatch(
            hostname=discovery.hostname,
            window_events=window_events,
            afk_events=afk_events,
            web_events=web_events,
            privacy_mode=self.settings.privacy_mode,
            utc_day_start_ms=day_start_ms,
            utc_day_end_ms=effective_end,
            created_at_ms=now_ms,
        )

    @staticmethod
    def _chunk_result(
        day_start: int,
        day_end: int,
        normalized: NormalizedActivityWatch,
        replacement: ActivityWatchReplaceResult | bool,
        closed_day: bool,
    ) -> ActivityWatchImportChunk:
        if isinstance(replacement, ActivityWatchReplaceResult):
            session_count = replacement.session_count
            total_duration_ms = replacement.total_duration_ms
            app_count = replacement.app_count
        else:
            session_count = len(normalized.sessions)
            total_duration_ms = sum(
                item.ended_at_ms - item.started_at_ms for item in normalized.sessions
            )
            app_count = len(normalized.apps)
        diagnostics = normalized.diagnostics
        return ActivityWatchImportChunk(
            day_start,
            day_end,
            closed_day,
            session_count,
            total_duration_ms,
            app_count,
            diagnostics,
            isinstance(replacement, ActivityWatchReplaceResult),
        )


__all__ = [
    "CONTEXT_MS",
    "DEFAULT_TIMEZONE",
    "INITIAL_LOOKBACK_DAYS",
    "MAX_RUN_DAYS",
    "MAX_RUN_MS",
    "MIN_SPLIT_MS",
    "SETTLEMENT_LAG_MS",
    "UTC_DAY_MS",
    "ActivityWatchEventClient",
    "ActivityWatchImportChunk",
    "ActivityWatchImportClient",
    "ActivityWatchImportError",
    "ActivityWatchImportResult",
    "ActivityWatchImportSettings",
    "ActivityWatchImporter",
    "ImportRangeError",
    "TooManyEventsError",
    "fetch_bucket_events",
    "iter_utc_days",
    "local_date_range_to_utc",
    "utc_day_end_ms",
    "utc_day_start_ms",
]
