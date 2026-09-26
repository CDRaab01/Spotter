"""equipment inventory on users — the weights the user can actually load

The progression engine suggested loads in abstract pounds (+2.5 lb upper body, a 10 % deload,
seeds floored to 2.5), so it routinely proposed weights nobody could put on the bar: 117.5 lb on a
45 lb bar needs 1.25 lb plates. This stores what the user owns — bars, plate pairs, dumbbells and
the machine/cable stack step, in the inventory's own unit — so every suggested load can be snapped
to one their equipment makes (``app/loading.py``).

One nullable JSON column rather than tables: it is always read and written whole, never queried
into, and NULL cleanly means "never set" (the server then assumes a standard commercial gym).

Revision ID: 0017
Revises: 0016
Create Date: 2026-09-25
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSON

revision = "0017"
down_revision = "0016"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("users", sa.Column("equipment_inventory", JSON(), nullable=True))


def downgrade() -> None:
    op.drop_column("users", "equipment_inventory")
