"""Safe ActivityWatch collector API contracts."""

from __future__ import annotations

from typing import Literal

from pydantic import Field

from app.schemas.api import ApiModel

CollectorState = Literal["disabled", "idle", "queued", "running", "needs_attention"]


class ActivityWatchStatusResponse(ApiModel):
    enabled: bool
    detail_mode: Literal["app_only", "titles", "web"] = Field(alias="detailMode")
    state: CollectorState
    last_result: str | None = Field(alias="lastResult")
    last_attempt_at: str | None = Field(alias="lastAttemptAt")
    last_success_at: str | None = Field(alias="lastSuccessAt")
    completed_through: str | None = Field(alias="completedThrough")
    next_attempt_at: str | None = Field(alias="nextAttemptAt")
    web_details_available: bool = Field(alias="webDetailsAvailable")


class ActivityWatchImportTriggerResponse(ApiModel):
    accepted: bool
    state: Literal["queued", "running", "idle"]


__all__ = [
    "ActivityWatchImportTriggerResponse",
    "ActivityWatchStatusResponse",
    "CollectorState",
]
