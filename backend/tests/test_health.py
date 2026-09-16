import asyncio
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient, Response

from app.config import DATA_DIR_ENV, Settings
from app.db import create_engine_for_settings, create_session_factory
from app.main import create_app


def _alembic_config() -> Config:
    return Config(str(Path(__file__).parents[1] / "alembic.ini"))


def _app(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, *, migrate: bool) -> FastAPI:
    data_dir = tmp_path / ("migrated" if migrate else "unmigrated")
    monkeypatch.setenv(DATA_DIR_ENV, str(data_dir))
    if migrate:
        command.upgrade(_alembic_config(), "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    return create_app(create_session_factory(engine))


async def get(application: FastAPI, path: str) -> Response:
    transport = ASGITransport(app=application)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        return await client.get(path)


def test_health_returns_ok(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    response = asyncio.run(get(_app(tmp_path, monkeypatch, migrate=True), "/api/v1/health"))

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_health_reports_unmigrated_database(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    application = _app(tmp_path, monkeypatch, migrate=False)
    response = asyncio.run(get(application, "/api/v1/health"))
    timeline_response = asyncio.run(
        get(application, "/api/v1/timeline?date=2026-09-03&timezone=UTC")
    )

    assert response.status_code == 503
    assert response.json() == {
        "error": {
            "code": "temporarily_unavailable",
            "message": (
                "Backend database is not initialized. Run the migration before starting the server."
            ),
            "field": None,
        }
    }
    assert timeline_response.status_code == 503
    assert timeline_response.json() == response.json()


def test_unknown_route_returns_not_found(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    response = asyncio.run(get(_app(tmp_path, monkeypatch, migrate=True), "/api/v1/unknown"))

    assert response.status_code == 404
