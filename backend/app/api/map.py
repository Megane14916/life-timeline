"""Daily Map read endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import get_session
from app.api.errors import InvalidRequestError
from app.schemas import ErrorResponse, MapResponse
from app.services.map_query import get_daily_map
from app.services.time_range import TimeRangeError

router = APIRouter()


@router.get(
    "/api/v1/map",
    response_model=MapResponse,
    responses={422: {"model": ErrorResponse}, 500: {"model": ErrorResponse}},
)
def daily_map(
    date: str | None = Query(default=None),
    timezone: str | None = Query(default=None),
    session: Session = Depends(get_session),  # noqa: B008
) -> MapResponse:
    try:
        return get_daily_map(session, date_value=date, timezone_name=timezone)
    except TimeRangeError as error:
        raise InvalidRequestError(error.message, field=error.field) from error
