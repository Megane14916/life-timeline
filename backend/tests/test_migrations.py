from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import inspect

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
        }
    finally:
        engine.dispose()
