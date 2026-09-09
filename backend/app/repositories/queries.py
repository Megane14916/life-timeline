"""Read-only queries shared by Timeline and Statistics services."""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import App, AppSession, Device
from app.services.time_range import QueryRange


@dataclass(frozen=True, slots=True)
class SessionWithMasters:
    app_session: AppSession
    device: Device
    app: App


def find_sessions(session: Session, query_range: QueryRange) -> list[SessionWithMasters]:
    statement = (
        select(AppSession, Device, App)
        .join(Device, Device.id == AppSession.device_id)
        .join(App, App.id == AppSession.app_id)
        .where(
            AppSession.started_at_ms < query_range.end_ms,
            AppSession.ended_at_ms > query_range.start_ms,
        )
        .order_by(
            AppSession.started_at_ms.asc(),
            AppSession.device_id.asc(),
            AppSession.id.asc(),
        )
    )
    return [
        SessionWithMasters(app_session=app_session, device=device, app=app)
        for app_session, device, app in session.execute(statement).tuples()
    ]
