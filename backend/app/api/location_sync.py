"""Android LocationPoint synchronization endpoint."""

from __future__ import annotations

import json
from collections.abc import Iterable
from typing import Any

from fastapi import APIRouter, Depends, Request
from pydantic import ValidationError
from sqlalchemy.orm import Session

from app.api.dependencies import get_session
from app.api.errors import InvalidRequestError, SyncConflictError
from app.schemas import ErrorResponse
from app.schemas.location_sync import LocationSyncRequest, LocationSyncResponse
from app.services.location_sync import sync_locations

router = APIRouter()
_SAFE_INVALID_REQUEST = "The location sync request is invalid."


def _field_path(parts: Iterable[Any]) -> str | None:
    path = ""
    for part in parts:
        if isinstance(part, int):
            path += f"[{part}]"
        elif isinstance(part, str) and part not in {"body", "metadata"}:
            path += ("." if path else "") + part
    return path or None


@router.post(
    "/api/v1/sync/locations",
    response_model=LocationSyncResponse,
    status_code=200,
    responses={
        409: {"model": ErrorResponse},
        413: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
    },
)
async def location_sync(
    request: Request,
    session: Session = Depends(get_session),  # noqa: B008
) -> LocationSyncResponse:
    content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if content_type != "application/json":
        raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="contentType")

    try:
        raw_payload = json.loads(await request.body())
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise InvalidRequestError(_SAFE_INVALID_REQUEST, field="body") from error

    try:
        location_request = LocationSyncRequest.model_validate(raw_payload)
    except ValidationError as error:
        errors = error.errors()
        field = _field_path(errors[0].get("loc", ())) if errors else None
        raise InvalidRequestError(_SAFE_INVALID_REQUEST, field=field) from error

    try:
        return sync_locations(session, location_request)
    except SyncConflictError:
        raise
    except ValueError as error:
        raise InvalidRequestError(_SAFE_INVALID_REQUEST) from error
