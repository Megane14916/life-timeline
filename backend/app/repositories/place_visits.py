"""Persistence helpers for rebuildable PlaceVisit facts."""

from __future__ import annotations

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.models import Device, LocationPoint, PlaceVisit
from app.services.stay_points import ALGORITHM_VERSION, StayPoint


def find_device_location_points(session: Session, device_id: str) -> list[LocationPoint]:
    statement = (
        select(LocationPoint)
        .where(LocationPoint.device_id == device_id)
        .order_by(LocationPoint.recorded_at_ms.asc(), LocationPoint.id.asc())
    )
    return list(session.scalars(statement))


def replace_device_place_visits(
    session: Session, device_id: str, visits: list[StayPoint], *, algorithm_version: str
) -> None:
    session.execute(
        delete(PlaceVisit).where(
            PlaceVisit.device_id == device_id,
            PlaceVisit.algorithm_version == algorithm_version,
        )
    )
    for visit in visits:
        session.add(
            PlaceVisit(
                id=visit.id,
                device_id=visit.device_id,
                started_at_ms=visit.started_at_ms,
                ended_at_ms=visit.ended_at_ms,
                duration_ms=visit.duration_ms,
                center_latitude=visit.center_latitude,
                center_longitude=visit.center_longitude,
                radius_m=visit.radius_m,
                point_count=visit.point_count,
                algorithm_version=visit.algorithm_version,
                source_first_point_id=visit.source_first_point_id,
                source_last_point_id=visit.source_last_point_id,
                created_at_ms=visit.created_at_ms,
            )
        )
    session.flush()


def list_device_ids(session: Session) -> list[str]:
    statement = select(Device.id).order_by(Device.id.asc())
    return list(session.scalars(statement))


def count_device_place_visits(
    session: Session, device_id: str, *, algorithm_version: str = ALGORITHM_VERSION
) -> int:
    from sqlalchemy import func

    return int(
        session.scalar(
            select(func.count())
            .select_from(PlaceVisit)
            .where(
                PlaceVisit.device_id == device_id,
                PlaceVisit.algorithm_version == algorithm_version,
            )
        )
        or 0
    )
