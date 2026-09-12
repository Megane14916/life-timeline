"""Photo day-range and managed thumbnail read APIs."""

from __future__ import annotations

import hashlib
import hmac

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.api.dependencies import get_session
from app.api.errors import (
    InvalidRequestError,
    ResourceNotFoundError,
    ThumbnailUnavailableError,
)
from app.config import Settings, get_settings
from app.ids import InvalidUlidError, validate_ulid
from app.models import MediaItem
from app.schemas import ErrorResponse, PhotosResponse
from app.schemas.photo_sync import PhotoSyncPolicy
from app.services.photos import get_photos
from app.services.thumbnail_store import ThumbnailStore, ThumbnailStoreError
from app.services.time_range import TimeRangeError

router = APIRouter()


@router.get(
    "/api/v1/photos",
    response_model=PhotosResponse,
    responses={422: {"model": ErrorResponse}, 500: {"model": ErrorResponse}},
)
def photos(
    date: str | None = Query(default=None),
    timezone: str | None = Query(default=None),
    session: Session = Depends(get_session),  # noqa: B008
) -> PhotosResponse:
    try:
        return get_photos(session, date_value=date, timezone_name=timezone)
    except TimeRangeError as error:
        raise InvalidRequestError(error.message, field=error.field) from error


@router.get(
    "/api/v1/media/{media_id}/thumbnail",
    response_class=Response,
    responses={
        200: {"content": {"image/webp": {}}},
        404: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
    },
)
def thumbnail(
    media_id: str,
    request: Request,
    session: Session = Depends(get_session),  # noqa: B008
) -> Response:
    try:
        validate_ulid(media_id, field_name="media_id")
    except InvalidUlidError as error:
        raise InvalidRequestError(str(error), field="media_id") from error

    media_item = session.get(MediaItem, media_id)
    if media_item is None or media_item.thumbnail_path is None:
        raise ResourceNotFoundError

    settings = getattr(request.app.state, "settings", None) or get_settings()
    if not isinstance(settings, Settings):
        raise ThumbnailUnavailableError
    store = ThumbnailStore(settings.thumbnail_dir)
    try:
        thumbnail_path = store.path_for_relative(media_item.thumbnail_path)
        file_size = thumbnail_path.stat().st_size
        if (
            not thumbnail_path.is_file()
            or file_size != media_item.thumbnail_size_bytes
            or file_size > PhotoSyncPolicy.MAX_THUMBNAIL_BYTES
            or media_item.thumbnail_sha256 is None
        ):
            raise ThumbnailUnavailableError
        with thumbnail_path.open("rb") as thumbnail_file:
            content = thumbnail_file.read(PhotoSyncPolicy.MAX_THUMBNAIL_BYTES + 1)
        if len(content) != file_size or not hmac.compare_digest(
            hashlib.sha256(content).hexdigest(), media_item.thumbnail_sha256
        ):
            raise ThumbnailUnavailableError
    except (OSError, ThumbnailStoreError) as error:
        raise ThumbnailUnavailableError from error

    return Response(
        content=content,
        media_type=PhotoSyncPolicy.THUMBNAIL_MIME_TYPE,
        headers={
            "X-Content-Type-Options": "nosniff",
            "Cache-Control": "private, max-age=86400",
        },
    )
