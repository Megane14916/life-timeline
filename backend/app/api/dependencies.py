"""FastAPI dependencies for request-scoped database sessions."""

from __future__ import annotations

from collections.abc import Iterator

from fastapi import Request
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db import create_engine_for_settings, create_session_factory


def get_session(request: Request) -> Iterator[Session]:
    """Create and close one SQLAlchemy session for each request."""

    session_factory = request.app.state.session_factory
    if session_factory is None:
        engine = create_engine_for_settings(get_settings())
        session_factory = create_session_factory(engine)
        request.app.state.engine = engine
        request.app.state.session_factory = session_factory
    session = session_factory()
    try:
        yield session
    finally:
        session.close()
