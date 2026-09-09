"""Timeline query and response transformation."""

from __future__ import annotations

from typing import Literal, cast

from sqlalchemy.orm import Session

from app.repositories.queries import find_sessions
from app.schemas import TimelineDisplay, TimelineItem, TimelineResponse
from app.services.time_range import build_day_range, clip_interval, format_epoch_ms


def get_timeline(
    session: Session, date_value: str | None, timezone_name: str | None
) -> TimelineResponse:
    date_string, query_range = build_day_range(date_value, timezone_name)
    items: list[TimelineItem] = []
    for row in find_sessions(session, query_range):
        app_session = row.app_session
        display = clip_interval(app_session.started_at_ms, app_session.ended_at_ms, query_range)
        items.append(
            TimelineItem(
                type="app_session",
                id=app_session.id,
                deviceId=row.device.id,
                deviceName=row.device.name,
                platform=cast(Literal["android", "windows"], row.device.platform),
                appId=row.app.id,
                appIdentifier=row.app.identifier,
                appName=row.app.display_name,
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
        )
    return TimelineResponse(
        date=date_string,
        timezone=query_range.timezone_name,
        rangeStart=query_range.start_iso,
        rangeEnd=query_range.end_iso,
        items=items,
    )
