"""Android AppSession synchronization endpoint."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.dependencies import get_session
from app.api.errors import InvalidRequestError, SyncConflictError
from app.schemas import ErrorResponse, SyncAppSessionsRequest, SyncAppSessionsResponse
from app.services.sync import sync_app_sessions

router = APIRouter()


@router.post(
    "/api/v1/sync/app-sessions",
    response_model=SyncAppSessionsResponse,
    status_code=200,
    responses={
        409: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
    },
)
def app_sessions_sync(
    request: SyncAppSessionsRequest,
    session: Session = Depends(get_session),  # noqa: B008
) -> SyncAppSessionsResponse:
    try:
        return sync_app_sessions(session, request)
    except SyncConflictError:
        raise
    except ValueError as error:
        raise InvalidRequestError(str(error)) from error
