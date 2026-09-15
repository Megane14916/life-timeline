from __future__ import annotations

import json
from collections.abc import Iterator
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from urllib.parse import parse_qs, urlsplit

import pytest

from app.activitywatch import ActivityWatchClient, ActivityWatchProtocolError


class _FakeActivityWatchHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlsplit(self.path)
        if parsed.path == "/api/0/info":
            payload: object = {
                "version": "0.13.2",
                "hostname": "http-fixture-host",
                "testing": True,
            }
        elif parsed.path == "/api/0/buckets/":
            payload = {
                "http-window": {
                    "id": "http-window",
                    "type": "currentwindow",
                    "client": "aw-watcher-window",
                    "hostname": "http-fixture-host",
                },
                "http-afk": {
                    "id": "http-afk",
                    "type": "afkstatus",
                    "client": "aw-watcher-afk",
                    "hostname": "http-fixture-host",
                },
                "http-malformed": {
                    "id": "http-malformed",
                    "type": "unknown",
                    "client": "fixture",
                    "hostname": "http-fixture-host",
                },
            }
        elif parsed.path.endswith("/events"):
            bucket_id = parsed.path.split("/")[-2]
            if bucket_id == "http-malformed":
                payload = [{"timestamp": "2026-09-14T00:00:00Z", "duration": -1, "data": {}}]
            else:
                query = parse_qs(parsed.query)
                start = query["start"][0]
                payload = [
                    {
                        "id": f"{bucket_id}-event",
                        "timestamp": start,
                        "duration": 12,
                        "data": {"app": "Fixture Editor.exe", "title": "<script>safe</script>"},
                    }
                ]
        else:
            self.send_error(404)
            return
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *_args: object) -> None:
        return


@pytest.fixture
def fake_activitywatch() -> Iterator[str]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), _FakeActivityWatchHandler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        thread.join(timeout=5)
        server.server_close()


def test_client_round_trips_against_real_loopback_http_server(fake_activitywatch: str) -> None:
    with ActivityWatchClient(base_url=fake_activitywatch) as client:
        info = client.get_info()
        buckets = client.get_buckets()
        events = client.get_events(
            "http-window",
            start=datetime(2026, 9, 14, tzinfo=UTC),
            end=datetime(2026, 9, 15, tzinfo=UTC),
        )

    assert info.hostname == "http-fixture-host"
    assert {bucket.id for bucket in buckets} == {
        "http-window",
        "http-afk",
        "http-malformed",
    }
    assert events[0].data["app"] == "Fixture Editor.exe"


def test_client_rejects_malformed_event_from_real_http_server(fake_activitywatch: str) -> None:
    with ActivityWatchClient(base_url=fake_activitywatch) as client:
        with pytest.raises(ActivityWatchProtocolError) as caught:
            client.get_events(
                "http-malformed",
                start="2026-09-14T00:00:00Z",
                end="2026-09-15T00:00:00Z",
            )

    assert caught.value.code == "incompatible_api"


@pytest.mark.parametrize(
    "base_url",
    (
        "https://127.0.0.1:5600",
        "http://example.invalid:5600",
        "http://127.0.0.1:5600/private",
        "http://user:password@127.0.0.1:5600",
    ),
)
def test_client_only_allows_http_loopback_base_urls(base_url: str) -> None:
    with pytest.raises(ValueError):
        ActivityWatchClient(base_url=base_url)
