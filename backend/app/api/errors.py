"""Application exceptions translated by the FastAPI error handlers."""

from __future__ import annotations


class InvalidRequestError(ValueError):
    """A request parameter does not satisfy the public API contract."""

    def __init__(self, message: str, *, field: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.field = field
