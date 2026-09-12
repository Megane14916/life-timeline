"""Contract and file-safety tests for photo read APIs."""

from __future__ import annotations

import asyncio
import hashlib
from io import BytesIO
from pathlib import Path
from typing import Any

import httpx
import pytest
from alembic import command
from alembic.config import Config
from PIL import Image
from sqlalchemy import Engine, text
from sqlalchemy.orm import Session, sessionmaker

from app.cli.seed import main as seed_main
from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory
from app.main import create_app
from app.models import MediaItem
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
        transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            return await client.get(path, params=params)

    return asyncio.run(request())


def _photo(
    photo_id: str,
    captured_at_ms: int,
    *,
    thumbnail_path: str | None = None,
    thumbnail: bytes | None = None,
    media_type: str = "photo",
) -> MediaItem:
    has_thumbnail = thumbnail is not None
    return MediaItem(
        id=photo_id,
        device_id="01J00000000000000000001001",
        type=media_type,
        source="android_media_store",
        source_id=f"content://media/{photo_id[-3:]}",
        filename="camera.jpg",
        captured_at_ms=captured_at_ms,
        width=640,
        height=480,
        duration_ms=None,
        latitude=35.6812,
        longitude=139.7671,
        thumbnail_path=thumbnail_path,
        thumbnail_mime_type="image/webp" if has_thumbnail else None,
        thumbnail_width=4 if has_thumbnail else None,
        thumbnail_height=4 if has_thumbnail else None,
        thumbnail_size_bytes=len(thumbnail) if thumbnail is not None else None,
        thumbnail_sha256=hashlib.sha256(thumbnail).hexdigest() if thumbnail is not None else None,
        mime_type="image/jpeg",
        created_at_ms=captured_at_ms,
    )


def test_photos_api_uses_local_day_bounds_and_descending_stable_order(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        _, day = build_day_range("2026-03-08", "America/New_York")
        photos = [
            _photo("01J00000000000000000001401", day.start_ms),
            _photo("01J00000000000000000001404", day.start_ms),
            _photo("01J00000000000000000001402", day.end_ms - 1),
            _photo("01J00000000000000000001403", day.end_ms),
            _photo("01J00000000000000000001405", day.start_ms, media_type="video"),
        ]
        with factory.begin() as session:
            session.add_all(photos)

        app = create_app(factory)
        response = _get(
            app,
            "/api/v1/photos",
            date="2026-03-08",
            timezone="America/New_York",
        )
        assert response.status_code == 200
        payload = response.json()
        assert payload["rangeStart"] == "2026-03-08T05:00:00.000Z"
        assert payload["rangeEnd"] == "2026-03-09T04:00:00.000Z"
        assert [item["id"] for item in payload["items"]] == [
            "01J00000000000000000001402",
            "01J00000000000000000001404",
            "01J00000000000000000001401",
        ]
        same_time = payload["items"][1]
        assert same_time["source"] == "android_media_store"
        assert same_time["deviceName"] == "Demo Android A"
        assert same_time["takenAt"] == day.start_iso
        assert same_time["thumbnailUrl"] is None

        empty = _get(
            app,
            "/api/v1/photos",
            date="2026-03-09",
            timezone="America/New_York",
        )
        assert empty.status_code == 200
        assert [item["id"] for item in empty.json()["items"]] == ["01J00000000000000000001403"]
    finally:
        engine.dispose()


def test_thumbnail_api_returns_verified_webp_with_private_headers(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        captured_at_ms = build_day_range("2026-09-03", "Asia/Tokyo")[1].start_ms
        photo_id = "01J00000000000000000001411"
        thumbnail_path = f"thumbnails/test/{photo_id}.webp"
        output = BytesIO()
        Image.new("RGB", (4, 4), color=(10, 20, 30)).save(output, format="WEBP")
        thumbnail_bytes = output.getvalue()
        with factory.begin() as session:
            session.add(
                _photo(
                    photo_id,
                    captured_at_ms,
                    thumbnail_path=thumbnail_path,
                    thumbnail=thumbnail_bytes,
                )
            )

        file_path = tmp_path / "data" / Path(*thumbnail_path.split("/"))
        file_path.parent.mkdir(parents=True)
        file_path.write_bytes(thumbnail_bytes)

        response = _get(create_app(factory), f"/api/v1/media/{photo_id}/thumbnail")
        assert response.status_code == 200
        assert response.content == thumbnail_bytes
        assert response.headers["content-type"] == "image/webp"
        assert response.headers["x-content-type-options"] == "nosniff"
        assert response.headers["cache-control"] == "private, max-age=86400"
    finally:
        engine.dispose()


def test_thumbnail_api_distinguishes_invalid_missing_and_metadata_only_items(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        captured_at_ms = build_day_range("2026-09-03", "Asia/Tokyo")[1].start_ms
        metadata_only_id = "01J00000000000000000001412"
        missing_file_id = "01J00000000000000000001413"
        placeholder = b"not a stored file"
        with factory.begin() as session:
            session.add_all(
                [
                    _photo(metadata_only_id, captured_at_ms),
                    _photo(
                        missing_file_id,
                        captured_at_ms,
                        thumbnail_path=f"thumbnails/missing/{missing_file_id}.webp",
                        thumbnail=placeholder,
                    ),
                ]
            )

        app = create_app(factory)
        invalid = _get(app, "/api/v1/media/not-a-ulid/thumbnail")
        assert invalid.status_code == 422
        assert invalid.json()["error"]["field"] == "media_id"

        for media_id in ("01J00000000000000000001499", metadata_only_id):
            missing = _get(app, f"/api/v1/media/{media_id}/thumbnail")
            assert missing.status_code == 404
            assert missing.json()["error"]["code"] == "not_found"

        missing_file = _get(app, f"/api/v1/media/{missing_file_id}/thumbnail")
        assert missing_file.status_code == 500
        assert missing_file.json() == {
            "error": {
                "code": "internal_error",
                "message": "Internal server error.",
                "field": None,
            }
        }
        assert str(tmp_path) not in missing_file.text
    finally:
        engine.dispose()


def test_thumbnail_api_rejects_a_traversing_database_path_safely(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _migrated_seeded_database(tmp_path, monkeypatch)
    try:
        captured_at_ms = build_day_range("2026-09-03", "Asia/Tokyo")[1].start_ms
        photo_id = "01J00000000000000000001414"
        thumbnail_bytes = b"thumbnail"
        with factory.begin() as session:
            session.add(
                _photo(
                    photo_id,
                    captured_at_ms,
                    thumbnail_path=f"thumbnails/{photo_id}.webp",
                    thumbnail=thumbnail_bytes,
                )
            )
        with engine.begin() as connection:
            connection.exec_driver_sql("PRAGMA ignore_check_constraints=ON")
            connection.execute(
                text("UPDATE media_items SET thumbnail_path=:path WHERE id=:id"),
                {"path": "thumbnails/../../outside.webp", "id": photo_id},
            )

        response = _get(create_app(factory), f"/api/v1/media/{photo_id}/thumbnail")
        assert response.status_code == 500
        assert response.json()["error"]["code"] == "internal_error"
        assert "outside.webp" not in response.text
        assert str(tmp_path) not in response.text
    finally:
        engine.dispose()
