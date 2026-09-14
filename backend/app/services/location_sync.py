"""Atomic service for importing Android LocationPoint batches."""

from __future__ import annotations

import time

from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session

from app.api.errors import SyncConflictError, TemporarilyUnavailableError
from app.repositories import (
    DeviceRecord,
    LocationPointConflictError,
    LocationPointRecord,
    LocationPointRepository,
    MasterRepository,
    RepositoryConflictError,
    RepositoryError,
)
from app.schemas.location_sync import LocationSyncRequest, LocationSyncResponse
from app.services.place_visits import rebuild_place_visits_for_points


def _is_sqlite_busy(error: OperationalError) -> bool:
    error_code = getattr(error.orig, "sqlite_errorcode", None)
    if isinstance(error_code, int) and error_code & 0xFF in {5, 6}:
        return True
    message = str(error.orig).lower()
    return "database is locked" in message or "database is busy" in message


def _records(request: LocationSyncRequest, created_at_ms: int) -> list[LocationPointRecord]:
    return [
        LocationPointRecord(
            id=location.id,
            device_id=request.device.id,
            recorded_at_ms=location.recorded_at_ms,
            latitude=float(location.latitude),
            longitude=float(location.longitude),
            accuracy_m=float(location.accuracy_m) if location.accuracy_m is not None else None,
            altitude_m=float(location.altitude_m) if location.altitude_m is not None else None,
            speed_mps=float(location.speed_mps) if location.speed_mps is not None else None,
            source=location.source,
            created_at_ms=created_at_ms,
        )
        for location in request.locations
    ]


def _save_batch(session: Session, request: LocationSyncRequest, received_at_ms: int) -> None:
    with session.begin():
        connection = session.connection()
        if connection.dialect.name == "sqlite":
            connection.exec_driver_sql("BEGIN IMMEDIATE")

        MasterRepository(session).save_device(
            DeviceRecord(
                id=request.device.id,
                name=request.device.name,
                platform=request.device.platform,
                created_at_ms=received_at_ms,
                last_seen_at_ms=received_at_ms,
            )
        )
        # Acquire SQLite's writer lock before checking replay conflicts.
        session.flush()
        points = LocationPointRepository(session).save_many(_records(request, received_at_ms))
        rebuild_place_visits_for_points(session, points)


def sync_locations(
    session: Session,
    request: LocationSyncRequest,
    *,
    received_at_ms: int | None = None,
) -> LocationSyncResponse:
    """Persist an all-or-nothing version 1 batch and ACK it in request order."""

    if not request.locations:
        return LocationSyncResponse(schemaVersion=1, accepted=[])

    now_ms = received_at_ms if received_at_ms is not None else time.time_ns() // 1_000_000
    try:
        _save_batch(session, request, now_ms)
    except LocationPointConflictError as error:
        conflict_index = next(
            index
            for index, location in enumerate(request.locations)
            if location.id == error.location_id
        )
        raise SyncConflictError(
            "A location ID is already stored with different content.",
            field=f"locations[{conflict_index}].id",
        ) from error
    except RepositoryConflictError as error:
        raise SyncConflictError(
            "The location sync request conflicts with existing data.", field="device.id"
        ) from error
    except RepositoryError as error:
        raise ValueError(str(error)) from error
    except OperationalError as error:
        if _is_sqlite_busy(error):
            raise TemporarilyUnavailableError() from error
        raise
    except IntegrityError as error:
        raise SyncConflictError(
            "The location sync request conflicts with existing data."
        ) from error

    return LocationSyncResponse(
        schemaVersion=1,
        accepted=[location.id for location in request.locations],
    )
