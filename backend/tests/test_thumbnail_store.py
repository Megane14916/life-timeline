"""Filesystem safety and bounded decode tests for the thumbnail store."""

from __future__ import annotations

import asyncio
import hashlib
import io
import json
from pathlib import Path

import pytest
from fastapi import UploadFile
from PIL import Image
from starlette.datastructures import Headers

from app.schemas.photo_sync import PhotoSyncPhoto, PhotoSyncRequest
from app.services.thumbnail_store import (
    InvalidThumbnailError,
    StagedThumbnail,
    ThumbnailStore,
    ThumbnailStoreConflictError,
    ThumbnailTooLargeError,
)

CONTRACT_PATH = Path(__file__).parents[2] / "contracts" / "sync" / "photos-v1.json"
THUMBNAIL_PATH = (
    Path(__file__).parents[2] / "contracts" / "sync" / "fixtures" / "synthetic-thumbnail.webp"
)


def _photo() -> PhotoSyncPhoto:
    fixture = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    request = PhotoSyncRequest.model_validate(fixture["request"])
    return request.photos[0]


def _upload(content: bytes, *, filename: str = "client-name-is-ignored.jpg") -> UploadFile:
    return UploadFile(
        file=io.BytesIO(content),
        filename=filename,
        headers=Headers({"content-type": "image/webp"}),
    )


async def _stage(store: ThumbnailStore, photo: PhotoSyncPhoto, content: bytes) -> StagedThumbnail:
    return await store.stage(photo, _upload(content, filename=r"C:\private\camera.jpg"))


def test_store_promotes_valid_webp_to_server_generated_relative_path(tmp_path: Path) -> None:
    store = ThumbnailStore(tmp_path / "data" / "thumbnails")
    content = THUMBNAIL_PATH.read_bytes()
    photo = _photo()

    async def stage() -> StagedThumbnail:
        return await _stage(store, photo, content)

    staged = asyncio.run(stage())
    promoted = store.promote(staged)
    final_path = store.path_for_relative(promoted.relative_path)

    assert promoted.created
    assert promoted.relative_path.startswith("thumbnails/")
    assert final_path.is_file()
    assert final_path.read_bytes() == content
    assert "private" not in promoted.relative_path
    store.cleanup_staged([staged])
    assert not staged.temporary_directory.exists()


def test_store_reuses_same_hash_file_and_rejects_different_hash_for_same_key(
    tmp_path: Path,
) -> None:
    store = ThumbnailStore(tmp_path / "data" / "thumbnails")
    photo = _photo()
    content = THUMBNAIL_PATH.read_bytes()

    async def stage_twice() -> tuple[StagedThumbnail, StagedThumbnail]:
        return await _stage(store, photo, content), await _stage(store, photo, content)

    first, second = asyncio.run(stage_twice())
    created = store.promote(first)
    reused = store.promote(second)
    assert created.created
    assert not reused.created
    store.cleanup_staged([first, second])

    image_buffer = io.BytesIO()
    Image.new("RGB", (3, 2), (12, 34, 56)).save(image_buffer, format="WEBP", quality=65)
    different_bytes = image_buffer.getvalue()
    thumbnail = photo.thumbnail
    assert thumbnail is not None
    different_photo = photo.model_copy(
        update={
            "thumbnail": thumbnail.model_copy(
                update={
                    "byte_size": len(different_bytes),
                    "sha256": hashlib.sha256(different_bytes).hexdigest(),
                }
            )
        }
    )

    async def stage_different() -> StagedThumbnail:
        return await _stage(store, different_photo, different_bytes)

    different_staged = asyncio.run(stage_different())
    with pytest.raises(ThumbnailStoreConflictError):
        store.promote(different_staged)
    store.cleanup_staged([different_staged])
    store.cleanup_promoted([reused])
    assert store.path_for_relative(created.relative_path).is_file()


def test_store_rejects_size_dimension_and_webp_mismatches(tmp_path: Path) -> None:
    store = ThumbnailStore(tmp_path / "data" / "thumbnails")
    photo = _photo()
    content = THUMBNAIL_PATH.read_bytes()
    thumbnail = photo.thumbnail
    assert thumbnail is not None
    wrong_dimensions = photo.model_copy(
        update={"thumbnail": thumbnail.model_copy(update={"width": 2, "height": 3})}
    )

    async def invalid_cases() -> None:
        await store.stage(wrong_dimensions, _upload(content))

    with pytest.raises(InvalidThumbnailError, match="pixel size"):
        asyncio.run(invalid_cases())

    oversized = content + b"x" * (1_048_577 - len(content))

    async def oversized_case() -> None:
        await store.stage(photo, _upload(oversized))

    with pytest.raises(ThumbnailTooLargeError):
        asyncio.run(oversized_case())


def test_store_rejects_database_paths_outside_the_thumbnail_root(tmp_path: Path) -> None:
    store = ThumbnailStore(tmp_path / "data" / "thumbnails")

    with pytest.raises(OSError, match="invalid"):
        store.path_for_relative("thumbnails/../../outside.webp")


def test_store_cleans_temp_directory_when_atomic_rename_fails(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    store = ThumbnailStore(tmp_path / "data" / "thumbnails")
    content = THUMBNAIL_PATH.read_bytes()

    staged = asyncio.run(_stage(store, _photo(), content))

    def fail_replace(_source: Path, _target: Path) -> None:
        raise PermissionError("synthetic rename failure")

    monkeypatch.setattr("app.services.thumbnail_store.os.replace", fail_replace)
    with pytest.raises(PermissionError):
        store.promote(staged)
    store.cleanup_staged([staged])

    assert not staged.temporary_directory.exists()
    assert list(store.root.rglob("*.webp")) == []
