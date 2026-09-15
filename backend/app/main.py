from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import replace
from typing import Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from sqlalchemy import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.activitywatch.scheduler import (
    ActivityWatchImportScheduler,
    load_runtime_config,
)
from app.api.activitywatch import router as activitywatch_router
from app.api.errors import (
    InvalidRequestError,
    PayloadTooLargeError,
    ResourceNotFoundError,
    SyncConflictError,
    TemporarilyUnavailableError,
    ThumbnailUnavailableError,
)
from app.api.location_limits import LocationRequestSizeLimitMiddleware
from app.api.location_sync import router as location_sync_router
from app.api.map import router as map_router
from app.api.photo_limits import PhotoRequestSizeLimitMiddleware
from app.api.photo_sync import router as photo_sync_router
from app.api.photos import router as photos_router
from app.api.statistics import router as statistics_router
from app.api.sync import router as sync_router
from app.api.timeline import router as timeline_router
from app.config import get_settings
from app.db import create_engine_for_settings, create_session_factory

logger = logging.getLogger(__name__)


class HealthResponse(BaseModel):
    status: Literal["ok"]


def _error_response(
    code: str,
    message: str,
    field: str | None = None,
    *,
    status_code: int | None = None,
) -> JSONResponse:
    detail: dict[str, str | None] = {"code": code, "message": message}
    detail["field"] = field
    return JSONResponse(
        status_code=status_code
        if status_code is not None
        else 422
        if code == "invalid_request"
        else 500,
        content={"error": detail},
    )


def _validation_field(exc: RequestValidationError) -> str | None:
    errors = exc.errors()
    if not errors:
        return None
    location = errors[0].get("loc", ())
    for value in reversed(location):
        if isinstance(value, str) and value not in {"query", "path", "body"}:
            return value
    return None


def create_app(
    session_factory: sessionmaker[Session] | None = None,
    *,
    engine: Engine | None = None,
    activitywatch_scheduler: ActivityWatchImportScheduler | None = None,
    activitywatch_enabled: bool | None = None,
) -> FastAPI:
    runtime = load_runtime_config()
    if activitywatch_enabled is not None:
        runtime = replace(runtime, enabled=activitywatch_enabled, configuration_error=None)
    application: FastAPI

    @asynccontextmanager
    async def lifespan(_application: FastAPI) -> AsyncIterator[None]:
        scheduler = _application.state.activitywatch_scheduler
        await scheduler.start()
        try:
            yield
        finally:
            await scheduler.stop()

    application = FastAPI(title="life-timeline", version="0.1.0", lifespan=lifespan)

    if session_factory is None and engine is not None:
        application.state.engine = engine
        session_factory = create_session_factory(engine)
    if activitywatch_scheduler is None:
        if runtime.enabled and session_factory is None:
            settings = get_settings()
            application.state.engine = create_engine_for_settings(settings)
            session_factory = create_session_factory(application.state.engine)
        activitywatch_scheduler = (
            ActivityWatchImportScheduler.from_defaults(
                session_factory,
                runtime=runtime,
            )
            if session_factory is not None
            else ActivityWatchImportScheduler(
                enabled=False,
                detail_mode=runtime.privacy_mode,
                configuration_error=runtime.configuration_error,
            )
        )
    application.state.session_factory = session_factory
    application.state.activitywatch_scheduler = activitywatch_scheduler

    @application.exception_handler(InvalidRequestError)
    async def invalid_request_handler(_request: Request, exc: InvalidRequestError) -> JSONResponse:
        return _error_response("invalid_request", exc.message, exc.field)

    @application.exception_handler(SyncConflictError)
    async def sync_conflict_handler(_request: Request, exc: SyncConflictError) -> JSONResponse:
        return _error_response("sync_conflict", exc.message, exc.field, status_code=409)

    @application.exception_handler(PayloadTooLargeError)
    async def payload_too_large_handler(
        _request: Request, exc: PayloadTooLargeError
    ) -> JSONResponse:
        return _error_response("payload_too_large", exc.message, status_code=413)

    @application.exception_handler(TemporarilyUnavailableError)
    async def temporarily_unavailable_handler(
        _request: Request, exc: TemporarilyUnavailableError
    ) -> JSONResponse:
        return _error_response("temporarily_unavailable", exc.message, status_code=503)

    @application.exception_handler(ResourceNotFoundError)
    async def resource_not_found_handler(
        _request: Request, _exc: ResourceNotFoundError
    ) -> JSONResponse:
        return _error_response(
            "not_found", "The requested resource was not found.", status_code=404
        )

    @application.exception_handler(ThumbnailUnavailableError)
    async def thumbnail_unavailable_handler(
        _request: Request, _exc: ThumbnailUnavailableError
    ) -> JSONResponse:
        return _error_response("internal_error", "Internal server error.", status_code=500)

    @application.exception_handler(RequestValidationError)
    async def request_validation_handler(
        _request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        field = _validation_field(exc)
        return _error_response("invalid_request", "Request parameters are invalid.", field)

    @application.exception_handler(Exception)
    async def unexpected_error_handler(_request: Request, exc: Exception) -> JSONResponse:
        logger.exception("Unhandled API error", exc_info=exc)
        return _error_response("internal_error", "Internal server error.")

    application.include_router(timeline_router)
    application.include_router(statistics_router)
    application.include_router(sync_router)
    application.include_router(photo_sync_router)
    application.include_router(location_sync_router)
    application.include_router(photos_router)
    application.include_router(map_router)
    application.include_router(activitywatch_router)
    application.add_middleware(PhotoRequestSizeLimitMiddleware)
    application.add_middleware(LocationRequestSizeLimitMiddleware)

    @application.get("/api/v1/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(status="ok")

    return application


app = create_app()
