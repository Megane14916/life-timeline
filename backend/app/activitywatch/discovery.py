"""Stable ActivityWatch bucket discovery without cross-host mixing."""

from __future__ import annotations

from dataclasses import dataclass

from app.activitywatch.errors import (
    ACTIVITYWATCH_MISSING_AFK_BUCKET,
    ACTIVITYWATCH_MISSING_WINDOW_BUCKET,
    ACTIVITYWATCH_WEB_DETAILS_UNAVAILABLE,
)
from app.activitywatch.schemas import ActivityWatchBucket, ActivityWatchInfo

WINDOW_CLIENT_PRIORITY = ("aw-watcher-window",)
AFK_CLIENT_PRIORITY = ("aw-watcher-afk",)
WEB_CLIENT_PRIORITY = ("aw-watcher-web",)


@dataclass(frozen=True, slots=True)
class ActivityWatchDiscovery:
    """Selected buckets and a safe status code for the next pipeline stage."""

    hostname: str
    window_bucket: ActivityWatchBucket | None
    afk_bucket: ActivityWatchBucket | None
    web_bucket: ActivityWatchBucket | None
    result_code: str

    @property
    def can_import_app_sessions(self) -> bool:
        return self.window_bucket is not None and self.afk_bucket is not None


def _priority(bucket: ActivityWatchBucket, clients: tuple[str, ...]) -> tuple[int, int, str]:
    try:
        client_priority = clients.index(bucket.client)
    except ValueError:
        client_priority = len(clients)
    created_priority = int(bucket.created.timestamp() * 1000) if bucket.created else 0
    return client_priority, created_priority, bucket.id


def _choose(
    buckets: list[ActivityWatchBucket], clients: tuple[str, ...]
) -> ActivityWatchBucket | None:
    if not buckets:
        return None
    return sorted(buckets, key=lambda bucket: _priority(bucket, clients))[0]


def discover_buckets(
    info: ActivityWatchInfo,
    buckets: tuple[ActivityWatchBucket, ...] | list[ActivityWatchBucket],
    *,
    expected_hostname: str | None = None,
) -> ActivityWatchDiscovery:
    """Select same-host buckets using metadata, never bucket-ID patterns."""

    hostname = expected_hostname or info.hostname
    if not hostname or (
        expected_hostname is not None and info.hostname.casefold() != expected_hostname.casefold()
    ):
        return ActivityWatchDiscovery(
            hostname, None, None, None, ACTIVITYWATCH_MISSING_WINDOW_BUCKET
        )
    same_host = [bucket for bucket in buckets if bucket.hostname.casefold() == hostname.casefold()]
    window = _choose(
        [bucket for bucket in same_host if bucket.type == "currentwindow"], WINDOW_CLIENT_PRIORITY
    )
    afk = _choose(
        [bucket for bucket in same_host if bucket.type == "afkstatus"], AFK_CLIENT_PRIORITY
    )
    web = _choose(
        [bucket for bucket in same_host if bucket.type == "web.tab.current"], WEB_CLIENT_PRIORITY
    )
    if window is None:
        result_code = ACTIVITYWATCH_MISSING_WINDOW_BUCKET
    elif afk is None:
        result_code = ACTIVITYWATCH_MISSING_AFK_BUCKET
    elif web is None:
        result_code = ACTIVITYWATCH_WEB_DETAILS_UNAVAILABLE
    else:
        result_code = "success"
    return ActivityWatchDiscovery(hostname, window, afk, web, result_code)
