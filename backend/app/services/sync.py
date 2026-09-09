"""Atomic service for importing Android AppSession batches."""

from __future__ import annotations

import time
from dataclasses import dataclass, replace

from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from app.adapters import SyncBatch, to_sync_batch
from app.api.errors import SyncConflictError, TemporarilyUnavailableError
from app.repositories import (
    AppSessionConflictError,
    NormalizedRepository,
    RepositoryConflictError,
    RepositoryError,
)
from app.schemas.sync import SyncAppSessionsRequest, SyncAppSessionsResponse


@dataclass(frozen=True, slots=True)
class SyncSaveResult:
    """Accepted Android IDs in their original request order."""

    accepted: tuple[str, ...]


def _is_sqlite_busy(error: OperationalError) -> bool:
    message = str(error).lower()
    return "database is locked" in message or "database table is locked" in message


def _save_batch(session: Session, batch: SyncBatch) -> SyncSaveResult:
    repository = NormalizedRepository(session)
    canonical_apps = {}
    accepted: list[str] = []

    with session.begin():
        repository.masters.save_device(batch.device)
        for app in batch.apps:
            canonical_apps[app.id] = repository.masters.save_app(app).id

        for app_session in batch.sessions:
            resolved_session = replace(
                app_session,
                app_id=canonical_apps[app_session.app_id],
            )
            repository.app_sessions.save(resolved_session)
            accepted.append(app_session.id)

    return SyncSaveResult(accepted=tuple(accepted))


def sync_app_sessions(
    session: Session,
    request: SyncAppSessionsRequest,
    *,
    received_at_ms: int | None = None,
) -> SyncAppSessionsResponse:
    """Validate and atomically save one version 1 Android batch."""

    if not request.sessions:
        return SyncAppSessionsResponse(schemaVersion=1, accepted=[])

    batch = to_sync_batch(
        request,
        received_at_ms=received_at_ms
        if received_at_ms is not None
        else time.time_ns() // 1_000_000,
    )
    try:
        result = _save_batch(session, batch)
    except AppSessionConflictError as error:
        raise SyncConflictError(
            "An app session ID is already stored with different content.",
            field="sessions[0].id",
        ) from error
    except RepositoryConflictError as error:
        raise SyncConflictError("The sync request conflicts with existing data.") from error
    except RepositoryError as error:
        raise ValueError(str(error)) from error
    except OperationalError as error:
        if _is_sqlite_busy(error):
            raise TemporarilyUnavailableError() from error
        raise

    return SyncAppSessionsResponse(schemaVersion=1, accepted=list(result.accepted))
