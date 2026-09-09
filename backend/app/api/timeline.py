"""Timeline HTTP endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import get_session
from app.api.errors import InvalidRequestError
from app.schemas import ErrorResponse, TimelineResponse
from app.services.time_range import TimeRangeError
from app.services.timeline import get_timeline

router = APIRouter()


@router.get(
    "/api/v1/timeline",
    response_model=TimelineResponse,
    responses={422: {"model": ErrorResponse}, 500: {"model": ErrorResponse}},
)
def timeline(
    date: str | None = Query(default=None),
    timezone: str | None = Query(default=None),
    session: Session = Depends(get_session),  # noqa: B008
) -> TimelineResponse:
    try:
        return get_timeline(session, date_value=date, timezone_name=timezone)
    except TimeRangeError as error:
        raise InvalidRequestError(error.message, field=error.field) from error
