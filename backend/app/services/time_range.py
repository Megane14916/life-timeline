"""Timezone-aware local-date ranges and interval clipping."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, date, datetime, time
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

DEFAULT_TIMEZONE = "Asia/Tokyo"
_DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class TimeRangeError(ValueError):
    """Raised when a requested date, period, or timezone is invalid."""

    def __init__(self, message: str, *, field: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.field = field


@dataclass(frozen=True, slots=True)
class QueryRange:
    start_ms: int
    end_ms: int
    start_iso: str
    end_iso: str
    timezone_name: str


@dataclass(frozen=True, slots=True)
class ClippedInterval:
    start_ms: int
    end_ms: int
    duration_ms: int
    continues_from_previous_day: bool
    continues_to_next_day: bool
    ends_at_day_boundary: bool


def _parse_date(value: str | None, *, field: str) -> date:
    if value is None:
        raise TimeRangeError(f"{field} is required.", field=field)
    if _DATE_PATTERN.fullmatch(value) is None:
        raise TimeRangeError(f"{field} must use YYYY-MM-DD format.", field=field)
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise TimeRangeError(f"{field} must be a real calendar date.", field=field) from error


def _resolve_timezone(value: str | None) -> ZoneInfo:
    timezone_name = DEFAULT_TIMEZONE if value is None else value
    if not timezone_name:
        raise TimeRangeError("timezone must not be empty.", field="timezone")
    try:
        return ZoneInfo(timezone_name)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise TimeRangeError(
            f"timezone '{timezone_name}' is not a valid IANA timezone.", field="timezone"
        ) from error


def _to_epoch_ms(value: datetime) -> int:
    return int(value.timestamp() * 1000)


def _format_epoch_ms(value: int) -> str:
    seconds, milliseconds = divmod(value, 1000)
    utc_value = datetime.fromtimestamp(seconds, tz=UTC).replace(microsecond=milliseconds * 1000)
    return utc_value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _build_range(start_date: date, end_date: date, timezone_name: str | None) -> QueryRange:
    if end_date <= start_date:
        raise TimeRangeError("to must be later than from.", field="to")
    zone = _resolve_timezone(timezone_name)
    start = datetime.combine(start_date, time.min, tzinfo=zone)
    end = datetime.combine(end_date, time.min, tzinfo=zone)
    start_ms = _to_epoch_ms(start.astimezone(UTC))
    end_ms = _to_epoch_ms(end.astimezone(UTC))
    return QueryRange(
        start_ms=start_ms,
        end_ms=end_ms,
        start_iso=_format_epoch_ms(start_ms),
        end_iso=_format_epoch_ms(end_ms),
        timezone_name=zone.key,
    )


def build_day_range(date_value: str | None, timezone_name: str | None) -> tuple[str, QueryRange]:
    target_date = _parse_date(date_value, field="date")
    if target_date == date.max:
        raise TimeRangeError("date is outside the supported range.", field="date")
    query_range = _build_range(
        target_date, target_date.fromordinal(target_date.toordinal() + 1), timezone_name
    )
    return target_date.isoformat(), query_range


def build_period_range(
    from_value: str | None, to_value: str | None, timezone_name: str | None
) -> tuple[str, str, QueryRange]:
    from_date = _parse_date(from_value, field="from")
    to_date = _parse_date(to_value, field="to")
    return (
        from_date.isoformat(),
        to_date.isoformat(),
        _build_range(from_date, to_date, timezone_name),
    )


def format_epoch_ms(value: int) -> str:
    """Format a stored UTC epoch millisecond value as RFC 3339 UTC."""

    return _format_epoch_ms(value)


def clip_interval(start_ms: int, end_ms: int, query_range: QueryRange) -> ClippedInterval:
    clipped_start = max(start_ms, query_range.start_ms)
    clipped_end = min(end_ms, query_range.end_ms)
    duration_ms = max(0, clipped_end - clipped_start)
    return ClippedInterval(
        start_ms=clipped_start,
        end_ms=clipped_end,
        duration_ms=duration_ms,
        continues_from_previous_day=start_ms < query_range.start_ms,
        continues_to_next_day=end_ms > query_range.end_ms,
        ends_at_day_boundary=clipped_end == query_range.end_ms,
    )
