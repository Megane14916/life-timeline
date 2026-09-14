"""Transactional PlaceVisit rebuild operations."""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy.orm import Session

from app.models import LocationPoint
from app.repositories.place_visits import (
    count_device_place_visits,
    find_device_location_points,
    list_device_ids,
    replace_device_place_visits,
)
from app.services.stay_points import ALGORITHM_VERSION, generate_stay_points


@dataclass(frozen=True, slots=True)
class DeviceRebuildCount:
    device_id: str
    existing: int
    generated: int


def rebuild_device_place_visits(
    session: Session,
    device_id: str,
    *,
    algorithm_version: str = ALGORITHM_VERSION,
    dry_run: bool = False,
) -> DeviceRebuildCount:
    if algorithm_version != ALGORITHM_VERSION:
        raise ValueError(f"Unsupported algorithm version: {algorithm_version}")
    raw_points = find_device_location_points(session, device_id)
    visits = generate_stay_points(raw_points)
    existing_count = count_device_place_visits(
        session, device_id, algorithm_version=algorithm_version
    )
    if not dry_run:
        replace_device_place_visits(session, device_id, visits, algorithm_version=algorithm_version)
    return DeviceRebuildCount(device_id, existing_count, len(visits))


def rebuild_all_place_visits(
    session: Session,
    *,
    algorithm_version: str = ALGORITHM_VERSION,
    dry_run: bool = False,
) -> list[DeviceRebuildCount]:
    return [
        rebuild_device_place_visits(
            session,
            device_id,
            algorithm_version=algorithm_version,
            dry_run=dry_run,
        )
        for device_id in list_device_ids(session)
    ]


def rebuild_place_visits_for_points(session: Session, points: list[LocationPoint]) -> None:
    """Rebuild each affected device as part of its caller's transaction."""

    for device_id in sorted({point.device_id for point in points}):
        rebuild_device_place_visits(session, device_id)
