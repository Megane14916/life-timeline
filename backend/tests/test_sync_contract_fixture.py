"""Verify the version 1 sync contract shared with the Android client."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

FIXTURE_PATH = Path(__file__).parents[2] / "contracts" / "sync" / "app-sessions-v1.json"


def _load_fixture() -> dict[str, Any]:
    with FIXTURE_PATH.open(encoding="utf-8") as fixture_file:
        value = json.load(fixture_file)
    assert isinstance(value, dict)
    return value


def test_shared_fixture_describes_version_one_request_and_responses() -> None:
    fixture = _load_fixture()
    assert set(fixture) == {
        "endpoint",
        "contentType",
        "maxSessions",
        "request",
        "success",
        "errors",
    }
    assert fixture["endpoint"] == "/api/v1/sync/app-sessions"
    assert fixture["contentType"] == "application/json"
    assert fixture["maxSessions"] == 100

    request = fixture["request"]
    assert set(request) == {"schemaVersion", "device", "apps", "sessions"}
    assert request["schemaVersion"] == 1
    assert set(request["device"]) == {"id", "name", "platform"}
    assert request["device"]["platform"] == "android"
    assert len(request["sessions"]) <= fixture["maxSessions"]

    app = request["apps"][0]
    session = request["sessions"][0]
    assert set(app) == {"id", "identifier", "displayName"}
    assert set(session) == {
        "id",
        "appId",
        "startedAtMs",
        "endedAtMs",
        "durationMs",
        "source",
    }
    assert session["appId"] == app["id"]
    assert session["endedAtMs"] > session["startedAtMs"]
    assert session["durationMs"] == session["endedAtMs"] - session["startedAtMs"]
    assert session["source"] == "android_usage_stats"

    success = fixture["success"]
    assert set(success) == {"schemaVersion", "accepted"}
    assert success["schemaVersion"] == 1
    assert success["accepted"] == [session["id"]]


def test_shared_fixture_covers_safe_error_contract() -> None:
    fixture = _load_fixture()
    errors = fixture["errors"]
    assert [error["status"] for error in errors] == [422, 409, 503, 500]
    assert {error["payload"]["error"]["code"] for error in errors} == {
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
