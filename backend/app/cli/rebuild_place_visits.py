"""Rebuild all derived PlaceVisits without exposing coordinate data."""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

from alembic.script import ScriptDirectory
from sqlalchemy import inspect, text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError

from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory, session_scope
from app.services.place_visits import rebuild_all_place_visits
from app.services.stay_points import ALGORITHM_VERSION


class RebuildError(RuntimeError):
    """Raised when PlaceVisit rebuild cannot run safely."""


@dataclass(frozen=True, slots=True)
class RebuildResult:
    database_path: Path
    algorithm_version: str
    device_count: int
    existing_count: int
    generated_count: int
    dry_run: bool

    def render(self) -> str:
        return (
            f"PlaceVisit rebuild: database={self.database_path}, "
            f"algorithm={self.algorithm_version}, devices={self.device_count}, "
            f"existing={self.existing_count}, generated={self.generated_count}, "
            f"dry_run={str(self.dry_run).lower()}"
        )


def _require_absolute_data_dir(raw_data_dir: str) -> Path:
    data_dir = Path(raw_data_dir).expanduser()
    if not data_dir.is_absolute():
        raise RebuildError("--data-dir must be an absolute path; no default is used.")
    return data_dir.resolve()


def _assert_migrated(engine: Engine) -> None:
    expected_tables = {
        "alembic_version",
        "devices",
        "location_points",
        "place_visits",
    }
    if not expected_tables.issubset(set(inspect(engine).get_table_names())):
        raise RebuildError("The database is not migrated to the current schema head.")
    with engine.connect() as connection:
        revision = connection.execute(
            text("SELECT version_num FROM alembic_version")
        ).scalar_one_or_none()
    migrations_path = Path(__file__).resolve().parents[2] / "migrations"
    heads = ScriptDirectory(str(migrations_path)).get_heads()
    if revision not in heads:
        raise RebuildError("Run 'alembic upgrade head' for this same --data-dir first.")


def run_rebuild(
    data_dir: Path,
    *,
    algorithm_version: str = ALGORITHM_VERSION,
    dry_run: bool = False,
) -> RebuildResult:
    resolved_data_dir = _require_absolute_data_dir(str(data_dir))
    if algorithm_version != ALGORITHM_VERSION:
        raise RebuildError(f"Unsupported algorithm version: {algorithm_version}")
    settings = Settings(data_dir=resolved_data_dir)
    engine = create_engine_for_settings(settings)
    try:
        _assert_migrated(engine)
        factory = create_session_factory(engine)
        try:
            with session_scope(factory) as session:
                with session.begin():
                    counts = rebuild_all_place_visits(
                        session,
                        algorithm_version=algorithm_version,
                        dry_run=dry_run,
                    )
        except IntegrityError as error:
            raise RebuildError("PlaceVisit rebuild failed and was rolled back.") from error
        return RebuildResult(
            database_path=settings.database_path,
            algorithm_version=algorithm_version,
            device_count=len(counts),
            existing_count=sum(item.existing for item in counts),
            generated_count=sum(item.generated for item in counts),
            dry_run=dry_run,
        )
    finally:
        engine.dispose()


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Rebuild derived PlaceVisits from stored LocationPoints."
    )
    parser.add_argument("--data-dir", required=True, help="absolute application data directory")
    parser.add_argument(
        "--algorithm-version",
        choices=(ALGORITHM_VERSION,),
        default=ALGORITHM_VERSION,
    )
    parser.add_argument("--dry-run", action="store_true", help="report counts without writing")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        result = run_rebuild(
            Path(args.data_dir),
            algorithm_version=args.algorithm_version,
            dry_run=args.dry_run,
        )
    except RebuildError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    print(result.render())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
