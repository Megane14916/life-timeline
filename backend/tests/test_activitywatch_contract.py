"""Verify the privacy-safe ActivityWatch source contract fixture."""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any


FIXTURE_PATH = Path(__file__).parents[2] / "contracts" / "activitywatch-v1.json"


def _load_fixture() -> dict[str, Any]:
    with FIXTURE_PATH.open(encoding="utf-8") as fixture_file:
        value = json.load(fixture_file)
    assert isinstance(value, dict)
    return value


def test_activitywatch_fixture_has_only_synthetic_source_data() -> None:
    fixture = _load_fixture()
    synthetic = fixture["fixture"]

    assert synthetic["synthetic"] is True
    assert synthetic["hostname"] == "fixture-host"
    assert synthetic["reservedDomain"] == "example.invalid"
    assert all("fixture" in bucket_id for bucket_id in synthetic["bucketIds"])

    serialized = json.dumps(fixture, ensure_ascii=False)
    assert "google.com" not in serialized
    assert "github.com" not in serialized
    assert "youtube.com" not in serialized


def test_activitywatch_contract_is_loopback_read_only_and_bounded() -> None:
    fixture = _load_fixture()
    requests = fixture["requests"]

    assert requests["baseUrl"] == "http://127.0.0.1:5600"
    assert requests["redirects"] == "disabled"
    assert requests["proxy"] == "disabled"
    assert requests["maxResponseBytes"] == 32 * 1024 * 1024
    assert {request["method"] for request in requests["allowed"]} == {"GET"}
    assert set(requests["forbiddenMethods"]) == {"POST", "PUT", "PATCH", "DELETE"}
    assert all("write" not in request["path"] for request in requests["allowed"])


def test_activitywatch_contract_covers_info_buckets_and_event_shapes() -> None:
    fixture = _load_fixture()
    responses = fixture["responses"]
    buckets = responses["buckets"]
    events = responses["events"]

    assert responses["info"]["version"] == fixture["activitywatchStableRelease"]
    assert set(buckets) == set(events)
    assert {bucket["type"] for bucket in buckets.values()} == {
        "currentwindow",
        "afkstatus",
        "web.tab.current",
    }

    for bucket_id, bucket_events in events.items():
        assert bucket_id.startswith("fixture-")
        for event in bucket_events:
            assert event["timestamp"].endswith("Z")
            assert event["duration"] > 0
            assert math.isfinite(event["duration"])
            assert isinstance(event["data"], dict)


def test_privacy_modes_and_limits_are_explicitly_versioned() -> None:
    policy = _load_fixture()["privacyPolicy"]

    assert policy["titlePolicyVersion"] == "activitywatch_title_v1"
    assert policy["urlPolicyVersion"] == "activitywatch_url_v1"
    assert set(policy["modes"]) == {"app_only", "titles", "web"}
    assert policy["modes"]["app_only"] == {
        "nonBrowserWindowTitle": False,
        "browserWindowTitle": False,
        "browserTitle": False,
        "url": False,
    }
    assert policy["modes"]["titles"]["nonBrowserWindowTitle"] is True
    assert policy["modes"]["titles"]["browserWindowTitle"] is False
    assert policy["modes"]["titles"]["browserTitle"] is False
    assert policy["modes"]["titles"]["url"] is False
    assert policy["modes"]["web"]["requiresActiveBrowser"] is True
    assert policy["modes"]["web"]["requiresNonIncognito"] is True
    assert policy["incognitoDetail"] == "null"
    assert policy["titleMaxUnicodeCharacters"] == 500
    assert policy["urlMaxCharacters"] == 2048
    assert set(policy["allowedUrlSchemes"]) == {"http", "https"}
    assert set(policy["removedUrlComponents"]) == {
        "userinfo",
        "query",
        "fragment",
        "default_port",
    }
    assert "javascript" in policy["rejectedUrlSchemes"]


def test_browser_mapping_is_versioned_and_does_not_learn_from_urls() -> None:
    mapping = _load_fixture()["browserMapping"]

    assert mapping["version"] == "activitywatch_browser_mapping_v1"
    assert set(mapping["normalizedIdentifiers"]) == {
        "chrome",
        "edge",
        "firefox",
        "brave",
        "opera",
        "vivaldi",
    }
    assert "chrome.exe" in mapping["normalizedIdentifiers"]["chrome"]
    assert "msedge.exe" in mapping["normalizedIdentifiers"]["edge"]
    assert mapping["unknownBrowserBehavior"] == "save_app_session_without_web_detail"


def test_contract_fixture_exercises_url_redaction_and_incognito_inputs() -> None:
    events = _load_fixture()["responses"]["events"]["fixture-web-bucket"]
    urls = [event["data"]["url"] for event in events]

    assert any("?" in url and "#" in url for url in urls)
    assert any("fixture-user:fixture-pass@" in url for url in urls)
    assert any(event["data"]["incognito"] is True for event in events)


def test_fixed_values_match_phase6_design() -> None:
    values = _load_fixture()["fixedValues"]

    assert values["enabledDefault"] is False
    assert values["detailModeDefault"] == "app_only"
    assert values["scheduleInitialDelaySeconds"] == 30
    assert values["scheduleIntervalSeconds"] == 15 * 60
    assert values["settlementLagSeconds"] == 2 * 60
    assert values["initialLookbackUtcDays"] == 7
    assert values["rollingRefreshUtcDays"] == 2
    assert values["queryContextSeconds"] == 5 * 60
    assert values["eventLimitPerRequest"] == 10_000
    assert values["minimumSplitSeconds"] == 5 * 60
    assert values["runBudgetUtcDays"] == 8
    assert values["runBudgetSeconds"] == 8 * 60
    assert values["leaseTtlSeconds"] == 15 * 60
