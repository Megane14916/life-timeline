"""Add ActivityWatch session details and import state.

Revision ID: 0004_activitywatch
Revises: 0003_locations
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0004_activitywatch"
down_revision: str | None = "0003_locations"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "desktop_session_details",
        sa.Column("session_id", sa.String(length=26), nullable=False),
        sa.Column("window_title", sa.String(length=500), nullable=True),
        sa.Column("url", sa.String(length=2048), nullable=True),
        sa.Column("source_event_id", sa.String(length=255), nullable=False),
        sa.Column("algorithm_version", sa.String(length=50), nullable=False),
        sa.Column("privacy_mode", sa.String(length=20), nullable=False),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.CheckConstraint(
            "algorithm_version = 'activitywatch_session_v1'",
            name=op.f("ck_desktop_session_details_algorithm_version"),
        ),
        sa.CheckConstraint(
            "privacy_mode IN ('app_only', 'titles', 'web')",
            name=op.f("ck_desktop_session_details_privacy_mode"),
        ),
        sa.ForeignKeyConstraint(
            ["session_id"],
            ["app_sessions.id"],
            name=op.f("fk_desktop_session_details_session_id_app_sessions"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("session_id", name=op.f("pk_desktop_session_details")),
    )
    op.create_index(
        "idx_desktop_session_details_source_event",
        "desktop_session_details",
        ["source_event_id"],
    )

    op.create_table(
        "activitywatch_import_states",
        sa.Column("source_key", sa.String(length=128), nullable=False),
        sa.Column("device_id", sa.String(length=26), nullable=False),
        sa.Column("algorithm_version", sa.String(length=50), nullable=False),
        sa.Column("privacy_mode", sa.String(length=20), nullable=False),
        sa.Column("completed_through_ms", sa.BigInteger(), nullable=True),
        sa.Column("last_attempt_at_ms", sa.BigInteger(), nullable=True),
        sa.Column("last_success_at_ms", sa.BigInteger(), nullable=True),
        sa.Column("last_result_code", sa.String(length=50), nullable=True),
        sa.Column("consecutive_failures", sa.BigInteger(), nullable=False, server_default="0"),
        sa.Column("next_eligible_at_ms", sa.BigInteger(), nullable=True),
        sa.Column("lease_token", sa.String(length=128), nullable=True),
        sa.Column("lease_expires_at_ms", sa.BigInteger(), nullable=True),
        sa.Column("created_at_ms", sa.BigInteger(), nullable=False),
        sa.Column("updated_at_ms", sa.BigInteger(), nullable=False),
        sa.CheckConstraint(
            "algorithm_version = 'activitywatch_session_v1'",
            name=op.f("ck_activitywatch_import_states_algorithm_version"),
        ),
        sa.CheckConstraint(
            "privacy_mode IN ('app_only', 'titles', 'web')",
            name=op.f("ck_activitywatch_import_states_privacy_mode"),
        ),
        sa.CheckConstraint(
            "consecutive_failures >= 0",
            name=op.f("ck_activitywatch_import_states_nonnegative_failures"),
        ),
        sa.ForeignKeyConstraint(
            ["device_id"],
            ["devices.id"],
            name=op.f("fk_activitywatch_import_states_device_id_devices"),
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("source_key", name=op.f("pk_activitywatch_import_states")),
    )


def downgrade() -> None:
    op.drop_table("activitywatch_import_states")
    op.drop_index("idx_desktop_session_details_source_event", table_name="desktop_session_details")
    op.drop_table("desktop_session_details")
