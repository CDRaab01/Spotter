"""program periodization metadata + per-exercise rest

workout_programs grows program-level metadata (created_at, source, description)
and periodization (weeks = mesocycle length, deload_week = which 1-based week is
the scheduled deload, started_on = stamped on first activation to anchor the
week counter). routine_exercises gains an optional per-exercise rest_seconds.
All additive; existing rows get source 'manual' and nulls elsewhere.

Revision ID: 0015
Revises: 0014
Create Date: 2026-07-28
"""

import sqlalchemy as sa
from alembic import op

revision = "0015"
down_revision = "0014"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "workout_programs",
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=True,
            server_default=sa.func.now(),
        ),
    )
    op.add_column(
        "workout_programs",
        sa.Column("source", sa.String(16), nullable=False, server_default="manual"),
    )
    op.add_column("workout_programs", sa.Column("description", sa.Text(), nullable=True))
    op.add_column("workout_programs", sa.Column("weeks", sa.Integer(), nullable=True))
    op.add_column("workout_programs", sa.Column("deload_week", sa.Integer(), nullable=True))
    op.add_column("workout_programs", sa.Column("started_on", sa.Date(), nullable=True))

    op.add_column("routine_exercises", sa.Column("rest_seconds", sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column("routine_exercises", "rest_seconds")

    op.drop_column("workout_programs", "started_on")
    op.drop_column("workout_programs", "deload_week")
    op.drop_column("workout_programs", "weeks")
    op.drop_column("workout_programs", "description")
    op.drop_column("workout_programs", "source")
    op.drop_column("workout_programs", "created_at")
