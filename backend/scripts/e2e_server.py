"""Prepare an isolated SQLite database and serve the real API for Playwright."""

from __future__ import annotations

import argparse
import os
from pathlib import Path

import uvicorn
from alembic import command
from alembic.config import Config

from app.cli.seed import main as seed_main
from app.config import DATA_DIR_ENV


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--data-dir",
        required=True,
        help="absolute directory for the isolated E2E SQLite database",
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    return parser


def _prepare_database(data_dir: Path) -> None:
    resolved_data_dir = data_dir.expanduser().resolve()
    if not resolved_data_dir.is_absolute():
        raise ValueError("--data-dir must be an absolute path")

    os.environ[DATA_DIR_ENV] = str(resolved_data_dir)
    config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
    command.upgrade(config, "head")
    if seed_main(["--data-dir", str(resolved_data_dir)]) != 0:
        raise RuntimeError("E2E fixture seeding failed")


def main() -> None:
    args = _build_parser().parse_args()
    _prepare_database(Path(args.data_dir))
    uvicorn.run(
        "app.main:app",
        host=args.host,
        port=args.port,
        log_level="info",
    )


if __name__ == "__main__":
    main()
