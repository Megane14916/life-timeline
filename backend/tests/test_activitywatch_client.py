from __future__ import annotations

from typing import Any

import httpx
import pytest

from app.activitywatch import (
    ActivityWatchBucket,
    ActivityWatchClient,
    ActivityWatchEvent,
    ActivityWatchInfo,
    ActivityWatchProtocolError,
    ActivityWatchUnavailableError,
    discover_buckets,
)

START = "2026-09-14T00:00:00.000Z"
END = "2026-09-14T00:05:00.000Z"


def _bucket(
    bucket_id: str, bucket_type: str, client: str, hostname: str = "fixture-host"
) -> dict[str, Any]:
    return {
        "id": bucket_id,
        "type": bucket_type,
        "client": client,
        "hostname": hostname,
        "created": "2026-09-14T00:00:00.000Z",
        "unknownFutureField": {"ignored": True},
    }


def _transport(payloads: dict[str, Any], calls: list[httpx.Request]) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        key = request.url.path
        if key.endswith("/events"):
            key = "events"
        return httpx.Response(200, json=payloads[key], request=request)

    return httpx.MockTransport(handler)


def test_client_reads_only_contract_get_endpoints_and_uses_fixed_loopback() -> None:
    calls: list[httpx.Request] = []
    payloads = {
        "/api/0/info": {"version": "0.13.2", "hostname": "fixture-host", "testing": False},
        "/api/0/buckets/": {
            "fixture-window": _bucket("fixture-window", "currentwindow", "aw-watcher-window"),
            "fixture-afk": _bucket("fixture-afk", "afkstatus", "aw-watcher-afk"),
        },
        "events": [
            {
                "id": "event-1",
                "timestamp": START,
                "duration": 1.25,
                "data": {"app": "Fixture Editor.exe"},
                "future": "ignored",
            }
        ],
    }
    with ActivityWatchClient(transport=_transport(payloads, calls)) as client:
        info = client.get_info()
        buckets = client.get_buckets()
        events = client.get_events("fixture window", start=START, end=END)

    assert info == ActivityWatchInfo("0.13.2", "fixture-host", False)
    assert {bucket.id for bucket in buckets} == {"fixture-window", "fixture-afk"}
    assert events[0].duration_seconds == 1.25
    assert all(request.method == "GET" for request in calls)
    assert all(
        request.url.scheme == "http" and request.url.host == "127.0.0.1" for request in calls
    )
    event_request = calls[-1]
    assert event_request.url.raw_path.startswith(b"/api/0/buckets/fixture%20window/events?")
    assert event_request.url.params["start"] == START
    assert event_request.url.params["end"] == END
    assert event_request.url.params["limit"] == "10000"


def test_client_rejects_bucket_path_escape_without_request() -> None:
    calls: list[httpx.Request] = []
    transport = _transport({"/api/0/info": {}}, calls)
    with ActivityWatchClient(transport=transport) as client:
        with pytest.raises(ActivityWatchProtocolError) as caught:
            client.get_events("../private", start=START, end=END)
    assert caught.value.code == "protocol_error"
    assert calls == []


@pytest.mark.parametrize("status_code", [408, 429, 500, 503])
def test_transient_http_failures_are_retryable_and_safe(status_code: int) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status_code, content=b"private response body", request=request)

    with ActivityWatchClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ActivityWatchUnavailableError) as caught:
            client.get_info()
    assert caught.value.code == "unavailable"
    assert caught.value.retryable is True
    assert "private" not in str(caught.value)


@pytest.mark.parametrize("status_code", [302, 400, 404])
def test_redirect_and_client_http_errors_are_permanent_protocol_errors(status_code: int) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status_code, content=b"secret bucket id", request=request)

    with ActivityWatchClient(transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ActivityWatchProtocolError) as caught:
            client.get_info()
    assert caught.value.code == "protocol_error"
    assert caught.value.retryable is False
    assert "secret" not in str(caught.value)


def test_body_limit_json_shape_and_timezone_validation_are_deterministic() -> None:
    def oversized(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b'{"version":"0.13.2"}', request=request)

    with ActivityWatchClient(
        transport=httpx.MockTransport(oversized), max_response_bytes=10
    ) as client:
        with pytest.raises(ActivityWatchProtocolError):
            client.get_info()

    with pytest.raises(ActivityWatchProtocolError) as naive_timestamp:
        ActivityWatchEvent.from_payload(
            {"timestamp": "2026-09-14T00:00:00", "duration": 1, "data": {}}
        )
    assert naive_timestamp.value.code == "incompatible_api"


def test_discovery_filters_hosts_and_uses_stable_client_priority() -> None:
    info = ActivityWatchInfo("0.13.2", "fixture-host")
    buckets = [
        ActivityWatchBucket("window-new", "currentwindow", "other-window", "fixture-host"),
        ActivityWatchBucket("window-good", "currentwindow", "aw-watcher-window", "fixture-host"),
        ActivityWatchBucket("afk-good", "afkstatus", "aw-watcher-afk", "fixture-host"),
        ActivityWatchBucket("web-other-host", "web.tab.current", "aw-watcher-web", "other-host"),
    ]
    discovery = discover_buckets(info, buckets)
    assert discovery.window_bucket is not None
    assert discovery.window_bucket.id == "window-good"
    assert discovery.afk_bucket is not None
    assert discovery.web_bucket is None
    assert discovery.result_code == "web_details_unavailable"
    assert discovery.can_import_app_sessions is True

    mismatch = discover_buckets(info, buckets, expected_hostname="another-host")
    assert mismatch.result_code == "missing_window_bucket"
    assert mismatch.can_import_app_sessions is False


def test_discovery_reports_missing_afk_and_missing_window() -> None:
    info = ActivityWatchInfo("0.13.2", "fixture-host")
    window = ActivityWatchBucket("window", "currentwindow", "aw-watcher-window", "fixture-host")
    assert discover_buckets(info, []).result_code == "missing_window_bucket"
    assert discover_buckets(info, [window]).result_code == "missing_afk_bucket"


def test_client_rejects_incompatible_version_and_schema_rejects_invalid_event_duration() -> None:
    def incompatible_info(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"version": "0.12.0", "hostname": "fixture-host"},
            request=request,
        )

    with ActivityWatchClient(transport=httpx.MockTransport(incompatible_info)) as client:
        with pytest.raises(ActivityWatchProtocolError) as version_error:
            client.get_info()
    assert version_error.value.code == "incompatible_api"
    with pytest.raises(ActivityWatchProtocolError):
        ActivityWatchEvent.from_payload({"timestamp": START, "duration": float("inf"), "data": {}})
