"""Timeline query and response transformation."""

from __future__ import annotations

from typing import Literal, cast

from sqlalchemy.orm import Session

from app.repositories.queries import find_photos, find_sessions
from app.schemas import (
    AppSessionTimelineItem,
    TimelineDisplay,
    TimelineItem,
    TimelineResponse,
)
from app.services.photos import to_photo_timeline_item
from app.services.time_range import build_day_range, clip_interval, format_epoch_ms


def get_timeline(
    session: Session, date_value: str | None, timezone_name: str | None
) -> TimelineResponse:
    date_string, query_range = build_day_range(date_value, timezone_name)
    ordered_items: list[tuple[int, str, str, TimelineItem]] = []
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
            display=TimelineDisplay(
                startedAt=format_epoch_ms(display.start_ms),
                endedAt=format_epoch_ms(display.end_ms),
                durationMs=display.duration_ms,
                continuesFromPreviousDay=display.continues_from_previous_day,
                continuesToNextDay=display.continues_to_next_day,
                endsAtDayBoundary=display.ends_at_day_boundary,
            ),
        )
        ordered_items.append((display.start_ms, "app_session", app_session.id, item))
    for photo_row in find_photos(session, query_range):
        photo = photo_row.media_item
        ordered_items.append(
            (
                photo.captured_at_ms,
                "photo",
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
