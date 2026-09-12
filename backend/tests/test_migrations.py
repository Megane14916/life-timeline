from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import inspect, text

from app.config import DATA_DIR_ENV, Settings
from app.db import create_engine_for_settings


def _alembic_config() -> Config:
    return Config(str(Path(__file__).parents[1] / "alembic.ini"))


def test_initial_migration_reaches_one_head_and_is_repeatable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(DATA_DIR_ENV, str(tmp_path / "migration-data"))
    config = _alembic_config()

    command.upgrade(config, "head")
    command.upgrade(config, "head")

    script = ScriptDirectory.from_config(config)
    assert len(script.get_heads()) == 1

    engine = create_engine_for_settings(Settings(data_dir=tmp_path / "migration-data"))
    try:
        assert set(inspect(engine).get_table_names()) == {
            "alembic_version",
            "app_sessions",
            "apps",
            "categories",
            "devices",
            "media_items",
        }
    finally:
        engine.dispose()


def test_media_migration_preserves_existing_rows_and_adds_constraints(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_dir = tmp_path / "media-migration-data"
    monkeypatch.setenv(DATA_DIR_ENV, str(data_dir))
    config = _alembic_config()
    command.upgrade(config, "0001_initial_normalized_schema")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO devices (id, name, platform, created_at_ms) "
                    "VALUES ('01J00000000000000000000001', 'Existing Android', 'android', 1)"
                )
            )
    finally:
        engine.dispose()

    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    try:
        inspector = inspect(engine)
        assert "media_items" in inspector.get_table_names()
        assert {index["name"] for index in inspector.get_indexes("media_items")} == {
            "idx_media_items_captured"
        }
        assert {
            constraint["name"] for constraint in inspector.get_unique_constraints("media_items")
        } == {"uq_media_items_device_source_source_id"}
        with engine.connect() as connection:
            assert connection.scalar(text("SELECT count(*) FROM devices")) == 1
            assert connection.scalar(
                text("SELECT count(*) FROM alembic_version WHERE version_num = '0002_media_items'")
            ) == 1
    finally:
        engine.dispose()
