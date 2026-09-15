"""Pure ActivityWatch active-period and Desktop Session normalization."""

from __future__ import annotations

import hashlib
import heapq
import json
import unicodedata
from bisect import bisect_left, bisect_right
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from itertools import pairwise

from app.activitywatch.periods import Period, clip_period, split_period, union_periods
from app.activitywatch.privacy import (
    URL_POLICY_VERSION,
    display_app_name,
    is_browser_identifier,
    normalize_app_identifier,
    sanitize_title,
    sanitize_url,
    validate_privacy_mode,
)
from app.activitywatch.schemas import ActivityWatchEvent
from app.ids import deterministic_ulid
from app.repositories import (
    ActivityWatchAppRecord,
    ActivityWatchDetailRecord,
    ActivityWatchDeviceRecord,
    ActivityWatchSessionRecord,
)


@dataclass(frozen=True, slots=True)
class ActivityWatchBucketEvent:
    bucket_id: str
    event: ActivityWatchEvent
    bucket_created_at_ms: int = 0


BucketEvent = ActivityWatchBucketEvent
UTC_DAY_MS = 86_400_000


@dataclass(frozen=True, slots=True)
class NormalizationDiagnostics:
    invalid_event_count: int = 0
    redacted_detail_count: int = 0
    truncated_title_count: int = 0


@dataclass(frozen=True, slots=True)
class NormalizedActivityWatch:
    device: ActivityWatchDeviceRecord
    apps: tuple[ActivityWatchAppRecord, ...]
    sessions: tuple[ActivityWatchSessionRecord, ...]
    details: tuple[ActivityWatchDetailRecord, ...]
    diagnostics: NormalizationDiagnostics


@dataclass(frozen=True, slots=True)
class _Window:
    source: ActivityWatchBucketEvent
    period: Period
    app_identifier: str
    display_name: str
    window_title: str | None
    title_truncated: int


@dataclass(frozen=True, slots=True)
class _Web:
    source: ActivityWatchBucketEvent
    period: Period
    title: str | None
    url: str | None
    usable_detail: bool
    title_truncated: int


@dataclass(frozen=True, slots=True)
class _Fragment:
    start_ms: int
    end_ms: int
    app_identifier: str
    display_name: str
    window_title: str | None
    url: str | None
    window_source: str
    contributing_sources: tuple[str, ...]
    title_truncated: int


def _period(source: ActivityWatchBucketEvent) -> Period:
    timestamp_ms = int(source.event.timestamp.timestamp() * 1000)
    duration_ms = int(
        (Decimal(str(source.event.duration_seconds)) * Decimal("1000")).quantize(
            Decimal("1"), rounding=ROUND_HALF_UP
        )
    )
    if duration_ms <= 0:
        raise ValueError("event duration is below one millisecond")
    return Period(timestamp_ms, timestamp_ms + duration_ms)


def _event_fingerprint(source: ActivityWatchBucketEvent) -> str:
    event = source.event
    event_identity = event.id
    if event_identity is None:
        event_identity = json.dumps(
            {
                "timestamp": event.timestamp.isoformat(),
                "duration": event.duration_seconds,
                "data": event.data,
                "bucket": source.bucket_id,
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    digest = hashlib.sha256(f"{source.bucket_id}\0{event_identity}".encode()).hexdigest()
    return f"aw1_{digest}"


def _device_id(hostname: str) -> str:
    normalized = unicodedata.normalize("NFKC", hostname).strip().casefold()
    try:
        normalized = normalized.encode("idna").decode("ascii")
    except UnicodeError as error:
        raise ValueError("hostname is invalid") from error
    if not normalized or len(normalized) > 255:
        raise ValueError("hostname is invalid")
    digest = hashlib.sha256(f"activitywatch-device-v1\0{normalized}".encode()).digest()[:10]
    return deterministic_ulid(0, digest)


def _app_id(identifier: str) -> str:
    digest = hashlib.sha256(f"activitywatch-app-v1\0{identifier}".encode()).digest()[:10]
    return deterministic_ulid(0, digest)


def _session_id(
    start_ms: int,
    device_id: str,
    window_source: str,
    end_ms: int,
    identifier: str,
    privacy_mode: str,
) -> str:
    seed = "\0".join(
        (
            device_id,
            window_source,
            str(start_ms),
            str(end_ms),
            identifier,
            URL_POLICY_VERSION,
            privacy_mode,
        )
    )
    digest = hashlib.sha256(seed.encode()).digest()[:10]
    return deterministic_ulid(start_ms, digest)


def _valid_window(source: ActivityWatchBucketEvent) -> _Window:
    data = source.event.data
    app = data.get("app")
    if not isinstance(app, str) or not app.strip():
        raise ValueError("window app is invalid")
    identifier = normalize_app_identifier(app)
    title_value = data.get("title")
    if title_value is not None and not isinstance(title_value, str):
        raise ValueError("window title is invalid")
    title, truncated = sanitize_title(title_value)
    return _Window(source, _period(source), identifier, display_app_name(app), title, truncated)


def _valid_afk(source: ActivityWatchBucketEvent) -> Period | None:
    status = source.event.data.get("status")
    if status == "not-afk":
        return _period(source)
    if status == "afk":
        return None
    raise ValueError("AFK status is invalid")


def _valid_web(source: ActivityWatchBucketEvent) -> _Web:
    data = source.event.data
    for field in ("url", "title"):
        if data.get(field) is not None and not isinstance(data[field], str):
            raise ValueError("web detail field is invalid")
    incognito_value = data.get("incognito")
    incognito = incognito_value if isinstance(incognito_value, bool) else None
    usable = incognito is not None
    title, truncated = (
        sanitize_title(
            data.get("title"),
        )
        if usable
        else (None, 0)
    )
    url = sanitize_url(data.get("url"), incognito=incognito is True) if usable else None
    return _Web(source, _period(source), title, url, usable and not incognito, truncated)


def _window_priority(window: _Window) -> tuple[int, str, str]:
    return (
        -window.source.bucket_created_at_ms,
        window.source.bucket_id,
        window.source.event.id or "",
    )


def _select_windows(windows: list[_Window]) -> tuple[_Window, ...]:
    if not windows:
        return ()
    boundaries = sorted(
        {point for window in windows for point in (window.period.start_ms, window.period.end_ms)}
    )
    ordered = sorted(enumerate(windows), key=lambda item: item[1].period.start_ms)
    active: list[tuple[tuple[int, str, str], int, int, _Window]] = []
    next_window = 0
    selected: list[_Window] = []
    for start_ms, end_ms in pairwise(boundaries):
        while next_window < len(ordered) and ordered[next_window][1].period.start_ms < end_ms:
            original_index, window = ordered[next_window]
            heapq.heappush(
                active,
                (_window_priority(window), original_index, window.period.end_ms, window),
            )
            next_window += 1
        while active and active[0][2] <= start_ms:
            heapq.heappop(active)
        if not active:
            continue
        winner = active[0][3]
        period = Period(start_ms, end_ms)
        if (
            selected
            and selected[-1].period.end_ms == start_ms
            and selected[-1].source == winner.source
        ):
            selected[-1] = _Window(
                winner.source,
                Period(selected[-1].period.start_ms, end_ms),
                winner.app_identifier,
                winner.display_name,
                winner.window_title,
                winner.title_truncated,
            )
        else:
            selected.append(
                _Window(
                    winner.source,
                    period,
                    winner.app_identifier,
                    winner.display_name,
                    winner.window_title,
                    winner.title_truncated,
                )
            )
    return tuple(selected)


def _matching_web(
    fragment: Period,
    web: list[_Web],
    web_starts: tuple[int, ...],
    web_prefix_max_ends: tuple[int, ...],
) -> tuple[_Web, ...]:
    if not web:
        return ()
    first = bisect_right(web_prefix_max_ends, fragment.start_ms)
    last = bisect_left(web_starts, fragment.end_ms)
    matching = [
        item
        for item in web[first:last]
        if item.period.start_ms < fragment.end_ms and item.period.end_ms > fragment.start_ms
    ]
    return tuple(
        sorted(
            matching,
            key=lambda item: (
                -item.source.bucket_created_at_ms,
                item.source.bucket_id,
                item.source.event.id or "",
            ),
        )
    )


def normalize_activitywatch(
    *,
    hostname: str,
    window_events: list[ActivityWatchBucketEvent] | tuple[ActivityWatchBucketEvent, ...],
    afk_events: list[ActivityWatchBucketEvent] | tuple[ActivityWatchBucketEvent, ...],
    web_events: list[ActivityWatchBucketEvent] | tuple[ActivityWatchBucketEvent, ...] = (),
    privacy_mode: str = "app_only",
    utc_day_start_ms: int | None = None,
    utc_day_end_ms: int | None = None,
    created_at_ms: int = 0,
) -> NormalizedActivityWatch:
    """Normalize window events intersected with unioned not-AFK periods."""

    validate_privacy_mode(privacy_mode)
    if (utc_day_start_ms is None) != (utc_day_end_ms is None):
        raise ValueError("UTC day start and end must be supplied together")
    if (
        utc_day_start_ms is not None
        and utc_day_end_ms is not None
        and utc_day_end_ms <= utc_day_start_ms
    ):
        raise ValueError("UTC day end must be after start")
    diagnostics = NormalizationDiagnostics()
    windows: list[_Window] = []
    for source in window_events:
        try:
            windows.append(_valid_window(source))
        except (ValueError, TypeError):
            diagnostics = NormalizationDiagnostics(
                diagnostics.invalid_event_count + 1,
                diagnostics.redacted_detail_count,
                diagnostics.truncated_title_count,
            )
    active: list[Period] = []
    for source in afk_events:
        try:
            period = _valid_afk(source)
            if period is not None:
                active.append(period)
        except (ValueError, TypeError):
            diagnostics = NormalizationDiagnostics(
                diagnostics.invalid_event_count + 1,
                diagnostics.redacted_detail_count,
                diagnostics.truncated_title_count,
            )
    web: list[_Web] = []
    for source in web_events:
        try:
            web.append(_valid_web(source))
        except (ValueError, TypeError):
            diagnostics = NormalizationDiagnostics(
                diagnostics.invalid_event_count + 1,
                diagnostics.redacted_detail_count,
                diagnostics.truncated_title_count,
            )
    web.sort(key=lambda item: (item.period.start_ms, item.period.end_ms))
    web_starts = tuple(item.period.start_ms for item in web)
    web_prefix_max_ends: list[int] = []
    for item in web:
        web_prefix_max_ends.append(
            max(item.period.end_ms, web_prefix_max_ends[-1] if web_prefix_max_ends else 0)
        )
    web_prefix_max_ends_tuple = tuple(web_prefix_max_ends)
    windows = list(_select_windows(windows))
    active_union = union_periods(active)
    fragments: list[_Fragment] = []
    for window in windows:
        intersections = []
        for active_period in active_union:
            start_ms = max(window.period.start_ms, active_period.start_ms)
            end_ms = min(window.period.end_ms, active_period.end_ms)
            if start_ms < end_ms:
                intersections.append(Period(start_ms, end_ms))
        for intersection in intersections:
            clipped = (
                clip_period(intersection, utc_day_start_ms, utc_day_end_ms)
                if utc_day_start_ms is not None and utc_day_end_ms is not None
                else intersection
            )
            if clipped is None:
                continue
            browser = is_browser_identifier(window.app_identifier)
            day_boundaries = list(
                range(
                    ((clipped.start_ms // UTC_DAY_MS) + 1) * UTC_DAY_MS,
                    clipped.end_ms,
                    UTC_DAY_MS,
                )
            )
            if privacy_mode == "web" and browser:
                day_boundaries.extend(
                    boundary
                    for item in web
                    for boundary in (item.period.start_ms, item.period.end_ms)
                )
            for piece in split_period(clipped, day_boundaries):
                title = None
                url = None
                sources = [_event_fingerprint(window.source)]
                truncated = window.title_truncated
                if privacy_mode != "app_only" and not browser:
                    title = window.window_title
                if privacy_mode == "web" and browser:
                    matched = _matching_web(
                        piece,
                        web,
                        web_starts,
                        web_prefix_max_ends_tuple,
                    )
                    if matched:
                        selected_web = matched[0]
                        sources.append(_event_fingerprint(selected_web.source))
                        if selected_web.usable_detail:
                            title = selected_web.title
                            url = selected_web.url
                            truncated += selected_web.title_truncated
                        else:
                            diagnostics = NormalizationDiagnostics(
                                diagnostics.invalid_event_count,
                                diagnostics.redacted_detail_count + 1,
                                diagnostics.truncated_title_count,
                            )
                if title is None and url is None and (privacy_mode == "web" and browser):
                    diagnostics = NormalizationDiagnostics(
                        diagnostics.invalid_event_count,
                        diagnostics.redacted_detail_count + 1,
                        diagnostics.truncated_title_count,
                    )
                fragments.append(
                    _Fragment(
                        piece.start_ms,
                        piece.end_ms,
                        window.app_identifier,
                        window.display_name,
                        title,
                        url,
                        _event_fingerprint(window.source),
                        tuple(sorted(sources)),
                        truncated,
                    )
                )
    fragments.sort(
        key=lambda item: (
            item.start_ms,
            item.end_ms,
            item.app_identifier,
            item.window_source,
            item.contributing_sources,
        )
    )
    merged: list[_Fragment] = []
    for fragment in fragments:
        if (
            merged
            and merged[-1].end_ms == fragment.start_ms
            and merged[-1].app_identifier == fragment.app_identifier
            and merged[-1].window_title == fragment.window_title
            and merged[-1].url == fragment.url
            and merged[-1].start_ms // UTC_DAY_MS == fragment.start_ms // UTC_DAY_MS
        ):
            previous = merged[-1]
            merged[-1] = _Fragment(
                previous.start_ms,
                fragment.end_ms,
                previous.app_identifier,
                previous.display_name,
                previous.window_title,
                previous.url,
                previous.window_source,
                tuple(sorted(set(previous.contributing_sources + fragment.contributing_sources))),
                previous.title_truncated + fragment.title_truncated,
            )
        else:
            merged.append(fragment)
    device_id = _device_id(hostname)
    device = ActivityWatchDeviceRecord(device_id, "Windows PC", created_at_ms)
    app_records: dict[str, ActivityWatchAppRecord] = {}
    session_records: list[ActivityWatchSessionRecord] = []
    details: list[ActivityWatchDetailRecord] = []
    for fragment in merged:
        if fragment.end_ms - fragment.start_ms < 1:
            continue
        app_id = _app_id(fragment.app_identifier)
        app_records.setdefault(
            fragment.app_identifier,
            ActivityWatchAppRecord(
                app_id, fragment.app_identifier, fragment.display_name, created_at_ms
            ),
        )
        source_seed = "\0".join(fragment.contributing_sources)
        session_id = _session_id(
            fragment.start_ms,
            device_id,
            source_seed,
            fragment.end_ms,
            fragment.app_identifier,
            privacy_mode,
        )
        session_records.append(
            ActivityWatchSessionRecord(
                session_id, app_id, fragment.start_ms, fragment.end_ms, created_at_ms
            )
        )
        detail_seed = hashlib.sha256(source_seed.encode()).hexdigest()
        details.append(
            ActivityWatchDetailRecord(
                session_id,
                fragment.window_title,
                fragment.url,
                f"aw1_{detail_seed}",
                created_at_ms,
                privacy_mode=privacy_mode,
            )
        )
    latest = max((record.ended_at_ms for record in session_records), default=created_at_ms)
    device = ActivityWatchDeviceRecord(device.id, device.name, device.created_at_ms, latest)
    diagnostics = NormalizationDiagnostics(
        diagnostics.invalid_event_count,
        diagnostics.redacted_detail_count,
        diagnostics.truncated_title_count + sum(record.title_truncated for record in merged),
    )
    return NormalizedActivityWatch(
        device,
        tuple(app_records[key] for key in sorted(app_records)),
        tuple(session_records),
        tuple(details),
        diagnostics,
    )


normalize_activitywatch_events = normalize_activitywatch
