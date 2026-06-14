"""add cardio_sessions table

Persists user-performed cardio runs (Couch to 5K guided days, Free Runs). The
program *definitions* ship static client-side; only session records are stored
here so they can drive Resume / history / completion dates and later feed the AI
coach.

Revision ID: 0010
Revises: 0009
Create Date: 2026-06-14
"""

import sqlalchemy as sa
from alembic import op

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "cardio_sessions",
        sa.Column("id", sa.UUID(), primary_key=True, nullable=False),
        sa.Column(
            "user_id",
            sa.UUID(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("program_id", sa.String(50), nullable=False),
        sa.Column("week_number", sa.Integer(), nullable=True),
        sa.Column("day_number", sa.Integer(), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("status", sa.String(20), nullable=False, server_default="in_progress"),
        sa.Column("total_elapsed_sec", sa.Integer(), nullable=False, server_default="0"),
    )
    op.create_index("ix_cardio_sessions_user_id", "cardio_sessions", ["user_id"])


def downgrade() -> None:
    op.drop_index("ix_cardio_sessions_user_id", table_name="cardio_sessions")
    op.drop_table("cardio_sessions")
