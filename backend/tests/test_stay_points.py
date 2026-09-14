"""Pure contract tests for deterministic ``stay_point_v1`` generation."""

from __future__ import annotations

import math

from app.models import LocationPoint
from app.services.stay_points import (
    ALGORITHM_VERSION,
    EARTH_RADIUS_M,
    deterministic_visit_id,
    generate_stay_points,
    haversine_m,
    weighted_center,
)

DEVICE_ID = "01J00000000000000000001001"
BASE_TIME_MS = 1_780_000_000_000


def _point(
    index: int,
    minute: int,
    *,
    longitude: float = 0.0,
    latitude: float = 0.0,
    accuracy: float | None = 10.0,
) -> LocationPoint:
    return LocationPoint(
        id=f"01J0000000000000000000{index:03d}",
        device_id=DEVICE_ID,
        recorded_at_ms=BASE_TIME_MS + minute * 60_000,
        latitude=latitude,
        longitude=longitude,
        accuracy_m=accuracy,
        altitude_m=None,
        speed_mps=None,
        source="android_fused_location",
        created_at_ms=BASE_TIME_MS + minute * 60_000 + 1,
    )


def _stationary_points(*, start_index: int = 1, start_minute: int = 0) -> list[LocationPoint]:
    return [_point(start_index + index, start_minute + index * 5) for index in range(4)]


def test_stay_requires_three_eligible_points_and_fifteen_minutes() -> None:
    assert generate_stay_points(_stationary_points()[:3]) == []
    assert generate_stay_points([*_stationary_points()[:2], _point(3, 15, accuracy=201)]) == []


def test_radius_boundary_is_inclusive_and_outside_point_starts_new_candidate() -> None:
    longitude_at_200m = math.degrees(200 / EARTH_RADIUS_M)
    inclusive = [
        _point(1, 0),
        _point(2, 5),
        _point(3, 10),
        _point(4, 15, longitude=longitude_at_200m),
    ]
    visit = generate_stay_points(inclusive)[0]
    assert visit.point_count == 4
    assert visit.radius_m <= 200.0

    outside = [
        _point(11, 0),
        _point(12, 5),
        _point(13, 10),
        _point(14, 15, longitude=math.degrees(200.1 / EARTH_RADIUS_M)),
        _point(15, 20, longitude=math.degrees(200.1 / EARTH_RADIUS_M)),
        _point(16, 25, longitude=math.degrees(200.1 / EARTH_RADIUS_M)),
        _point(17, 30, longitude=math.degrees(200.1 / EARTH_RADIUS_M)),
    ]
    visits = generate_stay_points(outside)
    assert len(visits) == 1
    assert visits[0].source_first_point_id == outside[3].id


def test_accuracy_exclusion_and_thirty_minute_gap_split_sequences() -> None:
    excluded = [
        _point(1, 0),
        _point(2, 5, accuracy=None),
        _point(3, 10),
        _point(4, 15, accuracy=200.01),
    ]
    assert generate_stay_points(excluded) == []

    points = _stationary_points() + _stationary_points(start_index=11, start_minute=46)
    visits = generate_stay_points(points)
    assert len(visits) == 2
    assert [(visit.started_at_ms, visit.ended_at_ms) for visit in visits] == [
        (points[0].recorded_at_ms, points[3].recorded_at_ms),
        (points[4].recorded_at_ms, points[7].recorded_at_ms),
    ]


def test_adjacent_candidates_merge_when_gap_and_center_are_within_limits() -> None:
    points = _stationary_points()
    offset_201m = math.degrees(201 / EARTH_RADIUS_M)
    offset_160m = math.degrees(160 / EARTH_RADIUS_M)
    points.extend(
        [
            _point(5, 20, longitude=offset_201m),
            _point(6, 25, longitude=offset_160m),
            _point(7, 30, longitude=offset_160m),
            _point(8, 35, longitude=offset_160m),
        ]
    )
    visits = generate_stay_points(points)
    assert len(visits) == 1
    assert visits[0].point_count == 8
    assert visits[0].source_first_point_id == points[0].id
    assert visits[0].source_last_point_id == points[-1].id


def test_candidates_more_than_ten_minutes_apart_are_not_merged() -> None:
    offset_201m = math.degrees(201 / EARTH_RADIUS_M)
    offset_160m = math.degrees(160 / EARTH_RADIUS_M)
    points = _stationary_points() + [
        _point(11, 26, longitude=offset_201m),
        _point(12, 31, longitude=offset_160m),
        _point(13, 36, longitude=offset_160m),
        _point(14, 41, longitude=offset_160m),
    ]
    visits = generate_stay_points(points)
    assert len(visits) == 2
    assert visits[1].started_at_ms - visits[0].ended_at_ms == 11 * 60_000


def test_weighted_center_haversine_and_identity_are_deterministic() -> None:
    first = _point(1, 0, accuracy=10.0)
    second = _point(2, 5, longitude=0.001, accuracy=20.0)
    latitude, longitude = weighted_center([first, second])
    assert latitude == 0.0
    assert 0.00019 < longitude < 0.00021
    assert 100 < haversine_m(0, 0, 0, 0.001) < 115

    points = _stationary_points()
    first_run = generate_stay_points(points)
    repeated_run = generate_stay_points(list(reversed(points)))
    assert first_run == repeated_run
    visit = first_run[0]
    assert visit.algorithm_version == ALGORITHM_VERSION
    assert visit.id == deterministic_visit_id(
        visit.started_at_ms,
        DEVICE_ID,
        visit.source_first_point_id,
        visit.source_last_point_id,
    )
    assert len(visit.id) == 26


def test_points_from_multiple_devices_are_rejected() -> None:
    points = _stationary_points()
    points[1].device_id = "01J00000000000000000001002"
    try:
        generate_stay_points(points)
    except ValueError as error:
        assert "exactly one device" in str(error)
    else:
        raise AssertionError("mixed devices must not be clustered together")
