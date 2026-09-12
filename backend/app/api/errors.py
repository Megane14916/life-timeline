"""Application exceptions translated by the FastAPI error handlers."""

from __future__ import annotations


class InvalidRequestError(ValueError):
    """A request parameter does not satisfy the public API contract."""

    def __init__(self, message: str, *, field: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.field = field


class SyncConflictError(ValueError):
    """Raised when a sync request conflicts with persisted normalized data."""

    def __init__(self, message: str, *, field: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.field = field


class PayloadTooLargeError(ValueError):
    """Raised when the request body or one thumbnail exceeds its fixed limit."""

    def __init__(self, message: str = "The photo sync request exceeds the size limit.") -> None:
        super().__init__(message)
        self.message = message


class TemporarilyUnavailableError(RuntimeError):
    """Raised when SQLite cannot accept a sync transaction before its timeout."""

    def __init__(self, message: str = "The PC is temporarily unavailable.") -> None:
        super().__init__(message)
        self.message = message
