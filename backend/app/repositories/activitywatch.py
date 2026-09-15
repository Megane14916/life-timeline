"""Transactional persistence for privacy-filtered ActivityWatch imports."""

from __future__ import annotations

import hashlib
import secrets
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from urllib.parse import urlsplit

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.ids import InvalidUlidError, validate_ulid
from app.models import ActivityWatchImportState, AppSession, DesktopSessionDetail, Device
from app.repositories.normalized import (
    AppRecord,
    DeviceRecord,
    MasterRepository,
    RepositoryConflictError,
    RepositoryValidationError,
)

ACTIVITYWATCH_SOURCE = "activitywatch"
ACTIVITYWATCH_ALGORITHM_VERSION = "activitywatch_session_v1"
PRIVACY_MODES = frozenset(("app_only", "titles", "web"))
VALID_RESULT_CODES = frozenset(
    (
        "success",
        "unavailable",
        "missing_window_bucket",
        "missing_afk_bucket",
        "web_details_unavailable",
        "incompatible_api",
        "too_many_events",
        "lease_busy",
        "invalid_config",
        "protocol_error",
    )
)
DEFAULT_LEASE_TTL_MS = 15 * 60 * 1000
FUTURE_CLOCK_RECOVERY_MS = 30 * 60 * 1000
MAX_SOURCE_KEY_LENGTH = 128


class ActivityWatchRepositoryError(ValueError):
    """Base error for ActivityWatch persistence failures."""


class ActivityWatchLeaseBusyError(ActivityWatchRepositoryError):
    """Raised when another importer currently owns the source lease."""


class ActivityWatchLeaseLostError(ActivityWatchRepositoryError):
    """Raised when a lease token no longer owns the source state."""


class ActivityWatchConflictError(RepositoryConflictError):
    """Raised when an ActivityWatch identifier is reused for other content."""


@dataclass(frozen=True, slots=True)
class ActivityWatchDeviceRecord:
    id: str
    name: str
    created_at_ms: int
    last_seen_at_ms: int | None = None


@dataclass(frozen=True, slots=True)
class ActivityWatchAppRecord:
    id: str
    identifier: str
    display_name: str
    created_at_ms: int


@dataclass(frozen=True, slots=True)
class ActivityWatchSessionRecord:
    id: str
    app_id: str
    started_at_ms: int
    ended_at_ms: int
    created_at_ms: int


@dataclass(frozen=True, slots=True)
class ActivityWatchDetailRecord:
    session_id: str
    window_title: str | None
    url: str | None
    source_event_id: str
    created_at_ms: int
    algorithm_version: str = ACTIVITYWATCH_ALGORITHM_VERSION
    privacy_mode: str = "app_only"


@dataclass(frozen=True, slots=True)
class ActivityWatchReplaceResult:
    session_count: int
    total_duration_ms: int
    app_count: int


def source_key_for(hostname: str, collector_version: str) -> str:
    """Return a non-reversible key for a normalized ActivityWatch source."""

    normalized_hostname = hostname.strip().casefold()
    normalized_version = collector_version.strip()
    if not normalized_hostname or not normalized_version:
        raise ActivityWatchRepositoryError("hostname and collector_version must not be blank.")
    digest = hashlib.sha256(
        f"activitywatch-source-v1\0{normalized_hostname}\0{normalized_version}".encode()
    ).hexdigest()
    return f"aw1_{digest}"


def _validate_source_key(source_key: str) -> None:
    if not isinstance(source_key, str) or not source_key.strip():
        raise RepositoryValidationError("source_key must not be blank.")
    if len(source_key) > MAX_SOURCE_KEY_LENGTH:
        raise RepositoryValidationError("source_key is too long.")


def _validate_id(value: str, field_name: str) -> None:
    try:
        validate_ulid(value, field_name=field_name)
    except InvalidUlidError as error:
        raise RepositoryValidationError(str(error)) from error


def _validate_timestamp(value: int, field_name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise RepositoryValidationError(f"{field_name} must be a non-negative integer epoch ms.")


def _validate_text(value: str, field_name: str, max_length: int) -> None:
    if not isinstance(value, str) or not value.strip() or len(value) > max_length:
        raise RepositoryValidationError(
            f"{field_name} must be nonblank and within its length limit."
        )


def _validate_privacy_mode(value: str) -> None:
    if value not in PRIVACY_MODES:
        raise RepositoryValidationError("privacy_mode is unsupported.")


def _validate_url(value: str | None) -> None:
    if value is None:
        return
    if not isinstance(value, str) or len(value) > 2048 or any(char.isspace() for char in value):
        raise RepositoryValidationError("detail.url must be a valid privacy-safe URL.")
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        _ = parsed.port
    except ValueError as error:
        raise RepositoryValidationError("detail.url must be a valid privacy-safe URL.") from error
    if (
        parsed.scheme.lower() not in {"http", "https"}
        or hostname is None
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise RepositoryValidationError("detail.url must omit userinfo, query, and fragment.")


def _validate_device(record: ActivityWatchDeviceRecord) -> None:
    _validate_id(record.id, "device.id")
    _validate_text(record.name, "device.name", 200)
    _validate_timestamp(record.created_at_ms, "device.created_at_ms")
    if record.last_seen_at_ms is not None:
        _validate_timestamp(record.last_seen_at_ms, "device.last_seen_at_ms")


def _validate_app(record: ActivityWatchAppRecord) -> None:
    _validate_id(record.id, "app.id")
    _validate_text(record.identifier, "app.identifier", 255)
    _validate_text(record.display_name, "app.display_name", 255)
    _validate_timestamp(record.created_at_ms, "app.created_at_ms")


def _validate_session(record: ActivityWatchSessionRecord) -> int:
    _validate_id(record.id, "app_session.id")
    _validate_id(record.app_id, "app_session.app_id")
    _validate_timestamp(record.started_at_ms, "app_session.started_at_ms")
    _validate_timestamp(record.ended_at_ms, "app_session.ended_at_ms")
    _validate_timestamp(record.created_at_ms, "app_session.created_at_ms")
    if record.ended_at_ms <= record.started_at_ms:
        raise RepositoryValidationError("app_session.ended_at_ms must be after started_at_ms.")
    return record.ended_at_ms - record.started_at_ms


def _validate_detail(record: ActivityWatchDetailRecord) -> None:
    _validate_id(record.session_id, "detail.session_id")
    _validate_text(record.source_event_id, "detail.source_event_id", 255)
    _validate_timestamp(record.created_at_ms, "detail.created_at_ms")
    if record.algorithm_version != ACTIVITYWATCH_ALGORITHM_VERSION:
        raise RepositoryValidationError("detail.algorithm_version is unsupported.")
    _validate_privacy_mode(record.privacy_mode)
    if record.window_title is not None:
        if not isinstance(record.window_title, str) or len(record.window_title) > 500:
            raise RepositoryValidationError("detail.window_title must be at most 500 characters.")
    _validate_url(record.url)


def _session_content(model: AppSession) -> tuple[object, ...]:
    return (
        model.device_id,
        model.app_id,
        model.started_at_ms,
        model.ended_at_ms,
        model.duration_ms,
        model.source,
    )


def _detail_content(model: DesktopSessionDetail) -> tuple[object, ...]:
    return (
        model.window_title,
        model.url,
        model.source_event_id,
        model.algorithm_version,
        model.privacy_mode,
    )


class ActivityWatchRepository:
    """Persist ActivityWatch data with short, lease-protected transactions."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def get_import_state(self, source_key: str) -> ActivityWatchImportState | None:
        _validate_source_key(source_key)
        with self._transaction_scope():
            return self.session.get(ActivityWatchImportState, source_key)

    def acquire_lease(
        self,
        *,
        source_key: str,
        device_id: str,
        privacy_mode: str,
        now_ms: int,
        ttl_ms: int = DEFAULT_LEASE_TTL_MS,
        token: str | None = None,
        allow_privacy_mode_change: bool = False,
    ) -> str | None:
        """Acquire a source lease, recovering expired or far-future leases."""

        _validate_source_key(source_key)
        _validate_id(device_id, "device_id")
        _validate_privacy_mode(privacy_mode)
        _validate_timestamp(now_ms, "now_ms")
        if isinstance(ttl_ms, bool) or not isinstance(ttl_ms, int) or ttl_ms <= 0:
            raise RepositoryValidationError("ttl_ms must be a positive integer.")
        lease_token = token or secrets.token_hex(32)
        _validate_text(lease_token, "lease_token", 128)

        with self._transaction_scope():
            state = self.session.get(ActivityWatchImportState, source_key)
            if state is None:
                device = self.session.get(Device, device_id)
                if device is None:
                    self.session.add(
                        Device(
                            id=device_id,
                            name="Windows desktop",
                            platform="windows",
                            created_at_ms=now_ms,
                        )
                    )
                    self.session.flush()
                elif device.platform != "windows":
                    raise RepositoryConflictError(
                        f"device ID '{device_id}' is not a Windows device."
                    )
                state = ActivityWatchImportState(
                    source_key=source_key,
                    device_id=device_id,
                    algorithm_version=ACTIVITYWATCH_ALGORITHM_VERSION,
                    privacy_mode=privacy_mode,
                    consecutive_failures=0,
                    lease_token=lease_token,
                    lease_expires_at_ms=now_ms + ttl_ms,
                    created_at_ms=now_ms,
                    updated_at_ms=now_ms,
                )
                self.session.add(state)
                return lease_token
            if state.device_id != device_id:
                raise RepositoryConflictError("source_key is already bound to another device.")
            if state.algorithm_version != ACTIVITYWATCH_ALGORITHM_VERSION:
                raise RepositoryConflictError("source state uses another algorithm version.")
            if state.privacy_mode != privacy_mode and not allow_privacy_mode_change:
                raise RepositoryConflictError("source state uses another privacy mode.")
            expiry = state.lease_expires_at_ms
            active = (
                state.lease_token is not None
                and expiry is not None
                and expiry > now_ms
                and expiry <= now_ms + FUTURE_CLOCK_RECOVERY_MS
            )
            if active:
                return None
            state.privacy_mode = privacy_mode
            state.lease_token = lease_token
            state.lease_expires_at_ms = now_ms + ttl_ms
            state.updated_at_ms = now_ms
        return lease_token

    def heartbeat_lease(
        self, *, source_key: str, token: str, now_ms: int, ttl_ms: int = DEFAULT_LEASE_TTL_MS
    ) -> bool:
        _validate_source_key(source_key)
        _validate_timestamp(now_ms, "now_ms")
        if isinstance(ttl_ms, bool) or not isinstance(ttl_ms, int) or ttl_ms <= 0:
            raise RepositoryValidationError("ttl_ms must be a positive integer.")
        with self._transaction_scope():
            state = self._locked_state(source_key)
            if state is None or state.lease_token != token:
                return False
            state.lease_expires_at_ms = now_ms + ttl_ms
            state.updated_at_ms = now_ms
        return True

    def release_lease(self, *, source_key: str, token: str, now_ms: int) -> bool:
        _validate_source_key(source_key)
        _validate_timestamp(now_ms, "now_ms")
        with self._transaction_scope():
            state = self._locked_state(source_key)
            if state is None or state.lease_token != token:
                return False
            state.lease_token = None
            state.lease_expires_at_ms = None
            state.updated_at_ms = now_ms
        return True

    def mark_attempt(self, *, source_key: str, token: str, now_ms: int) -> None:
        _validate_timestamp(now_ms, "now_ms")
        with self._transaction_scope():
            state = self._require_lease(source_key, token)
            state.last_attempt_at_ms = now_ms
            state.updated_at_ms = now_ms

    def record_failure(
        self,
        *,
        source_key: str,
        token: str,
        now_ms: int,
        result_code: str,
        next_eligible_at_ms: int | None = None,
    ) -> None:
        _validate_timestamp(now_ms, "now_ms")
        if result_code not in VALID_RESULT_CODES or result_code == "success":
            raise RepositoryValidationError("result_code is not an allowed failure code.")
        if next_eligible_at_ms is not None:
            _validate_timestamp(next_eligible_at_ms, "next_eligible_at_ms")
        with self._transaction_scope():
            state = self._require_lease(source_key, token)
            state.last_attempt_at_ms = now_ms
            state.last_result_code = result_code
            state.consecutive_failures += 1
            state.next_eligible_at_ms = next_eligible_at_ms
            state.updated_at_ms = now_ms

    def replace_utc_day(
        self,
        *,
        source_key: str,
        token: str,
        device: ActivityWatchDeviceRecord,
        apps: tuple[ActivityWatchAppRecord, ...] | list[ActivityWatchAppRecord],
        sessions: tuple[ActivityWatchSessionRecord, ...] | list[ActivityWatchSessionRecord],
        details: tuple[ActivityWatchDetailRecord, ...] | list[ActivityWatchDetailRecord],
        day_start_ms: int,
        day_end_ms: int,
        now_ms: int,
        closed_day: bool = True,
        privacy_mode: str = "app_only",
        ttl_ms: int = DEFAULT_LEASE_TTL_MS,
    ) -> ActivityWatchReplaceResult:
        """Replace one UTC day and advance state in one all-or-nothing transaction."""

        _validate_source_key(source_key)
        _validate_id(device.id, "device.id")
        _validate_device(device)
        _validate_timestamp(day_start_ms, "day_start_ms")
        _validate_timestamp(day_end_ms, "day_end_ms")
        _validate_timestamp(now_ms, "now_ms")
        if day_end_ms <= day_start_ms:
            raise RepositoryValidationError("day_end_ms must be after day_start_ms.")
        _validate_privacy_mode(privacy_mode)
        if isinstance(ttl_ms, bool) or not isinstance(ttl_ms, int) or ttl_ms <= 0:
            raise RepositoryValidationError("ttl_ms must be a positive integer.")
        for app in apps:
            _validate_app(app)
        durations = {session.id: _validate_session(session) for session in sessions}
        for detail_record in details:
            _validate_detail(detail_record)
            if detail_record.privacy_mode != privacy_mode:
                raise RepositoryValidationError("detail privacy mode differs from import state.")

        session_by_id: dict[str, ActivityWatchSessionRecord] = {}
        for session_record in sessions:
            if session_record.id in session_by_id:
                raise ActivityWatchConflictError("duplicate ActivityWatch session identifier.")
            session_by_id[session_record.id] = session_record
        detail_by_session: dict[str, ActivityWatchDetailRecord] = {}
        for detail_record in details:
            if detail_record.session_id in detail_by_session:
                raise ActivityWatchConflictError("duplicate ActivityWatch detail identifier.")
            detail_by_session[detail_record.session_id] = detail_record
            if detail_record.session_id not in session_by_id:
                raise RepositoryValidationError("detail references a session outside the import.")

        with self._transaction_scope():
            state = self._require_lease(source_key, token)
            if state.device_id != device.id or state.privacy_mode != privacy_mode:
                raise ActivityWatchLeaseLostError("lease state does not match this replacement.")
            device_model = MasterRepository(self.session).save_device(
                DeviceRecord(
                    device.id,
                    device.name,
                    "windows",
                    device.created_at_ms,
                    device.last_seen_at_ms,
                )
            )
            app_id_map: dict[str, str] = {}
            masters = MasterRepository(self.session)
            for app in apps:
                app_model = masters.save_app(
                    AppRecord(
                        app.id,
                        "windows",
                        app.identifier,
                        app.display_name,
                        app.created_at_ms,
                    )
                )
                app_id_map[app.id] = app_model.id
            if any(session_record.app_id not in app_id_map for session_record in sessions):
                raise RepositoryValidationError("app_session references an unknown app.")

            self._preflight_conflicts(
                sessions=sessions,
                details=details,
                app_id_map=app_id_map,
                device_id=device_model.id,
                durations=durations,
            )
            target_ids = select(AppSession.id).where(
                AppSession.device_id == device_model.id,
                AppSession.source == ACTIVITYWATCH_SOURCE,
                AppSession.started_at_ms >= day_start_ms,
                AppSession.started_at_ms < day_end_ms,
            )
            self.session.execute(delete(AppSession).where(AppSession.id.in_(target_ids)))

            for session_record in sorted(sessions, key=lambda item: (item.started_at_ms, item.id)):
                if self.session.get(AppSession, session_record.id) is not None:
                    continue
                self.session.add(
                    AppSession(
                        id=session_record.id,
                        device_id=device_model.id,
                        app_id=app_id_map[session_record.app_id],
                        started_at_ms=session_record.started_at_ms,
                        ended_at_ms=session_record.ended_at_ms,
                        duration_ms=durations[session_record.id],
                        source=ACTIVITYWATCH_SOURCE,
                        created_at_ms=session_record.created_at_ms,
                    )
                )
            self.session.flush()
            for detail_record in details:
                existing_detail = self.session.get(DesktopSessionDetail, detail_record.session_id)
                if existing_detail is None:
                    self.session.add(
                        DesktopSessionDetail(
                            session_id=detail_record.session_id,
                            window_title=detail_record.window_title,
                            url=detail_record.url,
                            source_event_id=detail_record.source_event_id,
                            algorithm_version=detail_record.algorithm_version,
                            privacy_mode=detail_record.privacy_mode,
                            created_at_ms=detail_record.created_at_ms,
                        )
                    )
            state.last_attempt_at_ms = now_ms
            state.last_success_at_ms = now_ms
            state.last_result_code = "success"
            state.consecutive_failures = 0
            state.next_eligible_at_ms = None
            if closed_day:
                state.completed_through_ms = max(state.completed_through_ms or 0, day_end_ms)
            state.lease_expires_at_ms = now_ms + ttl_ms
            state.updated_at_ms = now_ms
        return ActivityWatchReplaceResult(
            session_count=len(sessions),
            total_duration_ms=sum(durations.values()),
            app_count=len({session_record.app_id for session_record in sessions}),
        )

    def _locked_state(self, source_key: str) -> ActivityWatchImportState | None:
        _validate_source_key(source_key)
        return self.session.get(ActivityWatchImportState, source_key)

    @contextmanager
    def _transaction_scope(self) -> Iterator[None]:
        """Own a transaction when possible, or join the caller's transaction."""

        if self.session.in_transaction():
            yield
        else:
            with self.session.begin():
                yield

    def _require_lease(self, source_key: str, token: str) -> ActivityWatchImportState:
        state = self._locked_state(source_key)
        if state is None or state.lease_token != token:
            raise ActivityWatchLeaseLostError("the ActivityWatch lease token is not valid.")
        return state

    def _preflight_conflicts(
        self,
        *,
        sessions: list[ActivityWatchSessionRecord] | tuple[ActivityWatchSessionRecord, ...],
        details: list[ActivityWatchDetailRecord] | tuple[ActivityWatchDetailRecord, ...],
        app_id_map: dict[str, str],
        device_id: str,
        durations: dict[str, int],
    ) -> None:
        for session_record in sessions:
            existing = self.session.get(AppSession, session_record.id)
            if existing is not None and _session_content(existing) != (
                device_id,
                app_id_map[session_record.app_id],
                session_record.started_at_ms,
                session_record.ended_at_ms,
                durations[session_record.id],
                ACTIVITYWATCH_SOURCE,
            ):
                raise ActivityWatchConflictError(
                    "an ActivityWatch session identifier has different content."
                )
        for detail_record in details:
            existing_detail = self.session.get(DesktopSessionDetail, detail_record.session_id)
            if existing_detail is not None and _detail_content(existing_detail) != (
                detail_record.window_title,
                detail_record.url,
                detail_record.source_event_id,
                detail_record.algorithm_version,
                detail_record.privacy_mode,
            ):
                raise ActivityWatchConflictError(
                    "an ActivityWatch detail identifier has different content."
                )
