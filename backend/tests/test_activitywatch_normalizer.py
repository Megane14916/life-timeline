from __future__ import annotations

from datetime import UTC, datetime
from random import Random

from app.activitywatch import (
    ActivityWatchBucketEvent,
    ActivityWatchEvent,
    Period,
    clip_period,
    intersect_periods,
    normalize_activitywatch,
    normalize_app_identifier,
    sanitize_title,
    sanitize_url,
    split_period,
    union_periods,
)
from app.ids import validate_ulid

DAY_START = int(datetime(2026, 9, 14, tzinfo=UTC).timestamp() * 1000)
DAY_MS = 86_400_000


def _event(
    event_id: str | None,
    offset_ms: int,
    duration_seconds: float,
    data: dict[str, object],
) -> ActivityWatchEvent:
    timestamp = datetime.fromtimestamp((DAY_START + offset_ms) / 1000, tz=UTC)
    return ActivityWatchEvent(event_id, timestamp, duration_seconds, data)


def _bucket_event(
    bucket_id: str,
    event_id: str | None,
    offset_ms: int,
    duration_seconds: float,
    data: dict[str, object],
    created_at_ms: int = 0,
) -> ActivityWatchBucketEvent:
    return ActivityWatchBucketEvent(
        bucket_id,
        _event(event_id, offset_ms, duration_seconds, data),
        created_at_ms,
    )


def test_period_operations_are_sorted_non_overlapping_and_half_open() -> None:
    periods = [Period(5, 10), Period(0, 5), Period(4, 8), Period(20, 30)]
    assert union_periods(periods) == (Period(0, 10), Period(20, 30))
    assert intersect_periods([Period(0, 10)], [Period(3, 5), Period(7, 12)]) == (
        Period(3, 5),
        Period(7, 10),
    )
    assert clip_period(Period(0, 10), 3, 8) == Period(3, 8)
    assert split_period(Period(0, 10), [7, 3, 3]) == (
        Period(0, 3),
        Period(3, 7),
        Period(7, 10),
    )


def test_normalizer_excludes_afk_and_resolves_overlapping_windows_once() -> None:
    windows = [
        _bucket_event(
            "old-window",
            "old-1",
            0,
            10,
            {"app": "Fixture Editor.exe", "title": "Editor"},
            1,
        ),
        _bucket_event(
            "new-window",
            "new-1",
            4_000,
            4,
            {"app": "Other.exe", "title": "Other"},
            2,
        ),
    ]
    afk = [
        _bucket_event("afk", "afk-1", 0, 3, {"status": "not-afk"}),
        _bucket_event("afk", "afk-2", 7_000, 3, {"status": "not-afk"}),
    ]
    result = normalize_activitywatch(
        hostname="fixture-host",
        window_events=windows,
        afk_events=afk,
        privacy_mode="titles",
    )
    assert [(session.started_at_ms, session.ended_at_ms) for session in result.sessions] == [
        (DAY_START, DAY_START + 3_000),
        (DAY_START + 7_000, DAY_START + 8_000),
        (DAY_START + 8_000, DAY_START + 10_000),
    ]
    assert {app.identifier for app in result.apps} == {"fixture editor.exe", "other.exe"}
    assert [detail.window_title for detail in result.details] == ["Editor", "Other", "Editor"]
    assert sum(session.ended_at_ms - session.started_at_ms for session in result.sessions) == 6_000


def test_normalizer_is_input_order_independent_and_ids_are_deterministic() -> None:
    windows = [
        _bucket_event("window", "b", 2_000, 2, {"app": "  Fixture   Editor.EXE  "}),
        _bucket_event("window", "a", 0, 2, {"app": "Fixture Editor.exe"}),
    ]
    afk = [_bucket_event("afk", "active", 0, 4, {"status": "not-afk"})]
    first = normalize_activitywatch(
        hostname="Host.Example",
        window_events=windows,
        afk_events=afk,
        privacy_mode="app_only",
    )
    shuffled_windows = list(windows)
    shuffled_afk = list(afk)
    Random(42).shuffle(shuffled_windows)
    Random(42).shuffle(shuffled_afk)
    second = normalize_activitywatch(
        hostname="host.example",
        window_events=shuffled_windows,
        afk_events=shuffled_afk,
        privacy_mode="app_only",
    )
    assert first == second
    validate_ulid(first.device.id)
    validate_ulid(first.apps[0].id)
    validate_ulid(first.sessions[0].id)
    assert first.device.name == "Windows PC"
    assert all(detail.window_title is None and detail.url is None for detail in first.details)


def test_browser_web_overlay_splits_detail_changes_and_applies_privacy_rules() -> None:
    windows = [
        _bucket_event(
            "window",
            "window-1",
            0,
            10,
            {"app": "Chrome.exe", "title": "Private browser window"},
        )
    ]
    active = [_bucket_event("afk", "active", 0, 10, {"status": "not-afk"})]
    web = [
        _bucket_event(
            "web",
            "web-1",
            0,
            5,
            {
                "url": "https://Exämple.com:443/a//b?remove=yes#fragment",
                "title": "First page",
                "incognito": False,
            },
        ),
        _bucket_event(
            "web",
            "web-2",
            5_000,
            5,
            {
                "url": "https://user:password@example.invalid/private",
                "title": "Private page",
                "incognito": True,
            },
        ),
    ]
    result = normalize_activitywatch(
        hostname="fixture-host",
        window_events=windows,
        afk_events=active,
        web_events=web,
        privacy_mode="web",
    )
    assert [(session.started_at_ms, session.ended_at_ms) for session in result.sessions] == [
        (DAY_START, DAY_START + 5_000),
        (DAY_START + 5_000, DAY_START + 10_000),
    ]
    assert result.details[0].window_title == "First page"
    assert result.details[0].url == "https://xn--exmple-cua.com/a//b"
    assert result.details[1].window_title is None
    assert result.details[1].url is None

    titles = normalize_activitywatch(
        hostname="fixture-host",
        window_events=windows,
        afk_events=active,
        web_events=web,
        privacy_mode="titles",
    )
    assert len(titles.sessions) == 1
    assert titles.details[0].window_title is None
    assert titles.details[0].url is None


def test_non_browser_titles_and_unsafe_url_are_redacted_without_raw_values() -> None:
    title, truncated = sanitize_title(" \uff26\uff49\uff58\uff54\uff55\uff52\uff45\u202e title ")
    assert title == "Fixture title"
    assert truncated == 0
    assert sanitize_url("file:///secret/document.txt") is None
    assert sanitize_url("javascript:alert(1)") is None
    assert sanitize_url("https://user:pass@example.invalid/a?secret=1#x") == (
        "https://example.invalid/a"
    )
    assert normalize_app_identifier("  Fixture   Editor.EXE ") == "fixture editor.exe"

    result = normalize_activitywatch(
        hostname="fixture-host",
        window_events=[
            _bucket_event(
                "window",
                "window-1",
                0,
                1,
                {"app": "Fixture Editor.exe", "title": "Visible title"},
            )
        ],
        afk_events=[_bucket_event("afk", "active", 0, 1, {"status": "not-afk"})],
        privacy_mode="titles",
    )
    assert result.details[0].window_title == "Visible title"
    assert result.details[0].url is None


def test_normalizer_splits_utc_days_and_counts_invalid_events() -> None:
    result = normalize_activitywatch(
        hostname="fixture-host",
        window_events=[
            _bucket_event("window", "cross-day", DAY_MS - 500, 1, {"app": "Editor.exe"}),
            _bucket_event("window", "invalid", 0, 0, {"app": ""}),
        ],
        afk_events=[
            _bucket_event("afk", "cross-day-active", DAY_MS - 500, 1, {"status": "not-afk"}),
            _bucket_event("afk", "unknown", 0, 1, {"status": "unknown"}),
        ],
        privacy_mode="app_only",
    )
    assert [(item.started_at_ms, item.ended_at_ms) for item in result.sessions] == [
        (DAY_START + DAY_MS - 500, DAY_START + DAY_MS),
        (DAY_START + DAY_MS, DAY_START + DAY_MS + 500),
    ]
    assert result.diagnostics.invalid_event_count == 2
