from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import func, select
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory, session_scope
from app.models import App, AppSession, Category, Device
from app.repositories import (
    AppRecord,
    AppSessionConflictError,
    AppSessionRecord,
    CategoryRecord,
    DeviceRecord,
    NormalizedRepository,
    PlatformMismatchError,
    RepositoryValidationError,
)

VALID_DEVICE_ID = "01J00000000000000000000001"
VALID_DEVICE_2_ID = "01J00000000000000000000002"
VALID_WINDOWS_DEVICE_ID = "01J00000000000000000000003"
VALID_APP_ID = "01J00000000000000000000011"
VALID_OTHER_APP_ID = "01J00000000000000000000012"
VALID_WINDOWS_APP_ID = "01J00000000000000000000013"
VALID_CATEGORY_ID = "01J00000000000000000000021"


def _session_factory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Engine, sessionmaker[Session]]:
    data_dir = tmp_path / "data"
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    monkeypatch.setenv("LIFE_TIMELINE_DATA_DIR", str(data_dir))
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    return engine, create_session_factory(engine)


def _device(device_id: str, platform: str = "android") -> DeviceRecord:
    return DeviceRecord(device_id, f"{platform} phone", platform, 1_700_000_000_000)


def _app(app_id: str, platform: str = "android", identifier: str = "com.example.app") -> AppRecord:
    return AppRecord(app_id, platform, identifier, "Example App", 1_700_000_000_000)


def _fact(
    fact_id: str, device_id: str, app_id: str, source: str = "custom_collector"
) -> AppSessionRecord:
    return AppSessionRecord(
        fact_id,
        device_id,
        app_id,
        1_700_000_000_000,
        1_700_000_060_000,
        source,
        1_700_000_100_000,
    )


def test_master_natural_key_and_cross_platform_facts_are_persisted(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _session_factory(tmp_path, monkeypatch)
    try:
        with session_scope(factory) as session:
            repository = NormalizedRepository(session)
            repository.save_app_session(
                device=_device(VALID_DEVICE_ID),
                app=_app(VALID_APP_ID),
                app_session=_fact("01J00000000000000000000101", VALID_DEVICE_ID, VALID_APP_ID),
            )
            repository.save_app_session(
                device=_device(VALID_DEVICE_2_ID),
                app=_app(VALID_OTHER_APP_ID),
                app_session=_fact(
                    "01J00000000000000000000102", VALID_DEVICE_2_ID, VALID_OTHER_APP_ID
                ),
            )
            repository.save_app_session(
                device=_device(VALID_WINDOWS_DEVICE_ID, "windows"),
                app=_app(VALID_WINDOWS_APP_ID, "windows", "Code.exe"),
                app_session=_fact(
                    "01J00000000000000000000103",
                    VALID_WINDOWS_DEVICE_ID,
                    VALID_WINDOWS_APP_ID,
                    "another_collector",
                ),
            )
            session.commit()

            apps = session.scalars(select(App).order_by(App.identifier)).all()
            facts = session.scalars(select(AppSession).order_by(AppSession.id)).all()
            assert len(apps) == 2
            assert apps[1].id == VALID_APP_ID
            assert facts[1].duration_ms == 60_000
            assert facts[2].source == "another_collector"
            assert facts[0].app_id == facts[1].app_id
    finally:
        engine.dispose()


def test_same_fact_is_idempotent_and_keeps_created_at(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _session_factory(tmp_path, monkeypatch)
    try:
        with session_scope(factory) as session:
            repository = NormalizedRepository(session)
            fact = _fact("01J00000000000000000000201", VALID_DEVICE_ID, VALID_APP_ID)
            repository.save_app_session(
                device=_device(VALID_DEVICE_ID), app=_app(VALID_APP_ID), app_session=fact
            )
            repository.save_app_session(
                device=_device(VALID_DEVICE_ID),
                app=_app(VALID_APP_ID),
                app_session=AppSessionRecord(
                    fact.id,
                    fact.device_id,
                    fact.app_id,
                    fact.started_at_ms,
                    fact.ended_at_ms,
                    fact.source,
                    fact.created_at_ms + 99_000,
                ),
            )
            session.commit()
            stored = session.get(AppSession, fact.id)
            assert stored is not None
            assert stored.created_at_ms == fact.created_at_ms
            assert session.scalar(select(func.count()).select_from(AppSession)) == 1
    finally:
        engine.dispose()


def test_invalid_input_platform_mismatch_and_conflict_rollback(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _session_factory(tmp_path, monkeypatch)
    try:
        with session_scope(factory) as session:
            repository = NormalizedRepository(session)
            with pytest.raises(PlatformMismatchError):
                repository.save_app_session(
                    device=_device(VALID_DEVICE_ID),
                    app=_app(VALID_WINDOWS_APP_ID, "windows", "Code.exe"),
                    app_session=_fact(
                        "01J00000000000000000000301", VALID_DEVICE_ID, VALID_WINDOWS_APP_ID
                    ),
                )
            assert session.scalar(select(func.count()).select_from(Device)) == 0
            assert session.scalar(select(func.count()).select_from(App)) == 0
            session.rollback()

            with pytest.raises(RepositoryValidationError):
                repository.save_app_session(
                    device=_device(VALID_DEVICE_ID),
                    app=_app(VALID_APP_ID),
                    app_session=AppSessionRecord(
                        "not-a-ulid", VALID_DEVICE_ID, VALID_APP_ID, 1, 1, "source", 1
                    ),
                )
            assert session.scalar(select(func.count()).select_from(Device)) == 0

            repository.save_app_session(
                device=_device(VALID_DEVICE_ID),
                app=_app(VALID_APP_ID),
                app_session=_fact("01J00000000000000000000302", VALID_DEVICE_ID, VALID_APP_ID),
            )
            with pytest.raises(AppSessionConflictError):
                repository.save_app_session(
                    device=_device(VALID_DEVICE_ID),
                    app=_app(VALID_APP_ID),
                    app_session=AppSessionRecord(
                        "01J00000000000000000000302",
                        VALID_DEVICE_ID,
                        VALID_APP_ID,
                        1_700_000_000_000,
                        1_700_000_120_000,
                        "custom_collector",
                        1_700_000_100_000,
                    ),
                )
            session.rollback()
            assert session.scalar(select(func.count()).select_from(AppSession)) == 0
    finally:
        engine.dispose()


def test_foreign_key_deletes_are_restricted(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _session_factory(tmp_path, monkeypatch)
    try:
        with session_scope(factory) as session:
            repository = NormalizedRepository(session)
            repository.masters.save_category(
                CategoryRecord(VALID_CATEGORY_ID, "Development", 1_700_000_000_000)
            )
            repository.save_app_session(
                device=_device(VALID_DEVICE_ID),
                app=AppRecord(
                    VALID_APP_ID,
                    "android",
                    "com.example.app",
                    "Example App",
                    1_700_000_000_000,
                    category_id=VALID_CATEGORY_ID,
                ),
                app_session=_fact("01J00000000000000000000401", VALID_DEVICE_ID, VALID_APP_ID),
            )
            session.commit()

            category = session.get(Category, VALID_CATEGORY_ID)
            assert category is not None
            session.delete(category)
            with pytest.raises(IntegrityError):
                session.commit()
            session.rollback()

            device = session.get(Device, VALID_DEVICE_ID)
            assert device is not None
            session.delete(device)
            with pytest.raises(IntegrityError):
                session.commit()
    finally:
        engine.dispose()
