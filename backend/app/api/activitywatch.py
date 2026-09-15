"""Safe ActivityWatch collector status and manual trigger endpoints."""

from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.schemas.activitywatch import (
    ActivityWatchImportTriggerResponse,
    ActivityWatchStatusResponse,
)

router = APIRouter()


def _status_response(request: Request) -> ActivityWatchStatusResponse:
    scheduler = request.app.state.activitywatch_scheduler
    status = scheduler.status()
    return ActivityWatchStatusResponse(
        enabled=status.enabled,
        detailMode=status.detail_mode,
        state=status.state,
        lastResult=status.last_result,
        lastAttemptAt=status.last_attempt_at,
        lastSuccessAt=status.last_success_at,
        completedThrough=status.completed_through,
        nextAttemptAt=status.next_attempt_at,
        webDetailsAvailable=status.web_details_available,
    )


@router.get(
    "/api/v1/activitywatch/status",
    response_model=ActivityWatchStatusResponse,
)
def activitywatch_status(request: Request) -> ActivityWatchStatusResponse:
    return _status_response(request)


@router.post(
    "/api/v1/activitywatch/import",
    response_model=ActivityWatchImportTriggerResponse,
    status_code=202,
    responses={409: {"description": "ActivityWatch importing is disabled."}},
)
async def activitywatch_import(
    request: Request,
) -> ActivityWatchImportTriggerResponse | JSONResponse:
    scheduler = request.app.state.activitywatch_scheduler
    accepted = await scheduler.trigger_manual()
    if not accepted:
        return JSONResponse(
            status_code=409,
            content={
                "error": {
                    "code": "disabled",
                    "message": "ActivityWatch import is disabled.",
                    "field": None,
                }
            },
        )
    state = scheduler.status().state
    return ActivityWatchImportTriggerResponse(
        accepted=True,
        state="running" if state == "running" else "queued",
    )


__all__ = ["router"]
