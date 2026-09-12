"""Version 1 photo synchronization metadata and multipart rules."""

from __future__ import annotations

import hashlib
import hmac
from collections.abc import Iterable
from typing import Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictFloat,
    StrictInt,
    field_validator,
    model_validator,
)

from app.ids import InvalidUlidError, validate_ulid


class PhotoSyncPolicy:
    """Limits shared by the Android client and the photo sync API contract."""

    MAX_PHOTOS_PER_BATCH = 20
    MAX_THUMBNAIL_BYTES = 1_048_576
    MAX_REQUEST_BYTES = 20_971_520
    MAX_THUMBNAIL_DIMENSION_PX = 512
    THUMBNAIL_MIME_TYPE = "image/webp"
    THUMBNAIL_QUALITY = 65
    MAX_FILENAME_LENGTH = 255


class PhotoSyncModel(BaseModel):
    """Base class that rejects fields outside the versioned wire contract."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)


def _validate_ulid(value: str) -> str:
    try:
        return validate_ulid(value)
    except InvalidUlidError as error:
        raise ValueError(str(error)) from error


def _validate_non_blank(value: str) -> str:
    if not value.strip():
        raise ValueError("must not be blank")
    return value


class PhotoSyncDevice(PhotoSyncModel):
    id: str = Field(min_length=26, max_length=26)
    name: str = Field(min_length=1, max_length=200)
    platform: Literal["android"]

    _validate_id = field_validator("id")(_validate_ulid)
    _validate_name = field_validator("name")(_validate_non_blank)


class PhotoThumbnailMetadata(PhotoSyncModel):
    mime_type: Literal["image/webp"] = Field(alias="mimeType")
    width: StrictInt = Field(ge=1, le=PhotoSyncPolicy.MAX_THUMBNAIL_DIMENSION_PX)
    height: StrictInt = Field(ge=1, le=PhotoSyncPolicy.MAX_THUMBNAIL_DIMENSION_PX)
    byte_size: StrictInt = Field(alias="byteSize", ge=1, le=PhotoSyncPolicy.MAX_THUMBNAIL_BYTES)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class PhotoSyncPhoto(PhotoSyncModel):
    id: str = Field(min_length=26, max_length=26)
    source: Literal["android_media_store"]
    source_id: str = Field(alias="sourceId", min_length=1, max_length=255)
    filename: str = Field(min_length=1, max_length=PhotoSyncPolicy.MAX_FILENAME_LENGTH)
    captured_at_ms: StrictInt = Field(alias="capturedAtMs", ge=0)
    width: StrictInt | None = Field(gt=0)
    height: StrictInt | None = Field(gt=0)
    mime_type: str = Field(alias="mimeType", min_length=1, max_length=100)
    latitude: StrictFloat | StrictInt | None
    longitude: StrictFloat | StrictInt | None
    thumbnail: PhotoThumbnailMetadata | None

    _validate_id = field_validator("id")(_validate_ulid)
    _validate_source_id = field_validator("source_id")(_validate_non_blank)

    @field_validator("filename")
    @classmethod
    def validate_filename(cls, value: str) -> str:
        _validate_non_blank(value)
        if any(ord(character) < 32 or ord(character) == 127 for character in value):
            raise ValueError("filename must not contain control characters")
        if "/" in value or "\\" in value:
            raise ValueError("filename must not contain path separators")
        return value

    @field_validator("mime_type")
    @classmethod
    def validate_image_mime_type(cls, value: str) -> str:
        if not value.startswith("image/"):
            raise ValueError("mimeType must identify an image")
        return value

    @model_validator(mode="after")
    def validate_location(self) -> PhotoSyncPhoto:
        has_latitude = self.latitude is not None
        has_longitude = self.longitude is not None
        if has_latitude != has_longitude:
            raise ValueError("latitude and longitude must be provided together")
        if self.latitude is not None and not -90 <= self.latitude <= 90:
            raise ValueError("latitude is out of range")
        if self.longitude is not None and not -180 <= self.longitude <= 180:
            raise ValueError("longitude is out of range")
        return self


class PhotoSyncRequest(PhotoSyncModel):
    schema_version: Literal[1] = Field(alias="schemaVersion")
    device: PhotoSyncDevice
    photos: list[PhotoSyncPhoto] = Field(
        min_length=1,
        max_length=PhotoSyncPolicy.MAX_PHOTOS_PER_BATCH,
    )

    @model_validator(mode="after")
    def validate_unique_photo_keys(self) -> PhotoSyncRequest:
        photo_ids = [photo.id for photo in self.photos]
        if len(photo_ids) != len(set(photo_ids)):
            raise ValueError("photos must not contain duplicate IDs")

        source_ids = [photo.source_id for photo in self.photos]
        if len(source_ids) != len(set(source_ids)):
            raise ValueError("photos must not contain duplicate source IDs")
        return self


class PhotoSyncResponse(PhotoSyncModel):
    schema_version: Literal[1] = Field(alias="schemaVersion")
    accepted: list[str] = Field(max_length=PhotoSyncPolicy.MAX_PHOTOS_PER_BATCH)

    _validate_accepted_ids = field_validator("accepted")(
        lambda values: [_validate_ulid(value) for value in values]
    )

    @field_validator("accepted")
    @classmethod
    def validate_unique_accepted_ids(cls, values: list[str]) -> list[str]:
        if len(values) != len(set(values)):
            raise ValueError("accepted must not contain duplicate IDs")
        return values


def expected_thumbnail_part_names(request: PhotoSyncRequest) -> tuple[str, ...]:
    """Return the exact part names required for photos with a thumbnail."""

    return tuple(f"thumbnail_{photo.id}" for photo in request.photos if photo.thumbnail is not None)


def validate_thumbnail_part_names(
    request: PhotoSyncRequest,
    part_names: Iterable[str],
) -> None:
    """Reject missing, unexpected, and duplicate binary multipart fields."""

    received = tuple(part_names)
    if len(received) != len(set(received)):
        raise ValueError("thumbnail parts must not contain duplicate names")
    if set(received) != set(expected_thumbnail_part_names(request)):
        raise ValueError("thumbnail parts must exactly match thumbnail metadata")


def validate_thumbnail_bytes(photo: PhotoSyncPhoto, content: bytes) -> None:
    """Check the declared byte count, digest, and WebP container signature."""

    metadata = photo.thumbnail
    if metadata is None:
        raise ValueError("photo has no thumbnail metadata")
    if len(content) > PhotoSyncPolicy.MAX_THUMBNAIL_BYTES:
        raise ValueError("thumbnail exceeds the per-file size limit")
    if len(content) != metadata.byte_size:
        raise ValueError("thumbnail byte count does not match metadata")
    digest = hashlib.sha256(content).hexdigest()
    if not hmac.compare_digest(digest, metadata.sha256):
        raise ValueError("thumbnail digest does not match metadata")
    if len(content) < 12 or content[:4] != b"RIFF" or content[8:12] != b"WEBP":
        raise ValueError("thumbnail is not a WebP container")


def validate_accepted_ids(request: PhotoSyncRequest, response: PhotoSyncResponse) -> None:
    """Require a unique, ordered subset ACK; omitted IDs remain pending."""

    request_ids = [photo.id for photo in request.photos]
    request_positions = {photo_id: index for index, photo_id in enumerate(request_ids)}
    if any(photo_id not in request_positions for photo_id in response.accepted):
        raise ValueError("accepted IDs must be a subset of the request")
    positions = [request_positions[photo_id] for photo_id in response.accepted]
    if positions != sorted(positions):
        raise ValueError("accepted IDs must preserve request order")
