"""Measure a full SQLite PlaceVisit rebuild over 105,000 synthetic points."""

from __future__ import annotations

import os
import platform
import sqlite3
import tempfile
import time
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import insert

from app.config import DATA_DIR_ENV, Settings
from app.db import create_engine_for_settings, create_session_factory
from app.models import Device, LocationPoint
from app.services.place_visits import rebuild_device_place_visits

POINT_COUNT = 105_000
DEVICE_ID = "01J00000000000000000001001"
START_AT_MS = 1_780_000_000_000


def main() -> int:
    prior_data_dir = os.environ.get(DATA_DIR_ENV)
    with tempfile.TemporaryDirectory(prefix="life-timeline-place-visits-") as temporary_dir:
        data_dir = Path(temporary_dir).resolve()
        os.environ[DATA_DIR_ENV] = str(data_dir)
        try:
            config = Config(str(Path(__file__).parents[1] / "alembic.ini"))
            command.upgrade(config, "head")
            engine = create_engine_for_settings(Settings(data_dir=data_dir))
            try:
                with engine.begin() as connection:
                    connection.execute(
                        insert(Device),
                        {
                            "id": DEVICE_ID,
                            "name": "Synthetic Android",
                            "platform": "android",
                            "created_at_ms": 1,
                            "last_seen_at_ms": 1,
                        },
                    )
                    batch_size = 5_000
                    for batch_start in range(0, POINT_COUNT, batch_size):
                        batch_end = min(POINT_COUNT, batch_start + batch_size)
                        connection.execute(
                            insert(LocationPoint),
                            [
                                {
                                    "id": f"{index:026d}",
                                    "device_id": DEVICE_ID,
                                    "recorded_at_ms": START_AT_MS + index * 5 * 60_000,
                                    "latitude": 0.0,
                                    "longitude": 0.0,
                                    "accuracy_m": 25.0,
                                    "altitude_m": None,
                                    "speed_mps": None,
                                    "source": "android_fused_location",
                                    "created_at_ms": 1,
                                }
                                for index in range(batch_start, batch_end)
                            ],
                        )

                factory = create_session_factory(engine)
                started = time.perf_counter()
                with factory.begin() as session:
                    result = rebuild_device_place_visits(session, DEVICE_ID)
                elapsed_ms = (time.perf_counter() - started) * 1000
                print(
                    f"points={POINT_COUNT} generated={result.generated} "
                    f"rebuild_ms={elapsed_ms:.1f} "
                    f"os={platform.system()} python={platform.python_version()} "
                    f"sqlite={sqlite3.sqlite_version}"
                )
            finally:
                engine.dispose()
        finally:
            if prior_data_dir is None:
                os.environ.pop(DATA_DIR_ENV, None)
            else:
                os.environ[DATA_DIR_ENV] = prior_data_dir
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
