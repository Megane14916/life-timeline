"""End-to-end tests for the bounded JSON LocationPoint sync endpoint."""

from __future__ import annotations

import asyncio
import copy
import json
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any, cast

import httpx
import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import func, select
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session, sessionmaker

from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory
from app.ids import new_ulid
from app.main import create_app
from app.models import Device, LocationPoint
from app.repositories.locations import LocationPointRepository

CONTRACT_PATH = Path(__file__).parents[2] / "contracts" / "sync" / "locations-v1.json"
MAX_REQUEST_BYTES = 262_144


def _fixture() -> dict[str, Any]:
    return cast(dict[str, Any], json.loads(CONTRACT_PATH.read_text(encoding="utf-8")))


def _database(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Engine, sessionmaker[Session], Any]:
    data_dir = tmp_path / "data"
    monkeypatch.setenv("LIFE_TIMELINE_DATA_DIR", str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    factory = create_session_factory(engine)
    return engine, factory, create_app(factory)


def _post(application: Any, payload: dict[str, Any]) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post("/api/v1/sync/locations", json=payload)

    return asyncio.run(request())


def _payload(locations: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    request = copy.deepcopy(_fixture()["request"])
    if locations is not None:
        request["locations"] = locations
    return request


def _counts(factory: sessionmaker[Session]) -> tuple[int, int]:
    with factory() as session:
        return (
            session.scalar(select(func.count()).select_from(Device)) or 0,
            session.scalar(select(func.count()).select_from(LocationPoint)) or 0,
        )


def test_location_sync_saves_fixture_idempotently_and_preserves_raw_values(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    request = _fixture()["request"]
    try:
        first = _post(application, request)
        with factory() as session:
            point = session.get(LocationPoint, request["locations"][0]["id"])
            assert point is not None
            created_at = point.created_at_ms
        replay = _post(application, request)

        expected = {"schemaVersion": 1, "accepted": [request["locations"][0]["id"]]}
        assert first.status_code == replay.status_code == 200
        assert first.json() == replay.json() == expected
        assert _counts(factory) == (1, 1)
        with factory() as session:
            point = session.get(LocationPoint, request["locations"][0]["id"])
            assert point is not None
            assert point.created_at_ms == created_at
            assert point.latitude == 0.001
            assert point.longitude == 0.001
            assert point.accuracy_m == 25.0
            assert point.altitude_m is None
            assert point.speed_mps is None
            assert point.source == "android_fused_location"
    finally:
        engine.dispose()


def test_empty_batch_returns_empty_ack_without_creating_device(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    try:
        response = _post(application, _payload([]))
        assert response.status_code == 200
        assert response.json() == {"schemaVersion": 1, "accepted": []}
        assert _counts(factory) == (0, 0)
    finally:
        engine.dispose()


def test_sync_accepts_two_hundred_locations_and_rejects_two_hundred_one(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    base = copy.deepcopy(_fixture()["request"]["locations"][0])
    locations = [
        {**base, "id": new_ulid(), "recordedAtMs": base["recordedAtMs"] + index}
        for index in range(200)
    ]
    try:
        accepted = _post(application, _payload(locations))
        too_many = _post(application, _payload([*locations, {**base, "id": new_ulid()}]))
        assert accepted.status_code == 200
        assert accepted.json() == {"schemaVersion": 1, "accepted": [p["id"] for p in locations]}
        assert too_many.status_code == 422
        assert too_many.json()["error"]["code"] == "invalid_request"
        assert _counts(factory) == (1, 200)
    finally:
        engine.dispose()


def test_partial_duplicate_batch_acks_all_ids_in_request_order(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    initial = copy.deepcopy(_fixture()["request"]["locations"][0])
    first, second, third = initial, {**initial, "id": new_ulid()}, {**initial, "id": new_ulid()}
    try:
        assert _post(application, _payload([first, second])).status_code == 200
        replay = _post(application, _payload([second, third, first]))
        assert replay.status_code == 200
        assert replay.json() == {
            "schemaVersion": 1,
            "accepted": [second["id"], third["id"], first["id"]],
        }
        assert _counts(factory) == (1, 3)
    finally:
        engine.dispose()


def test_different_content_for_existing_id_returns_409_and_rolls_back_batch(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    initial = copy.deepcopy(_fixture()["request"]["locations"][0])
    other = {**initial, "id": new_ulid()}
    try:
        assert _post(application, _payload([initial, other])).status_code == 200
        changed = {**other, "latitude": 0.002}
        new_row = {**initial, "id": new_ulid()}
        conflicting = _post(application, _payload([new_row, changed]))
        assert conflicting.status_code == 409
        assert conflicting.json() == {
            "error": {
                "code": "sync_conflict",
                "message": "A location ID is already stored with different content.",
                "field": "locations[1].id",
            }
        }
        assert _counts(factory) == (1, 2)
        with factory() as session:
            assert session.get(LocationPoint, new_row["id"]) is None
            device = session.get(Device, _fixture()["request"]["device"]["id"])
            assert device is not None and device.name == "Synthetic Android"
    finally:
        engine.dispose()


def test_unknown_fields_invalid_coordinates_and_wrong_content_type_are_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    invalid = _payload()
    invalid["locations"][0]["latitude"] = 90.1
    unknown = _payload()
    unknown["locations"][0]["provider"] = "synthetic-only"

    async def send_without_json_content_type() -> httpx.Response:
        transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/sync/locations",
                content=json.dumps(_payload()),
                headers={"content-type": "text/plain"},
            )

    try:
        invalid_response = _post(application, invalid)
        unknown_response = _post(application, unknown)
        wrong_type = asyncio.run(send_without_json_content_type())
        for response in (invalid_response, unknown_response, wrong_type):
            assert response.status_code == 422
            assert response.json()["error"]["code"] == "invalid_request"
        assert invalid_response.json()["error"]["field"] == "locations[0].latitude"
        assert wrong_type.json()["error"]["field"] == "contentType"
        assert _counts(factory) == (0, 0)
    finally:
        engine.dispose()


def test_request_size_limit_accepts_exact_cap_and_rejects_oversize_streams(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    raw_json = json.dumps(_payload()).encode("utf-8")
    exact_body = raw_json + b" " * (MAX_REQUEST_BYTES - len(raw_json))

    async def post_body(body: bytes) -> httpx.Response:
        transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/sync/locations", content=body, headers={"content-type": "application/json"}
            )

    async def post_stream() -> httpx.Response:
        async def chunks() -> AsyncIterator[bytes]:
            yield b" " * (MAX_REQUEST_BYTES // 2)
            yield b" " * (MAX_REQUEST_BYTES // 2 + 1)

        transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/sync/locations",
                content=chunks(),
                headers={"content-type": "application/json"},
            )

    try:
        exact = asyncio.run(post_body(exact_body))
        oversized = asyncio.run(post_body(b" " * (MAX_REQUEST_BYTES + 1)))
        streamed = asyncio.run(post_stream())
        assert exact.status_code == 200
        for response in (oversized, streamed):
            assert response.status_code == 413
            assert response.json() == {
                "error": {
                    "code": "payload_too_large",
                    "message": "The location sync request exceeds the size limit.",
                    "field": None,
                }
            }
        assert _counts(factory) == (1, 1)
    finally:
        engine.dispose()


def test_sqlite_busy_is_reported_as_temporary_unavailable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)

    def fail_save_many(self: LocationPointRepository, records: Any) -> Any:
        raise OperationalError("write", {}, RuntimeError("database is locked"))

    monkeypatch.setattr(LocationPointRepository, "save_many", fail_save_many)
    try:
        response = _post(application, _fixture()["request"])
        assert response.status_code == 503
        assert response.json() == {
            "error": {
                "code": "temporarily_unavailable",
                "message": "The PC is temporarily unavailable.",
                "field": None,
            }
        }
        assert _counts(factory) == (0, 0)
    finally:
        engine.dispose()


def test_database_constraints_reject_invalid_location_values(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, _application = _database(tmp_path, monkeypatch)
    device_id = _fixture()["request"]["device"]["id"]
    with factory.begin() as session:
        session.add(
            Device(
                id=device_id,
                name="Synthetic Android",
                platform="android",
                created_at_ms=1,
                last_seen_at_ms=1,
            )
        )

    invalid_values = [
        {"latitude": 90.1},
        {"longitude": -180.1},
        {"accuracy_m": -1.0},
        {"altitude_m": float("inf")},
        {"speed_mps": -1.0},
        {"source": "unknown"},
    ]
    try:
        for invalid in invalid_values:
            values: dict[str, Any] = {
                "id": new_ulid(),
                "device_id": device_id,
                "recorded_at_ms": 1,
                "latitude": 0.0,
                "longitude": 0.0,
                "accuracy_m": None,
                "altitude_m": None,
                "speed_mps": None,
                "source": "android_fused_location",
                "created_at_ms": 1,
            }
            values.update(invalid)
            with factory() as session:
                session.add(LocationPoint(**values))
                with pytest.raises(IntegrityError):
                    session.flush()
                session.rollback()
        assert _counts(factory) == (1, 0)
    finally:
        engine.dispose()
