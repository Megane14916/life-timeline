"""Deterministic demo records used by the seed command."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from app.repositories import AppRecord, AppSessionRecord, CategoryRecord, DeviceRecord

SEED_TIMEZONE = "Asia/Tokyo"
SEED_DATES = ("2026-09-02", "2026-09-03", "2026-09-04")

ANDROID_DEVICE_A_ID = "01J00000000000000000001001"
ANDROID_DEVICE_B_ID = "01J00000000000000000001002"
WINDOWS_DEVICE_ID = "01J00000000000000000001003"

ANDROID_CHROME_ID = "01J00000000000000000001101"
ANDROID_MAPS_ID = "01J00000000000000000001102"
ANDROID_UNKNOWN_APP_ID = "01J00000000000000000001103"
WINDOWS_CHROME_ID = "01J00000000000000000001104"
ANDROID_LONG_NAME_APP_ID = "01J00000000000000000001105"

BROWSER_CATEGORY_ID = "01J00000000000000000001201"
DEVELOPMENT_CATEGORY_ID = "01J00000000000000000001202"


def _at_ms(value: str) -> int:
    """Convert a fixed, timezone-aware ISO timestamp to UTC epoch milliseconds."""

    timestamp = datetime.fromisoformat(value)
    if timestamp.tzinfo is None:
        raise ValueError("seed timestamps must include a timezone")
    return int(timestamp.timestamp() * 1000)


def _session(
    session_id: str,
    device_id: str,
    app_id: str,
    started_at: str,
    ended_at: str,
    source: str,
) -> AppSessionRecord:
    return AppSessionRecord(
        id=session_id,
        device_id=device_id,
        app_id=app_id,
        started_at_ms=_at_ms(started_at),
        ended_at_ms=_at_ms(ended_at),
        source=source,
        created_at_ms=_at_ms("2026-09-01T00:00:00+09:00"),
    )


@dataclass(frozen=True, slots=True)
class SeedFixture:
    categories: tuple[CategoryRecord, ...]
    devices: tuple[DeviceRecord, ...]
    apps: tuple[AppRecord, ...]
    app_sessions: tuple[AppSessionRecord, ...]


FIXTURE = SeedFixture(
    categories=(
        CategoryRecord(BROWSER_CATEGORY_ID, "Browser", _at_ms("2026-09-01T00:00:00+09:00")),
        CategoryRecord(DEVELOPMENT_CATEGORY_ID, "Development", _at_ms("2026-09-01T00:00:00+09:00")),
    ),
    devices=(
        DeviceRecord(
            ANDROID_DEVICE_A_ID,
            "Demo Android A",
            "android",
            _at_ms("2026-09-01T00:00:00+09:00"),
        ),
        DeviceRecord(
            ANDROID_DEVICE_B_ID,
            "Demo Android B",
            "android",
            _at_ms("2026-09-01T00:00:00+09:00"),
        ),
        DeviceRecord(
            WINDOWS_DEVICE_ID,
            "Demo Windows",
            "windows",
            _at_ms("2026-09-01T00:00:00+09:00"),
        ),
    ),
    apps=(
        AppRecord(
            ANDROID_CHROME_ID,
            "android",
            "com.google.android.chrome",
            "Chrome",
            _at_ms("2026-09-01T00:00:00+09:00"),
            category_id=BROWSER_CATEGORY_ID,
        ),
        AppRecord(
            ANDROID_MAPS_ID,
            "android",
            "com.google.android.apps.maps",
            "Google Maps",
            _at_ms("2026-09-01T00:00:00+09:00"),
        ),
        AppRecord(
            ANDROID_UNKNOWN_APP_ID,
            "android",
            "com.example.unknown-app",
            "com.example.unknown-app",
            _at_ms("2026-09-01T00:00:00+09:00"),
        ),
        AppRecord(
            WINDOWS_CHROME_ID,
            "windows",
            "chrome.exe",
            "Chrome",
            _at_ms("2026-09-01T00:00:00+09:00"),
            category_id=BROWSER_CATEGORY_ID,
        ),
        AppRecord(
            ANDROID_LONG_NAME_APP_ID,
            "android",
            "com.example.long-name",
            "日本語の長いアプリ表示名を確認するためのデモアプリケーション",
            _at_ms("2026-09-01T00:00:00+09:00"),
            category_id=DEVELOPMENT_CATEGORY_ID,
        ),
    ),
    app_sessions=(
        _session(
            "01J00000000000000000001301",
            ANDROID_DEVICE_A_ID,
            ANDROID_MAPS_ID,
            "2026-09-02T23:40:00+09:00",
            "2026-09-03T00:00:00+09:00",
            "android_usage_stats",
        ),
        _session(
            "01J00000000000000000001302",
            ANDROID_DEVICE_A_ID,
            ANDROID_CHROME_ID,
            "2026-09-02T23:50:00+09:00",
            "2026-09-03T00:10:00+09:00",
            "android_usage_stats",
        ),
        _session(
            "01J00000000000000000001303",
            ANDROID_DEVICE_A_ID,
            ANDROID_CHROME_ID,
            "2026-09-03T09:00:00+09:00",
            "2026-09-03T09:20:00+09:00",
            "android_usage_stats",
        ),
        _session(
            "01J00000000000000000001304",
            ANDROID_DEVICE_B_ID,
            ANDROID_CHROME_ID,
            "2026-09-03T09:10:00+09:00",
            "2026-09-03T09:15:00+09:00",
            "android_usage_stats",
        ),
        _session(
            "01J00000000000000000001305",
            WINDOWS_DEVICE_ID,
            WINDOWS_CHROME_ID,
            "2026-09-03T09:00:00+09:00",
            "2026-09-03T09:30:00+09:00",
            "activitywatch",
        ),
        _session(
            "01J00000000000000000001306",
            ANDROID_DEVICE_A_ID,
            ANDROID_MAPS_ID,
            "2026-09-02T12:00:00+09:00",
            "2026-09-02T12:08:00+09:00",
            "android_usage_stats",
        ),
        _session(
            "01J00000000000000000001307",
            ANDROID_DEVICE_B_ID,
            ANDROID_UNKNOWN_APP_ID,
            "2026-09-02T12:00:00+09:00",
            "2026-09-02T12:00:01.123+09:00",
            "android_usage_stats",
        ),
        _session(
            "01J00000000000000000001308",
            WINDOWS_DEVICE_ID,
            WINDOWS_CHROME_ID,
            "2026-09-04T00:00:00+09:00",
            "2026-09-04T00:00:00.999+09:00",
            "activitywatch",
        ),
        _session(
            "01J00000000000000000001309",
            ANDROID_DEVICE_A_ID,
            ANDROID_LONG_NAME_APP_ID,
            "2026-09-04T14:00:00+09:00",
            "2026-09-04T14:00:59.999+09:00",
            "android_usage_stats",
        ),
        _session(
            "01J00000000000000000001310",
            ANDROID_DEVICE_A_ID,
            ANDROID_LONG_NAME_APP_ID,
            "2026-09-04T23:59:30+09:00",
            "2026-09-05T00:00:00+09:00",
            "android_usage_stats",
        ),
    ),
)

EXPECTED_STATISTICS = {
    "2026-09-03": {
        "usage_ms": 3_900_000,
        "session_count": 4,
        "app_count": 2,
    },
    "2026-09-05": {
        "usage_ms": 0,
        "session_count": 0,
        "app_count": 0,
    },
}
