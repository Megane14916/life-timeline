"""Statistics query and response transformation."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, cast

from sqlalchemy.orm import Session

from app.repositories.queries import find_sessions
from app.schemas import StatisticsAppItem, StatisticsResponse, StatisticsTotals
from app.services.time_range import build_period_range, clip_interval


@dataclass(slots=True)
class _AppTotals:
    platform: str
    identifier: str
    name: str
    usage_ms: int = 0
    session_count: int = 0


def get_app_statistics(
    session: Session,
    from_value: str | None,
    to_value: str | None,
    timezone_name: str | None,
) -> StatisticsResponse:
    from_date, to_date, query_range = build_period_range(from_value, to_value, timezone_name)
    totals_by_app: dict[str, _AppTotals] = {}
    for row in find_sessions(session, query_range):
        app_session = row.app_session
        display = clip_interval(app_session.started_at_ms, app_session.ended_at_ms, query_range)
        app_totals = totals_by_app.setdefault(
            row.app.id,
            _AppTotals(
                platform=row.app.platform,
                identifier=row.app.identifier,
                name=row.app.display_name,
            ),
        )
        app_totals.usage_ms += display.duration_ms
        app_totals.session_count += 1

    items = [
        StatisticsAppItem(
            appId=app_id,
            platform=cast(Literal["android", "windows"], app_totals.platform),
            appIdentifier=app_totals.identifier,
            appName=app_totals.name,
            usageMs=app_totals.usage_ms,
            sessionCount=app_totals.session_count,
        )
        for app_id, app_totals in sorted(
            totals_by_app.items(), key=lambda item: (-item[1].usage_ms, item[0])
        )
    ]
    totals = StatisticsTotals.model_validate(
        {
            "usageMs": sum(item.usage_ms for item in totals_by_app.values()),
            "sessionCount": sum(item.session_count for item in totals_by_app.values()),
            "appCount": len(totals_by_app),
        }
    )
    return StatisticsResponse.model_validate(
        {
            "from": from_date,
            "to": to_date,
            "timezone": query_range.timezone_name,
            "rangeStart": query_range.start_iso,
            "rangeEnd": query_range.end_iso,
            "totals": totals,
            "items": items,
        }
    )
