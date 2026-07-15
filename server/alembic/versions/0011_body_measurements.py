"""body measurements (tape measurements beyond weight)

Adds the standard competitor tape-measurement set (neck/chest/waist/hips/arm/thigh) to
body_metrics as nullable columns, so a check-in can carry measurements alongside the weigh-in.
All optional — an ordinary weigh-in leaves them null.

Revision ID: 0011
Revises: 0010
Create Date: 2026-07-15
"""

import sqlalchemy as sa
from alembic import op

revision = "0011"
down_revision = "0010"
branch_labels = None
depends_on = None

_COLS = ["neck", "chest", "waist", "hips", "arm", "thigh"]


def upgrade() -> None:
    for col in _COLS:
        op.add_column("body_metrics", sa.Column(col, sa.Float(), nullable=True))


def downgrade() -> None:
    for col in _COLS:
        op.drop_column("body_metrics", col)
