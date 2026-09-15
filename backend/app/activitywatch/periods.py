"""Deterministic interval union, overlap resolution, intersection, and clipping."""

from __future__ import annotations

from dataclasses import dataclass, replace
from itertools import pairwise


@dataclass(frozen=True, slots=True)
class Period:
    start_ms: int
    end_ms: int
    key: str = ""
    value: object | None = None

    def __post_init__(self) -> None:
        if self.end_ms <= self.start_ms:
            raise ValueError("period end must be after period start.")


def union_periods(periods: list[Period] | tuple[Period, ...]) -> tuple[Period, ...]:
    """Return sorted, non-overlapping periods, merging overlap and adjacency."""

    if not periods:
        return ()
    ordered = sorted(periods, key=lambda period: (period.start_ms, period.end_ms, period.key))
    merged: list[Period] = [ordered[0]]
    for period in ordered[1:]:
        current = merged[-1]
        if period.start_ms <= current.end_ms:
            merged[-1] = replace(current, end_ms=max(current.end_ms, period.end_ms))
        else:
            merged.append(period)
    return tuple(merged)


def intersect_periods(
    left: list[Period] | tuple[Period, ...], right: list[Period] | tuple[Period, ...]
) -> tuple[Period, ...]:
    """Return the non-overlapping intersection of two period collections."""

    left_union = union_periods(left)
    right_union = union_periods(right)
    intersections: list[Period] = []
    left_index = right_index = 0
    while left_index < len(left_union) and right_index < len(right_union):
        left_period = left_union[left_index]
        right_period = right_union[right_index]
        start_ms = max(left_period.start_ms, right_period.start_ms)
        end_ms = min(left_period.end_ms, right_period.end_ms)
        if start_ms < end_ms:
            intersections.append(Period(start_ms, end_ms))
        if left_period.end_ms <= right_period.end_ms:
            left_index += 1
        else:
            right_index += 1
    return tuple(intersections)


def clip_period(period: Period, start_ms: int, end_ms: int) -> Period | None:
    """Clip one period to a half-open range."""

    if end_ms <= start_ms:
        raise ValueError("clip end must be after clip start.")
    clipped_start = max(period.start_ms, start_ms)
    clipped_end = min(period.end_ms, end_ms)
    if clipped_start >= clipped_end:
        return None
    return replace(period, start_ms=clipped_start, end_ms=clipped_end)


def split_period(period: Period, boundaries_ms: list[int] | tuple[int, ...]) -> tuple[Period, ...]:
    """Split a period at sorted or unsorted boundaries inside its range."""

    boundaries = sorted(
        {boundary for boundary in boundaries_ms if period.start_ms < boundary < period.end_ms}
    )
    points = [period.start_ms, *boundaries, period.end_ms]
    return tuple(
        replace(period, start_ms=start_ms, end_ms=end_ms)
        for start_ms, end_ms in pairwise(points)
        if start_ms < end_ms
    )
