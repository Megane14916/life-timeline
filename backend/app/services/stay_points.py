"""Deterministic stay-point generation for the ``stay_point_v1`` algorithm."""

from __future__ import annotations

import hashlib
import math
from collections.abc import Sequence
from dataclasses import dataclass

from app.models import LocationPoint

EARTH_RADIUS_M = 6_371_008.8
ALGORITHM_VERSION = "stay_point_v1"
MAX_ACCURACY_M = 200.0
STAY_RADIUS_M = 200.0
MIN_DWELL_MS = 15 * 60 * 1000
MIN_POINT_COUNT = 3
MAX_GAP_MS = 30 * 60 * 1000
MERGE_GAP_MS = 10 * 60 * 1000

_CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


@dataclass(frozen=True, slots=True)
class StayPoint:
    id: str
    device_id: str
    started_at_ms: int
    ended_at_ms: int
    duration_ms: int
    center_latitude: float
    center_longitude: float
    radius_m: float
    point_count: int
    algorithm_version: str
    source_first_point_id: str
    source_last_point_id: str
    created_at_ms: int


def haversine_m(
    latitude_a: float, longitude_a: float, latitude_b: float, longitude_b: float
) -> float:
    """Return the great-circle distance between two coordinates in meters."""

    lat_a, lat_b = math.radians(latitude_a), math.radians(latitude_b)
    delta_lat = lat_b - lat_a
    delta_lon = math.radians(longitude_b - longitude_a)
    value = (
        math.sin(delta_lat / 2) ** 2
        + math.cos(lat_a) * math.cos(lat_b) * math.sin(delta_lon / 2) ** 2
    )
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(min(1.0, value)))


def weighted_center(points: Sequence[LocationPoint]) -> tuple[float, float]:
    """Calculate an accuracy-weighted spherical centroid."""

    if not points:
        raise ValueError("a center requires at least one point")

    x, y, z, total_weight = _weighted_totals(points)
    return _center_from_components(x, y, z, total_weight, points[0])


def _weighted_totals(points: Sequence[LocationPoint]) -> tuple[float, float, float, float]:
    x = y = z = total_weight = 0.0
    for point in points:
        point_x, point_y, point_z, weight = _weighted_components(point)
        x += point_x
        y += point_y
        z += point_z
        total_weight += weight

    return x, y, z, total_weight


def _weighted_components(point: LocationPoint) -> tuple[float, float, float, float]:
    accuracy = point.accuracy_m
    if accuracy is None:
        raise ValueError("stay-point inputs must have measured accuracy")
    weight = 1.0 / max(accuracy, 10.0) ** 2
    latitude = math.radians(point.latitude)
    longitude = math.radians(point.longitude)
    return (
        weight * math.cos(latitude) * math.cos(longitude),
        weight * math.cos(latitude) * math.sin(longitude),
        weight * math.sin(latitude),
        weight,
    )


def _center_from_components(
    x: float, y: float, z: float, total_weight: float, fallback: LocationPoint
) -> tuple[float, float]:
    horizontal = math.hypot(x, y)
    if math.hypot(horizontal, z) <= total_weight * 1e-15:
        # A tight stay cluster cannot normally be antipodal; keep a deterministic
        # fallback for malformed/out-of-contract direct callers.
        return fallback.latitude, fallback.longitude
    return (
        math.degrees(math.atan2(z, horizontal)),
        math.degrees(math.atan2(y, x)),
    )


def _encode_base32(value: int, length: int) -> str:
    result = ["0"] * length
    for index in range(length - 1, -1, -1):
        value, remainder = divmod(value, 32)
        result[index] = _CROCKFORD_ALPHABET[remainder]
    return "".join(result)


def deterministic_visit_id(
    started_at_ms: int,
    device_id: str,
    first_point_id: str,
    last_point_id: str,
    *,
    algorithm_version: str = ALGORITHM_VERSION,
) -> str:
    """Build a deterministic ULID from start time and source identity."""

    if not 0 <= started_at_ms < 2**48:
        raise ValueError("started_at_ms cannot be represented by a ULID")
    identity = f"{device_id}{algorithm_version}{first_point_id}{last_point_id}".encode()
    randomness = int.from_bytes(hashlib.sha256(identity).digest()[:10], "big")
    return _encode_base32(started_at_ms, 10) + _encode_base32(randomness, 16)


def _center_and_radius(points: Sequence[LocationPoint]) -> tuple[float, float, float]:
    latitude, longitude = weighted_center(points)
    radius = max(
        haversine_m(latitude, longitude, point.latitude, point.longitude) for point in points
    )
    return latitude, longitude, radius


def _make_visit(points: Sequence[LocationPoint]) -> StayPoint:
    first, last = points[0], points[-1]
    latitude, longitude, radius = _center_and_radius(points)
    started_at_ms = first.recorded_at_ms
    ended_at_ms = last.recorded_at_ms
    return StayPoint(
        id=deterministic_visit_id(started_at_ms, first.device_id, first.id, last.id),
        device_id=first.device_id,
        started_at_ms=started_at_ms,
        ended_at_ms=ended_at_ms,
        duration_ms=ended_at_ms - started_at_ms,
        center_latitude=latitude,
        center_longitude=longitude,
        radius_m=radius,
        point_count=len(points),
        algorithm_version=ALGORITHM_VERSION,
        source_first_point_id=first.id,
        source_last_point_id=last.id,
        created_at_ms=max(point.created_at_ms for point in points),
    )


def _candidate_groups(points: Sequence[LocationPoint]) -> list[list[LocationPoint]]:
    groups: list[list[LocationPoint]] = []
    candidate: list[LocationPoint] = []
    x = y = z = total_weight = 0.0

    def finish() -> None:
        nonlocal candidate, x, y, z, total_weight
        if (
            len(candidate) >= MIN_POINT_COUNT
            and candidate[-1].recorded_at_ms - candidate[0].recorded_at_ms >= MIN_DWELL_MS
        ):
            groups.append(candidate)
        candidate = []
        x = y = z = total_weight = 0.0

    def add(point: LocationPoint) -> None:
        nonlocal x, y, z, total_weight
        point_x, point_y, point_z, weight = _weighted_components(point)
        x += point_x
        y += point_y
        z += point_z
        total_weight += weight
        candidate.append(point)

    for point in points:
        if candidate and point.recorded_at_ms - candidate[-1].recorded_at_ms > MAX_GAP_MS:
            finish()
        if not candidate:
            add(point)
            continue
        center_latitude, center_longitude = _center_from_components(
            x, y, z, total_weight, candidate[0]
        )
        if (
            haversine_m(center_latitude, center_longitude, point.latitude, point.longitude)
            <= STAY_RADIUS_M
        ):
            add(point)
        else:
            finish()
            add(point)
    finish()
    return groups


def generate_stay_points(points: Sequence[LocationPoint]) -> list[StayPoint]:
    """Generate ordered stay points from one device's raw fixes."""

    if not points:
        return []
    device_ids = {point.device_id for point in points}
    if len(device_ids) != 1:
        raise ValueError("stay-point generation accepts points from exactly one device")

    ordered = sorted(points, key=lambda point: (point.recorded_at_ms, point.id))
    eligible = [
        point
        for point in ordered
        if point.accuracy_m is not None and point.accuracy_m <= MAX_ACCURACY_M
    ]
    groups = _candidate_groups(eligible)

    merged: list[list[LocationPoint]] = []
    merged_totals: list[tuple[float, float, float, float]] = []
    for group in groups:
        group_totals = _weighted_totals(group)
        if merged:
            previous = merged[-1]
            previous_center = _center_from_components(*merged_totals[-1], previous[0])
            group_center = _center_from_components(*group_totals, group[0])
            gap_ms = group[0].recorded_at_ms - previous[-1].recorded_at_ms
            if (
                gap_ms <= MERGE_GAP_MS
                and haversine_m(*previous_center, *group_center) <= STAY_RADIUS_M
            ):
                previous.extend(group)
                previous_totals = merged_totals[-1]
                merged_totals[-1] = (
                    previous_totals[0] + group_totals[0],
                    previous_totals[1] + group_totals[1],
                    previous_totals[2] + group_totals[2],
                    previous_totals[3] + group_totals[3],
                )
                continue
        merged.append(list(group))
        merged_totals.append(group_totals)

    return [_make_visit(group) for group in merged]
