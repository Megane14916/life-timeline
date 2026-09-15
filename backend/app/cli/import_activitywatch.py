"""Import or rebuild ActivityWatch UTC-day chunks from the command line."""

from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

from alembic.script import ScriptDirectory
from sqlalchemy import inspect, text
from sqlalchemy.engine import Engine

from app.activitywatch import (
    ActivityWatchClient,
    ActivityWatchImporter,
    ActivityWatchImportError,
    ActivityWatchImportResult,
    ActivityWatchImportSettings,
    ImportRangeError,
    local_date_range_to_utc,
)
from app.config import Settings, get_settings
from app.db import create_engine_for_settings, create_session_factory


class ActivityWatchCliError(RuntimeError):
    """Raised when the CLI cannot safely start an import."""


def _resolve_settings(raw_data_dir: str | None) -> Settings:
    if raw_data_dir is None:
        return get_settings()
    data_dir = Path(raw_data_dir).expanduser()
    if not data_dir.is_absolute():
        raise ActivityWatchCliError("--data-dir must be an absolute path.")
    return Settings(data_dir=data_dir)


def _assert_migrated(engine: Engine) -> None:
    expected_tables = {
        "alembic_version",
        "devices",
        "apps",
        "app_sessions",
        "desktop_session_details",
        "activitywatch_import_states",
    }
    if not expected_tables.issubset(set(inspect(engine).get_table_names())):
        raise ActivityWatchCliError("The database is not migrated to the current schema head.")
    with engine.connect() as connection:
        revision = connection.execute(
            text("SELECT version_num FROM alembic_version")
        ).scalar_one_or_none()
    migrations_path = Path(__file__).resolve().parents[2] / "migrations"
    heads = ScriptDirectory(str(migrations_path)).get_heads()
    if revision not in heads:
        raise ActivityWatchCliError(
            "Run 'alembic upgrade head' for this same data directory first."
        )


def _parse_date(value: str, *, option: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise ImportRangeError(f"{option} must use YYYY-MM-DD.") from error


def _range_ms(args: argparse.Namespace) -> tuple[int | None, int | None]:
    if (args.from_date is None) != (args.to_date is None):
        raise ImportRangeError("--from and --to must be supplied together.")
    if args.from_date is None:
        return None, None
    from_date = _parse_date(args.from_date, option="--from")
    to_date = _parse_date(args.to_date, option="--to")
    if to_date <= from_date:
        raise ImportRangeError("--to must be after --from.")
    if (to_date - from_date).days > 31:
        raise ImportRangeError("backfill range may cover at most 31 local days.")
    return local_date_range_to_utc(from_date, to_date, args.timezone)


def render_result(result: ActivityWatchImportResult) -> str:
    return (
        "ActivityWatch import: "
        f"chunks={len(result.chunks)}, sessions={result.session_count}, "
        f"duration_ms={result.total_duration_ms}, invalid={result.invalid_event_count}, "
        f"redacted={result.redacted_detail_count}, truncated={result.truncated_title_count}, "
        f"dry_run={str(result.dry_run).lower()}, partial={str(result.partial).lower()}"
    )


def run_import(
    *,
    data_dir: Path | None = None,
    dry_run: bool = False,
    from_date: date | None = None,
    to_date: date | None = None,
    timezone_name: str = "UTC",
    privacy_mode: str = "app_only",
) -> ActivityWatchImportResult:
    """Run the CLI workflow with safe, injectable date arguments."""

    if (from_date is None) != (to_date is None):
        raise ImportRangeError("--from and --to must be supplied together.")
    from_ms = to_ms = None
    if from_date is not None and to_date is not None:
        if (to_date - from_date).days > 31:
            raise ImportRangeError("backfill range may cover at most 31 local days.")
        from_ms, to_ms = local_date_range_to_utc(from_date, to_date, timezone_name)
    settings = _resolve_settings(str(data_dir) if data_dir is not None else None)
    engine = create_engine_for_settings(settings)
    try:
        _assert_migrated(engine)
        factory = create_session_factory(engine)
        with ActivityWatchClient() as client:
            importer = ActivityWatchImporter(
                client,
                factory,
                settings=ActivityWatchImportSettings(privacy_mode=privacy_mode),
            )
            return importer.run(dry_run=dry_run, from_ms=from_ms, to_ms=to_ms)
    finally:
        engine.dispose()


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Import privacy-filtered ActivityWatch sessions by UTC day."
    )
    parser.add_argument("--data-dir", help="absolute application data directory")
    parser.add_argument("--from", dest="from_date", help="inclusive local date, YYYY-MM-DD")
    parser.add_argument("--to", dest="to_date", help="exclusive local date, YYYY-MM-DD")
    parser.add_argument("--timezone", default="UTC", help="IANA timezone for --from/--to")
    parser.add_argument(
        "--privacy-mode",
        choices=("app_only", "titles", "web"),
        default="app_only",
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="fetch and normalize without DB writes"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        from_ms, to_ms = _range_ms(args)
        settings = _resolve_settings(args.data_dir)
        engine = create_engine_for_settings(settings)
        try:
            _assert_migrated(engine)
            factory = create_session_factory(engine)
            with ActivityWatchClient() as client:
                importer = ActivityWatchImporter(
                    client,
                    factory,
                    settings=ActivityWatchImportSettings(privacy_mode=args.privacy_mode),
                )
                result = importer.run(
                    dry_run=args.dry_run,
                    from_ms=from_ms,
                    to_ms=to_ms,
                )
        finally:
            engine.dispose()
    except (ActivityWatchCliError, ActivityWatchImportError, ImportRangeError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    print(render_result(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
