"""API and persistence tests for the Android AppSession sync endpoint."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import httpx
import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import func, select
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory
from app.ids import new_ulid
from app.main import create_app
from app.models import App, AppSession, Device

DEVICE_ID = "01J00000000000000000000001"
APP_ID = "01J00000000000000000000011"
SESSION_ID = "01J00000000000000000000101"
STARTED_AT_MS = 1_700_000_000_000


def _database(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Engine, sessionmaker[Session]]:
    data_dir = tmp_path / "data"
    monkeypatch.setenv("LIFE_TIMELINE_DATA_DIR", str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    return engine, create_session_factory(engine)


def _payload(
    *,
    device_id: str = DEVICE_ID,
    app_id: str = APP_ID,
    session_id: str = SESSION_ID,
    identifier: str = "com.example.browser",
    started_at_ms: int = STARTED_AT_MS,
    duration_ms: int = 60_000,
) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "device": {"id": device_id, "name": "Example Android", "platform": "android"},
        "apps": [{"id": app_id, "identifier": identifier, "displayName": "Example Browser"}],
        "sessions": [
            {
                "id": session_id,
                "appId": app_id,
                "startedAtMs": started_at_ms,
                "endedAtMs": started_at_ms + duration_ms,
                "durationMs": duration_ms,
                "source": "android_usage_stats",
            }
        ],
    }


def _post(app: Any, payload: dict[str, Any]) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post("/api/v1/sync/app-sessions", json=payload)

    return asyncio.run(request())


def _get(app: Any, path: str, **params: str) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path, params=params)

    return asyncio.run(request())


def test_sync_accepts_new_and_identical_replay_and_updates_existing_views(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    application = create_app(factory)
    try:
        payload = _payload()
        first = _post(application, payload)
        second = _post(application, payload)

        assert first.status_code == 200
        assert first.json() == {"schemaVersion": 1, "accepted": [SESSION_ID]}
        assert second.status_code == 200
        assert second.json() == first.json()

        with factory() as session:
            assert session.scalar(select(func.count()).select_from(Device)) == 1
            assert session.scalar(select(func.count()).select_from(App)) == 1
            assert session.scalar(select(func.count()).select_from(AppSession)) == 1
            stored = session.get(AppSession, SESSION_ID)
            assert stored is not None
            created_at_ms = stored.created_at_ms

        timeline = _get(
            application,
            "/api/v1/timeline",
            date="2023-11-14",
            timezone="UTC",
        )
        statistics = _get(
            application,
            "/api/v1/stats/apps",
            **{"from": "2023-11-14", "to": "2023-11-15", "timezone": "UTC"},
        )
        assert timeline.status_code == 200
        assert [item["id"] for item in timeline.json()["items"]] == [SESSION_ID]
        assert statistics.status_code == 200
        assert statistics.json()["totals"] == {
            "usageMs": 60_000,
            "sessionCount": 1,
            "appCount": 1,
        }

        with factory() as session:
            stored_again = session.get(AppSession, SESSION_ID)
            assert stored_again is not None
            assert stored_again.created_at_ms == created_at_ms
    finally:
        engine.dispose()


def test_sync_accepts_empty_and_one_hundred_session_batches(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    application = create_app(factory)
    try:
        empty = _payload()
        empty["apps"] = []
        empty["sessions"] = []
        assert _post(application, empty).json() == {"schemaVersion": 1, "accepted": []}

        sessions = []
        for index in range(100):
            started_at_ms = STARTED_AT_MS + index * 2_000
            sessions.append(
                {
                    "id": new_ulid(),
                    "appId": APP_ID,
                    "startedAtMs": started_at_ms,
                    "endedAtMs": started_at_ms + 1_000,
                    "durationMs": 1_000,
                    "source": "android_usage_stats",
                }
            )
        batch = _payload()
        batch["sessions"] = sessions
        response = _post(application, batch)

        assert response.status_code == 200
        assert response.json()["accepted"] == [item["id"] for item in sessions]
        with factory() as session:
            assert session.scalar(select(func.count()).select_from(AppSession)) == 100
    finally:
        engine.dispose()


def test_sync_reuses_app_natural_key_across_devices(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    application = create_app(factory)
    try:
        first = _post(application, _payload())
        second = _post(
            application,
            _payload(
                device_id="01J00000000000000000000002",
                app_id="01J00000000000000000000012",
                session_id="01J00000000000000000000102",
            ),
        )

        assert first.status_code == second.status_code == 200
        with factory() as session:
            assert session.scalar(select(func.count()).select_from(Device)) == 2
            assert session.scalar(select(func.count()).select_from(App)) == 1
            assert session.scalar(select(func.count()).select_from(AppSession)) == 2
            rows = session.scalars(select(AppSession).order_by(AppSession.id)).all()
            assert rows[0].app_id == rows[1].app_id
    finally:
        engine.dispose()


def test_invalid_and_conflicting_batches_rollback_all_master_and_fact_writes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    application = create_app(factory)
    try:
        invalid = _payload()
        invalid["sessions"][0]["durationMs"] = 1
        invalid_response = _post(application, invalid)
        assert invalid_response.status_code == 422

        with factory() as session:
            assert session.scalar(select(func.count()).select_from(Device)) == 0
            assert session.scalar(select(func.count()).select_from(App)) == 0

        assert _post(application, _payload()).status_code == 200
        conflict = _payload(
            app_id="01J00000000000000000000012",
            identifier="com.example.conflict",
            duration_ms=61_000,
        )
        conflict["apps"].append(
            {"id": APP_ID, "identifier": "com.example.browser", "displayName": "Example Browser"}
        )
        conflict["sessions"].append(
            {
                "id": "01J00000000000000000000102",
                "appId": "01J00000000000000000000012",
                "startedAtMs": STARTED_AT_MS + 120_000,
                "endedAtMs": STARTED_AT_MS + 121_000,
                "durationMs": 1_000,
                "source": "android_usage_stats",
            }
        )
        conflict["sessions"][0]["appId"] = APP_ID
        conflict["sessions"][0]["startedAtMs"] = STARTED_AT_MS
        conflict["sessions"][0]["endedAtMs"] = STARTED_AT_MS + 61_000
        response = _post(application, conflict)

        assert response.status_code == 409
        assert response.json()["error"]["code"] == "sync_conflict"
        with factory() as session:
            assert session.scalar(select(func.count()).select_from(Device)) == 1
            assert session.scalar(select(func.count()).select_from(App)) == 1
            assert session.scalar(select(func.count()).select_from(AppSession)) == 1
    finally:
        engine.dispose()


def test_invalid_batch_shape_and_safe_server_errors(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    application = create_app(factory)
    try:
        extra = _payload()
        extra["unexpected"] = "must be rejected"
        response = _post(application, extra)
        assert response.status_code == 422
        assert response.json()["error"]["code"] == "invalid_request"

        import app.api.sync as sync_api
        from app.api.errors import TemporarilyUnavailableError

        def unavailable(*_args: Any, **_kwargs: Any) -> Any:
            raise TemporarilyUnavailableError()

        monkeypatch.setattr(sync_api, "sync_app_sessions", unavailable)
        unavailable_response = _post(application, _payload())
        assert unavailable_response.status_code == 503
        assert unavailable_response.json() == {
            "error": {
                "code": "temporarily_unavailable",
                "message": "The PC is temporarily unavailable.",
                "field": None,
            }
        }

        def unexpected(*_args: Any, **_kwargs: Any) -> Any:
            raise RuntimeError("secret database path and SQL must not leak")

        monkeypatch.setattr(sync_api, "sync_app_sessions", unexpected)
        error_response = _post(application, _payload())
        assert error_response.status_code == 500
        assert error_response.json() == {
            "error": {
                "code": "internal_error",
                "message": "Internal server error.",
                "field": None,
            }
        }
    finally:
        engine.dispose()
