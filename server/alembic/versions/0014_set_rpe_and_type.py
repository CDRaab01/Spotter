"""set logs: RPE + set type

Adds an optional per-set RPE (1-10, bounds in app/limits.py) and a set_type
discriminator (normal | warmup | drop | failure | amrap, server_default
'normal' so every existing row stays a working set). Warm-up sets are excluded
from volume, progression, and PR detection by the read paths.

Revision ID: 0014
Revises: 0013
Create Date: 2026-07-28
"""

import sqlalchemy as sa
from alembic import op

revision = "0014"
down_revision = "0013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("set_logs", sa.Column("rpe", sa.Float(), nullable=True))
    op.add_column(
        "set_logs",
        sa.Column("set_type", sa.String(16), nullable=False, server_default="normal"),
    )


def downgrade() -> None:
    op.drop_column("set_logs", "set_type")
    op.drop_column("set_logs", "rpe")
