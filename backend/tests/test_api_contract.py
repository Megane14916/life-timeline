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
from app.models import AppSession
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
