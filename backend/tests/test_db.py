from pathlib import Path

import pytest
from sqlalchemy import text

from app.config import Settings
from app.db import (
    DataDirectoryError,
    create_engine_for_settings,
    create_session_factory,
    ensure_data_directory,
    session_scope,
)


def test_data_directory_is_created_and_database_path_is_stable(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    data_dir = tmp_path / "nested" / "life-timeline"
    settings = Settings(data_dir=data_dir)

    engine = create_engine_for_settings(settings)
    try:
        assert data_dir.is_dir()
        assert settings.database_path == data_dir / "lifelog.db"

        with engine.connect() as connection:
            connection.execute(text("CREATE TABLE example (id INTEGER PRIMARY KEY)"))
            connection.commit()

        other_working_directory = tmp_path / "other-working-directory"
        other_working_directory.mkdir()
        monkeypatch.chdir(other_working_directory)
        second_engine = create_engine_for_settings(Settings(data_dir=data_dir))
        try:
            with second_engine.connect() as connection:
                assert (
                    connection.execute(
                        text("SELECT name FROM sqlite_master WHERE name = 'example'")
                    ).scalar_one()
                    == "example"
                )
        finally:
            second_engine.dispose()
    finally:
        engine.dispose()


def test_sqlite_foreign_keys_are_enabled_for_each_connection(tmp_path: Path) -> None:
    engine = create_engine_for_settings(
        Settings(data_dir=tmp_path / "data", sqlite_timeout_seconds=12.5)
    )
    try:
        with engine.connect() as connection:
            assert connection.execute(text("PRAGMA foreign_keys")).scalar_one() == 1
            assert connection.execute(text("PRAGMA busy_timeout")).scalar_one() == 12_500
    finally:
        engine.dispose()


def test_session_scope_rolls_back_and_closes_on_error(tmp_path: Path) -> None:
    engine = create_engine_for_settings(Settings(data_dir=tmp_path / "data"))
    session_factory = create_session_factory(engine)
    try:
        with engine.begin() as connection:
            connection.execute(text("CREATE TABLE example (id INTEGER PRIMARY KEY)"))

        with pytest.raises(RuntimeError, match="expected failure"):
            with session_scope(session_factory) as session:
                session.execute(text("INSERT INTO example (id) VALUES (1)"))
                raise RuntimeError("expected failure")

        with session_factory() as session:
            assert session.execute(text("SELECT COUNT(*) FROM example")).scalar_one() == 0
    finally:
        engine.dispose()


def test_unusable_data_directory_reports_the_path(tmp_path: Path) -> None:
    path_that_is_a_file = tmp_path / "not-a-directory"
    path_that_is_a_file.write_text("not a directory", encoding="utf-8")

    with pytest.raises(DataDirectoryError, match="not-a-directory"):
        ensure_data_directory(path_that_is_a_file)
