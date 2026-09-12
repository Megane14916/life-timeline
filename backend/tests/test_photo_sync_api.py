"""End-to-end tests for the bounded multipart photo sync endpoint."""

from __future__ import annotations

import asyncio
import copy
import hashlib
import io
import json
from pathlib import Path
from typing import Any

import httpx
import pytest
from alembic import command
from alembic.config import Config
from PIL import Image
from sqlalchemy import event, func, select
from sqlalchemy.engine import Engine
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session, sessionmaker

from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory
from app.main import create_app
from app.models import Device, MediaItem
from app.repositories import MasterRepository
from app.services.thumbnail_store import ThumbnailStore

CONTRACT_PATH = Path(__file__).parents[2] / "contracts" / "sync" / "photos-v1.json"
THUMBNAIL_PATH = (
    Path(__file__).parents[2] / "contracts" / "sync" / "fixtures" / "synthetic-thumbnail.webp"
)
MAX_REQUEST_BYTES = 20_971_520
MAX_THUMBNAIL_BYTES = 1_048_576


def _fixture() -> dict[str, Any]:
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


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


def _post(
    application: Any,
    metadata: dict[str, Any],
    files: list[tuple[str, tuple[str, bytes, str]]] | None = None,
    *,
    headers: dict[str, str] | None = None,
) -> httpx.Response:
    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
        parts = [("metadata", (None, json.dumps(metadata), "application/json"))]
        parts.extend(files or [])
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/sync/photos",
                files=parts,
                headers=headers,
            )

    return asyncio.run(request())


def _file(photo_id: str, content: bytes | None = None) -> tuple[str, tuple[str, bytes, str]]:
    return (
        f"thumbnail_{photo_id}",
        (r"C:\private\camera-original.jpg", content or THUMBNAIL_PATH.read_bytes(), "image/webp"),
    )


def _counts(factory: sessionmaker[Session]) -> tuple[int, int]:
    with factory() as session:
        return (
            session.scalar(select(func.count()).select_from(Device)) or 0,
            session.scalar(select(func.count()).select_from(MediaItem)) or 0,
        )


def test_sync_saves_fixture_idempotently_and_keeps_server_generated_paths(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    metadata = _fixture()["request"]
    image = THUMBNAIL_PATH.read_bytes()
    try:
        first = _post(application, metadata, [_file(metadata["photos"][0]["id"], image)])
        with factory() as session:
            created_at = session.get(MediaItem, metadata["photos"][0]["id"]).created_at_ms
        second = _post(application, metadata, [_file(metadata["photos"][0]["id"], image)])

        expected = {"schemaVersion": 1, "accepted": [photo["id"] for photo in metadata["photos"]]}
        assert first.status_code == second.status_code == 200
        assert first.json() == second.json() == expected
        assert _counts(factory) == (1, 2)
        with factory() as session:
            item = session.get(MediaItem, metadata["photos"][0]["id"])
            assert item is not None
            assert item.created_at_ms == created_at
            assert item.thumbnail_path is not None
            assert item.thumbnail_path.startswith("thumbnails/")
            assert r"C:\private" not in item.thumbnail_path
        assert len(list((tmp_path / "data" / "thumbnails").rglob("*.webp"))) == 1
    finally:
        engine.dispose()


def test_metadata_only_item_can_later_receive_its_thumbnail(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    fixture_request = _fixture()["request"]
    metadata_only_photo = copy.deepcopy(fixture_request["photos"][1])
    request = {**fixture_request, "photos": [metadata_only_photo]}
    try:
        initial = _post(application, request)
        assert initial.status_code == 200
        assert _counts(factory) == (1, 1)
        with factory() as session:
            before = session.get(MediaItem, metadata_only_photo["id"])
            assert before is not None
            created_at = before.created_at_ms
            assert before.thumbnail_path is None

        completed_photo = copy.deepcopy(metadata_only_photo)
        completed_photo["thumbnail"] = copy.deepcopy(fixture_request["photos"][0]["thumbnail"])
        completed = {**fixture_request, "photos": [completed_photo]}
        response = _post(
            application,
            completed,
            [_file(completed_photo["id"], THUMBNAIL_PATH.read_bytes())],
        )

        assert response.status_code == 200
        assert response.json() == {"schemaVersion": 1, "accepted": [completed_photo["id"]]}
        assert _counts(factory) == (1, 1)
        with factory() as session:
            after = session.get(MediaItem, completed_photo["id"])
            assert after is not None
            assert after.created_at_ms == created_at
            assert after.thumbnail_path is not None
            assert after.thumbnail_sha256 == completed_photo["thumbnail"]["sha256"]
    finally:
        engine.dispose()


@pytest.mark.parametrize(
    ("mutation", "field"),
    [
        ("unknown", "originalPath"),
        ("hash", "sha256"),
        ("size", "byteSize"),
        ("dimensions", "width"),
    ],
)
def test_invalid_metadata_and_thumbnail_are_rejected_without_writes(
    mutation: str,
    field: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    original_metadata = copy.deepcopy(_fixture()["request"])
    metadata = copy.deepcopy(original_metadata)
    photo = metadata["photos"][0]
    if mutation == "unknown":
        photo[field] = "must not be stored"
    elif mutation == "hash":
        photo["thumbnail"][field] = "0" * 64
    elif mutation == "size":
        photo["thumbnail"][field] += 1
    else:
        photo["thumbnail"][field] = 2
    try:
        response = _post(application, metadata, [_file(photo["id"])])
        assert response.status_code == 422
        assert response.json()["error"]["code"] == "invalid_request"
        assert _counts(factory) == (0, 0)
        assert list((tmp_path / "data" / "thumbnails").rglob("*.webp")) == []
    finally:
        engine.dispose()


def test_multipart_file_part_mismatch_and_wrong_content_type_are_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    metadata = _fixture()["request"]
    photo_id = metadata["photos"][0]["id"]
    try:
        missing = _post(application, metadata, [_file("01J00000000000000000000199")])
        wrong_type = _post(
            application,
            metadata,
            [
                (
                    f"thumbnail_{photo_id}",
                    ("thumbnail.webp", THUMBNAIL_PATH.read_bytes(), "image/png"),
                )
            ],
        )
        assert missing.status_code == wrong_type.status_code == 422
        assert (
            missing.json()["error"]["code"]
            == wrong_type.json()["error"]["code"]
            == "invalid_request"
        )
        assert _counts(factory) == (0, 0)
        assert list((tmp_path / "data" / "thumbnails").rglob("*.webp")) == []
    finally:
        engine.dispose()


def test_conflicting_photo_id_or_source_returns_409_and_preserves_existing_file(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    original_metadata = copy.deepcopy(_fixture()["request"])
    metadata = copy.deepcopy(original_metadata)
    photo = metadata["photos"][0]
    image_buffer = io.BytesIO()
    Image.new("RGB", (3, 2), (15, 21, 33)).save(image_buffer, format="WEBP", quality=65)
    changed_bytes = image_buffer.getvalue()
    photo["thumbnail"]["byteSize"] = len(changed_bytes)
    photo["thumbnail"]["sha256"] = hashlib.sha256(changed_bytes).hexdigest()
    try:
        assert (
            _post(
                application,
                original_metadata,
                [_file(original_metadata["photos"][0]["id"])],
            ).status_code
            == 200
        )
        hash_conflict = _post(application, metadata, [_file(photo["id"], changed_bytes)])
        source_conflict_metadata = copy.deepcopy(original_metadata)
        source_conflict_metadata["photos"][0]["id"] = "01K4N70E3Q6N9D6E6G0C8M2H1R"
        source_conflict = _post(
            application,
            source_conflict_metadata,
            [_file(source_conflict_metadata["photos"][0]["id"])],
        )

        assert hash_conflict.status_code == source_conflict.status_code == 409
        assert hash_conflict.json()["error"]["code"] == "sync_conflict"
        assert source_conflict.json()["error"]["code"] == "sync_conflict"
        assert _counts(factory) == (1, 2)
        assert len(list((tmp_path / "data" / "thumbnails").rglob("*.webp"))) == 1
    finally:
        engine.dispose()


def test_total_and_per_file_limits_return_safe_413_envelopes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)

    async def oversized_request() -> httpx.Response:
        transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/sync/photos",
                content=b"x" * (MAX_REQUEST_BYTES + 1),
                headers={"content-type": "multipart/form-data; boundary=invalid"},
            )

    oversized_metadata = copy.deepcopy(_fixture()["request"])
    photo_id = oversized_metadata["photos"][0]["id"]
    oversized_file = _file(photo_id, b"x" * (MAX_THUMBNAIL_BYTES + 1))
    try:
        total = asyncio.run(oversized_request())
        per_file = _post(application, oversized_metadata, [oversized_file])
        for response in (total, per_file):
            assert response.status_code == 413
            assert response.json() == {
                "error": {
                    "code": "payload_too_large",
                    "message": "The photo sync request exceeds the size limit.",
                    "field": None,
                }
            }
        assert _counts(factory) == (0, 0)
        assert list((tmp_path / "data" / "thumbnails").rglob("*.webp")) == []
    finally:
        engine.dispose()


def test_unbounded_request_stream_is_limited_without_content_length(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, _factory, application = _database(tmp_path, monkeypatch)

    async def chunks():
        yield b"x" * (MAX_REQUEST_BYTES // 2)
        yield b"x" * (MAX_REQUEST_BYTES // 2 + 1)

    async def request() -> httpx.Response:
        transport = httpx.ASGITransport(app=application, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.post(
                "/api/v1/sync/photos",
                content=chunks(),
                headers={"content-type": "multipart/form-data; boundary=invalid"},
            )

    try:
        response = asyncio.run(request())
        assert response.status_code == 413
        assert response.json()["error"]["code"] == "payload_too_large"
    finally:
        engine.dispose()


def test_write_rename_and_database_commit_failures_leave_no_dangling_rows_or_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)
    metadata = copy.deepcopy(_fixture()["request"])
    photo_id = metadata["photos"][0]["id"]
    try:
        original_path_open = Path.open

        def fail_temp_open(path: Path, *args: Any, **kwargs: Any) -> Any:
            if path.name == "thumbnail.part":
                raise PermissionError("synthetic thumbnail write failure")
            return original_path_open(path, *args, **kwargs)

        monkeypatch.setattr(Path, "open", fail_temp_open)
        failed_write = _post(application, metadata, [_file(photo_id)])
        monkeypatch.setattr(Path, "open", original_path_open)

        original_promote = ThumbnailStore.promote
        promote_count = 0

        def fail_second_promote(self: ThumbnailStore, staged: Any) -> Any:
            nonlocal promote_count
            promote_count += 1
            if promote_count == 2:
                raise PermissionError("synthetic rename failure")
            return original_promote(self, staged)

        two_photos = copy.deepcopy(metadata)
        second = copy.deepcopy(two_photos["photos"][0])
        second["id"] = "01K4N70E3Q6N9D6E6G0C8M2H1R"
        second["sourceId"] = "fixture-volume:other"
        two_photos["photos"].append(second)
        monkeypatch.setattr(ThumbnailStore, "promote", fail_second_promote)
        failed_rename = _post(
            application,
            two_photos,
            [_file(photo_id), _file(second["id"])],
        )
        monkeypatch.setattr(ThumbnailStore, "promote", original_promote)

        def fail_commit(_session: Session) -> None:
            raise RuntimeError("synthetic database commit failure")

        event.listen(Session, "before_commit", fail_commit)
        try:
            failed_commit = _post(application, metadata, [_file(photo_id)])
        finally:
            event.remove(Session, "before_commit", fail_commit)

        assert (
            failed_write.status_code
            == failed_rename.status_code
            == failed_commit.status_code
            == 500
        )
        assert (
            failed_write.json()["error"]["code"]
            == failed_rename.json()["error"]["code"]
            == failed_commit.json()["error"]["code"]
            == "internal_error"
        )
        assert _counts(factory) == (0, 0)
        assert list((tmp_path / "data" / "thumbnails").rglob("*.webp")) == []
        assert list((tmp_path / "data" / "thumbnails").rglob(".photo-sync-*")) == []
    finally:
        engine.dispose()


def test_sqlite_busy_is_reported_as_temporary_unavailable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory, application = _database(tmp_path, monkeypatch)

    def fail_device(self: MasterRepository, record: Any) -> Any:
        raise OperationalError("write", {}, RuntimeError("database is locked"))

    monkeypatch.setattr(MasterRepository, "save_device", fail_device)
    metadata = _fixture()["request"]
    try:
        response = _post(application, metadata, [_file(metadata["photos"][0]["id"])])
        assert response.status_code == 503
        assert response.json() == {
            "error": {
                "code": "temporarily_unavailable",
                "message": "The PC is temporarily unavailable.",
                "field": None,
            }
        }
        assert _counts(factory) == (0, 0)
        assert list((tmp_path / "data" / "thumbnails").rglob("*.webp")) == []
    finally:
        engine.dispose()
