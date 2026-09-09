"""Translate the Android sync payload into normalized repository records."""

from __future__ import annotations

from dataclasses import dataclass

from app.repositories import AppRecord, AppSessionRecord, DeviceRecord
from app.schemas.sync import SyncAppSessionsRequest


@dataclass(frozen=True, slots=True)
class SyncBatch:
    """Normalized records and the request-order IDs used for the ACK."""

    device: DeviceRecord
    apps: tuple[AppRecord, ...]
    sessions: tuple[AppSessionRecord, ...]


def to_sync_batch(request: SyncAppSessionsRequest, *, received_at_ms: int) -> SyncBatch:
    """Convert a validated v1 request using server time for PC timestamps."""

    device = DeviceRecord(
        id=request.device.id,
        name=request.device.name,
        platform=request.device.platform,
        created_at_ms=received_at_ms,
        last_seen_at_ms=received_at_ms,
    )
    apps = tuple(
        AppRecord(
            id=app.id,
            platform=request.device.platform,
            identifier=app.identifier,
            display_name=app.display_name,
            created_at_ms=received_at_ms,
        )
        for app in request.apps
    )
    sessions = tuple(
        AppSessionRecord(
            id=app_session.id,
            device_id=request.device.id,
            app_id=app_session.app_id,
            started_at_ms=app_session.started_at_ms,
            ended_at_ms=app_session.ended_at_ms,
            source=app_session.source,
            created_at_ms=received_at_ms,
            duration_ms=app_session.duration_ms,
        )
        for app_session in request.sessions
    )
    return SyncBatch(device=device, apps=apps, sessions=sessions)
