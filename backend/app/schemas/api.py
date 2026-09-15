"""Public Timeline and Statistics response contracts."""

from __future__ import annotations

from typing import Annotated, Literal

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


class DesktopSessionDetailResponse(ApiModel):
    window_title: str | None = Field(alias="windowTitle")
    url: str | None


class AppSessionTimelineItem(ApiModel):
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
    desktop_detail: DesktopSessionDetailResponse | None = Field(alias="desktopDetail")
    display: TimelineDisplay


class PlaceVisitTimelineItem(ApiModel):
    type: Literal["place_visit"]
    id: str
    device_id: str = Field(alias="deviceId")
    device_name: str = Field(alias="deviceName")
    started_at: str = Field(alias="startedAt")
    ended_at: str = Field(alias="endedAt")
    duration_ms: int = Field(alias="durationMs")
    center_latitude: float = Field(alias="centerLatitude")
    center_longitude: float = Field(alias="centerLongitude")
    radius_m: float = Field(alias="radiusM")
    point_count: int = Field(alias="pointCount")
    label: Literal["滞在地点"]
    display: TimelineDisplay


class PhotoTimelineItem(ApiModel):
    type: Literal["photo"]
    id: str
    device_id: str = Field(alias="deviceId")
    device_name: str = Field(alias="deviceName")
    source: Literal["android_media_store"]
    taken_at: str = Field(alias="takenAt")
    filename: str
    mime_type: str = Field(alias="mimeType")
    width: int | None
    height: int | None
    latitude: float | None
    longitude: float | None
    thumbnail_url: str | None = Field(alias="thumbnailUrl")


TimelineItem = Annotated[
    AppSessionTimelineItem | PlaceVisitTimelineItem | PhotoTimelineItem,
    Field(discriminator="type"),
]


class TimelineResponse(ApiModel):
    date: str
    timezone: str
    range_start: str = Field(alias="rangeStart")
    range_end: str = Field(alias="rangeEnd")
    items: list[TimelineItem]


class PhotosResponse(ApiModel):
    date: str
    timezone: str
    range_start: str = Field(alias="rangeStart")
    range_end: str = Field(alias="rangeEnd")
    items: list[PhotoTimelineItem]


class StatisticsTotals(ApiModel):
    usage_ms: int = Field(alias="usageMs")
    session_count: int = Field(alias="sessionCount")
    app_count: int = Field(alias="appCount")


class StatisticsPlatformTotal(ApiModel):
    platform: Literal["android", "windows"]
    usage_ms: int = Field(alias="usageMs")
    session_count: int = Field(alias="sessionCount")


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
    platform_totals: list[StatisticsPlatformTotal] = Field(alias="platformTotals")
    items: list[StatisticsAppItem]


class MapRoutePoint(ApiModel):
    recorded_at: str = Field(alias="recordedAt")
    latitude: float
    longitude: float
    accuracy_m: float = Field(alias="accuracyM")


class MapRoute(ApiModel):
    device_id: str = Field(alias="deviceId")
    device_name: str = Field(alias="deviceName")
    started_at: str = Field(alias="startedAt")
    ended_at: str = Field(alias="endedAt")
    point_count: int = Field(alias="pointCount")
    points: list[MapRoutePoint]


class MapPhotoItem(PhotoTimelineItem):
    latitude: float
    longitude: float


class MapResponse(ApiModel):
    date: str
    timezone: str
    range_start: str = Field(alias="rangeStart")
    range_end: str = Field(alias="rangeEnd")
    routes: list[MapRoute]
    place_visits: list[PlaceVisitTimelineItem] = Field(alias="placeVisits")
    photos: list[MapPhotoItem]
