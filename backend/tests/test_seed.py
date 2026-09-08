from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import func, select
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.cli.fixtures import (
    ANDROID_MAPS_ID,
    EXPECTED_STATISTICS,
    _at_ms,
)
from app.cli.seed import _build_parser, main
from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory, session_scope
from app.models import App, AppSession


def _migrated_session(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[Engine, sessionmaker[Session]]:
    data_dir = tmp_path / "data"
    monkeypatch.setenv("LIFE_TIMELINE_DATA_DIR", str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    return engine, create_session_factory(engine)


def test_seed_reports_inserts_then_idempotent_existing_counts_and_expected_baseline(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    engine, factory = _migrated_session(tmp_path, monkeypatch)
    data_dir = tmp_path / "data"
    try:
        assert main(["--data-dir", str(data_dir)]) == 0
        first_output = capsys.readouterr().out
        assert "database=" in first_output
        assert "categories: inserted=2, existing=0" in first_output
        assert "devices: inserted=3, existing=0" in first_output
        assert "apps: inserted=5, existing=0" in first_output
        assert "app_sessions: inserted=10, existing=0" in first_output

        assert main(["--data-dir", str(data_dir)]) == 0
        second_output = capsys.readouterr().out
        assert "categories: inserted=0, existing=2" in second_output
        assert "devices: inserted=0, existing=3" in second_output
        assert "apps: inserted=0, existing=5" in second_output
        assert "app_sessions: inserted=0, existing=10" in second_output

        with session_scope(factory) as session:
            target_ids = {
                "01J00000000000000000001302",
                "01J00000000000000000001303",
                "01J00000000000000000001304",
                "01J00000000000000000001305",
            }
            target_facts = session.scalars(
                select(AppSession).where(AppSession.id.in_(target_ids))
            ).all()
            target_start_ms = _at_ms("2026-09-03T00:00:00+09:00")
            target_end_ms = _at_ms("2026-09-04T00:00:00+09:00")
            assert (
                sum(
                    max(
                        0,
                        min(item.ended_at_ms, target_end_ms)
                        - max(item.started_at_ms, target_start_ms),
                    )
                    for item in target_facts
                )
                == EXPECTED_STATISTICS["2026-09-03"]["usage_ms"]
            )
            assert len(target_facts) == EXPECTED_STATISTICS["2026-09-03"]["session_count"]
            assert (
                len({item.app_id for item in target_facts})
                == EXPECTED_STATISTICS["2026-09-03"]["app_count"]
            )
            assert session.scalar(select(func.count()).select_from(AppSession)) == 10
    finally:
        engine.dispose()


def test_seed_requires_migrated_database_and_explicit_absolute_path(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    data_dir = tmp_path / "unmigrated"

    assert main(["--data-dir", str(data_dir)]) == 1
    error_output = capsys.readouterr().err
    assert "alembic upgrade head" in error_output
    assert (data_dir / "lifelog.db").exists()

    assert main(["--data-dir", "relative-demo-data"]) == 1
    assert "absolute path" in capsys.readouterr().err


def test_seed_conflict_rolls_back_master_changes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    engine, factory = _migrated_session(tmp_path, monkeypatch)
    data_dir = tmp_path / "data"
    try:
        assert main(["--data-dir", str(data_dir)]) == 0
        with session_scope(factory) as session:
            app = session.get(App, ANDROID_MAPS_ID)
            fact = session.get(AppSession, "01J00000000000000000001301")
            assert app is not None
            assert fact is not None
            app.display_name = "Changed before conflict"
            fact.ended_at_ms += 1_000
            fact.duration_ms += 1_000
            session.commit()

        assert main(["--data-dir", str(data_dir)]) == 1
        assert "rolled back" in capsys.readouterr().err.lower()

        with session_scope(factory) as session:
            app = session.get(App, ANDROID_MAPS_ID)
            fact = session.get(AppSession, "01J00000000000000000001301")
            assert app is not None
            assert fact is not None
            assert app.display_name == "Changed before conflict"
            assert fact.ended_at_ms - fact.started_at_ms == fact.duration_ms
            assert fact.duration_ms == 1_201_000
    finally:
        engine.dispose()


def test_seed_does_not_add_a_reset_option() -> None:
    assert all(action.dest != "reset" for action in _build_parser()._actions)
