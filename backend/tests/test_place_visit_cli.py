"""Tests for the explicit, privacy-safe PlaceVisit rebuild CLI."""

from __future__ import annotations

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import func, insert, select

from app.cli.rebuild_place_visits import main, run_rebuild
from app.config import DATA_DIR_ENV, Settings
from app.db import create_engine_for_settings
from app.models import Device, LocationPoint, PlaceVisit
from app.services.stay_points import ALGORITHM_VERSION


def _migrated_database(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    data_dir = tmp_path.resolve()
    monkeypatch.setenv(DATA_DIR_ENV, str(data_dir))
    config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    try:
        with engine.begin() as connection:
            connection.execute(
                insert(Device),
                {
                    "id": "01J00000000000000000001001",
                    "name": "Synthetic Android",
                    "platform": "android",
                    "created_at_ms": 1,
                    "last_seen_at_ms": 1,
                },
            )
            connection.execute(
                insert(LocationPoint),
                [
                    {
                        "id": f"01J0000000000000000000{index:03d}",
                        "device_id": "01J00000000000000000001001",
                        "recorded_at_ms": 1_780_000_000_000 + index * 5 * 60_000,
                        "latitude": 0.0,
                        "longitude": 0.0,
                        "accuracy_m": 10.0,
                        "altitude_m": None,
                        "speed_mps": None,
                        "source": "android_fused_location",
                        "created_at_ms": 1,
                    }
                    for index in range(1, 5)
                ],
            )
    finally:
        engine.dispose()


def test_rebuild_cli_supports_dry_run_repeat_and_privacy_safe_summary(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _migrated_database(tmp_path, monkeypatch)
    data_dir = tmp_path.resolve()
    preview = run_rebuild(data_dir, dry_run=True)
    assert preview.algorithm_version == ALGORITHM_VERSION
    assert (preview.device_count, preview.existing_count, preview.generated_count) == (1, 0, 1)

    engine = create_engine_for_settings(Settings(data_dir=data_dir))
    try:
        with engine.connect() as connection:
            assert connection.scalar(select(func.count()).select_from(PlaceVisit)) == 0
    finally:
        engine.dispose()

    first = run_rebuild(data_dir)
    second = run_rebuild(data_dir)
    assert (first.existing_count, first.generated_count) == (0, 1)
    assert (second.existing_count, second.generated_count) == (1, 1)
    assert main(["--data-dir", str(data_dir), "--dry-run"]) == 0
    output = capsys.readouterr().out
    assert "generated=1" in output
    assert "0.0" not in output
    assert "Synthetic Android" not in output
