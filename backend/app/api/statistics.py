"""Application statistics HTTP endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.api.dependencies import get_session
from app.api.errors import InvalidRequestError
from app.schemas import ErrorResponse, StatisticsResponse
from app.services.statistics import get_app_statistics
from app.services.time_range import TimeRangeError

router = APIRouter()


@router.get(
    "/api/v1/stats/apps",
    response_model=StatisticsResponse,
    responses={422: {"model": ErrorResponse}, 500: {"model": ErrorResponse}},
)
def app_statistics(
    from_date: str | None = Query(default=None, alias="from"),
    to_date: str | None = Query(default=None, alias="to"),
    timezone: str | None = Query(default=None),
    session: Session = Depends(get_session),  # noqa: B008
) -> StatisticsResponse:
    try:
        return get_app_statistics(
            session,
            from_value=from_date,
            to_value=to_date,
            timezone_name=timezone,
        )
    except TimeRangeError as error:
        raise InvalidRequestError(error.message, field=error.field) from error
