from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel


class HealthResponse(BaseModel):
    status: Literal["ok"]


def create_app() -> FastAPI:
    application = FastAPI(title="life-timeline", version="0.1.0")

    @application.get("/api/v1/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(status="ok")

    return application


app = create_app()
