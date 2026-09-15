from __future__ import annotations

from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import func, select

from app.activitywatch import (
    ActivityWatchBucket,
    ActivityWatchDiscovery,
    ActivityWatchEvent,
    ActivityWatchImportError,
    ActivityWatchImportSettings,
    ActivityWatchImporter,
    ImportRangeError,
    TooManyEventsError,
    fetch_bucket_events,
    iter_utc_days,
    local_date_range_to_utc,
)
from app.activitywatch.errors import ActivityWatchUnavailableError
from app.config import DATA_DIR_ENV, Settings
from app.db import create_engine_for_settings, create_session_factory
from app.models import ActivityWatchImportState, AppSession
from app.repositories import source_key_for

DAY_MS = 86_400_000
DAY_START = 1_788_825_600_000


def _bucket(bucket_id: str, bucket_type: str, client: str) -> ActivityWatchBucket:
    return ActivityWatchBucket(
        bucket_id,
        bucket_type,
        client,
        "fixture-host",
        datetime.fromtimestamp(DAY_START / 1000, tz=UTC),
    )


def _event(
    event_id: str,
    timestamp_ms: int,
    duration_ms: int,
    data: dict[str, Any],
) -> ActivityWatchEvent:
    return ActivityWatchEvent(
        event_id,
        datetime.fromtimestamp(timestamp_ms / 1000, tz=UTC),
        duration_ms / 1000,
        data,
    )


class _FakeClient:
    def __init__(self, events: dict[str, tuple[ActivityWatchEvent, ...]]) -> None:
        self.events = events
        self.calls: list[tuple[str, datetime, datetime, int]] = []
        self.fail_bucket: str | None = None

    def discover(self, *, expected_hostname: str | None = None) -> ActivityWatchDiscovery:
        buckets = {
            bucket_id: _bucket(bucket_id, bucket_type, client)
            for bucket_id, bucket_type, client in (
                ("window", "currentwindow", "aw-watcher-window"),
                ("afk", "afkstatus", "aw-watcher-afk"),
                ("web", "web.tab.current", "aw-watcher-web"),
            )
        }
        return ActivityWatchDiscovery(
            "fixture-host",
            buckets["window"],
            buckets["afk"],
            buckets["web"],
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
        if self.fail_bucket == bucket_id:
            raise ActivityWatchUnavailableError()
        assert isinstance(start, datetime)
        assert isinstance(end, datetime)
        self.calls.append((bucket_id, start, end, limit))
        return self.events.get(bucket_id, ())


class _DenseClient:
    def __init__(self, event: ActivityWatchEvent) -> None:
        self.event = event
        self.calls: list[tuple[datetime, datetime]] = []

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
        self.calls.append((start, end))
        if (end - start).total_seconds() * 1000 > 300_000:
            return tuple([self.event] * limit)
        return (self.event,)


def test_utc_day_helpers_expand_ranges_and_handle_dst() -> None:
    assert iter_utc_days(DAY_START + 1, DAY_START + DAY_MS + 1) == (
        (DAY_START, DAY_START + DAY_MS),
        (DAY_START + DAY_MS, DAY_START + 2 * DAY_MS),
    )
    start_ms, end_ms = local_date_range_to_utc(
        date(2026, 3, 8), date(2026, 3, 10), "America/New_York"
    )
    assert end_ms - start_ms == 47 * 60 * 60 * 1000
    with pytest.raises(ImportRangeError):
        iter_utc_days(DAY_START, DAY_START)


def test_exact_limit_response_is_split_and_deduplicated() -> None:
    event = _event("dense-event", DAY_START + 1_000, 1_000, {"app": "Editor"})
    client = _DenseClient(event)
    bucket = _bucket("window", "currentwindow", "aw-watcher-window")

    result = fetch_bucket_events(
        client,
        bucket,
        start_ms=DAY_START,
        end_ms=DAY_START + 15 * 60 * 1000,
    )

    assert [item.event.id for item in result] == ["dense-event"]
    assert len(client.calls) > 1
    assert all(
        int((end - start).total_seconds() * 1000) <= 7_500_000 for start, end in client.calls
    )

    class AlwaysDense(_DenseClient):
        def get_events(self, *args: Any, **kwargs: Any) -> tuple[ActivityWatchEvent, ...]:
            return tuple([self.event] * 10_000)

    with pytest.raises(TooManyEventsError):
        fetch_bucket_events(
            AlwaysDense(event),
            bucket,
            start_ms=DAY_START,
            end_ms=DAY_START + 5 * 60 * 1000,
        )


def test_import_writes_closed_day_idempotently_and_rolls_back_fetch_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_dir = tmp_path / "data"
    monkeypatch.setenv(DATA_DIR_ENV, str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    factory = create_session_factory(engine)
    try:
        events = {
            "window": (_event("window-1", DAY_START + 1_000, 60_000, {"app": "Editor.exe"}),),
            "afk": (_event("afk-1", DAY_START, 120_000, {"status": "not-afk"}),),
            "web": (),
        }
        client = _FakeClient(events)
        now_ms = DAY_START + 2 * DAY_MS + 3 * 60 * 1000
        importer = ActivityWatchImporter(
            client,
            factory,
            settings=ActivityWatchImportSettings(privacy_mode="titles"),
            clock_ms=lambda: now_ms,
        )
        first = importer.run(from_ms=DAY_START, to_ms=DAY_START + DAY_MS)
        second = importer.run(from_ms=DAY_START, to_ms=DAY_START + DAY_MS)

        assert first.session_count == second.session_count == 1
        assert first.total_duration_ms == second.total_duration_ms == 60_000
        assert first.chunks[0].replaced is True
        assert first.chunks[0].closed_day is True
        with factory() as session:
            assert session.scalar(select(func.count()).select_from(AppSession)) == 1
            state = session.get(ActivityWatchImportState, source_key_for("fixture-host", "0.13.2"))
            assert state is not None
            assert state.completed_through_ms == DAY_START + DAY_MS
            cursor_before_failure = state.completed_through_ms
        client.fail_bucket = "afk"
        with pytest.raises(ActivityWatchImportError) as caught:
            importer.run(from_ms=DAY_START, to_ms=DAY_START + DAY_MS)
        assert caught.value.result_code == "unavailable"
        with factory() as session:
            assert session.scalar(select(func.count()).select_from(AppSession)) == 1
            state = session.get(ActivityWatchImportState, source_key_for("fixture-host", "0.13.2"))
            assert state is not None
            assert state.completed_through_ms == cursor_before_failure
    finally:
        engine.dispose()


def test_dry_run_never_creates_import_state_or_lease(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_dir = tmp_path / "data"
    monkeypatch.setenv(DATA_DIR_ENV, str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    factory = create_session_factory(engine)
    try:
        client = _FakeClient(
            {
                "window": (),
                "afk": (),
                "web": (),
            }
        )
        result = ActivityWatchImporter(
            client,
            factory,
            clock_ms=lambda: DAY_START + 2 * DAY_MS,
        ).run(dry_run=True, from_ms=DAY_START, to_ms=DAY_START + DAY_MS)
        assert result.dry_run is True
        with factory() as session:
            assert session.scalar(select(func.count()).select_from(ActivityWatchImportState)) == 0
    finally:
        engine.dispose()
