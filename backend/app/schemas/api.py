"""Public Timeline and Statistics response contracts."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ApiModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class ErrorDetail(ApiModel):
    code: str
    message: str
    field: str | None = None


class ErrorResponse(ApiModel):
    error: ErrorDetail


class TimelineDisplay(ApiModel):
    started_at: str = Field(alias="startedAt")
    ended_at: str = Field(alias="endedAt")
    duration_ms: int = Field(alias="durationMs")
    continues_from_previous_day: bool = Field(alias="continuesFromPreviousDay")
    continues_to_next_day: bool = Field(alias="continuesToNextDay")
    ends_at_day_boundary: bool = Field(alias="endsAtDayBoundary")


class TimelineItem(ApiModel):
    type: Literal["app_session"]
    id: str
    device_id: str = Field(alias="deviceId")
    device_name: str = Field(alias="deviceName")
    platform: Literal["android", "windows"]
    app_id: str = Field(alias="appId")
    app_identifier: str = Field(alias="appIdentifier")
    app_name: str = Field(alias="appName")
    source: str
    started_at: str = Field(alias="startedAt")
    ended_at: str = Field(alias="endedAt")
    duration_ms: int = Field(alias="durationMs")
    display: TimelineDisplay


class TimelineResponse(ApiModel):
    date: str
    timezone: str
    range_start: str = Field(alias="rangeStart")
    range_end: str = Field(alias="rangeEnd")
    items: list[TimelineItem]


class StatisticsTotals(ApiModel):
    usage_ms: int = Field(alias="usageMs")
    session_count: int = Field(alias="sessionCount")
    app_count: int = Field(alias="appCount")


class StatisticsAppItem(ApiModel):
    app_id: str = Field(alias="appId")
    platform: Literal["android", "windows"]
    app_identifier: str = Field(alias="appIdentifier")
    app_name: str = Field(alias="appName")
    usage_ms: int = Field(alias="usageMs")
    session_count: int = Field(alias="sessionCount")


class StatisticsResponse(ApiModel):
    from_date: str = Field(alias="from")
    to_date: str = Field(alias="to")
    timezone: str
    range_start: str = Field(alias="rangeStart")
    range_end: str = Field(alias="rangeEnd")
    totals: StatisticsTotals
    items: list[StatisticsAppItem]
