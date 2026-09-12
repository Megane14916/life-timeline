"""Bounded, validated, and atomic storage for generated WebP thumbnails."""

from __future__ import annotations

import hashlib
import hmac
import os
import shutil
import tempfile
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath

from PIL import Image, UnidentifiedImageError
from starlette.datastructures import UploadFile

from app.schemas.photo_sync import PhotoSyncPhoto, PhotoSyncPolicy

CHUNK_SIZE = 64 * 1024


class InvalidThumbnailError(ValueError):
    """Raised when received thumbnail bytes do not match their declaration."""


class ThumbnailStoreConflictError(ValueError):
    """Raised when a different immutable file already occupies the final key."""


class ThumbnailStoreError(OSError):
    """Raised when the managed thumbnail directory cannot be used safely."""


class ThumbnailTooLargeError(ValueError):
    """Raised when a streamed thumbnail exceeds the fixed per-file limit."""


@dataclass(frozen=True, slots=True)
class StagedThumbnail:
    photo_id: str
    temporary_directory: Path
    temporary_path: Path
    relative_path: str
    sha256: str
    size_bytes: int
    width: int
    height: int


@dataclass(frozen=True, slots=True)
class PromotedThumbnail:
    relative_path: str
    sha256: str
    created: bool


class ThumbnailStore:
    """Store thumbnails under one root and never use client-supplied paths."""

    def __init__(self, root: Path) -> None:
        self.root = root.expanduser().resolve()
        self.data_dir = self.root.parent.resolve()

    async def stage(self, photo: PhotoSyncPhoto, upload: UploadFile) -> StagedThumbnail:
        """Stream one UploadFile into a private temp directory and validate it."""

        metadata = photo.thumbnail
        if metadata is None:
            raise InvalidThumbnailError("thumbnail metadata is missing.")
        self.root.mkdir(parents=True, exist_ok=True)
        temporary_directory = Path(tempfile.mkdtemp(prefix=".photo-sync-", dir=self.root))
        temporary_path = temporary_directory / "thumbnail.part"
        digest = hashlib.sha256()
        size_bytes = 0
        try:
            with temporary_path.open("xb") as temporary_file:
                while chunk := await upload.read(CHUNK_SIZE):
                    size_bytes += len(chunk)
                    if size_bytes > PhotoSyncPolicy.MAX_THUMBNAIL_BYTES:
                        raise ThumbnailTooLargeError("thumbnail exceeds the per-file size limit.")
                    temporary_file.write(chunk)
                    digest.update(chunk)
                temporary_file.flush()
                os.fsync(temporary_file.fileno())

            actual_sha256 = digest.hexdigest()
            if size_bytes != metadata.byte_size:
                raise InvalidThumbnailError("thumbnail byte count does not match metadata.")
            if not hmac.compare_digest(actual_sha256, metadata.sha256):
                raise InvalidThumbnailError("thumbnail digest does not match metadata.")
            width, height = self._validate_webp(temporary_path, metadata.width, metadata.height)
            relative_path = self._relative_path(photo)
            return StagedThumbnail(
                photo_id=photo.id,
                temporary_directory=temporary_directory,
                temporary_path=temporary_path,
                relative_path=relative_path,
                sha256=actual_sha256,
                size_bytes=size_bytes,
                width=width,
                height=height,
            )
        except BaseException:
            self._remove_temporary_directory(temporary_directory)
            raise

    def promote(self, staged: StagedThumbnail) -> PromotedThumbnail:
        """Atomically rename one fully validated temp file to its final key."""

        final_path = self.path_for_relative(staged.relative_path)
        self._ensure_parent_directories(final_path)
        if final_path.exists():
            self._assert_inside_root(final_path)
            if final_path.is_symlink() or not final_path.is_file():
                raise ThumbnailStoreError("A managed thumbnail entry is not a regular file.")
            if self._sha256_file(final_path) != staged.sha256:
                raise ThumbnailStoreConflictError("A different thumbnail already uses this key.")
            return PromotedThumbnail(staged.relative_path, staged.sha256, created=False)

        os.replace(staged.temporary_path, final_path)
        return PromotedThumbnail(staged.relative_path, staged.sha256, created=True)

    def cleanup_promoted(self, promoted: list[PromotedThumbnail]) -> None:
        """Remove only final files this request created and that still match."""

        for item in promoted:
            if not item.created:
                continue
            try:
                final_path = self.path_for_relative(item.relative_path)
                if (
                    final_path.is_file()
                    and not final_path.is_symlink()
                    and hmac.compare_digest(self._sha256_file(final_path), item.sha256)
                ):
                    final_path.unlink()
                    parent = final_path.parent
                    while parent != self.root and parent.is_relative_to(self.root):
                        parent.rmdir()
                        parent = parent.parent
            except OSError:
                # A leftover unreferenced file is safer than masking the original failure.
                continue

    def cleanup_staged(self, staged: list[StagedThumbnail]) -> None:
        """Remove request-owned temp directories without following their entries."""

        for item in staged:
            self._remove_temporary_directory(item.temporary_directory)

    def path_for_relative(self, relative_path: str) -> Path:
        """Resolve a DB-relative path only when it belongs to this thumbnail root."""

        relative = PurePosixPath(relative_path)
        if (
            relative.is_absolute()
            or "\\" in relative_path
            or not relative.parts
            or relative.parts[0] != "thumbnails"
            or any(part in {"", ".", ".."} for part in relative.parts)
        ):
            raise ThumbnailStoreError("Stored thumbnail path is invalid.")
        result = self.data_dir.joinpath(*relative.parts).resolve()
        self._assert_inside_root(result)
        return result

    @staticmethod
    def _relative_path(photo: PhotoSyncPhoto) -> str:
        captured_at = datetime.fromtimestamp(photo.captured_at_ms / 1000, tz=UTC)
        return PurePosixPath(
            "thumbnails",
            captured_at.strftime("%Y"),
            captured_at.strftime("%m"),
            captured_at.strftime("%d"),
            f"{photo.id}.webp",
        ).as_posix()

    @staticmethod
    def _validate_webp(path: Path, expected_width: int, expected_height: int) -> tuple[int, int]:
        try:
            with Image.open(path) as image:
                width, height = image.size
                if image.format != "WEBP":
                    raise InvalidThumbnailError("thumbnail must be a WebP image.")
                if (
                    width != expected_width
                    or height != expected_height
                    or not 1 <= width <= PhotoSyncPolicy.MAX_THUMBNAIL_DIMENSION_PX
                    or not 1 <= height <= PhotoSyncPolicy.MAX_THUMBNAIL_DIMENSION_PX
                ):
                    raise InvalidThumbnailError("thumbnail pixel size does not match metadata.")
                image.verify()
            with Image.open(path) as image:
                image.load()
        except (
            Image.DecompressionBombError,
            OSError,
            SyntaxError,
            UnidentifiedImageError,
        ) as error:
            raise InvalidThumbnailError("thumbnail is not a valid WebP image.") from error
        return width, height

    def _assert_inside_root(self, path: Path) -> None:
        resolved = path.resolve()
        if not resolved.is_relative_to(self.root):
            raise ThumbnailStoreError("A managed thumbnail path escaped its configured root.")

    def _ensure_parent_directories(self, final_path: Path) -> None:
        relative_parts = final_path.relative_to(self.root).parts
        current = self.root
        for part in relative_parts[:-1]:
            current = current / part
            if current.is_symlink():
                raise ThumbnailStoreError("A managed thumbnail directory must not be a symlink.")
            current.mkdir(exist_ok=True)
            self._assert_inside_root(current)

    @staticmethod
    def _sha256_file(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as file:
            while chunk := file.read(CHUNK_SIZE):
                digest.update(chunk)
        return digest.hexdigest()

    @staticmethod
    def _remove_temporary_directory(directory: Path) -> None:
        if directory.exists() and not directory.is_symlink():
            try:
                shutil.rmtree(directory)
            except OSError:
                # Best-effort temp cleanup keeps the original request error intact.
                pass
