"""Persistence rules for photo and video metadata."""

from __future__ import annotations

import re
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.ids import InvalidUlidError, validate_ulid
from app.models import MediaItem
from app.repositories.normalized import RepositoryValidationError

THUMBNAIL_MAX_BYTES = 1_048_576
THUMBNAIL_MAX_DIMENSION = 512
LOWERCASE_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class MediaItemConflictError(ValueError):
    """Raised when a photo ID or source key is reused for different content."""

    def __init__(self, field: str) -> None:
        super().__init__("A photo identifier conflicts with previously stored content.")
        self.field = field


@dataclass(frozen=True, slots=True)
class MediaItemRecord:
    id: str
    device_id: str
    type: str
    source: str
    source_id: str
    filename: str
    captured_at_ms: int
    duration_ms: int | None
    mime_type: str
    created_at_ms: int
    width: int | None
    height: int | None
    latitude: float | None
    longitude: float | None
    thumbnail_path: str | None
    thumbnail_mime_type: str | None
    thumbnail_width: int | None
    thumbnail_height: int | None
    thumbnail_size_bytes: int | None
    thumbnail_sha256: str | None


def _validate_ulid(value: str, field: str) -> None:
    try:
        validate_ulid(value, field_name=field)
    except InvalidUlidError as error:
        raise RepositoryValidationError(str(error)) from error


def _validate_timestamp(value: int, field: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise RepositoryValidationError(f"{field} must be a non-negative integer epoch ms.")


def _validate_record(record: MediaItemRecord) -> None:
    _validate_ulid(record.id, "media_item.id")
    _validate_ulid(record.device_id, "media_item.device_id")
    if record.type != "photo":
        raise RepositoryValidationError("media_item.type must be photo for this API.")
    if record.source != "android_media_store":
        raise RepositoryValidationError("media_item.source is unsupported.")
    for value, field, max_length in (
        (record.source_id, "media_item.source_id", 255),
        (record.filename, "media_item.filename", 255),
        (record.mime_type, "media_item.mime_type", 100),
    ):
        if not value.strip() or len(value) > max_length:
            raise RepositoryValidationError(f"{field} must be nonblank and within its length limit.")
    if record.mime_type[:6] != "image/":
        raise RepositoryValidationError("media_item.mime_type must identify an image.")
    if any(ord(character) < 32 or ord(character) == 127 for character in record.filename):
        raise RepositoryValidationError("media_item.filename must not contain control characters.")
    if "/" in record.filename or "\\" in record.filename:
        raise RepositoryValidationError("media_item.filename must not contain path separators.")
    _validate_timestamp(record.captured_at_ms, "media_item.captured_at_ms")
    _validate_timestamp(record.created_at_ms, "media_item.created_at_ms")
    if any(value is not None and value <= 0 for value in (record.width, record.height)):
        raise RepositoryValidationError("media_item dimensions must be positive when present.")
    if record.duration_ms is not None and record.duration_ms <= 0:
        raise RepositoryValidationError("media_item.duration_ms must be positive when present.")
    if (record.latitude is None) != (record.longitude is None):
        raise RepositoryValidationError("media_item coordinates must be provided together.")
    if record.latitude is not None and not -90 <= record.latitude <= 90:
        raise RepositoryValidationError("media_item.latitude is out of range.")
    if record.longitude is not None and not -180 <= record.longitude <= 180:
        raise RepositoryValidationError("media_item.longitude is out of range.")

    thumbnail_fields = (
        record.thumbnail_path,
        record.thumbnail_mime_type,
        record.thumbnail_width,
        record.thumbnail_height,
        record.thumbnail_size_bytes,
        record.thumbnail_sha256,
    )
    if all(value is None for value in thumbnail_fields):
        return
    if any(value is None for value in thumbnail_fields):
        raise RepositoryValidationError("media_item thumbnail metadata must be complete.")
    assert record.thumbnail_path is not None
    assert record.thumbnail_mime_type is not None
    assert record.thumbnail_width is not None
    assert record.thumbnail_height is not None
    assert record.thumbnail_size_bytes is not None
    assert record.thumbnail_sha256 is not None
    if record.thumbnail_mime_type != "image/webp":
        raise RepositoryValidationError("media_item thumbnail must use image/webp.")
    if not 1 <= record.thumbnail_width <= THUMBNAIL_MAX_DIMENSION:
        raise RepositoryValidationError("media_item thumbnail width is out of range.")
    if not 1 <= record.thumbnail_height <= THUMBNAIL_MAX_DIMENSION:
        raise RepositoryValidationError("media_item thumbnail height is out of range.")
    if not 1 <= record.thumbnail_size_bytes <= THUMBNAIL_MAX_BYTES:
        raise RepositoryValidationError("media_item thumbnail size is out of range.")
    if not LOWERCASE_SHA256.fullmatch(record.thumbnail_sha256):
        raise RepositoryValidationError("media_item thumbnail hash is invalid.")
    path = record.thumbnail_path
    if path.startswith("/") or "\\" in path or any(part in {"", ".", ".."} for part in path.split("/")):
        raise RepositoryValidationError("media_item thumbnail path must be relative and safe.")


def _base_content(item: MediaItem | MediaItemRecord) -> tuple[object, ...]:
    return (
        item.id,
        item.device_id,
        item.type,
        item.source,
        item.source_id,
        item.filename,
        item.captured_at_ms,
        item.width,
        item.height,
        item.duration_ms,
        item.latitude,
        item.longitude,
        item.mime_type,
    )


def _thumbnail_content(item: MediaItem | MediaItemRecord) -> tuple[object, ...]:
    return (
        item.thumbnail_mime_type,
        item.thumbnail_width,
        item.thumbnail_height,
        item.thumbnail_size_bytes,
        item.thumbnail_sha256,
    )


class MediaRepository:
    """Persist MediaItem facts with source-key uniqueness and safe completion."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def assert_compatible(self, record: MediaItemRecord) -> MediaItem | None:
        """Check natural-key and ID semantics without mutating database rows."""

        _validate_record(record)
        existing = self.session.get(MediaItem, record.id)
        by_source = self.session.scalar(
            select(MediaItem).where(
                MediaItem.device_id == record.device_id,
                MediaItem.source == record.source,
                MediaItem.source_id == record.source_id,
            )
        )
        if by_source is not None and by_source.id != record.id:
            raise MediaItemConflictError("sourceId")
        if existing is None:
            return None
        if _base_content(existing) != _base_content(record):
            raise MediaItemConflictError("id")
        incoming_thumbnail = record.thumbnail_sha256 is not None
        stored_thumbnail = existing.thumbnail_sha256 is not None
        if incoming_thumbnail and stored_thumbnail and _thumbnail_content(existing) != _thumbnail_content(
            record
        ):
            raise MediaItemConflictError("thumbnail.sha256")
        return existing

    def save(self, record: MediaItemRecord) -> MediaItem:
        """Create a MediaItem or add a previously absent thumbnail."""

        existing = self.assert_compatible(record)
        if existing is None:
            item = MediaItem(
                id=record.id,
                device_id=record.device_id,
                type=record.type,
                source=record.source,
                source_id=record.source_id,
                filename=record.filename,
                captured_at_ms=record.captured_at_ms,
                width=record.width,
                height=record.height,
                duration_ms=None,
                latitude=record.latitude,
                longitude=record.longitude,
                thumbnail_path=record.thumbnail_path,
                thumbnail_mime_type=record.thumbnail_mime_type,
                thumbnail_width=record.thumbnail_width,
                thumbnail_height=record.thumbnail_height,
                thumbnail_size_bytes=record.thumbnail_size_bytes,
                thumbnail_sha256=record.thumbnail_sha256,
                mime_type=record.mime_type,
                created_at_ms=record.created_at_ms,
            )
            self.session.add(item)
            return item

        if existing.thumbnail_path is None and record.thumbnail_path is not None:
            existing.thumbnail_path = record.thumbnail_path
            existing.thumbnail_mime_type = record.thumbnail_mime_type
            existing.thumbnail_width = record.thumbnail_width
            existing.thumbnail_height = record.thumbnail_height
            existing.thumbnail_size_bytes = record.thumbnail_size_bytes
            existing.thumbnail_sha256 = record.thumbnail_sha256
        return existing

    def save_many(self, records: list[MediaItemRecord]) -> list[MediaItem]:
        """Validate all conflicts first, then persist the batch atomically."""

        if self.session.in_transaction():
            return self._save_many(records)
        with self.session.begin():
            return self._save_many(records)

    def _save_many(self, records: list[MediaItemRecord]) -> list[MediaItem]:
        for record in records:
            self.assert_compatible(record)
        items = [self.save(record) for record in records]
        self.session.flush()
        return items
