"""SQLite engine and session helpers."""

from __future__ import annotations

import os
import tempfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

from sqlalchemy import Engine, create_engine, event, inspect
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from app.config import Settings
from app.models import Base


class DataDirectoryError(OSError):
    """Raised when the configured data directory cannot be prepared."""


REQUIRED_TABLES = frozenset(Base.metadata.tables)


def ensure_data_directory(data_dir: Path) -> Path:
    """Create and return the resolved data directory.

    A failure is surfaced with the configured path so callers do not silently fall
    back to another database location.
    """

    resolved_dir = data_dir.expanduser().resolve()
    try:
        resolved_dir.mkdir(parents=True, exist_ok=True)
        file_descriptor, probe_path = tempfile.mkstemp(
            prefix=".life-timeline-write-test-",
            dir=resolved_dir,
        )
        os.close(file_descriptor)
        Path(probe_path).unlink()
    except OSError as error:
        raise DataDirectoryError(
            f"Unable to create or write to the life-timeline data directory "
            f"'{resolved_dir}': {error.strerror or error}"
        ) from error

    if not resolved_dir.is_dir():
        raise DataDirectoryError(f"Configured data directory is not a directory: '{resolved_dir}'.")
    return resolved_dir


def _sqlite_url(database_path: Path) -> str:
    """Build a SQLite URL that works for absolute Windows and POSIX paths."""

    return f"sqlite:///{database_path.as_posix()}"


def create_engine_for_settings(settings: Settings) -> Engine:
    """Create a configured SQLAlchemy engine for the application database."""

    data_dir = ensure_data_directory(settings.data_dir)
    engine = create_engine(
        _sqlite_url(data_dir / "lifelog.db"),
        connect_args={
            "check_same_thread": False,
            "timeout": settings.sqlite_timeout_seconds,
        },
    )

    @event.listens_for(engine, "connect")
    def enable_sqlite_foreign_keys(dbapi_connection: object, _connection_record: object) -> None:
        cursor = dbapi_connection.cursor()  # type: ignore[attr-defined]
        try:
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute(f"PRAGMA busy_timeout={int(settings.sqlite_timeout_seconds * 1000)}")
            cursor.execute("PRAGMA journal_mode=WAL")
        finally:
            cursor.close()

    return engine


def create_session_factory(engine: Engine) -> sessionmaker[Session]:
    """Create a non-global session factory bound to ``engine``."""

    return sessionmaker(bind=engine, class_=Session, expire_on_commit=False)


def is_database_ready(engine: Engine) -> bool:
    """Return whether the configured database has the current application schema.

    The API must not treat a newly-created empty SQLite file as a usable database.
    Checking the model table set catches the common case where the server and the
    migration command used different ``LIFE_TIMELINE_DATA_DIR`` values.
    """

    try:
        with engine.connect() as connection:
            existing_tables = set(inspect(connection).get_table_names())
    except SQLAlchemyError:
        return False
    return REQUIRED_TABLES.issubset(existing_tables)


@contextmanager
def session_scope(session_factory: sessionmaker[Session]) -> Iterator[Session]:
    """Yield one session and guarantee rollback/close on every exit path."""

    session = session_factory()
    try:
        yield session
    except BaseException:
        session.rollback()
        raise
    finally:
        session.close()
