"""cardio manual entry: activity_type + distance_meters

Adds two nullable columns to cardio_sessions so a user can log a walk/run after the
fact (a "manual" completed session). Both are null for existing guided/free live runs:
``activity_type`` ("walk"|"run") distinguishes manual entries, ``distance_meters`` carries
an optional canonical distance (meters). Additive — no data change to existing rows.

Revision ID: 0012
Revises: 0011
Create Date: 2026-07-16
"""

import sqlalchemy as sa
from alembic import op

revision = "0012"
down_revision = "0011"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "cardio_sessions", sa.Column("activity_type", sa.String(10), nullable=True)
    )
    op.add_column(
        "cardio_sessions", sa.Column("distance_meters", sa.Integer(), nullable=True)
    )


def downgrade() -> None:
    op.drop_column("cardio_sessions", "distance_meters")
    op.drop_column("cardio_sessions", "activity_type")
