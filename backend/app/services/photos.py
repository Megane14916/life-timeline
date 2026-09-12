"""Photo day-range queries and public response transformation."""

from __future__ import annotations

from typing import Literal, cast

from sqlalchemy.orm import Session

from app.repositories.queries import PhotoWithDevice, find_photos
from app.schemas import PhotosResponse, PhotoTimelineItem
from app.services.time_range import build_day_range, format_epoch_ms


def to_photo_timeline_item(row: PhotoWithDevice) -> PhotoTimelineItem:
    photo = row.media_item
    return PhotoTimelineItem(
        type="photo",
        id=photo.id,
        deviceId=row.device.id,
        deviceName=row.device.name,
        source=cast(Literal["android_media_store"], photo.source),
        takenAt=format_epoch_ms(photo.captured_at_ms),
        filename=photo.filename,
        mimeType=photo.mime_type,
        width=photo.width,
        height=photo.height,
        latitude=photo.latitude,
        longitude=photo.longitude,
        thumbnailUrl=(
            f"/api/v1/media/{photo.id}/thumbnail" if photo.thumbnail_path is not None else None
        ),
    )


def get_photos(
    session: Session, date_value: str | None, timezone_name: str | None
) -> PhotosResponse:
    date_string, query_range = build_day_range(date_value, timezone_name)
    return PhotosResponse(
        date=date_string,
        timezone=query_range.timezone_name,
        rangeStart=query_range.start_iso,
        rangeEnd=query_range.end_iso,
        items=[to_photo_timeline_item(row) for row in find_photos(session, query_range)],
    )
