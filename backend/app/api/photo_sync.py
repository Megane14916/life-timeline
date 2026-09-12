"""Multipart API for idempotent Android photo synchronization."""

from __future__ import annotations

import json
import time
from collections.abc import Iterable
from typing import Any

from fastapi import APIRouter, Depends, Request
from pydantic import ValidationError
from python_multipart.exceptions import MultipartParseError
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session
from starlette.datastructures import FormData, UploadFile
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api.dependencies import get_session
from app.api.errors import (
    InvalidRequestError,
    PayloadTooLargeError,
    SyncConflictError,
    TemporarilyUnavailableError,
)
from app.config import Settings, get_settings
from app.repositories import (
    DeviceRecord,
    MasterRepository,
    MediaItemConflictError,
    MediaItemRecord,
    MediaRepository,
    RepositoryConflictError,
    RepositoryValidationError,
)
from app.schemas.api import ErrorResponse
from app.schemas.photo_sync import (
    PhotoSyncPolicy,
    PhotoSyncRequest,
    PhotoSyncResponse,
    validate_thumbnail_part_names,
)
from app.services.thumbnail_store import (
    InvalidThumbnailError,
    PromotedThumbnail,
    StagedThumbnail,
    ThumbnailStore,
    ThumbnailStoreConflictError,
    ThumbnailTooLargeError,
)

router = APIRouter()
_SAFE_INVALID_REQUEST = "The photo sync request is invalid."
_SAFE_CONFLICT = "A photo ID or source is already stored with different content."


def _field_path(parts: Iterable[Any]) -> str | None:
    path = ""
    for part in parts:
        if isinstance(part, int):
            path += f"[{part}]"
        elif isinstance(part, str) and part not in {"body", "metadata"}:
            path += ("." if path else "") + part
    return path or "metadata"


def _parse_metadata(value: str) -> PhotoSyncRequest:
    try:
        raw = json.loads(value)
    except (json.JSONDecodeError, TypeError) as error:
        raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="metadata") from error
    try:
        return PhotoSyncRequest.model_validate(raw)
    except ValidationError as error:
        errors = error.errors()
        field = _field_path(errors[0].get("loc", ())) if errors else "metadata"
        raise InvalidRequestError(_SAFE_INVALID_REQUEST, field=field) from error


def _parse_form(form: FormData) -> tuple[PhotoSyncRequest, dict[str, UploadFile]]:
    entries = list(form.multi_items())
    metadata_entries = [value for name, value in entries if name == "metadata"]
    if len(metadata_entries) != 1 or not isinstance(metadata_entries[0], str):
        raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="metadata")

    photo_request = _parse_metadata(metadata_entries[0])
    upload_entries: list[tuple[str, UploadFile]] = []
    for name, value in entries:
        if name == "metadata":
            continue
        if not isinstance(value, UploadFile):
            raise InvalidRequestError(_SAFE_INVALID_REQUEST, field=name)
        upload_entries.append((name, value))

    try:
        validate_thumbnail_part_names(photo_request, (name for name, _ in upload_entries))
    except ValueError as error:
        raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="photos.thumbnail") from error

    uploads: dict[str, UploadFile] = {}
    for name, upload in upload_entries:
        if upload.content_type != PhotoSyncPolicy.THUMBNAIL_MIME_TYPE:
            raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="photos.thumbnail.mimeType")
        uploads[name] = upload
    return photo_request, uploads


def _media_record(
    request: PhotoSyncRequest,
    photo_index: int,
    staged: StagedThumbnail | None,
    created_at_ms: int,
) -> MediaItemRecord:
    photo = request.photos[photo_index]
    thumbnail = photo.thumbnail
    return MediaItemRecord(
        id=photo.id,
        device_id=request.device.id,
        type="photo",
        source=photo.source,
        source_id=photo.source_id,
        filename=photo.filename,
        captured_at_ms=photo.captured_at_ms,
        duration_ms=None,
        mime_type=photo.mime_type,
        created_at_ms=created_at_ms,
        width=photo.width,
        height=photo.height,
        latitude=float(photo.latitude) if photo.latitude is not None else None,
        longitude=float(photo.longitude) if photo.longitude is not None else None,
        thumbnail_path=staged.relative_path if staged is not None else None,
        thumbnail_mime_type=thumbnail.mime_type if staged is not None and thumbnail else None,
        thumbnail_width=staged.width if staged is not None else None,
        thumbnail_height=staged.height if staged is not None else None,
        thumbnail_size_bytes=staged.size_bytes if staged is not None else None,
        thumbnail_sha256=staged.sha256 if staged is not None else None,
    )


def _is_sqlite_busy(error: OperationalError) -> bool:
    original = error.orig
    error_code = getattr(original, "sqlite_errorcode", None)
    busy_codes = {5, 6}  # SQLITE_BUSY and SQLITE_LOCKED
    message = str(original).lower()
    return (
        error_code in busy_codes or "database is locked" in message or "database is busy" in message
    )


def _cleanup_after_failure(store: ThumbnailStore, promoted: list[PromotedThumbnail]) -> None:
    store.cleanup_promoted(promoted)


@router.post(
    "/api/v1/sync/photos",
    response_model=PhotoSyncResponse,
    status_code=200,
    responses={
        409: {"model": ErrorResponse},
        413: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
    },
)
async def photo_sync(
    request: Request,
    session: Session = Depends(get_session),  # noqa: B008
) -> PhotoSyncResponse:
    settings = getattr(request.app.state, "settings", None) or get_settings()
    if not isinstance(settings, Settings):
        raise RuntimeError("Application settings are invalid.")
    store = ThumbnailStore(settings.thumbnail_dir)
    staged: list[StagedThumbnail] = []
    promoted: list[PromotedThumbnail] = []
    photo_request: PhotoSyncRequest | None = None

    try:
        try:
            content_type = request.headers.get("content-type", "").split(";", 1)[0].strip()
            if content_type.lower() != "multipart/form-data":
                raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="contentType")
            async with request.form(
                max_files=PhotoSyncPolicy.MAX_PHOTOS_PER_BATCH,
                max_fields=1,
                max_part_size=PhotoSyncPolicy.MAX_REQUEST_BYTES,
            ) as form:
                photo_request, uploads = _parse_form(form)
                staged_by_id: dict[str, StagedThumbnail] = {}
                for photo in photo_request.photos:
                    if photo.thumbnail is None:
                        continue
                    upload = uploads[f"thumbnail_{photo.id}"]
                    staged_item = await store.stage(photo, upload)
                    staged.append(staged_item)
                    staged_by_id[photo.id] = staged_item

                now_ms = time.time_ns() // 1_000_000
                device_record = DeviceRecord(
                    id=photo_request.device.id,
                    name=photo_request.device.name,
                    platform=photo_request.device.platform,
                    created_at_ms=now_ms,
                    last_seen_at_ms=now_ms,
                )
                records = [
                    _media_record(photo_request, index, staged_by_id.get(photo.id), now_ms)
                    for index, photo in enumerate(photo_request.photos)
                ]

                with session.begin():
                    connection = session.connection()
                    if connection.dialect.name == "sqlite":
                        connection.exec_driver_sql("BEGIN IMMEDIATE")
                    MasterRepository(session).save_device(device_record)
                    # Force SQLite's writer lock before checking source keys and moving files.
                    session.flush()
                    repository = MediaRepository(session)
                    for record in records:
                        repository.assert_compatible(record)
                    for staged_item in staged:
                        promoted.append(store.promote(staged_item))
                    repository.save_many(records)

        except ThumbnailTooLargeError as error:
            _cleanup_after_failure(store, promoted)
            raise PayloadTooLargeError() from error
        except InvalidThumbnailError as error:
            _cleanup_after_failure(store, promoted)
            raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="photos.thumbnail") from error
        except ThumbnailStoreConflictError as error:
            _cleanup_after_failure(store, promoted)
            raise SyncConflictError(_SAFE_CONFLICT, field="photos.id") from error
        except MediaItemConflictError as error:
            _cleanup_after_failure(store, promoted)
            raise SyncConflictError(_SAFE_CONFLICT, field=error.field) from error
        except RepositoryConflictError as error:
            _cleanup_after_failure(store, promoted)
            raise SyncConflictError(_SAFE_CONFLICT, field="device.id") from error
        except RepositoryValidationError as error:
            _cleanup_after_failure(store, promoted)
            raise InvalidRequestError(_SAFE_INVALID_REQUEST) from error
        except OperationalError as error:
            _cleanup_after_failure(store, promoted)
            if _is_sqlite_busy(error):
                raise TemporarilyUnavailableError() from error
            raise
        except IntegrityError as error:
            _cleanup_after_failure(store, promoted)
            raise SyncConflictError(_SAFE_CONFLICT) from error
        except MultipartParseError as error:
            _cleanup_after_failure(store, promoted)
            raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="metadata") from error
        except StarletteHTTPException as error:
            _cleanup_after_failure(store, promoted)
            raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="metadata") from error
        except BaseException:
            _cleanup_after_failure(store, promoted)
            raise
    finally:
        store.cleanup_staged(staged)

    assert photo_request is not None
    return PhotoSyncResponse(schemaVersion=1, accepted=[photo.id for photo in photo_request.photos])
