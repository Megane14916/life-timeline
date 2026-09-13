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


class MediaItem(Base):
    __tablename__ = "media_items"
    __table_args__ = (
        CheckConstraint("type IN ('photo', 'video')", name="type"),
        CheckConstraint(
            "(latitude IS NULL AND longitude IS NULL) OR "
            "(latitude IS NOT NULL AND longitude IS NOT NULL)",
            name="location_pair",
        ),
        CheckConstraint(
            "(width IS NULL OR width > 0) AND (height IS NULL OR height > 0)",
            name="positive_dimensions",
        ),
        CheckConstraint("duration_ms IS NULL OR duration_ms > 0", name="positive_duration"),
        CheckConstraint(
            "(thumbnail_path IS NULL AND thumbnail_mime_type IS NULL "
            "AND thumbnail_width IS NULL AND thumbnail_height IS NULL "
            "AND thumbnail_size_bytes IS NULL AND thumbnail_sha256 IS NULL) OR "
            "(thumbnail_path IS NOT NULL AND thumbnail_mime_type IS NOT NULL "
            "AND thumbnail_mime_type = 'image/webp' "
            "AND thumbnail_width IS NOT NULL "
            "AND thumbnail_width BETWEEN 1 AND 512 "
            "AND thumbnail_height IS NOT NULL "
            "AND thumbnail_height BETWEEN 1 AND 512 "
            "AND thumbnail_size_bytes IS NOT NULL "
            "AND thumbnail_size_bytes BETWEEN 1 AND 1048576 "
            "AND thumbnail_sha256 IS NOT NULL "
            "AND length(thumbnail_sha256) = 64)",
            name="thumbnail_fields_complete",
        ),
        CheckConstraint(
            "thumbnail_path IS NULL OR "
            "(substr(thumbnail_path, 1, 11) = 'thumbnails/' "
            "AND instr(thumbnail_path, char(92)) = 0 "
            "AND thumbnail_path NOT LIKE '%/..' "
            "AND thumbnail_path NOT LIKE '../%' "
            "AND thumbnail_path NOT LIKE '%/../%')",
            name="thumbnail_relative_path",
        ),
        UniqueConstraint(
            "device_id",
            "source",
            "source_id",
            name="uq_media_items_device_source_source_id",
        ),
        Index("idx_media_items_captured", "captured_at_ms"),
    )

    id: Mapped[str] = mapped_column(String(26), primary_key=True)
    device_id: Mapped[str] = mapped_column(
        String(26), ForeignKey("devices.id", ondelete="RESTRICT"), nullable=False
    )
    type: Mapped[str] = mapped_column(String(20), nullable=False)
    source: Mapped[str] = mapped_column(String(100), nullable=False)
    source_id: Mapped[str] = mapped_column(String(255), nullable=False)
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    captured_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    width: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    height: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    duration_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    latitude: Mapped[float | None] = mapped_column(nullable=True)
    longitude: Mapped[float | None] = mapped_column(nullable=True)
    thumbnail_path: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    thumbnail_mime_type: Mapped[str | None] = mapped_column(String(100), nullable=True)
    thumbnail_width: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    thumbnail_height: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    thumbnail_size_bytes: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    thumbnail_sha256: Mapped[str | None] = mapped_column(String(64), nullable=True)
    mime_type: Mapped[str] = mapped_column(String(100), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)


class LocationPoint(Base):
    __tablename__ = "location_points"
    __table_args__ = (
        CheckConstraint("latitude BETWEEN -90 AND 90", name="latitude_range"),
        CheckConstraint("longitude BETWEEN -180 AND 180", name="longitude_range"),
        CheckConstraint("accuracy_m IS NULL OR accuracy_m >= 0", name="nonnegative_accuracy"),
        CheckConstraint("speed_mps IS NULL OR speed_mps >= 0", name="nonnegative_speed"),
        CheckConstraint("recorded_at_ms >= 0", name="nonnegative_recorded_at"),
        CheckConstraint("created_at_ms >= 0", name="nonnegative_created_at"),
        CheckConstraint("source = 'android_fused_location'", name="source"),
        Index("idx_location_points_recorded", "recorded_at_ms", "id"),
        Index("idx_location_points_device_recorded", "device_id", "recorded_at_ms", "id"),
    )

    id: Mapped[str] = mapped_column(String(26), primary_key=True)
    device_id: Mapped[str] = mapped_column(
        String(26), ForeignKey("devices.id", ondelete="RESTRICT"), nullable=False
    )
    recorded_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    latitude: Mapped[float] = mapped_column(nullable=False)
    longitude: Mapped[float] = mapped_column(nullable=False)
    accuracy_m: Mapped[float | None] = mapped_column(nullable=True)
    altitude_m: Mapped[float | None] = mapped_column(nullable=True)
    speed_mps: Mapped[float | None] = mapped_column(nullable=True)
    source: Mapped[str] = mapped_column(String(100), nullable=False)
    created_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)


class PlaceVisit(Base):
    """Derived location fact; population is added by the PlaceVisit phase."""

    __tablename__ = "place_visits"
    __table_args__ = (
        CheckConstraint("ended_at_ms > started_at_ms", name="ended_after_started"),
        CheckConstraint("duration_ms = ended_at_ms - started_at_ms", name="duration_matches_range"),
        CheckConstraint("radius_m >= 0", name="nonnegative_radius"),
        CheckConstraint("point_count >= 3", name="minimum_point_count"),
        CheckConstraint("algorithm_version = 'stay_point_v1'", name="algorithm_version"),
        UniqueConstraint(
            "device_id",
            "algorithm_version",
            "source_first_point_id",
            "source_last_point_id",
            name="uq_place_visits_source_range",
        ),
        Index("idx_place_visits_range", "started_at_ms", "ended_at_ms", "id"),
        Index("idx_place_visits_device_started", "device_id", "started_at_ms", "id"),
    )

    id: Mapped[str] = mapped_column(String(26), primary_key=True)
    device_id: Mapped[str] = mapped_column(
        String(26), ForeignKey("devices.id", ondelete="RESTRICT"), nullable=False
    )
    started_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    ended_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    duration_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
    center_latitude: Mapped[float] = mapped_column(nullable=False)
    center_longitude: Mapped[float] = mapped_column(nullable=False)
    radius_m: Mapped[float] = mapped_column(nullable=False)
    point_count: Mapped[int] = mapped_column(BigInteger, nullable=False)
    algorithm_version: Mapped[str] = mapped_column(String(50), nullable=False)
    source_first_point_id: Mapped[str] = mapped_column(
        String(26), ForeignKey("location_points.id", ondelete="RESTRICT"), nullable=False
    )
    source_last_point_id: Mapped[str] = mapped_column(
        String(26), ForeignKey("location_points.id", ondelete="RESTRICT"), nullable=False
    )
    created_at_ms: Mapped[int] = mapped_column(BigInteger, nullable=False)
