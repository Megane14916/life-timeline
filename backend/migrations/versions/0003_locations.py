"""Add raw LocationPoint and derived PlaceVisit tables.

Revision ID: 0003_locations
Revises: 0002_media_items
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0003_locations"
down_revision: str | None = "0002_media_items"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "location_points",
        sa.Column("id", sa.String(length=26), nullable=False),
        sa.Column("device_id", sa.String(length=26), nullable=False),
        sa.Column("recorded_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("latitude", sa.Float(), nullable=False),
        sa.Column("longitude", sa.Float(), nullable=False),
        sa.Column("accuracy_m", sa.Float(), nullable=True),
        sa.Column("altitude_m", sa.Float(), nullable=True),
        sa.Column("speed_mps", sa.Float(), nullable=True),
        sa.Column("source", sa.String(length=100), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.CheckConstraint(
            "latitude BETWEEN -90 AND 90", name=op.f("ck_location_points_latitude_range")
        ),
        sa.CheckConstraint(
            "longitude BETWEEN -180 AND 180", name=op.f("ck_location_points_longitude_range")
        ),
        sa.CheckConstraint(
            "accuracy_m IS NULL OR accuracy_m >= 0",
            name=op.f("ck_location_points_nonnegative_accuracy"),
        ),
        sa.CheckConstraint(
            "speed_mps IS NULL OR speed_mps >= 0", name=op.f("ck_location_points_nonnegative_speed")
        ),
        sa.CheckConstraint(
            "recorded_at_ms >= 0", name=op.f("ck_location_points_nonnegative_recorded_at")
        ),
        sa.CheckConstraint(
            "created_at_ms >= 0", name=op.f("ck_location_points_nonnegative_created_at")
        ),
        sa.CheckConstraint(
            "source = 'android_fused_location'", name=op.f("ck_location_points_source")
        ),
        sa.ForeignKeyConstraint(
            ["device_id"],
            ["devices.id"],
            name=op.f("fk_location_points_device_id_devices"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_location_points")),
    )
    op.create_index("idx_location_points_recorded", "location_points", ["recorded_at_ms", "id"])
    op.create_index(
        "idx_location_points_device_recorded",
        "location_points",
        ["device_id", "recorded_at_ms", "id"],
    )

    op.create_table(
        "place_visits",
        sa.Column("id", sa.String(length=26), nullable=False),
        sa.Column("device_id", sa.String(length=26), nullable=False),
        sa.Column("started_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("ended_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("duration_ms", sa.BigInteger(), nullable=False),
        sa.Column("center_latitude", sa.Float(), nullable=False),
        sa.Column("center_longitude", sa.Float(), nullable=False),
        sa.Column("radius_m", sa.Float(), nullable=False),
        sa.Column("point_count", sa.BigInteger(), nullable=False),
        sa.Column("algorithm_version", sa.String(length=50), nullable=False),
        sa.Column("source_first_point_id", sa.String(length=26), nullable=False),
        sa.Column("source_last_point_id", sa.String(length=26), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.CheckConstraint(
            "ended_at_ms > started_at_ms", name=op.f("ck_place_visits_ended_after_started")
        ),
        sa.CheckConstraint(
            "duration_ms = ended_at_ms - started_at_ms",
            name=op.f("ck_place_visits_duration_matches_range"),
        ),
        sa.CheckConstraint("radius_m >= 0", name=op.f("ck_place_visits_nonnegative_radius")),
        sa.CheckConstraint("point_count >= 3", name=op.f("ck_place_visits_minimum_point_count")),
        sa.CheckConstraint(
            "algorithm_version = 'stay_point_v1'", name=op.f("ck_place_visits_algorithm_version")
        ),
        sa.ForeignKeyConstraint(
            ["device_id"],
            ["devices.id"],
            name=op.f("fk_place_visits_device_id_devices"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["source_first_point_id"],
            ["location_points.id"],
            name=op.f("fk_place_visits_source_first_point_id_location_points"),
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["source_last_point_id"],
            ["location_points.id"],
            name=op.f("fk_place_visits_source_last_point_id_location_points"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_place_visits")),
        sa.UniqueConstraint(
            "device_id",
            "algorithm_version",
            "source_first_point_id",
            "source_last_point_id",
            name="uq_place_visits_source_range",
        ),
    )
    op.create_index(
        "idx_place_visits_range", "place_visits", ["started_at_ms", "ended_at_ms", "id"]
    )
    op.create_index(
        "idx_place_visits_device_started", "place_visits", ["device_id", "started_at_ms", "id"]
    )


def downgrade() -> None:
    op.drop_index("idx_place_visits_device_started", table_name="place_visits")
    op.drop_index("idx_place_visits_range", table_name="place_visits")
    op.drop_table("place_visits")
    op.drop_index("idx_location_points_device_recorded", table_name="location_points")
    op.drop_index("idx_location_points_recorded", table_name="location_points")
    op.drop_table("location_points")
