"""Verify the shared photo sync v1 contract and its multipart invariants."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from app.schemas.photo_sync import (
    PhotoSyncPolicy,
    PhotoSyncRequest,
    PhotoSyncResponse,
    expected_thumbnail_part_names,
    validate_accepted_ids,
    validate_thumbnail_bytes,
    validate_thumbnail_part_names,
)

CONTRACT_PATH = Path(__file__).parents[2] / "contracts" / "sync" / "photos-v1.json"
THUMBNAIL_PATH = (
    Path(__file__).parents[2] / "contracts" / "sync" / "fixtures" / "synthetic-thumbnail.webp"
)


def _load_fixture() -> dict[str, Any]:
    with CONTRACT_PATH.open(encoding="utf-8") as fixture_file:
        fixture = json.load(fixture_file)
    assert isinstance(fixture, dict)
    return fixture


def test_shared_fixture_matches_photo_policy_and_version_one_contract() -> None:
    fixture = _load_fixture()
    assert set(fixture) == {
        "endpoint",
        "contentType",
        "metadataPart",
        "thumbnailPartNameFormat",
        "thumbnailPartFilename",
        "policy",
        "request",
        "success",
        "errors",
    }
    assert fixture["endpoint"] == "/api/v1/sync/photos"
    assert fixture["contentType"] == "multipart/form-data"
    assert fixture["metadataPart"] == {"name": "metadata", "contentType": "application/json"}
    assert fixture["thumbnailPartNameFormat"] == "thumbnail_<photo-id>"
    assert fixture["thumbnailPartFilename"] == "thumbnail.webp"
    assert fixture["policy"] == {
        "maxPhotosPerBatch": PhotoSyncPolicy.MAX_PHOTOS_PER_BATCH,
        "maxThumbnailBytes": PhotoSyncPolicy.MAX_THUMBNAIL_BYTES,
        "maxRequestBytes": PhotoSyncPolicy.MAX_REQUEST_BYTES,
        "maxThumbnailDimensionPx": PhotoSyncPolicy.MAX_THUMBNAIL_DIMENSION_PX,
        "thumbnailMimeType": PhotoSyncPolicy.THUMBNAIL_MIME_TYPE,
        "thumbnailQuality": PhotoSyncPolicy.THUMBNAIL_QUALITY,
        "maxFilenameLength": PhotoSyncPolicy.MAX_FILENAME_LENGTH,
    }

    request = PhotoSyncRequest.model_validate(fixture["request"])
    response = PhotoSyncResponse.model_validate(fixture["success"])
    assert request.model_dump(mode="json", by_alias=True) == fixture["request"]
    assert response.model_dump(mode="json", by_alias=True) == fixture["success"]
    assert request.schema_version == 1
    assert request.device.platform == "android"
    assert len(request.photos) == 2
    assert request.photos[-1].thumbnail is None
    assert response.accepted == [photo.id for photo in request.photos]
    validate_accepted_ids(request, response)

    thumbnail = request.photos[0]
    assert thumbnail.thumbnail is not None
    validate_thumbnail_part_names(request, expected_thumbnail_part_names(request))
    validate_thumbnail_bytes(thumbnail, THUMBNAIL_PATH.read_bytes())


def test_shared_fixture_has_safe_error_envelopes() -> None:
    fixture = _load_fixture()
    errors = fixture["errors"]
    assert [error["status"] for error in errors] == [413, 422, 409, 503, 500]
    assert {error["payload"]["error"]["code"] for error in errors} == {
        "payload_too_large",
        "invalid_request",
        "sync_conflict",
        "temporarily_unavailable",
        "internal_error",
    }
    for error in errors:
        assert set(error) == {"status", "payload"}
        assert set(error["payload"]) == {"error"}
        assert set(error["payload"]["error"]) == {"code", "message", "field"}
        assert error["payload"]["error"]["message"]


def test_photo_request_rejects_unknown_fields_and_duplicate_keys() -> None:
    fixture_request = _load_fixture()["request"]

    unknown_field = copy.deepcopy(fixture_request)
    unknown_field["photos"][0]["originalPath"] = "must-not-be-part-of-the-contract"
    with pytest.raises(ValidationError):
        PhotoSyncRequest.model_validate(unknown_field)

    duplicate_id = copy.deepcopy(fixture_request)
    duplicate_id["photos"][1]["id"] = duplicate_id["photos"][0]["id"]
    with pytest.raises(ValidationError, match="duplicate IDs"):
        PhotoSyncRequest.model_validate(duplicate_id)

    duplicate_source = copy.deepcopy(fixture_request)
    duplicate_source["photos"][1]["sourceId"] = duplicate_source["photos"][0]["sourceId"]
    with pytest.raises(ValidationError, match="duplicate source IDs"):
        PhotoSyncRequest.model_validate(duplicate_source)

    invalid_dimension = copy.deepcopy(fixture_request)
    invalid_dimension["photos"][0]["width"] = 0
    with pytest.raises(ValidationError):
        PhotoSyncRequest.model_validate(invalid_dimension)


def test_thumbnail_hash_size_and_multipart_correspondence_are_enforced() -> None:
    fixture_request = _load_fixture()["request"]
    request = PhotoSyncRequest.model_validate(fixture_request)
    photo = request.photos[0]
    assert photo.thumbnail is not None
    thumbnail = THUMBNAIL_PATH.read_bytes()

    wrong_hash = copy.deepcopy(fixture_request)
    wrong_hash["photos"][0]["thumbnail"]["sha256"] = "0" * 64
    with pytest.raises(ValueError, match="digest"):
        validate_thumbnail_bytes(PhotoSyncRequest.model_validate(wrong_hash).photos[0], thumbnail)

    wrong_size = copy.deepcopy(fixture_request)
    wrong_size["photos"][0]["thumbnail"]["byteSize"] += 1
    with pytest.raises(ValueError, match="byte count"):
        validate_thumbnail_bytes(PhotoSyncRequest.model_validate(wrong_size).photos[0], thumbnail)

    expected_parts = expected_thumbnail_part_names(request)
    with pytest.raises(ValueError, match="exactly match"):
        validate_thumbnail_part_names(request, ())
    with pytest.raises(ValueError, match="exactly match"):
        validate_thumbnail_part_names(request, (*expected_parts, "thumbnail_unknown"))
    with pytest.raises(ValueError, match="duplicate names"):
        validate_thumbnail_part_names(request, (*expected_parts, *expected_parts))


def test_ack_requires_a_unique_ordered_subset_and_required_field() -> None:
    fixture = _load_fixture()
    request = PhotoSyncRequest.model_validate(fixture["request"])
    response = PhotoSyncResponse.model_validate(fixture["success"])
    validate_accepted_ids(request, response)

    partial_ack = PhotoSyncResponse.model_validate(
        {"schemaVersion": 1, "accepted": [request.photos[0].id]}
    )
    validate_accepted_ids(request, partial_ack)

    with pytest.raises(ValidationError, match="duplicate IDs"):
        PhotoSyncResponse.model_validate(
            {"schemaVersion": 1, "accepted": [request.photos[0].id, request.photos[0].id]}
        )
    with pytest.raises(ValueError, match="subset"):
        validate_accepted_ids(
            request,
            PhotoSyncResponse.model_validate(
                {"schemaVersion": 1, "accepted": ["01K4N70E3Q6N9D6E6G0C8M2H1R"]}
            ),
        )
    with pytest.raises(ValueError, match="request order"):
        validate_accepted_ids(request, response.model_copy(update={"accepted": list(reversed(response.accepted))}))
    with pytest.raises(ValidationError):
        PhotoSyncResponse.model_validate({"schemaVersion": 1})
