"""Timeline query and response transformation."""

from __future__ import annotations

from typing import Literal, cast

from sqlalchemy.orm import Session

from app.repositories.queries import (
    SessionWithMasters,
    find_photos,
    find_place_visits,
    find_sessions,
)
from app.schemas import (
    AppSessionTimelineItem,
    DesktopSessionDetailResponse,
    PlaceVisitTimelineItem,
    TimelineDisplay,
    TimelineItem,
    TimelineResponse,
)
from app.services.photos import to_photo_timeline_item
from app.services.time_range import build_day_range, clip_interval, format_epoch_ms


def _to_desktop_detail(row: SessionWithMasters) -> DesktopSessionDetailResponse | None:
    if row.app_session.source != "activitywatch" or row.device.platform != "windows":
        return None
    return DesktopSessionDetailResponse(
        windowTitle=row.desktop_detail.window_title if row.desktop_detail is not None else None,
        url=row.desktop_detail.url if row.desktop_detail is not None else None,
    )


def get_timeline(
    session: Session, date_value: str | None, timezone_name: str | None
) -> TimelineResponse:
    date_string, query_range = build_day_range(date_value, timezone_name)
    ordered_items: list[tuple[int, int, str, TimelineItem]] = []
    for session_row in find_sessions(session, query_range):
        app_session = session_row.app_session
        display = clip_interval(app_session.started_at_ms, app_session.ended_at_ms, query_range)
        item = AppSessionTimelineItem(
            type="app_session",
            id=app_session.id,
            deviceId=session_row.device.id,
            deviceName=session_row.device.name,
            platform=cast(Literal["android", "windows"], session_row.device.platform),
            appId=session_row.app.id,
            appIdentifier=session_row.app.identifier,
            appName=session_row.app.display_name,
            source=app_session.source,
            startedAt=format_epoch_ms(app_session.started_at_ms),
            endedAt=format_epoch_ms(app_session.ended_at_ms),
            durationMs=app_session.duration_ms,
            desktopDetail=_to_desktop_detail(session_row),
            display=TimelineDisplay(
                startedAt=format_epoch_ms(display.start_ms),
                endedAt=format_epoch_ms(display.end_ms),
                durationMs=display.duration_ms,
                continuesFromPreviousDay=display.continues_from_previous_day,
                continuesToNextDay=display.continues_to_next_day,
                endsAtDayBoundary=display.ends_at_day_boundary,
            ),
        )
        ordered_items.append((display.start_ms, 0, app_session.id, item))
    for visit_row in find_place_visits(session, query_range):
        visit = visit_row.place_visit
        display = clip_interval(visit.started_at_ms, visit.ended_at_ms, query_range)
        visit_item = PlaceVisitTimelineItem(
            type="place_visit",
            id=visit.id,
            deviceId=visit.device_id,
            deviceName=visit_row.device.name,
            startedAt=format_epoch_ms(visit.started_at_ms),
            endedAt=format_epoch_ms(visit.ended_at_ms),
            durationMs=visit.duration_ms,
            centerLatitude=visit.center_latitude,
            centerLongitude=visit.center_longitude,
            radiusM=visit.radius_m,
            pointCount=visit.point_count,
            label="滞在地点",
            display=TimelineDisplay(
                startedAt=format_epoch_ms(display.start_ms),
                endedAt=format_epoch_ms(display.end_ms),
                durationMs=display.duration_ms,
                continuesFromPreviousDay=display.continues_from_previous_day,
                continuesToNextDay=display.continues_to_next_day,
                endsAtDayBoundary=display.ends_at_day_boundary,
            ),
        )
        ordered_items.append((display.start_ms, 1, visit.id, visit_item))
    for photo_row in find_photos(session, query_range):
        photo = photo_row.media_item
        ordered_items.append(
            (
                photo.captured_at_ms,
                2,
                photo.id,
                to_photo_timeline_item(photo_row),
            )
        )
    ordered_items.sort(key=lambda item: item[:3])
    items = [item[3] for item in ordered_items]
    return TimelineResponse(
        date=date_string,
        timezone=query_range.timezone_name,
        rangeStart=query_range.start_iso,
        rangeEnd=query_range.end_iso,
        items=items,
    )
