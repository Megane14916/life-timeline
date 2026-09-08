"""Normalized SQLAlchemy models for the PC-side SQLite database."""

from __future__ import annotations

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    ForeignKey,
    Index,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (CheckConstraint("platform IN ('android', 'windows')", name="platform"),)

    id: Mapped[str] = mapped_column(String(26), primary_key=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    platform: Mapped[str] = mapped_column(String(20), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    last_seen_at_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)


class Category(Base):
    __tablename__ = "categories"

    id: Mapped[str] = mapped_column(String(26), primary_key=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)


class App(Base):
    __tablename__ = "apps"
    __table_args__ = (
        UniqueConstraint("platform", "identifier", name="uq_apps_platform_identifier"),
        CheckConstraint("platform IN ('android', 'windows')", name="platform"),
    )

    id: Mapped[str] = mapped_column(String(26), primary_key=True)
    platform: Mapped[str] = mapped_column(String(20), nullable=False)
    identifier: Mapped[str] = mapped_column(String(255), nullable=False)
    display_name: Mapped[str] = mapped_column(String(255), nullable=False)
    category_id: Mapped[str | None] = mapped_column(
        String(26), ForeignKey("categories.id", ondelete="RESTRICT"), nullable=True
    )
    icon_path: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    created_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)


class AppSession(Base):
    __tablename__ = "app_sessions"
    __table_args__ = (
        CheckConstraint("ended_at_ms > started_at_ms", name="ended_after_started"),
        CheckConstraint("duration_ms = ended_at_ms - started_at_ms", name="duration_matches_range"),
        Index("idx_app_sessions_started", "started_at_ms"),
    )

    id: Mapped[str] = mapped_column(String(26), primary_key=True)
    device_id: Mapped[str] = mapped_column(
        String(26), ForeignKey("devices.id", ondelete="RESTRICT"), nullable=False
    )
    app_id: Mapped[str] = mapped_column(
        String(26), ForeignKey("apps.id", ondelete="RESTRICT"), nullable=False
    )
    started_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    ended_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    duration_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    source: Mapped[str] = mapped_column(String(100), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
