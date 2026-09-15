"""Contract and boundary tests for the daily Map API."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import httpx
import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.cli.fixtures import WINDOWS_DEVICE_ID
from app.cli.seed import main as seed_main
from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory
from app.main import create_app
from app.models import LocationPoint, MediaItem, PlaceVisit
from app.services.time_range import build_day_range

DEVICE_A = "01J00000000000000000001001"
DEVICE_B = "01J00000000000000000001002"


def _migrated_seeded_database(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Engine, sessionmaker[Session]]:
    data_dir = tmp_path / "data"
    monkeypatch.setenv("LIFE_TIMELINE_DATA_DIR", str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    assert seed_main(["--data-dir", str(data_dir)]) == 0
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    return engine, create_session_factory(engine)


def _get(app: Any, path: str, **params: str) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path, params=params)

    return asyncio.run(request())


def _point(
    point_id: int,
    device_id: str,
    recorded_at_ms: int,
    *,
    accuracy_m: float | None = 25.0,
) -> LocationPoint:
    return LocationPoint(
        id=f"{point_id:026d}",
        device_id=device_id,
        recorded_at_ms=recorded_at_ms,
        latitude=35.0 + point_id / 1_000_000,
        longitude=139.0,
        accuracy_m=accuracy_m,
        altitude_m=None,
        speed_mps=None,
        source="android_fused_location",
        created_at_ms=recorded_at_ms,
    )


def _photo(
    photo_id: int,
    captured_at_ms: int,
    *,
    latitude: float | None,
    longitude: float | None,
) -> MediaItem:
    return MediaItem(
        id=f"{photo_id:026d}",
        device_id=DEVICE_A,
        type="photo",
        source="android_media_store",
        source_id=f"content://media/{photo_id}",
        filename=f"photo-{photo_id}.jpg",
        captured_at_ms=captured_at_ms,
        width=640,
        height=480,
        duration_ms=None,
        latitude=latitude,
        longitude=longitude,
        thumbnail_path=None,
        thumbnail_mime_type=None,
        thumbnail_width=None,
        thumbnail_height=None,
        thumbnail_size_bytes=None,
        thumbnail_sha256=None,
        mime_type="image/jpeg",
        created_at_ms=captured_at_ms,
    )


def test_empty_map_and_invalid_date_or_timezone_use_public_contract(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        app = create_app(factory)
        empty = _get(app, "/api/v1/map", date="2026-09-03", timezone="Asia/Tokyo")
        assert empty.status_code == 200
        payload = empty.json()
        assert payload["rangeStart"] == "2026-09-02T15:00:00.000Z"
        assert payload["rangeEnd"] == "2026-09-03T15:00:00.000Z"
        assert payload["routes"] == []
        assert payload["placeVisits"] == []
        assert payload["photos"] == []

        for params, expected_field in [
            ({"date": "2026-02-30"}, "date"),
            ({"date": "2026-09-03", "timezone": "Not/AZone"}, "timezone"),
        ]:
            invalid = _get(app, "/api/v1/map", **params)
            assert invalid.status_code == 422
            assert invalid.json()["error"]["code"] == "invalid_request"
            assert invalid.json()["error"]["field"] == expected_field
    finally:
        engine.dispose()


def test_routes_filter_accuracy_and_split_per_device_after_thirty_minutes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        start = build_day_range("2026-09-03", "UTC")[1].start_ms
        minutes = 60_000
        timestamps = [0, 10, 20, 25, 51, 56]
        with factory.begin() as session:
            for index, minute in enumerate(timestamps):
                session.add(
                    _point(
                        200 + index,
                        DEVICE_A,
                        start + minute * minutes,
                        accuracy_m=1_001.0 if minute == 25 else 25.0,
                    )
                )
            session.add(_point(210, DEVICE_A, start + 30 * minutes, accuracy_m=None))
            session.add(_point(211, DEVICE_B, start + minutes, accuracy_m=1_000.0))
            session.add(_point(212, WINDOWS_DEVICE_ID, start + minutes))
            session.add(_point(213, WINDOWS_DEVICE_ID, start + 31 * minutes))

        response = _get(create_app(factory), "/api/v1/map", date="2026-09-03", timezone="UTC")
        assert response.status_code == 200
        routes = response.json()["routes"]
        assert [(route["deviceId"], route["pointCount"]) for route in routes] == [
            (DEVICE_A, 3),
            (DEVICE_A, 2),
            (DEVICE_B, 1),
            (WINDOWS_DEVICE_ID, 2),
        ]
        assert [route["points"][0]["recordedAt"] for route in routes] == [
            "2026-09-03T00:00:00.000Z",
            "2026-09-03T00:51:00.000Z",
            "2026-09-03T00:01:00.000Z",
            "2026-09-03T00:01:00.000Z",
        ]
        assert all(point["accuracyM"] <= 1_000 for route in routes for point in route["points"])
    finally:
        engine.dispose()


def test_map_does_not_draw_dwell_fixes_as_routes_or_bridge_across_a_place_visit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        start = build_day_range("2026-09-03", "UTC")[1].start_ms
        dwell_points = [
            LocationPoint(
                id=f"{60_000 + index:026d}",
                device_id=DEVICE_A,
                recorded_at_ms=start + index * 5 * 60_000,
                latitude=35.0,
                longitude=139.0,
                accuracy_m=20.0,
                altitude_m=None,
                speed_mps=None,
                source="android_fused_location",
                created_at_ms=start + index * 5 * 60_000,
            )
            for index in range(9)
        ]
        # This lower-confidence fix is offset from the stationary cluster, but its
        # accuracy radius still overlaps the same PlaceVisit.
        dwell_points[4].latitude = 35.004
        dwell_points[4].accuracy_m = 500.0
        visit = PlaceVisit(
            id="01J00000000000000000003001",
            device_id=DEVICE_A,
            started_at_ms=dwell_points[0].recorded_at_ms,
            ended_at_ms=dwell_points[-1].recorded_at_ms,
            duration_ms=dwell_points[-1].recorded_at_ms - dwell_points[0].recorded_at_ms,
            center_latitude=35.0,
            center_longitude=139.0,
            radius_m=5.0,
            point_count=8,
            algorithm_version="stay_point_v1",
            source_first_point_id=dwell_points[0].id,
            source_last_point_id=dwell_points[-1].id,
            created_at_ms=dwell_points[-1].created_at_ms,
        )
        with factory.begin() as session:
            session.add_all(dwell_points)
            session.flush()
            session.add(visit)

        response = _get(create_app(factory), "/api/v1/map", date="2026-09-03", timezone="UTC")

        assert response.status_code == 200
        payload = response.json()
        assert payload["routes"] == []
        assert len(payload["placeVisits"]) == 1
        assert payload["placeVisits"][0]["pointCount"] == 8
        with factory() as session:
            assert session.query(LocationPoint).count() >= len(dwell_points)
    finally:
        engine.dispose()


def test_map_keeps_distant_point_as_isolated_marker_instead_of_linking_through_visit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        start = build_day_range("2026-09-03", "UTC")[1].start_ms
        dwell_points = [
            LocationPoint(
                id=f"{61_000 + index:026d}",
                device_id=DEVICE_A,
                recorded_at_ms=start + index * 5 * 60_000,
                latitude=35.0,
                longitude=139.0,
                accuracy_m=20.0,
                altitude_m=None,
                speed_mps=None,
                source="android_fused_location",
                created_at_ms=start + index * 5 * 60_000,
            )
            for index in range(9)
        ]
        outlier = LocationPoint(
            id=f"{61_100:026d}",
            device_id=DEVICE_A,
            recorded_at_ms=start + 45 * 60_000,
            latitude=35.05,
            longitude=139.0,
            accuracy_m=25.0,
            altitude_m=None,
            speed_mps=None,
            source="android_fused_location",
            created_at_ms=start + 45 * 60_000,
        )
        visit = PlaceVisit(
            id="01J00000000000000000003002",
            device_id=DEVICE_A,
            started_at_ms=dwell_points[0].recorded_at_ms,
            ended_at_ms=dwell_points[-1].recorded_at_ms,
            duration_ms=dwell_points[-1].recorded_at_ms - dwell_points[0].recorded_at_ms,
            center_latitude=35.0,
            center_longitude=139.0,
            radius_m=5.0,
            point_count=9,
            algorithm_version="stay_point_v1",
            source_first_point_id=dwell_points[0].id,
            source_last_point_id=dwell_points[-1].id,
            created_at_ms=dwell_points[-1].created_at_ms,
        )
        with factory.begin() as session:
            session.add_all([*dwell_points, outlier])
            session.flush()
            session.add(visit)

        response = _get(create_app(factory), "/api/v1/map", date="2026-09-03", timezone="UTC")

        assert response.status_code == 200
        routes = response.json()["routes"]
        assert len(routes) == 1
        assert routes[0]["pointCount"] == 1
        assert routes[0]["points"][0]["latitude"] == outlier.latitude
    finally:
        engine.dispose()


def test_map_excludes_low_confidence_jitter_near_daily_place_visit_outside_visit_time(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        start = build_day_range("2026-09-03", "UTC")[1].start_ms
        minutes = 60_000
        points = [
            LocationPoint(
                id=f"{62_000 + index:026d}",
                device_id=DEVICE_A,
                recorded_at_ms=start + minute * minutes,
                latitude=35.0 if minute < 30 else 35.003,
                longitude=139.0,
                accuracy_m=20.0 if minute < 30 else 500.0,
                altitude_m=None,
                speed_mps=None,
                source="android_fused_location",
                created_at_ms=start + minute * minutes,
            )
            for index, minute in enumerate([0, 5, 10, 15, 20, 25, 56, 61, 66])
        ]
        visit = PlaceVisit(
            id="01J00000000000000000003003",
            device_id=DEVICE_A,
            started_at_ms=points[0].recorded_at_ms,
            ended_at_ms=points[5].recorded_at_ms,
            duration_ms=points[5].recorded_at_ms - points[0].recorded_at_ms,
            center_latitude=35.0,
            center_longitude=139.0,
            radius_m=1.0,
            point_count=6,
            algorithm_version="stay_point_v1",
            source_first_point_id=points[0].id,
            source_last_point_id=points[5].id,
            created_at_ms=points[5].created_at_ms,
        )
        with factory.begin() as session:
            session.add_all(points)
            session.flush()
            session.add(visit)

        response = _get(create_app(factory), "/api/v1/map", date="2026-09-03", timezone="UTC")

        assert response.status_code == 200
        payload = response.json()
        assert payload["routes"] == []
        assert len(payload["placeVisits"]) == 1
        with factory() as session:
            assert session.query(LocationPoint).count() >= len(points)
    finally:
        engine.dispose()


def test_place_visit_is_clipped_and_only_geotagged_photos_are_returned(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        start = build_day_range("2026-09-03", "Asia/Tokyo")[1].start_ms
        visit_start = start - 10 * 60_000
        visit_end = start + 10 * 60_000
        source_ids = [f"{point_id:026d}" for point_id in (301, 302, 303)]
        with factory.begin() as session:
            for index, point_id in enumerate((301, 302, 303)):
                session.add(_point(point_id, DEVICE_A, visit_start + index * 10 * 60_000))
            session.flush()
            session.add(
                PlaceVisit(
                    id=f"{401:026d}",
                    device_id=DEVICE_A,
                    started_at_ms=visit_start,
                    ended_at_ms=visit_end,
                    duration_ms=visit_end - visit_start,
                    center_latitude=35.0,
                    center_longitude=139.0,
                    radius_m=25.0,
                    point_count=3,
                    algorithm_version="stay_point_v1",
                    source_first_point_id=source_ids[0],
                    source_last_point_id=source_ids[-1],
                    created_at_ms=start,
                )
            )
            session.add(_photo(501, start + 2 * 60_000, latitude=35.1, longitude=139.1))
            session.add(_photo(502, start + 3 * 60_000, latitude=None, longitude=None))

        response = _get(
            create_app(factory),
            "/api/v1/map",
            date="2026-09-03",
            timezone="Asia/Tokyo",
        )
        assert response.status_code == 200
        payload = response.json()
        assert len(payload["placeVisits"]) == 1
        visit = payload["placeVisits"][0]
        assert visit["durationMs"] == 20 * 60_000
        assert visit["display"] == {
            "startedAt": "2026-09-02T15:00:00.000Z",
            "endedAt": "2026-09-02T15:10:00.000Z",
            "durationMs": 10 * 60_000,
            "continuesFromPreviousDay": True,
            "continuesToNextDay": False,
            "endsAtDayBoundary": False,
        }
        assert len(payload["photos"]) == 1
        assert payload["photos"][0]["id"] == f"{501:026d}"
        assert payload["photos"][0]["latitude"] == 35.1
        assert payload["photos"][0]["longitude"] == 139.1
    finally:
        engine.dispose()


def test_dst_and_utc_midnight_ranges_are_half_open(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        dst_range = build_day_range("2026-03-08", "America/New_York")[1]
        dst_start, dst_end = dst_range.start_ms, dst_range.end_ms
        assert dst_end - dst_start == 23 * 60 * 60 * 1000
        utc_range = build_day_range("2026-09-03", "UTC")[1]
        utc_start, utc_end = utc_range.start_ms, utc_range.end_ms
        with factory.begin() as session:
            session.add_all(
                [
                    _point(601, DEVICE_A, dst_start - 1),
                    _point(602, DEVICE_A, dst_start),
                    _point(603, DEVICE_A, dst_end - 1),
                    _point(604, DEVICE_A, dst_end),
                    _point(605, DEVICE_B, utc_start - 1),
                    _point(606, DEVICE_B, utc_start),
                    _point(607, DEVICE_B, utc_end),
                ]
            )

        app = create_app(factory)
        dst = _get(
            app,
            "/api/v1/map",
            date="2026-03-08",
            timezone="America/New_York",
        )
        assert dst.status_code == 200
        assert dst.json()["rangeStart"] == "2026-03-08T05:00:00.000Z"
        assert dst.json()["rangeEnd"] == "2026-03-09T04:00:00.000Z"
        assert [
            point["recordedAt"] for route in dst.json()["routes"] for point in route["points"]
        ] == ["2026-03-08T05:00:00.000Z", "2026-03-09T03:59:59.999Z"]

        utc = _get(app, "/api/v1/map", date="2026-09-03", timezone="UTC")
        assert utc.status_code == 200
        assert [
            point["recordedAt"] for route in utc.json()["routes"] for point in route["points"]
        ] == ["2026-09-03T00:00:00.000Z"]
    finally:
        engine.dispose()


def test_map_returns_all_one_thousand_points_without_a_route_cap(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        start = build_day_range("2026-09-03", "UTC")[1].start_ms
        with factory.begin() as session:
            session.add_all(
                _point(10_000 + index, DEVICE_A, start + index * 1_000) for index in range(1_000)
            )

        response = _get(create_app(factory), "/api/v1/map", date="2026-09-03", timezone="UTC")
        assert response.status_code == 200
        routes = response.json()["routes"]
        assert len(routes) == 1
        assert routes[0]["pointCount"] == 1_000
        assert len(routes[0]["points"]) == 1_000
    finally:
        engine.dispose()


def test_openapi_exposes_daily_map_response_contract() -> None:
    schema = create_app().openapi()
    assert "/api/v1/map" in schema["paths"]
