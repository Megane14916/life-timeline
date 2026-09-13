"""Verify the shared LocationPoint sync v1 contract."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from app.schemas.location_sync import (
    LocationSyncPolicy,
    LocationSyncRequest,
    LocationSyncResponse,
    validate_accepted_ids,
)

CONTRACT_PATH = Path(__file__).parents[2] / "contracts" / "sync" / "locations-v1.json"


def _load_fixture() -> dict[str, Any]:
    value = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def test_shared_fixture_matches_location_policy_and_version_one_contract() -> None:
    fixture = _load_fixture()
    assert set(fixture) == {"endpoint", "contentType", "policy", "request", "success", "errors"}
    assert fixture["endpoint"] == "/api/v1/sync/locations"
    assert fixture["contentType"] == "application/json"
    assert fixture["policy"] == {
        "maxLocationsPerBatch": LocationSyncPolicy.MAX_LOCATIONS_PER_BATCH,
        "maxRequestBytes": LocationSyncPolicy.MAX_REQUEST_BYTES,
        "source": LocationSyncPolicy.SOURCE,
    }

    request = LocationSyncRequest.model_validate(fixture["request"])
    response = LocationSyncResponse.model_validate(fixture["success"])
    assert request.model_dump(mode="json", by_alias=True) == fixture["request"]
    assert response.model_dump(mode="json", by_alias=True) == fixture["success"]
    assert request.schema_version == 1
    assert request.device.platform == "android"
    assert len(request.locations) == 1
    assert response.accepted == [location.id for location in request.locations]
    validate_accepted_ids(request, response)


def test_shared_fixture_has_safe_error_envelopes() -> None:
    errors = _load_fixture()["errors"]
    assert [error["status"] for error in errors] == [413, 422, 409, 503, 500]
    for error in errors:
        assert set(error) == {"status", "payload"}
        assert set(error["payload"]) == {"error"}
        assert set(error["payload"]["error"]) == {"code", "message", "field"}
        assert error["payload"]["error"]["message"]


def test_location_request_rejects_unknown_fields_duplicate_ids_and_invalid_coordinates() -> None:
    request = _load_fixture()["request"]
    unknown = copy.deepcopy(request)
    unknown["locations"][0]["provider"] = "must-not-be-part-of-the-contract"
    with pytest.raises(ValidationError):
        LocationSyncRequest.model_validate(unknown)

    duplicate = copy.deepcopy(request)
    duplicate["locations"] *= 2
    with pytest.raises(ValidationError, match="duplicate IDs"):
        LocationSyncRequest.model_validate(duplicate)

    invalid = copy.deepcopy(request)
    invalid["locations"][0]["latitude"] = 90.1
    with pytest.raises(ValidationError, match="latitude is out of range"):
        LocationSyncRequest.model_validate(invalid)

    non_finite = copy.deepcopy(request)
    non_finite["locations"][0]["speedMps"] = float("inf")
    with pytest.raises(ValidationError, match="finite"):
        LocationSyncRequest.model_validate(non_finite)


def test_ack_requires_a_unique_ordered_subset_and_required_field() -> None:
    fixture = _load_fixture()
    request = LocationSyncRequest.model_validate(fixture["request"])
    response = LocationSyncResponse.model_validate(fixture["success"])
    validate_accepted_ids(request, response)

    with pytest.raises(ValidationError):
        LocationSyncResponse.model_validate({"schemaVersion": 1})
    with pytest.raises(ValidationError, match="duplicate IDs"):
        LocationSyncResponse.model_validate(
            {"schemaVersion": 1, "accepted": [response.accepted[0], response.accepted[0]]}
        )
    with pytest.raises(ValueError, match="subset"):
        validate_accepted_ids(
            request,
            LocationSyncResponse.model_validate(
                {"schemaVersion": 1, "accepted": ["01K00000000000000000000003"]}
            ),
        )
