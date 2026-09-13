"""Persistence rules for raw LocationPoint facts."""

from __future__ import annotations

import math
from dataclasses import dataclass

from sqlalchemy.orm import Session

from app.ids import InvalidUlidError, validate_ulid
from app.models import LocationPoint
from app.repositories.normalized import RepositoryValidationError
from app.schemas.location_sync import LocationSyncPolicy


class LocationPointConflictError(ValueError):
    """Raised when a LocationPoint ID is replayed with different content."""

    def __init__(self, location_id: str) -> None:
        super().__init__("A location identifier conflicts with previously stored content.")
        self.location_id = location_id


@dataclass(frozen=True, slots=True)
class LocationPointRecord:
    id: str
    device_id: str
    recorded_at_ms: int
    latitude: float
    longitude: float
    accuracy_m: float | None
    altitude_m: float | None
    speed_mps: float | None
    source: str
    created_at_ms: int


def _validate_id(value: str, field_name: str) -> None:
    try:
        validate_ulid(value, field_name=field_name)
    except InvalidUlidError as error:
        raise RepositoryValidationError(str(error)) from error


def _validate_timestamp(value: int, field_name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise RepositoryValidationError(f"{field_name} must be a non-negative integer epoch ms.")


def _validate_finite(value: float | None, field_name: str) -> None:
    if value is not None and not math.isfinite(value):
        raise RepositoryValidationError(f"{field_name} must be finite.")


def _validate_record(record: LocationPointRecord) -> None:
    _validate_id(record.id, "location_point.id")
    _validate_id(record.device_id, "location_point.device_id")
    _validate_timestamp(record.recorded_at_ms, "location_point.recorded_at_ms")
    _validate_timestamp(record.created_at_ms, "location_point.created_at_ms")
    _validate_finite(record.latitude, "location_point.latitude")
    _validate_finite(record.longitude, "location_point.longitude")
    if not -90 <= record.latitude <= 90:
        raise RepositoryValidationError("location_point.latitude is out of range.")
    if not -180 <= record.longitude <= 180:
        raise RepositoryValidationError("location_point.longitude is out of range.")
    for value, field_name in (
        (record.accuracy_m, "location_point.accuracy_m"),
        (record.altitude_m, "location_point.altitude_m"),
        (record.speed_mps, "location_point.speed_mps"),
    ):
        _validate_finite(value, field_name)
    if record.accuracy_m is not None and record.accuracy_m < 0:
        raise RepositoryValidationError("location_point.accuracy_m must be nonnegative.")
    if record.speed_mps is not None and record.speed_mps < 0:
        raise RepositoryValidationError("location_point.speed_mps must be nonnegative.")
    if record.source != LocationSyncPolicy.SOURCE:
        raise RepositoryValidationError("location_point.source is unsupported.")


def _content(point: LocationPoint | LocationPointRecord) -> tuple[object, ...]:
    return (
        point.device_id,
        point.recorded_at_ms,
        point.latitude,
        point.longitude,
        point.accuracy_m,
        point.altitude_m,
        point.speed_mps,
        point.source,
    )


class LocationPointRepository:
    """Persist raw location facts with strict ID-based idempotency."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def assert_compatible(self, record: LocationPointRecord) -> LocationPoint | None:
        _validate_record(record)
        existing = self.session.get(LocationPoint, record.id)
        if existing is not None and _content(existing) != _content(record):
            raise LocationPointConflictError(record.id)
        return existing

    def save_many(self, records: list[LocationPointRecord]) -> list[LocationPoint]:
        """Validate the whole batch before adding any rows to the session."""

        if len(records) > LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH:
            raise RepositoryValidationError("location batch exceeds the maximum size.")
        if len({record.id for record in records}) != len(records):
            raise RepositoryValidationError("location batch contains duplicate IDs.")

        existing = [self.assert_compatible(record) for record in records]
        points: list[LocationPoint] = []
        for record, stored in zip(records, existing, strict=True):
            if stored is None:
                stored = LocationPoint(
                    id=record.id,
                    device_id=record.device_id,
                    recorded_at_ms=record.recorded_at_ms,
                    latitude=record.latitude,
                    longitude=record.longitude,
                    accuracy_m=record.accuracy_m,
                    altitude_m=record.altitude_m,
                    speed_mps=record.speed_mps,
                    source=record.source,
                    created_at_ms=record.created_at_ms,
                )
                self.session.add(stored)
            points.append(stored)
        self.session.flush()
        return points
