"""Minimal, privacy-neutral validation models for ActivityWatch responses."""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from app.activitywatch.errors import ActivityWatchProtocolError


def _required_string(value: object, *, incompatible: bool = True) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ActivityWatchProtocolError(incompatible=incompatible)
    return value


def parse_timestamp(value: object, *, naive_is_utc: bool = False) -> datetime:
    """Parse an ISO-8601 timestamp, optionally treating a naive value as UTC."""

    if not isinstance(value, str) or not value.strip():
        raise ActivityWatchProtocolError(incompatible=True)
    candidate = value.strip()
    if candidate.endswith("Z"):
        candidate = f"{candidate[:-1]}+00:00"
    try:
        parsed = datetime.fromisoformat(candidate)
    except ValueError as error:
        raise ActivityWatchProtocolError(incompatible=True) from error
    if parsed.tzinfo is None:
        if not naive_is_utc:
            raise ActivityWatchProtocolError(incompatible=True)
        parsed = parsed.replace(tzinfo=UTC)
    if parsed.utcoffset() is None:
        raise ActivityWatchProtocolError(incompatible=True)
    return parsed.astimezone(UTC)


@dataclass(frozen=True, slots=True)
class ActivityWatchInfo:
    version: str
    hostname: str
    testing: bool | None = None

    @classmethod
    def from_payload(cls, payload: object) -> ActivityWatchInfo:
        if not isinstance(payload, dict):
            raise ActivityWatchProtocolError(incompatible=True)
        testing = payload.get("testing")
        if testing is not None and not isinstance(testing, bool):
            raise ActivityWatchProtocolError(incompatible=True)
        return cls(
            version=_required_string(payload.get("version")),
            hostname=_required_string(payload.get("hostname")),
            testing=testing,
        )


@dataclass(frozen=True, slots=True)
class ActivityWatchBucket:
    id: str
    type: str
    client: str
    hostname: str
    created: datetime | None = None

    @classmethod
    def from_payload(cls, bucket_id: str, payload: object) -> ActivityWatchBucket:
        if not isinstance(payload, dict):
            raise ActivityWatchProtocolError(incompatible=True)
        declared_id = _required_string(payload.get("id"))
        if declared_id != bucket_id:
            raise ActivityWatchProtocolError(incompatible=True)
        created_value = payload.get("created")
        created = (
            parse_timestamp(created_value, naive_is_utc=True) if created_value is not None else None
        )
        return cls(
            id=declared_id,
            type=_required_string(payload.get("type")),
            client=_required_string(payload.get("client")),
            hostname=_required_string(payload.get("hostname")),
            created=created,
        )


@dataclass(frozen=True, slots=True)
class ActivityWatchEvent:
    id: str | None
    timestamp: datetime
    duration_seconds: float
    data: dict[str, Any]

    @classmethod
    def from_payload(cls, payload: object) -> ActivityWatchEvent:
        if not isinstance(payload, dict):
            raise ActivityWatchProtocolError(incompatible=True)
        event_id = payload.get("id")
        if event_id is not None and (not isinstance(event_id, str) or not event_id.strip()):
            raise ActivityWatchProtocolError(incompatible=True)
        duration = payload.get("duration")
        if isinstance(duration, bool) or not isinstance(duration, (int, float)):
            raise ActivityWatchProtocolError(incompatible=True)
        if not math.isfinite(float(duration)) or duration <= 0:
            raise ActivityWatchProtocolError(incompatible=True)
        data = payload.get("data")
        if not isinstance(data, dict):
            raise ActivityWatchProtocolError(incompatible=True)
        return cls(
            id=event_id,
            timestamp=parse_timestamp(payload.get("timestamp")),
            duration_seconds=float(duration),
            data=dict(data),
        )
