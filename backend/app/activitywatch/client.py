"""Read-only, bounded HTTP client for the ActivityWatch loopback API."""

from __future__ import annotations

import json
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from urllib.parse import quote

import httpx

from app.activitywatch.discovery import ActivityWatchDiscovery, discover_buckets
from app.activitywatch.errors import (
    ActivityWatchError,
    ActivityWatchProtocolError,
    ActivityWatchUnavailableError,
)
from app.activitywatch.schemas import (
    ActivityWatchBucket,
    ActivityWatchEvent,
    ActivityWatchInfo,
    parse_timestamp,
)

LOOPBACK_BASE_URL = "http://127.0.0.1:5600"
ACTIVITYWATCH_API_PREFIX = "/api/0"
ACTIVITYWATCH_STABLE_RELEASE = "0.13.2"
MAX_RESPONSE_BYTES = 32 * 1024 * 1024
EVENT_LIMIT = 10_000


def _timestamp_param(value: datetime | str) -> str:
    if isinstance(value, str):
        parsed = parse_timestamp(value)
    elif isinstance(value, datetime):
        if value.tzinfo is None or value.utcoffset() is None:
            raise ActivityWatchProtocolError(incompatible=True)
        parsed = value
    else:
        raise ActivityWatchProtocolError(incompatible=True)
    utc_value = parsed.astimezone(UTC)
    return utc_value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _validate_bucket_id(bucket_id: str) -> str:
    if (
        not isinstance(bucket_id, str)
        or not bucket_id
        or len(bucket_id) > 255
        or any(ord(character) < 0x20 for character in bucket_id)
        or "/" in bucket_id
        or "\\" in bucket_id
        or bucket_id in {".", ".."}
    ):
        raise ActivityWatchProtocolError()
    return bucket_id


class ActivityWatchClient:
    """Expose only the three GET endpoints allowed by the Phase 6 contract."""

    def __init__(
        self,
        *,
        transport: httpx.BaseTransport | None = None,
        expected_version: str = ACTIVITYWATCH_STABLE_RELEASE,
        max_response_bytes: int = MAX_RESPONSE_BYTES,
        connect_timeout_seconds: float = 2.0,
        read_timeout_seconds: float = 60.0,
    ) -> None:
        if max_response_bytes <= 0 or connect_timeout_seconds <= 0 or read_timeout_seconds <= 0:
            raise ValueError("ActivityWatch client limits must be positive.")
        self._expected_version = expected_version
        self._max_response_bytes = max_response_bytes
        self._client = httpx.Client(
            base_url=LOOPBACK_BASE_URL,
            follow_redirects=False,
            trust_env=False,
            timeout=httpx.Timeout(
                connect=connect_timeout_seconds,
                read=read_timeout_seconds,
                write=read_timeout_seconds,
                pool=connect_timeout_seconds,
            ),
            transport=transport,
            headers={"Accept": "application/json"},
        )

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> ActivityWatchClient:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def get_info(self) -> ActivityWatchInfo:
        payload = self._get_json(f"{ACTIVITYWATCH_API_PREFIX}/info")
        info = ActivityWatchInfo.from_payload(payload)
        if info.version != self._expected_version:
            raise ActivityWatchProtocolError(incompatible=True)
        return info

    def get_buckets(self) -> tuple[ActivityWatchBucket, ...]:
        payload = self._get_json(f"{ACTIVITYWATCH_API_PREFIX}/buckets/")
        if not isinstance(payload, dict):
            raise ActivityWatchProtocolError(incompatible=True)
        buckets = tuple(
            ActivityWatchBucket.from_payload(bucket_id, bucket_payload)
            for bucket_id, bucket_payload in payload.items()
            if isinstance(bucket_id, str)
        )
        if len(buckets) != len(payload):
            raise ActivityWatchProtocolError(incompatible=True)
        return buckets

    def get_events(
        self,
        bucket_id: str,
        *,
        start: datetime | str,
        end: datetime | str,
        limit: int = EVENT_LIMIT,
    ) -> tuple[ActivityWatchEvent, ...]:
        safe_bucket_id = _validate_bucket_id(bucket_id)
        start_param = _timestamp_param(start)
        end_param = _timestamp_param(end)
        if start_param >= end_param or isinstance(limit, bool) or not 1 <= limit <= EVENT_LIMIT:
            raise ActivityWatchProtocolError(incompatible=True)
        encoded_bucket_id = quote(safe_bucket_id, safe="")
        path = f"{ACTIVITYWATCH_API_PREFIX}/buckets/{encoded_bucket_id}/events"
        payload = self._get_json(
            path,
            params={"start": start_param, "end": end_param, "limit": str(limit)},
        )
        if not isinstance(payload, list):
            raise ActivityWatchProtocolError(incompatible=True)
        return tuple(ActivityWatchEvent.from_payload(event) for event in payload)

    def discover(self, *, expected_hostname: str | None = None) -> ActivityWatchDiscovery:
        info = self.get_info()
        buckets = self.get_buckets()
        return discover_buckets(info, buckets, expected_hostname=expected_hostname)

    @contextmanager
    def _stream_get(
        self, path: str, *, params: dict[str, str] | None = None
    ) -> Iterator[httpx.Response]:
        try:
            with self._client.stream("GET", path, params=params) as response:
                if 300 <= response.status_code < 400:
                    raise ActivityWatchProtocolError()
                if 408 == response.status_code or response.status_code == 429:
                    raise ActivityWatchUnavailableError(
                        status_class=f"{response.status_code // 100}xx"
                    )
                if response.status_code >= 500:
                    raise ActivityWatchUnavailableError(status_class="5xx")
                if response.status_code >= 400:
                    raise ActivityWatchProtocolError()
                content_length = response.headers.get("content-length")
                if content_length is not None:
                    try:
                        if int(content_length) > self._max_response_bytes:
                            raise ActivityWatchProtocolError()
                    except ValueError as error:
                        raise ActivityWatchProtocolError() from error
                yield response
        except ActivityWatchError:
            raise
        except httpx.TimeoutException as error:
            raise ActivityWatchUnavailableError() from error
        except httpx.RequestError as error:
            raise ActivityWatchUnavailableError() from error

    def _get_json(self, path: str, *, params: dict[str, str] | None = None) -> object:
        body = bytearray()
        with self._stream_get(path, params=params) as response:
            for chunk in response.iter_bytes():
                body.extend(chunk)
                if len(body) > self._max_response_bytes:
                    raise ActivityWatchProtocolError()
        try:
            return json.loads(bytes(body).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ActivityWatchProtocolError(incompatible=True) from error
