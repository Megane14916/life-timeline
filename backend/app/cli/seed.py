"""CLI for loading the fixed, non-personal demo dataset."""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

from alembic.script import ScriptDirectory
from sqlalchemy import func, inspect, select, text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.cli.fixtures import FIXTURE
from app.config import Settings
from app.db import create_engine_for_settings, create_session_factory, session_scope
from app.models import App, AppSession, Category, Device
from app.repositories import NormalizedRepository, RepositoryError


class SeedError(RuntimeError):
    """Raised when the demo dataset cannot be safely loaded."""


@dataclass(frozen=True, slots=True)
class SeedEntityCounts:
    inserted: int
    existing: int


@dataclass(frozen=True, slots=True)
class SeedResult:
    database_path: Path
    counts: dict[str, SeedEntityCounts]

    def render(self) -> str:
        lines = [f"Seed complete: database={self.database_path}"]
        for entity in ("categories", "devices", "apps", "app_sessions"):
            counts = self.counts[entity]
            lines.append(f"{entity}: inserted={counts.inserted}, existing={counts.existing}")
        return "\n".join(lines)


def _require_absolute_data_dir(raw_data_dir: str) -> Path:
    data_dir = Path(raw_data_dir).expanduser()
    if not data_dir.is_absolute():
        raise SeedError("--data-dir must be an absolute path; no default is used.")
    return data_dir.resolve()


def _assert_migrated(engine: Engine) -> None:
    expected_tables = {"alembic_version", "categories", "devices", "apps", "app_sessions"}
    actual_tables = set(inspect(engine).get_table_names())
    if not expected_tables.issubset(actual_tables):
        raise SeedError(
            "The database schema is not migrated. Run 'alembic upgrade head' "
            "for this same --data-dir before running seed."
        )

    with engine.connect() as connection:
        current_revision = connection.execute(
            text("SELECT version_num FROM alembic_version")
        ).scalar_one_or_none()
    if current_revision is None:
        raise SeedError(
            "The database has no Alembic revision. Run 'alembic upgrade head' "
            "for this same --data-dir before running seed."
        )

    # Keep the check explicit so a database migrated by an unknown branch is not
    # silently treated as compatible with this fixture.
    migrations_path = Path(__file__).resolve().parents[2] / "migrations"
    script_directory = ScriptDirectory(str(migrations_path))
    if current_revision not in script_directory.get_heads():
        raise SeedError(
            f"The database revision '{current_revision}' is not the current migration head. "
            "Run 'alembic upgrade head' for this same --data-dir."
        )


def _row_count(
    session: Session, model: type[Category] | type[Device] | type[App] | type[AppSession]
) -> int:
    return session.scalar(select(func.count()).select_from(model)) or 0


def _seed_records(session: Session) -> dict[str, SeedEntityCounts]:
    repository = NormalizedRepository(session)
    before_counts = {
        "categories": _row_count(session, Category),
        "devices": _row_count(session, Device),
        "apps": _row_count(session, App),
        "app_sessions": _row_count(session, AppSession),
    }

    for category_record in FIXTURE.categories:
        repository.masters.save_category(category_record)
    for device_record in FIXTURE.devices:
        repository.masters.save_device(device_record)
    for app_record in FIXTURE.apps:
        repository.masters.save_app(app_record)
    for app_session_record in FIXTURE.app_sessions:
        device = next(item for item in FIXTURE.devices if item.id == app_session_record.device_id)
        app = next(item for item in FIXTURE.apps if item.id == app_session_record.app_id)
        repository.save_app_session(device=device, app=app, app_session=app_session_record)

    after_counts = {
        "categories": _row_count(session, Category),
        "devices": _row_count(session, Device),
        "apps": _row_count(session, App),
        "app_sessions": _row_count(session, AppSession),
    }
    fixture_counts = {
        "categories": len(FIXTURE.categories),
        "devices": len(FIXTURE.devices),
        "apps": len(FIXTURE.apps),
        "app_sessions": len(FIXTURE.app_sessions),
    }
    counts: dict[str, SeedEntityCounts] = {}
    for entity, fixture_count in fixture_counts.items():
        inserted = after_counts[entity] - before_counts[entity]
        counts[entity] = SeedEntityCounts(inserted=inserted, existing=fixture_count - inserted)
    return counts


def run_seed(data_dir: Path) -> SeedResult:
    """Apply the fixed fixture atomically to an already migrated database."""

    resolved_data_dir = _require_absolute_data_dir(str(data_dir))
    settings = Settings(data_dir=resolved_data_dir)
    engine = create_engine_for_settings(settings)
    try:
        _assert_migrated(engine)
        session_factory = create_session_factory(engine)
        try:
            with session_scope(session_factory) as session:
                with session.begin():
                    counts = _seed_records(session)
        except (IntegrityError, RepositoryError) as error:
            raise SeedError(f"Seed aborted and rolled back: {error}") from error
        return SeedResult(database_path=settings.database_path, counts=counts)
    finally:
        engine.dispose()


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Load the fixed life-timeline demo dataset.")
    parser.add_argument(
        "--data-dir",
        required=True,
        help="absolute directory containing the already migrated lifelog.db",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        result = run_seed(Path(args.data_dir))
    except SeedError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    print(result.render())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
