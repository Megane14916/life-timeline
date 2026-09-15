from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import func, inspect, select, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.config import DATA_DIR_ENV, Settings
from app.db import create_engine_for_settings, create_session_factory
from app.models import (
    AppSession,
    DesktopSessionDetail,
)
from app.repositories import (
    ActivityWatchAppRecord,
    ActivityWatchConflictError,
    ActivityWatchDetailRecord,
    ActivityWatchDeviceRecord,
    ActivityWatchLeaseLostError,
    ActivityWatchReplaceResult,
    ActivityWatchRepository,
    ActivityWatchSessionRecord,
    RepositoryValidationError,
    source_key_for,
)

DEVICE_ID = "01J00000000000000000000001"
APP_ID = "01J00000000000000000000002"
SESSION_ID = "01J00000000000000000000003"
DETAIL_ID = SESSION_ID
SOURCE_KEY = source_key_for("DESKTOP.example", "collector-1")
DAY_START = 1_700_000_000_000
DAY_END = DAY_START + 86_400_000


def _factory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Engine, sessionmaker[Session]]:
    data_dir = tmp_path / "data"
    monkeypatch.setenv(DATA_DIR_ENV, str(data_dir))
    command.upgrade(Config(str(Path(__file__).parents[1] / "alembic.ini")), "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    return engine, create_session_factory(engine)


def _device() -> ActivityWatchDeviceRecord:
    return ActivityWatchDeviceRecord(DEVICE_ID, "Windows desktop", 1_700_000_000_000)


def _app() -> ActivityWatchAppRecord:
    return ActivityWatchAppRecord(APP_ID, "Code.exe", "Visual Studio Code", 1_700_000_000_000)


def _session(
    session_id: str = SESSION_ID, started_at_ms: int = DAY_START
) -> ActivityWatchSessionRecord:
    return ActivityWatchSessionRecord(
        session_id, APP_ID, started_at_ms, started_at_ms + 60_000, 1_700_000_000_000
    )


def _detail(
    session_id: str = DETAIL_ID, title: str | None = "README.md"
) -> ActivityWatchDetailRecord:
    return ActivityWatchDetailRecord(
        session_id,
        title,
        "https://example.com/docs",
        "event-1",
        1_700_000_000_000,
        privacy_mode="web",
    )


def _seed_existing_android_and_derived_facts(engine: Engine) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO devices (id, name, platform, created_at_ms) "
                "VALUES ('01J00000000000000000000011', 'Android', 'android', 1)"
            )
        )
        connection.execute(
            text(
                "INSERT INTO apps (id, platform, identifier, display_name, created_at_ms) "
                "VALUES ('01J00000000000000000000012', 'android', 'com.example', 'Example', 1)"
            )
        )
        connection.execute(
            text(
                "INSERT INTO app_sessions "
                "(id, device_id, app_id, started_at_ms, ended_at_ms, duration_ms, source, "
                "created_at_ms) "
                "VALUES ('01J00000000000000000000013', '01J00000000000000000000011', "
                "'01J00000000000000000000012', 1, 2, 1, 'android_app_session', 1)"
            )
        )
        connection.execute(
            text(
                "INSERT INTO media_items "
                "(id, device_id, type, source, source_id, filename, captured_at_ms, mime_type, "
                "created_at_ms) "
                "VALUES ('01J00000000000000000000014', '01J00000000000000000000011', 'photo', "
                "'android_media_store', 'fixture', 'photo.jpg', 1, 'image/jpeg', 1)"
            )
        )
        connection.execute(
            text(
                "INSERT INTO location_points "
                "(id, device_id, recorded_at_ms, latitude, longitude, source, created_at_ms) "
                "VALUES ('01J00000000000000000000015', '01J00000000000000000000011', 1, 35, 139, "
                "'android_fused_location', 1)"
            )
        )
        connection.execute(
            text(
                "INSERT INTO place_visits "
                "(id, device_id, started_at_ms, ended_at_ms, duration_ms, center_latitude, "
                "center_longitude, radius_m, point_count, algorithm_version, "
                "source_first_point_id, "
                "source_last_point_id, created_at_ms) VALUES "
                "('01J00000000000000000000016', '01J00000000000000000000011', "
                "1, 4, 3, 35, 139, 10, 3, "
                "'stay_point_v1', '01J00000000000000000000015', '01J00000000000000000000015', 1)"
            )
        )


def test_0003_upgrade_preserves_all_existing_fact_tables_and_adds_p602_tables(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_dir = tmp_path / "migration-data"
    monkeypatch.setenv(DATA_DIR_ENV, str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "0003_locations")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    try:
        _seed_existing_android_and_derived_facts(engine)
    finally:
        engine.dispose()
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    try:
        inspector = inspect(engine)
        assert {"desktop_session_details", "activitywatch_import_states"}.issubset(
            inspector.get_table_names()
        )
        assert {index["name"] for index in inspector.get_indexes("desktop_session_details")} == {
            "idx_desktop_session_details_source_event"
        }
        with engine.connect() as connection:
            for table in ("app_sessions", "media_items", "location_points", "place_visits"):
                assert connection.scalar(text(f"SELECT count(*) FROM {table}")) == 1
            assert (
                connection.scalar(text("SELECT version_num FROM alembic_version"))
                == "0004_activitywatch"
            )
    finally:
        engine.dispose()


def test_replace_is_atomic_idempotent_and_does_not_delete_other_sources_or_devices(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _factory(tmp_path, monkeypatch)
    try:
        with factory() as session:
            repository = ActivityWatchRepository(session)
            token = repository.acquire_lease(
                source_key=SOURCE_KEY,
                device_id=DEVICE_ID,
                privacy_mode="web",
                now_ms=DAY_START,
            )
            assert token is not None
            result = repository.replace_utc_day(
                source_key=SOURCE_KEY,
                token=token,
                device=_device(),
                apps=[_app()],
                sessions=[_session()],
                details=[_detail()],
                day_start_ms=DAY_START,
                day_end_ms=DAY_END,
                now_ms=DAY_START + 1_000,
                privacy_mode="web",
            )
            assert result == ActivityWatchReplaceResult(1, 60_000, 1)
            state = repository.get_import_state(SOURCE_KEY)
            assert state is not None
            assert state.completed_through_ms == DAY_END
            assert state.last_result_code == "success"

            repository.replace_utc_day(
                source_key=SOURCE_KEY,
                token=token,
                device=_device(),
                apps=[_app()],
                sessions=[_session()],
                details=[_detail()],
                day_start_ms=DAY_START,
                day_end_ms=DAY_END,
                now_ms=DAY_START + 2_000,
                privacy_mode="web",
            )
            assert session.scalar(select(func.count()).select_from(AppSession)) == 1
            assert session.scalar(select(func.count()).select_from(DesktopSessionDetail)) == 1
    finally:
        engine.dispose()


def test_invalid_detail_and_conflicting_id_roll_back_the_day_replace(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _factory(tmp_path, monkeypatch)
    try:
        with factory() as session:
            repository = ActivityWatchRepository(session)
            token = repository.acquire_lease(
                source_key=SOURCE_KEY,
                device_id=DEVICE_ID,
                privacy_mode="web",
                now_ms=DAY_START,
            )
            assert token is not None
            repository.replace_utc_day(
                source_key=SOURCE_KEY,
                token=token,
                device=_device(),
                apps=[_app()],
                sessions=[_session()],
                details=[_detail()],
                day_start_ms=DAY_START,
                day_end_ms=DAY_END,
                now_ms=DAY_START + 1_000,
                privacy_mode="web",
            )
            with pytest.raises(RepositoryValidationError):
                repository.replace_utc_day(
                    source_key=SOURCE_KEY,
                    token=token,
                    device=_device(),
                    apps=[_app()],
                    sessions=[_session()],
                    details=[
                        ActivityWatchDetailRecord(
                            DETAIL_ID,
                            "README.md",
                            "https://example.com/docs?raw=1",
                            "event-1",
                            1_700_000_000_000,
                            privacy_mode="web",
                        )
                    ],
                    day_start_ms=DAY_START,
                    day_end_ms=DAY_END,
                    now_ms=DAY_START + 2_000,
                    privacy_mode="web",
                )
            assert session.scalar(select(func.count()).select_from(AppSession)) == 1
            stored_detail = session.get(DesktopSessionDetail, DETAIL_ID)
            assert stored_detail is not None
            assert stored_detail.url == "https://example.com/docs"

            with pytest.raises(ActivityWatchConflictError):
                repository.replace_utc_day(
                    source_key=SOURCE_KEY,
                    token=token,
                    device=_device(),
                    apps=[_app()],
                    sessions=[
                        ActivityWatchSessionRecord(
                            SESSION_ID, APP_ID, DAY_START, DAY_START + 120_000, 1_700_000_000_000
                        )
                    ],
                    details=[_detail()],
                    day_start_ms=DAY_START,
                    day_end_ms=DAY_END,
                    now_ms=DAY_START + 3_000,
                    privacy_mode="web",
                )
            stored_session = session.get(AppSession, SESSION_ID)
            assert stored_session is not None
            assert stored_session.duration_ms == 60_000
    finally:
        engine.dispose()


def test_lease_acquire_deny_heartbeat_token_mismatch_and_stale_recovery(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine, factory = _factory(tmp_path, monkeypatch)
    try:
        with factory() as first, factory() as second:
            first_repo = ActivityWatchRepository(first)
            second_repo = ActivityWatchRepository(second)
            first_token = first_repo.acquire_lease(
                source_key=SOURCE_KEY,
                device_id=DEVICE_ID,
                privacy_mode="app_only",
                now_ms=100,
                ttl_ms=1_000,
                token="first-token",
            )
            assert first_token == "first-token"
            assert (
                second_repo.acquire_lease(
                    source_key=SOURCE_KEY,
                    device_id=DEVICE_ID,
                    privacy_mode="app_only",
                    now_ms=500,
                    ttl_ms=1_000,
                    token="second-token",
                )
                is None
            )
            assert not second_repo.release_lease(source_key=SOURCE_KEY, token="wrong", now_ms=600)
            assert first_repo.heartbeat_lease(source_key=SOURCE_KEY, token=first_token, now_ms=700)
            assert first_repo.release_lease(source_key=SOURCE_KEY, token=first_token, now_ms=800)
            assert (
                second_repo.acquire_lease(
                    source_key=SOURCE_KEY,
                    device_id=DEVICE_ID,
                    privacy_mode="app_only",
                    now_ms=900,
                    token="second-token",
                )
                == "second-token"
            )
            assert (
                first_repo.acquire_lease(
                    source_key=SOURCE_KEY,
                    device_id=DEVICE_ID,
                    privacy_mode="app_only",
                    now_ms=1_000,
                    token="third-token",
                )
                is None
            )
            assert (
                second_repo.acquire_lease(
                    source_key=SOURCE_KEY,
                    device_id=DEVICE_ID,
                    privacy_mode="app_only",
                    now_ms=DAY_START,
                    token="recovered-token",
                )
                == "recovered-token"
            )
            with pytest.raises(ActivityWatchLeaseLostError):
                first_repo.mark_attempt(source_key=SOURCE_KEY, token="second-token", now_ms=1_001)
    finally:
        engine.dispose()
