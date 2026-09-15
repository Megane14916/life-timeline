"""Measure ActivityWatch normalization and SQLite replacement on a safe fixture."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from alembic import command
from alembic.config import Config
from sqlalchemy.orm import Session, sessionmaker

from app.activitywatch import (
    ActivityWatchBucket,
    ActivityWatchDiscovery,
    ActivityWatchEvent,
    ActivityWatchImporter,
    ActivityWatchImportSettings,
)
from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory

DAY_MS = 86_400_000
FIXTURE_DAYS = 7
FIXTURE_START_MS = 1_787_932_800_000  # 2026-09-01T00:00:00Z
WINDOW_EVENT_COUNT = 20_000
AFK_EVENT_COUNT = 5_000
WEB_EVENT_COUNT = 20_000
TARGETS_MS = {
    "initial_pure": 30_000,
    "initial_sqlite": 120_000,
    "rolling_refresh": 15_000,
}


def _event(
    bucket_id: str,
    index: int,
    timestamp_ms: int,
    duration_ms: int,
) -> ActivityWatchEvent:
    if bucket_id == "benchmark-window":
        data: dict[str, Any] = {
            "app": "chrome.exe",
            "title": "benchmark window fixture",
        }
    elif bucket_id == "benchmark-web":
        data = {
            "title": "benchmark web fixture",
            "url": "https://benchmark.example.invalid/timeline",
            "incognito": False,
        }
    else:
        data = {"status": "not-afk"}
    return ActivityWatchEvent(
        f"p609-benchmark-{bucket_id}-{index:05d}",
        datetime.fromtimestamp(timestamp_ms / 1000, tz=UTC),
        duration_ms / 1000,
        data,
    )


def _build_events(bucket_id: str, count: int, duration_ms: int) -> tuple[ActivityWatchEvent, ...]:
    span_ms = FIXTURE_DAYS * DAY_MS - 60_000
    return tuple(
        _event(bucket_id, index, FIXTURE_START_MS + index * span_ms // count, duration_ms)
        for index in range(count)
    )


class DenseFixtureClient:
    def __init__(self) -> None:
        self.events = {
            "benchmark-window": _build_events("benchmark-window", WINDOW_EVENT_COUNT, 10_000),
            "benchmark-afk": _build_events("benchmark-afk", AFK_EVENT_COUNT, 60_000),
            "benchmark-web": _build_events("benchmark-web", WEB_EVENT_COUNT, 10_000),
        }
        created = datetime.fromtimestamp(FIXTURE_START_MS / 1000, tz=UTC)
        self.buckets = {
            bucket.id: bucket
            for bucket in (
                ActivityWatchBucket(
                    "benchmark-window",
                    "currentwindow",
                    "aw-watcher-window",
                    "benchmark-host",
                    created,
                ),
                ActivityWatchBucket(
                    "benchmark-afk", "afkstatus", "aw-watcher-afk", "benchmark-host", created
                ),
                ActivityWatchBucket(
                    "benchmark-web", "web.tab.current", "aw-watcher-web", "benchmark-host", created
                ),
            )
        }

    def discover(self, *, expected_hostname: str | None = None) -> ActivityWatchDiscovery:
        return ActivityWatchDiscovery(
            "benchmark-host",
            self.buckets["benchmark-window"],
            self.buckets["benchmark-afk"],
            self.buckets["benchmark-web"],
            "success",
        )

    def get_events(
        self,
        bucket_id: str,
        *,
        start: datetime | str,
        end: datetime | str,
        limit: int = 10_000,
    ) -> tuple[ActivityWatchEvent, ...]:
        assert isinstance(start, datetime)
        assert isinstance(end, datetime)
        return tuple(
            event
            for event in self.events[bucket_id]
            if event.timestamp < end
            and event.timestamp.timestamp() + event.duration_seconds > start.timestamp()
        )[:limit]


def _run(
    client: DenseFixtureClient,
    session_factory: sessionmaker[Session] | None = None,
    *,
    from_ms: int,
    to_ms: int,
) -> tuple[int, int]:
    importer = ActivityWatchImporter(
        client,
        session_factory,
        settings=ActivityWatchImportSettings(
            max_run_days=FIXTURE_DAYS,
            max_run_ms=600_000,
            privacy_mode="web",
        ),
        clock_ms=lambda: to_ms + DAY_MS,
    )
    started = time.perf_counter()
    result = importer.run(from_ms=from_ms, to_ms=to_ms, dry_run=session_factory is None)
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    return elapsed_ms, result.session_count


def run_benchmark() -> dict[str, object]:
    client = DenseFixtureClient()
    first_end_ms = FIXTURE_START_MS + FIXTURE_DAYS * DAY_MS
    initial_pure_ms, initial_pure_sessions = _run(
        client, from_ms=FIXTURE_START_MS, to_ms=first_end_ms
    )
    with tempfile.TemporaryDirectory(prefix="life-timeline-p609-benchmark-") as directory:
        settings = Settings(data_dir=Path(directory))
        engine = create_engine_for_settings(settings)
        previous_data_dir = os.environ.get("LIFE_TIMELINE_DATA_DIR")
        os.environ["LIFE_TIMELINE_DATA_DIR"] = str(settings.data_dir)
        try:
            migration_config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
            command.upgrade(migration_config, "head")
            session_factory = create_session_factory(engine)
            initial_sqlite_ms, initial_sqlite_sessions = _run(
                client,
                session_factory,
                from_ms=FIXTURE_START_MS,
                to_ms=first_end_ms,
            )
            rolling_ms, rolling_sessions = _run(
                client,
                session_factory,
                from_ms=first_end_ms - DAY_MS,
                to_ms=first_end_ms,
            )
        finally:
            if previous_data_dir is None:
                os.environ.pop("LIFE_TIMELINE_DATA_DIR", None)
            else:
                os.environ["LIFE_TIMELINE_DATA_DIR"] = previous_data_dir
            engine.dispose()
    measurements: dict[str, dict[str, int]] = {
        "initial_pure": {
            "duration_ms": initial_pure_ms,
            "session_count": initial_pure_sessions,
            "target_ms": TARGETS_MS["initial_pure"],
        },
        "initial_sqlite": {
            "duration_ms": initial_sqlite_ms,
            "session_count": initial_sqlite_sessions,
            "target_ms": TARGETS_MS["initial_sqlite"],
        },
        "rolling_refresh": {
            "duration_ms": rolling_ms,
            "session_count": rolling_sessions,
            "target_ms": TARGETS_MS["rolling_refresh"],
        },
    }
    result_code = (
        "success"
        if all(item["duration_ms"] <= item["target_ms"] for item in measurements.values())
        and all(item["session_count"] > 0 for item in measurements.values())
        else "performance_regression"
    )
    return {
        "fixture": {
            "days": FIXTURE_DAYS,
            "window_events": WINDOW_EVENT_COUNT,
            "afk_events": AFK_EVENT_COUNT,
            "web_events": WEB_EVENT_COUNT,
        },
        "measurements": measurements,
        "result_code": result_code,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="safe JSON record path")
    arguments = parser.parse_args()
    try:
        record = run_benchmark()
    except Exception:
        record = {"result_code": "benchmark_error"}
    rendered = json.dumps(record, indent=2) + "\n"
    if arguments.output is not None:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if record["result_code"] == "success" else 1


if __name__ == "__main__":
    raise SystemExit(main())
