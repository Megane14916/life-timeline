"""Create the initial normalized PC schema.

Revision ID: 0001_initial_normalized_schema
Revises:
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0001_initial_normalized_schema"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "categories",
        sa.Column("id", sa.String(length=26), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_categories")),
    )
    op.create_table(
        "devices",
        sa.Column("id", sa.String(length=26), nullable=False),
        sa.Column("name", sa.String(length=200), nullable=False),
        sa.Column("platform", sa.String(length=20), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("last_seen_at_ms", sa.BigInteger(), nullable=True),
        sa.CheckConstraint("platform IN ('android', 'windows')", name=op.f("ck_devices_platform")),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_devices")),
    )
    op.create_table(
        "apps",
        sa.Column("id", sa.String(length=26), nullable=False),
        sa.Column("platform", sa.String(length=20), nullable=False),
        sa.Column("identifier", sa.String(length=255), nullable=False),
        sa.Column("display_name", sa.String(length=255), nullable=False),
        sa.Column("category_id", sa.String(length=26), nullable=True),
        sa.Column("icon_path", sa.String(length=1000), nullable=True),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.CheckConstraint("platform IN ('android', 'windows')", name=op.f("ck_apps_platform")),
        sa.ForeignKeyConstraint(
            ["category_id"],
            ["categories.id"],
            name=op.f("fk_apps_category_id_categories"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_apps")),
        sa.UniqueConstraint("platform", "identifier", name="uq_apps_platform_identifier"),
    )
    op.create_table(
        "app_sessions",
        sa.Column("id", sa.String(length=26), nullable=False),
        sa.Column("device_id", sa.String(length=26), nullable=False),
        sa.Column("app_id", sa.String(length=26), nullable=False),
        sa.Column("started_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("ended_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("duration_ms", sa.BigInteger(), nullable=False),
        sa.Column("source", sa.String(length=100), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.CheckConstraint(
            "ended_at_ms > started_at_ms", name=op.f("ck_app_sessions_ended_after_started")
        ),
        sa.CheckConstraint(
            "duration_ms = ended_at_ms - started_at_ms",
            name=op.f("ck_app_sessions_duration_matches_range"),
        ),
        sa.ForeignKeyConstraint(
            ["app_id"], ["apps.id"], name=op.f("fk_app_sessions_app_id_apps"), ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["device_id"],
            ["devices.id"],
            name=op.f("fk_app_sessions_device_id_devices"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_app_sessions")),
    )
    op.create_index("idx_app_sessions_started", "app_sessions", ["started_at_ms"], unique=False)


def downgrade() -> None:
    op.drop_index("idx_app_sessions_started", table_name="app_sessions")
    op.drop_table("app_sessions")
    op.drop_table("apps")
    op.drop_table("devices")
    op.drop_table("categories")
