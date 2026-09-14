"""Bounded day-range reads for the daily Map API."""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Device, LocationPoint, MediaItem
from app.repositories.queries import PhotoWithDevice
from app.services.time_range import QueryRange

MAP_MAX_ACCURACY_M = 1_000.0


@dataclass(frozen=True, slots=True)
class LocationPointWithDevice:
    location_point: LocationPoint
    device: Device


def find_map_location_points(
    session: Session, query_range: QueryRange
) -> list[LocationPointWithDevice]:
    """Find map-eligible points in the requested half-open local day."""

    statement = (
        select(LocationPoint, Device)
        .join(Device, Device.id == LocationPoint.device_id)
        .where(
            LocationPoint.recorded_at_ms >= query_range.start_ms,
            LocationPoint.recorded_at_ms < query_range.end_ms,
            LocationPoint.accuracy_m.is_not(None),
            LocationPoint.accuracy_m <= MAP_MAX_ACCURACY_M,
        )
        .order_by(
            LocationPoint.device_id.asc(),
            LocationPoint.recorded_at_ms.asc(),
            LocationPoint.id.asc(),
        )
    )
    return [
        LocationPointWithDevice(location_point=point, device=device)
        for point, device in session.execute(statement).tuples()
    ]


def find_map_photos(session: Session, query_range: QueryRange) -> list[PhotoWithDevice]:
    """Find only photos with a complete EXIF coordinate pair in the day range."""

    statement = (
        select(MediaItem, Device)
        .join(Device, Device.id == MediaItem.device_id)
        .where(
            MediaItem.type == "photo",
            MediaItem.captured_at_ms >= query_range.start_ms,
            MediaItem.captured_at_ms < query_range.end_ms,
            MediaItem.latitude.is_not(None),
            MediaItem.longitude.is_not(None),
        )
        .order_by(MediaItem.captured_at_ms.desc(), MediaItem.id.desc())
    )
    return [
        PhotoWithDevice(media_item=photo, device=device)
        for photo, device in session.execute(statement).tuples()
    ]
