"""Repositories for the normalized Master and AppSession records."""

from __future__ import annotations

from dataclasses import dataclass, replace

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.ids import InvalidUlidError, validate_ulid
from app.models import App, AppSession, Category, Device

PLATFORMS = frozenset(("android", "windows"))


class RepositoryError(ValueError):
    """Base error for invalid or conflicting normalized records."""


class RepositoryValidationError(RepositoryError):
    """Raised when a record violates a generic normalized-data rule."""


class RepositoryConflictError(RepositoryError):
    """Raised when a Master identifier already represents different data."""


class AppSessionConflictError(RepositoryConflictError):
    """Raised when an AppSession ID is re-used for different data."""


class PlatformMismatchError(RepositoryValidationError):
    """Raised when a device and app belong to different platforms."""


@dataclass(frozen=True, slots=True)
class DeviceRecord:
    id: str
    name: str
    platform: str
    created_at_ms: int
    last_seen_at_ms: int | None = None


@dataclass(frozen=True, slots=True)
class CategoryRecord:
    id: str
    name: str
    created_at_ms: int


@dataclass(frozen=True, slots=True)
class AppRecord:
    id: str
    platform: str
    identifier: str
    display_name: str
    created_at_ms: int
    category_id: str | None = None
    icon_path: str | None = None


@dataclass(frozen=True, slots=True)
class AppSessionRecord:
    id: str
    device_id: str
    app_id: str
    started_at_ms: int
    ended_at_ms: int
    source: str
    created_at_ms: int
    duration_ms: int | None = None


def _validate_text(value: str, *, field_name: str, max_length: int) -> str:
    if not isinstance(value, str) or not value.strip():
        raise RepositoryValidationError(f"{field_name} must not be blank.")
    if len(value) > max_length:
        raise RepositoryValidationError(f"{field_name} must be at most {max_length} characters.")
    return value


def _validate_timestamp(value: int, *, field_name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise RepositoryValidationError(f"{field_name} must be a non-negative integer epoch ms.")
    return value


def _validate_id(value: str, *, field_name: str) -> str:
    try:
        return validate_ulid(value, field_name=field_name)
    except InvalidUlidError as error:
        raise RepositoryValidationError(str(error)) from error


def _validate_platform(value: str) -> str:
    if value not in PLATFORMS:
        raise RepositoryValidationError("platform must be either 'android' or 'windows'.")
    return value


def _validate_device(record: DeviceRecord) -> None:
    _validate_id(record.id, field_name="device.id")
    _validate_text(record.name, field_name="device.name", max_length=200)
    _validate_platform(record.platform)
    _validate_timestamp(record.created_at_ms, field_name="device.created_at_ms")
    if record.last_seen_at_ms is not None:
        _validate_timestamp(record.last_seen_at_ms, field_name="device.last_seen_at_ms")


def _validate_category(record: CategoryRecord) -> None:
    _validate_id(record.id, field_name="category.id")
    _validate_text(record.name, field_name="category.name", max_length=200)
    _validate_timestamp(record.created_at_ms, field_name="category.created_at_ms")


def _validate_app(record: AppRecord) -> None:
    _validate_id(record.id, field_name="app.id")
    _validate_platform(record.platform)
    _validate_text(record.identifier, field_name="app.identifier", max_length=255)
    _validate_text(record.display_name, field_name="app.display_name", max_length=255)
    _validate_timestamp(record.created_at_ms, field_name="app.created_at_ms")
    if record.category_id is not None:
        _validate_id(record.category_id, field_name="app.category_id")
    if record.icon_path is not None:
        _validate_text(record.icon_path, field_name="app.icon_path", max_length=1000)


def _validate_app_session(record: AppSessionRecord) -> int:
    _validate_id(record.id, field_name="app_session.id")
    _validate_id(record.device_id, field_name="app_session.device_id")
    _validate_id(record.app_id, field_name="app_session.app_id")
    _validate_timestamp(record.started_at_ms, field_name="app_session.started_at_ms")
    _validate_timestamp(record.ended_at_ms, field_name="app_session.ended_at_ms")
    _validate_timestamp(record.created_at_ms, field_name="app_session.created_at_ms")
    source = _validate_text(record.source, field_name="app_session.source", max_length=100)
    if source != record.source:
        raise RepositoryValidationError(
            "app_session.source must not have leading or trailing whitespace."
        )
    if record.ended_at_ms <= record.started_at_ms:
        raise RepositoryValidationError("app_session.ended_at_ms must be after started_at_ms.")
    duration_ms = record.ended_at_ms - record.started_at_ms
    if record.duration_ms is not None and record.duration_ms != duration_ms:
        raise RepositoryValidationError(
            "app_session.duration_ms must equal the timestamp difference."
        )
    return duration_ms


class MasterRepository:
    """Create or update device, category, and app Master records."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def save_device(self, record: DeviceRecord) -> Device:
        _validate_device(record)
        device = self.session.get(Device, record.id)
        if device is None:
            device = Device(
                id=record.id,
                name=record.name,
                platform=record.platform,
                created_at_ms=record.created_at_ms,
                last_seen_at_ms=record.last_seen_at_ms,
            )
            self.session.add(device)
        elif device.platform != record.platform:
            raise RepositoryConflictError(f"device ID '{record.id}' already has another platform.")
        else:
            device.name = record.name
            device.last_seen_at_ms = record.last_seen_at_ms
        return device

    def save_category(self, record: CategoryRecord) -> Category:
        _validate_category(record)
        category = self.session.get(Category, record.id)
        if category is None:
            category = Category(id=record.id, name=record.name, created_at_ms=record.created_at_ms)
            self.session.add(category)
        else:
            category.name = record.name
        return category

    def save_app(self, record: AppRecord) -> App:
        _validate_app(record)
        if (
            record.category_id is not None
            and self.session.get(Category, record.category_id) is None
        ):
            raise RepositoryValidationError(f"category '{record.category_id}' does not exist.")
        app_by_id = self.session.get(App, record.id)
        app_by_key = self.session.scalar(
            select(App).where(App.platform == record.platform, App.identifier == record.identifier)
        )

        if app_by_id is not None and (
            app_by_id.platform != record.platform or app_by_id.identifier != record.identifier
        ):
            raise RepositoryConflictError(f"app ID '{record.id}' already has another natural key.")

        app = app_by_key or app_by_id
        if app is None:
            app = App(
                id=record.id,
                platform=record.platform,
                identifier=record.identifier,
                display_name=record.display_name,
                category_id=record.category_id,
                icon_path=record.icon_path,
                created_at_ms=record.created_at_ms,
            )
            self.session.add(app)
        else:
            app.display_name = record.display_name
            app.category_id = record.category_id
            app.icon_path = record.icon_path
        return app


class AppSessionRepository:
    """Persist normalized AppSession facts with idempotency and conflict checks."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def save(self, record: AppSessionRecord) -> AppSession:
        duration_ms = _validate_app_session(record)
        device = self.session.get(Device, record.device_id)
        app = self.session.get(App, record.app_id)
        if device is None:
            raise RepositoryValidationError(f"device '{record.device_id}' does not exist.")
        if app is None:
            raise RepositoryValidationError(f"app '{record.app_id}' does not exist.")
        if device.platform != app.platform:
            raise PlatformMismatchError(
                f"device '{record.device_id}' ({device.platform}) and app "
                f"'{record.app_id}' ({app.platform}) must use the same platform."
            )

        existing = self.session.get(AppSession, record.id)
        if existing is not None:
            same_content = (
                existing.device_id == record.device_id
                and existing.app_id == record.app_id
                and existing.started_at_ms == record.started_at_ms
                and existing.ended_at_ms == record.ended_at_ms
                and existing.duration_ms == duration_ms
                and existing.source == record.source
            )
            if not same_content:
                raise AppSessionConflictError(
                    f"app_session ID '{record.id}' is already stored with different content."
                )
            return existing

        app_session = AppSession(
            id=record.id,
            device_id=record.device_id,
            app_id=record.app_id,
            started_at_ms=record.started_at_ms,
            ended_at_ms=record.ended_at_ms,
            duration_ms=duration_ms,
            source=record.source,
            created_at_ms=record.created_at_ms,
        )
        self.session.add(app_session)
        self.session.flush()
        return app_session


class NormalizedRepository:
    """Coordinate Master and Fact writes in one transaction."""

    def __init__(self, session: Session) -> None:
        self.session = session
        self.masters = MasterRepository(session)
        self.app_sessions = AppSessionRepository(session)

    def save_app_session(
        self,
        *,
        device: DeviceRecord,
        app: AppRecord,
        app_session: AppSessionRecord,
    ) -> AppSession:
        """Save the required Masters and Fact atomically.

        If the caller already owns a transaction, the records join it. Otherwise
        this method creates and commits a transaction around the complete write.
        """

        if self.session.in_transaction():
            return self._save_app_session(device=device, app=app, app_session=app_session)
        with self.session.begin():
            return self._save_app_session(device=device, app=app, app_session=app_session)

    def _save_app_session(
        self, *, device: DeviceRecord, app: AppRecord, app_session: AppSessionRecord
    ) -> AppSession:
        device_model = self.masters.save_device(device)
        app_model = self.masters.save_app(app)
        if app_session.device_id != device_model.id:
            raise RepositoryValidationError(
                "AppSession must reference the supplied device and app."
            )
        resolved_app_session = replace(app_session, app_id=app_model.id)
        return self.app_sessions.save(resolved_app_session)
