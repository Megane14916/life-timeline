"""Add normalized media metadata and thumbnail attributes.

Revision ID: 0002_media_items
Revises: 0001_initial_normalized_schema
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0002_media_items"
down_revision: str | None = "0001_initial_normalized_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "media_items",
        sa.Column("id", sa.String(length=26), nullable=False),
        sa.Column("device_id", sa.String(length=26), nullable=False),
        sa.Column("type", sa.String(length=20), nullable=False),
        sa.Column("source", sa.String(length=100), nullable=False),
        sa.Column("source_id", sa.String(length=255), nullable=False),
        sa.Column("filename", sa.String(length=255), nullable=False),
        sa.Column("captured_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("width", sa.BigInteger(), nullable=True),
        sa.Column("height", sa.BigInteger(), nullable=True),
        sa.Column("duration_ms", sa.BigInteger(), nullable=True),
        sa.Column("latitude", sa.Float(), nullable=True),
        sa.Column("longitude", sa.Float(), nullable=True),
        sa.Column("thumbnail_path", sa.String(length=1000), nullable=True),
        sa.Column("thumbnail_mime_type", sa.String(length=100), nullable=True),
        sa.Column("thumbnail_width", sa.BigInteger(), nullable=True),
        sa.Column("thumbnail_height", sa.BigInteger(), nullable=True),
        sa.Column("thumbnail_size_bytes", sa.BigInteger(), nullable=True),
        sa.Column("thumbnail_sha256", sa.String(length=64), nullable=True),
        sa.Column("mime_type", sa.String(length=100), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.CheckConstraint("type IN ('photo', 'video')", name=op.f("ck_media_items_type")),
        sa.CheckConstraint(
            "(latitude IS NULL AND longitude IS NULL) OR "
            "(latitude IS NOT NULL AND longitude IS NOT NULL)",
            name=op.f("ck_media_items_location_pair"),
        ),
        sa.CheckConstraint(
            "(width IS NULL OR width > 0) AND (height IS NULL OR height > 0)",
            name=op.f("ck_media_items_positive_dimensions"),
        ),
        sa.CheckConstraint(
            "duration_ms IS NULL OR duration_ms > 0",
            name=op.f("ck_media_items_positive_duration"),
        ),
        sa.CheckConstraint(
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
            name=op.f("ck_media_items_thumbnail_fields_complete"),
        ),
        sa.CheckConstraint(
            "thumbnail_path IS NULL OR "
            "(substr(thumbnail_path, 1, 11) = 'thumbnails/' "
            "AND instr(thumbnail_path, char(92)) = 0 "
            "AND thumbnail_path NOT LIKE '%/..' "
            "AND thumbnail_path NOT LIKE '../%' "
            "AND thumbnail_path NOT LIKE '%/../%')",
            name=op.f("ck_media_items_thumbnail_relative_path"),
        ),
        sa.ForeignKeyConstraint(
            ["device_id"],
            ["devices.id"],
            name=op.f("fk_media_items_device_id_devices"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_media_items")),
        sa.UniqueConstraint(
            "device_id",
            "source",
            "source_id",
            name="uq_media_items_device_source_source_id",
        ),
    )
    op.create_index("idx_media_items_captured", "media_items", ["captured_at_ms"])


def downgrade() -> None:
    op.drop_index("idx_media_items_captured", table_name="media_items")
    op.drop_table("media_items")
