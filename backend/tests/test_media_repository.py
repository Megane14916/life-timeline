"""Persistence tests for media facts and their idempotent thumbnail updates."""

from __future__ import annotations

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import func, select
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory
from app.models import MediaItem
from app.repositories import (
    DeviceRecord,
    MasterRepository,
    MediaItemConflictError,
    MediaItemRecord,
    MediaRepository,
)

DEVICE_ID = "01J00000000000000000000001"
PHOTO_ID = "01J00000000000000000000101"
OTHER_PHOTO_ID = "01J00000000000000000000102"
CAPTURED_AT_MS = 1_789_052_400_000
CREATED_AT_MS = 1_789_052_500_000


def _database(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Engine, sessionmaker[Session]]:
    data_dir = tmp_path / "data"
    monkeypatch.setenv("LIFE_TIMELINE_DATA_DIR", str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    return engine, create_session_factory(engine)


def _device() -> DeviceRecord:
    return DeviceRecord(DEVICE_ID, "Synthetic Android", "android", CREATED_AT_MS)


def _record(
    *,
    photo_id: str = PHOTO_ID,
    source_id: str = "external_primary:101",
    filename: str = "synthetic.jpg",
    thumbnail: bool = False,
    sha256: str = "a" * 64,
    created_at_ms: int = CREATED_AT_MS,
) -> MediaItemRecord:
    return MediaItemRecord(
        id=photo_id,
        device_id=DEVICE_ID,
        type="photo",
        source="android_media_store",
        source_id=source_id,
        filename=filename,
        captured_at_ms=CAPTURED_AT_MS,
        duration_ms=None,
        mime_type="image/jpeg",
        created_at_ms=created_at_ms,
        width=3,
        height=2,
        latitude=None,
        longitude=None,
        thumbnail_path=(f"thumbnails/2026/09/12/{photo_id}.webp" if thumbnail else None),
        thumbnail_mime_type="image/webp" if thumbnail else None,
        thumbnail_width=3 if thumbnail else None,
        thumbnail_height=2 if thumbnail else None,
        thumbnail_size_bytes=60 if thumbnail else None,
        thumbnail_sha256=sha256 if thumbnail else None,
    )


def test_media_repository_keeps_created_at_and_row_count_on_identical_replay(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    try:
        with factory.begin() as session:
            MasterRepository(session).save_device(_device())
            repository = MediaRepository(session)
            repository.save_many([_record(thumbnail=True)])

        with factory.begin() as session:
            repository = MediaRepository(session)
            repository.save_many([_record(thumbnail=True, created_at_ms=CREATED_AT_MS + 5_000)])

        with factory() as session:
            item = session.get(MediaItem, PHOTO_ID)
            assert item is not None
            assert item.created_at_ms == CREATED_AT_MS
            assert item.thumbnail_path == f"thumbnails/2026/09/12/{PHOTO_ID}.webp"
            assert session.scalar(select(func.count()).select_from(MediaItem)) == 1
    finally:
        engine.dispose()


def test_metadata_only_item_can_be_completed_without_overwriting_created_at(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    try:
        with factory.begin() as session:
            MasterRepository(session).save_device(_device())
            repository = MediaRepository(session)
            repository.save_many([_record()])

        with factory.begin() as session:
            repository = MediaRepository(session)
            repository.save_many([_record(thumbnail=True)])

        with factory.begin() as session:
            repository = MediaRepository(session)
            repository.save_many([_record()])

        with factory() as session:
            item = session.get(MediaItem, PHOTO_ID)
            assert item is not None
            assert item.created_at_ms == CREATED_AT_MS
            assert item.thumbnail_path == f"thumbnails/2026/09/12/{PHOTO_ID}.webp"
            assert item.thumbnail_sha256 == "a" * 64
            assert session.scalar(select(func.count()).select_from(MediaItem)) == 1
    finally:
        engine.dispose()


def test_reused_id_source_key_or_thumbnail_hash_with_changed_content_conflicts(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    try:
        with factory.begin() as session:
            MasterRepository(session).save_device(_device())
            MediaRepository(session).save_many([_record(thumbnail=True)])

        with factory.begin() as session:
            with pytest.raises(MediaItemConflictError, match="conflicts"):
                MediaRepository(session).save_many([_record(filename="different.jpg")])

        with factory.begin() as session:
            with pytest.raises(MediaItemConflictError, match="conflicts"):
                MediaRepository(session).save_many(
                    [_record(photo_id=OTHER_PHOTO_ID, thumbnail=True)]
                )

        with factory.begin() as session:
            with pytest.raises(MediaItemConflictError, match="conflicts"):
                MediaRepository(session).save_many([_record(thumbnail=True, sha256="b" * 64)])

        with factory() as session:
            assert session.scalar(select(func.count()).select_from(MediaItem)) == 1
            assert session.get(MediaItem, PHOTO_ID) is not None
    finally:
        engine.dispose()


def test_database_constraints_require_location_pairs_and_complete_thumbnail_metadata(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _database(tmp_path, monkeypatch)
    try:
        with factory.begin() as session:
            MasterRepository(session).save_device(_device())

        with factory() as session:
            session.add(
                MediaItem(
                    id=PHOTO_ID,
                    device_id=DEVICE_ID,
                    type="photo",
                    source="android_media_store",
                    source_id="external_primary:101",
                    filename="synthetic.jpg",
                    captured_at_ms=CAPTURED_AT_MS,
                    width=3,
                    height=2,
                    duration_ms=None,
                    latitude=35.0,
                    longitude=None,
                    mime_type="image/jpeg",
                    created_at_ms=CREATED_AT_MS,
                )
            )
            with pytest.raises(IntegrityError):
                session.commit()
            session.rollback()

            session.add(
                MediaItem(
                    id=PHOTO_ID,
                    device_id=DEVICE_ID,
                    type="photo",
                    source="android_media_store",
                    source_id="external_primary:101",
                    filename="synthetic.jpg",
                    captured_at_ms=CAPTURED_AT_MS,
                    width=3,
                    height=2,
                    duration_ms=None,
                    latitude=None,
                    longitude=None,
                    thumbnail_path="thumbnails/2026/09/12/photo.webp",
                    thumbnail_mime_type="image/webp",
                    thumbnail_width=3,
                    thumbnail_height=None,
                    thumbnail_size_bytes=60,
                    thumbnail_sha256="a" * 64,
                    mime_type="image/jpeg",
                    created_at_ms=CREATED_AT_MS,
                )
            )
            with pytest.raises(IntegrityError):
                session.commit()
    finally:
        engine.dispose()
