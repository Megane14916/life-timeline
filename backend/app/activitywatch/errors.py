"""Safe error types and retry classification for ActivityWatch transport."""

from __future__ import annotations

from dataclasses import dataclass

ACTIVITYWATCH_UNAVAILABLE = "unavailable"
ACTIVITYWATCH_PROTOCOL_ERROR = "protocol_error"
ACTIVITYWATCH_INCOMPATIBLE_API = "incompatible_api"
ACTIVITYWATCH_MISSING_WINDOW_BUCKET = "missing_window_bucket"
ACTIVITYWATCH_MISSING_AFK_BUCKET = "missing_afk_bucket"
ACTIVITYWATCH_WEB_DETAILS_UNAVAILABLE = "web_details_unavailable"


@dataclass(frozen=True, slots=True)
class ActivityWatchErrorInfo:
    """Safe fields that can be passed to status/retry handling."""

    code: str
    retryable: bool
    status_class: str | None = None


class ActivityWatchError(Exception):
    """Base error whose message never contains response or identifier data."""

    def __init__(self, info: ActivityWatchErrorInfo) -> None:
        self.info = info
        super().__init__(self._safe_message(info))

    @property
    def code(self) -> str:
        return self.info.code

    @property
    def retryable(self) -> bool:
        return self.info.retryable

    @staticmethod
    def _safe_message(info: ActivityWatchErrorInfo) -> str:
        if info.code == ACTIVITYWATCH_UNAVAILABLE:
            return "ActivityWatch is temporarily unavailable."
        if info.code == ACTIVITYWATCH_INCOMPATIBLE_API:
            return "ActivityWatch returned an incompatible response."
        return "ActivityWatch returned an invalid response."


class ActivityWatchUnavailableError(ActivityWatchError):
    """Raised for connection, timeout, and transient HTTP failures."""

    def __init__(self, *, status_class: str | None = None) -> None:
        super().__init__(
            ActivityWatchErrorInfo(
                code=ACTIVITYWATCH_UNAVAILABLE,
                retryable=True,
                status_class=status_class,
            )
        )


class ActivityWatchProtocolError(ActivityWatchError):
    """Raised for permanent HTTP, JSON, or response-shape violations."""

    def __init__(self, *, incompatible: bool = False, reason: str | None = None) -> None:
        # ``reason`` is intentionally a fixed, caller-supplied category. It is
        # not included in the exception message, so response contents cannot
        # leak into API responses or user-facing logs.
        self.reason = reason
        super().__init__(
            ActivityWatchErrorInfo(
                code=ACTIVITYWATCH_INCOMPATIBLE_API
                if incompatible
                else ACTIVITYWATCH_PROTOCOL_ERROR,
                retryable=False,
            )
        )
