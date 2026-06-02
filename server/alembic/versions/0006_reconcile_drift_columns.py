"""reconcile columns missing on DBs created outside the migration system

Some deployments had their schema bootstrapped with ``Base.metadata.create_all``
against an older model snapshot, so they never ran the migrations that introduced
``users.reset_token`` (0003), ``planned_exercises.superset_group`` (0005), or even
``workout_sessions.exercise_notes`` (added back in 0001). The latter is unreachable
by stamping at a later revision, so this migration idempotently ensures all of these
columns exist. It is a no-op on a DB built cleanly through ``alembic upgrade head``.

Revision ID: 0006
Revises: 0005
Create Date: 2026-06-02
"""

from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Postgres supports IF NOT EXISTS on ADD COLUMN, making this safe to run whether
    # or not the column is already present.
    op.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_token VARCHAR(6)")
    op.execute(
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS reset_token_expires_at TIMESTAMPTZ"
    )
    op.execute(
        "ALTER TABLE workout_sessions ADD COLUMN IF NOT EXISTS exercise_notes JSON"
    )
    op.execute(
        "ALTER TABLE planned_exercises ADD COLUMN IF NOT EXISTS superset_group INTEGER"
    )


def downgrade() -> None:
    # No-op: these columns are owned by their original migrations (0001/0003/0005);
    # dropping them here would corrupt the schema those revisions expect.
    pass
