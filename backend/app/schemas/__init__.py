"""Pydantic schemas for the public API."""

from app.schemas.activitywatch import (
    ActivityWatchImportTriggerResponse,
    ActivityWatchStatusResponse,
)
from app.schemas.api import (
    AppSessionTimelineItem,
    DesktopSessionDetailResponse,
    ErrorDetail,
    ErrorResponse,
    MapPhotoItem,
    MapResponse,
    MapRoute,
    MapRoutePoint,
    PhotosResponse,
    PhotoTimelineItem,
    PlaceVisitTimelineItem,
    StatisticsAppItem,
    StatisticsPlatformTotal,
    StatisticsResponse,
    StatisticsTotals,
    TimelineDisplay,
    TimelineItem,
    TimelineResponse,
)
from app.schemas.location_sync import LocationSyncRequest, LocationSyncResponse
from app.schemas.sync import (
    SyncApp,
    SyncAppSession,
    SyncAppSessionsRequest,
    SyncAppSessionsResponse,
    SyncDevice,
)

__all__ = [
    "ActivityWatchImportTriggerResponse",
    "ActivityWatchStatusResponse",
    "AppSessionTimelineItem",
    "DesktopSessionDetailResponse",
    "ErrorDetail",
    "ErrorResponse",
    "LocationSyncRequest",
    "LocationSyncResponse",
    "MapPhotoItem",
    "MapResponse",
    "MapRoute",
    "MapRoutePoint",
    "PhotoTimelineItem",
    "PhotosResponse",
    "PlaceVisitTimelineItem",
    "StatisticsAppItem",
    "StatisticsPlatformTotal",
    "StatisticsResponse",
    "StatisticsTotals",
    "SyncApp",
    "SyncAppSession",
    "SyncAppSessionsRequest",
    "SyncAppSessionsResponse",
    "SyncDevice",
    "TimelineDisplay",
    "TimelineItem",
    "TimelineResponse",
]
