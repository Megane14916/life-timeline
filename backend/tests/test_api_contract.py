"""Contract tests for the read-only Timeline and Statistics APIs."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import httpx
import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import Engine, select
from sqlalchemy.orm import Session, sessionmaker

from app.cli.seed import main as seed_main
from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory
from app.main import create_app
from app.models import AppSession, LocationPoint, MediaItem, PlaceVisit
from app.services.time_range import build_day_range


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


def test_timeline_returns_clipped_master_joined_items_in_stable_order(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        response = _get(
            create_app(factory),
            "/api/v1/timeline",
            date="2026-09-03",
            timezone="Asia/Tokyo",
        )

        assert response.status_code == 200
        payload = response.json()
        assert payload["rangeStart"] == "2026-09-02T15:00:00.000Z"
        assert payload["rangeEnd"] == "2026-09-03T15:00:00.000Z"
        assert [item["id"] for item in payload["items"]] == [
            "01J00000000000000000001302",
            "01J00000000000000000001303",
            "01J00000000000000000001305",
            "01J00000000000000000001304",
        ]
        assert sum(item["display"]["durationMs"] for item in payload["items"]) == 3_900_000
        first = payload["items"][0]
        assert first["deviceName"] == "Demo Android A"
        assert first["appName"] == "Chrome"
        assert first["durationMs"] == 1_200_000
        assert first["display"] == {
            "startedAt": "2026-09-02T15:00:00.000Z",
            "endedAt": "2026-09-02T15:10:00.000Z",
            "durationMs": 600_000,
            "continuesFromPreviousDay": True,
            "continuesToNextDay": False,
            "endsAtDayBoundary": False,
        }
    finally:
        engine.dispose()


def test_timeline_merges_photo_and_app_session_items_in_stable_order(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        day_start = build_day_range("2026-09-03", "Asia/Tokyo")[1].start_ms
        with factory.begin() as session:
            point_ids = [
                "01J00000000000000000001601",
                "01J00000000000000000001602",
                "01J00000000000000000001603",
            ]
            for index, point_id in enumerate(point_ids):
                session.add(
                    LocationPoint(
                        id=point_id,
                        device_id="01J00000000000000000001001",
                        recorded_at_ms=day_start + 9 * 60 * 60 * 1000 + index * 5 * 60_000,
                        latitude=35.0,
                        longitude=139.0,
                        accuracy_m=10.0,
                        altitude_m=None,
                        speed_mps=None,
                        source="android_fused_location",
                        created_at_ms=day_start,
                    )
                )
            session.flush()
            visit_start = day_start + 9 * 60 * 60 * 1000
            session.add(
                PlaceVisit(
                    id="01J00000000000000000001501",
                    device_id="01J00000000000000000001001",
                    started_at_ms=visit_start,
                    ended_at_ms=visit_start + 15 * 60_000,
                    duration_ms=15 * 60_000,
                    center_latitude=35.0,
                    center_longitude=139.0,
                    radius_m=0,
                    point_count=3,
                    algorithm_version="stay_point_v1",
                    source_first_point_id=point_ids[0],
                    source_last_point_id=point_ids[-1],
                    created_at_ms=day_start,
                )
            )
            session.add(
                MediaItem(
                    id="01J00000000000000000001401",
                    device_id="01J00000000000000000001001",
                    type="photo",
                    source="android_media_store",
                    source_id="content://media/401",
                    filename="photo.jpg",
                    captured_at_ms=day_start + 9 * 60 * 60 * 1000,
                    width=640,
                    height=480,
                    duration_ms=None,
                    latitude=None,
                    longitude=None,
                    thumbnail_path=None,
                    thumbnail_mime_type=None,
                    thumbnail_width=None,
                    thumbnail_height=None,
                    thumbnail_size_bytes=None,
                    thumbnail_sha256=None,
                    mime_type="image/jpeg",
                    created_at_ms=day_start,
                )
            )

        response = _get(
            create_app(factory),
            "/api/v1/timeline",
            date="2026-09-03",
            timezone="Asia/Tokyo",
        )
        assert response.status_code == 200
        items = response.json()["items"]
        at_nine_am = [
            item
            for item in items
            if item.get("startedAt", item.get("takenAt")) == "2026-09-03T00:00:00.000Z"
        ]
        assert [item["type"] for item in at_nine_am] == [
            "app_session",
            "app_session",
            "place_visit",
            "photo",
        ]
        visit = at_nine_am[2]
        assert visit["label"] == "滞在地点"
        assert visit["deviceName"] == "Demo Android A"
        assert visit["pointCount"] == 3
        photo = at_nine_am[-1]
        assert photo["id"] == "01J00000000000000000001401"
        assert photo["takenAt"] == "2026-09-03T00:00:00.000Z"
        assert photo["thumbnailUrl"] is None
    finally:
        engine.dispose()


def test_place_visit_overlapping_timezone_day_is_clipped_without_changing_raw_duration(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        day_start = build_day_range("2026-09-03", "Asia/Tokyo")[1].start_ms
        started_at = day_start - 10 * 60_000
        ended_at = day_start + 10 * 60_000
        point_ids = [
            "01J00000000000000000001701",
            "01J00000000000000000001702",
            "01J00000000000000000001703",
        ]
        with factory.begin() as session:
            for index, point_id in enumerate(point_ids):
                session.add(
                    LocationPoint(
                        id=point_id,
                        device_id="01J00000000000000000001001",
                        recorded_at_ms=started_at + index * 10 * 60_000,
                        latitude=0.0,
                        longitude=0.0,
                        accuracy_m=10.0,
                        altitude_m=None,
                        speed_mps=None,
                        source="android_fused_location",
                        created_at_ms=started_at,
                    )
                )
            session.flush()
            session.add(
                PlaceVisit(
                    id="01J00000000000000000001502",
                    device_id="01J00000000000000000001001",
                    started_at_ms=started_at,
                    ended_at_ms=ended_at,
                    duration_ms=ended_at - started_at,
                    center_latitude=0.0,
                    center_longitude=0.0,
                    radius_m=0.0,
                    point_count=3,
                    algorithm_version="stay_point_v1",
                    source_first_point_id=point_ids[0],
                    source_last_point_id=point_ids[-1],
                    created_at_ms=started_at,
                )
            )

        application = create_app(factory)
        current_day = _get(
            application,
            "/api/v1/timeline",
            date="2026-09-03",
            timezone="Asia/Tokyo",
        )
        previous_day = _get(
            application,
            "/api/v1/timeline",
            date="2026-09-02",
            timezone="Asia/Tokyo",
        )
        assert current_day.status_code == previous_day.status_code == 200
        current_visit = next(
            item for item in current_day.json()["items"] if item["type"] == "place_visit"
        )
        previous_visit = next(
            item for item in previous_day.json()["items"] if item["type"] == "place_visit"
        )
        assert current_visit["startedAt"] == "2026-09-02T14:50:00.000Z"
        assert current_visit["endedAt"] == "2026-09-02T15:10:00.000Z"
        assert current_visit["durationMs"] == 1_200_000
        assert current_visit["display"] == {
            "startedAt": "2026-09-02T15:00:00.000Z",
            "endedAt": "2026-09-02T15:10:00.000Z",
            "durationMs": 600_000,
            "continuesFromPreviousDay": True,
            "continuesToNextDay": False,
            "endsAtDayBoundary": False,
        }
        assert previous_visit["display"]["continuesToNextDay"] is True
        assert previous_visit["display"]["endsAtDayBoundary"] is True
    finally:
        engine.dispose()


def test_statistics_aggregates_direct_overlaps_and_empty_ranges(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    app = create_app(factory)
    try:
        response = _get(
            app,
            "/api/v1/stats/apps",
            **{"from": "2026-09-03", "to": "2026-09-04", "timezone": "Asia/Tokyo"},
        )
        assert response.status_code == 200
        payload = response.json()
        assert payload["totals"] == {"usageMs": 3_900_000, "sessionCount": 4, "appCount": 2}
        assert [
            (item["appId"], item["usageMs"], item["sessionCount"]) for item in payload["items"]
        ] == [
            ("01J00000000000000000001101", 2_100_000, 3),
            ("01J00000000000000000001104", 1_800_000, 1),
        ]

        empty = _get(
            app,
            "/api/v1/stats/apps",
            **{"from": "2026-09-05", "to": "2026-09-06"},
        )
        assert empty.status_code == 200
        assert empty.json()["totals"] == {"usageMs": 0, "sessionCount": 0, "appCount": 0}
        assert empty.json()["items"] == []
    finally:
        engine.dispose()


@pytest.mark.parametrize(
    ("path", "params", "field"),
    [
        ("/api/v1/timeline", {}, "date"),
        ("/api/v1/timeline", {"date": "2026-02-30"}, "date"),
        ("/api/v1/timeline", {"date": "2026-09-03", "timezone": "Not/AZone"}, "timezone"),
        ("/api/v1/stats/apps", {"from": "2026-09-03"}, "to"),
        ("/api/v1/stats/apps", {"from": "2026-09-04", "to": "2026-09-03"}, "to"),
    ],
)
def test_invalid_requests_use_public_error_shape(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    path: str,
    params: dict[str, str],
    field: str,
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        response = _get(create_app(factory), path, **params)
        assert response.status_code == 422
        assert response.json()["error"]["code"] == "invalid_request"
        assert response.json()["error"]["field"] == field
    finally:
        engine.dispose()


def test_timezone_ranges_handle_dst_without_mutating_database(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    spring_date, spring = build_day_range("2026-03-08", "America/New_York")
    fall_date, fall = build_day_range("2026-11-01", "America/New_York")
    assert spring_date == "2026-03-08"
    assert fall_date == "2026-11-01"
    assert spring.end_ms - spring.start_ms == 23 * 60 * 60 * 1000
    assert fall.end_ms - fall.start_ms == 25 * 60 * 60 * 1000

    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        before = _session_snapshot(factory)
        app = create_app(factory)
        assert _get(app, "/api/v1/timeline", date="2026-09-03").status_code == 200
        assert (
            _get(
                app,
                "/api/v1/stats/apps",
                **{"from": "2026-09-03", "to": "2026-09-04"},
            ).status_code
            == 200
        )
        assert _session_snapshot(factory) == before
    finally:
        engine.dispose()


def _session_snapshot(factory: sessionmaker[Session]) -> tuple[int, int, int]:
    with factory() as session:
        rows = session.scalars(select(AppSession).order_by(AppSession.id)).all()
        return (
            len(rows),
            sum(row.duration_ms for row in rows),
            sum(row.created_at_ms for row in rows),
        )


def test_openapi_exposes_timeline_and_statistics_contracts() -> None:
    schema = create_app().openapi()
    assert "/api/v1/timeline" in schema["paths"]
    assert "/api/v1/stats/apps" in schema["paths"]
