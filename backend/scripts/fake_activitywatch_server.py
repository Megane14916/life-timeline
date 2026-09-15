"""Serve a deterministic, loopback-only ActivityWatch fixture for CI E2E runs."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

FIXTURE_DAYS = 7
EVENTS_PER_DAY = 120
HOSTNAME = "p6-09-fake-host"
VERSION = "0.13.2"


def _parse_timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.removesuffix("Z") + "+00:00").astimezone(UTC)


def _fixture_events(bucket_id: str, start: datetime, end: datetime) -> list[dict[str, object]]:
    now = datetime.now(UTC)
    current_day = now.replace(hour=0, minute=0, second=0, microsecond=0)
    first_day = current_day - timedelta(days=FIXTURE_DAYS - 1)
    events: list[dict[str, object]] = []
    for day_index in range(FIXTURE_DAYS):
        day_start = first_day + timedelta(days=day_index)
        for event_index in range(EVENTS_PER_DAY):
            timestamp = day_start + timedelta(minutes=120 + event_index * 10)
            if timestamp >= now - timedelta(minutes=3):
                continue
            duration = 30
            event_end = timestamp + timedelta(seconds=duration)
            if event_end <= start or timestamp >= end:
                continue
            event_id = f"p609-{bucket_id}-{day_index:02d}-{event_index:03d}"
            if bucket_id == "p6-window":
                data: dict[str, object] = {
                    "app": "chrome.exe",
                    "title": "P6-09 <script>alert(1)</script> \"fixture\"",
                }
            elif bucket_id == "p6-web":
                data = {
                    "title": "P6-09 <script>alert(1)</script> \"web fixture\"",
                    "url": "https://p6-09.example.invalid/timeline/path",
                    "incognito": False,
                }
            else:
                data = {"status": "not-afk"}
            events.append(
                {
                    "id": event_id,
                    "timestamp": timestamp.isoformat(timespec="milliseconds").replace(
                        "+00:00", "Z"
                    ),
                    "duration": duration,
                    "data": data,
                }
            )
    return events


class FakeActivityWatchHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlsplit(self.path)
        if parsed.path == "/health":
            payload: object = {"status": "ok"}
        elif parsed.path == "/api/0/info":
            payload = {"version": VERSION, "hostname": HOSTNAME, "testing": True}
        elif parsed.path == "/api/0/buckets/":
            created = "2026-09-15T00:00:00.000Z"
            payload = {
                bucket_id: {
                    "id": bucket_id,
                    "type": bucket_type,
                    "client": client,
                    "hostname": HOSTNAME,
                    "created": created,
                }
                for bucket_id, bucket_type, client in (
                    ("p6-window", "currentwindow", "aw-watcher-window"),
                    ("p6-afk", "afkstatus", "aw-watcher-afk"),
                    ("p6-web", "web.tab.current", "aw-watcher-web"),
                    ("p6-malformed", "fixture.invalid", "fixture-invalid"),
                )
            }
        elif parsed.path.endswith("/events"):
            bucket_id = unquote(parsed.path.split("/")[-2])
            query = parse_qs(parsed.query)
            try:
                start = _parse_timestamp(query["start"][0])
                end = _parse_timestamp(query["end"][0])
            except (KeyError, IndexError, ValueError):
                self._write_json(400, {"error": "invalid fixture range"})
                return
            if bucket_id == "p6-malformed":
                payload = [
                    {
                        "id": "p6-malformed-event",
                        "timestamp": start.isoformat().replace("+00:00", "Z"),
                        "duration": -1,
                        "data": {},
                    }
                ]
            elif bucket_id in {"p6-window", "p6-afk", "p6-web"}:
                payload = _fixture_events(bucket_id, start, end)
            else:
                self._write_json(404, {"error": "unknown fixture bucket"})
                return
        else:
            self._write_json(404, {"error": "not found"})
            return
        self._write_json(200, payload)

    def _write_json(self, status: int, payload: object) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format: str, *_args: object) -> None:
        return


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5600)
    arguments = parser.parse_args()
    if arguments.host not in {"127.0.0.1", "localhost", "::1"}:
        parser.error("fake ActivityWatch server must bind to loopback")
    server = ThreadingHTTPServer((arguments.host, arguments.port), FakeActivityWatchHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
