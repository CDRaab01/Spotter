"""Rename plan → routine throughout (tables, columns, constraints)

Revision ID: 0008
Revises: 0007
Create Date: 2026-06-06
"""

from alembic import op

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # 1. Drop the index on workout_plans before renaming the table.
    op.execute("DROP INDEX IF EXISTS ix_workout_plans_user_id")

    # 2. Drop FK constraints that reference workout_plans or carry the plan_id
    #    column name (use IF EXISTS — generated names may differ across envs).
    op.execute(
        "ALTER TABLE planned_exercises "
        "DROP CONSTRAINT IF EXISTS planned_exercises_plan_id_fkey"
    )
    op.execute(
        "ALTER TABLE program_days "
        "DROP CONSTRAINT IF EXISTS program_days_plan_id_fkey"
    )
    op.execute(
        "ALTER TABLE workout_sessions "
        "DROP CONSTRAINT IF EXISTS workout_sessions_plan_id_fkey"
    )

    # 3. Rename tables.
    op.execute("ALTER TABLE workout_plans RENAME TO workout_routines")
    op.execute("ALTER TABLE planned_exercises RENAME TO routine_exercises")

    # 4. Rename FK columns in child tables.
    op.execute("ALTER TABLE routine_exercises RENAME COLUMN plan_id TO routine_id")
    op.execute("ALTER TABLE program_days RENAME COLUMN plan_id TO routine_id")
    op.execute("ALTER TABLE workout_sessions RENAME COLUMN plan_id TO routine_id")

    # 5. Re-create FK constraints with new names.
    op.execute(
        "ALTER TABLE routine_exercises "
        "ADD CONSTRAINT routine_exercises_routine_id_fkey "
        "FOREIGN KEY (routine_id) REFERENCES workout_routines(id) ON DELETE CASCADE"
    )
    op.execute(
        "ALTER TABLE program_days "
        "ADD CONSTRAINT program_days_routine_id_fkey "
        "FOREIGN KEY (routine_id) REFERENCES workout_routines(id) ON DELETE SET NULL"
    )
    op.execute(
        "ALTER TABLE workout_sessions "
        "ADD CONSTRAINT workout_sessions_routine_id_fkey "
        "FOREIGN KEY (routine_id) REFERENCES workout_routines(id) ON DELETE SET NULL"
    )

    # 6. Re-create index with new table name.
    op.execute(
        "CREATE INDEX ix_workout_routines_user_id ON workout_routines (user_id)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_workout_routines_user_id")

    op.execute(
        "ALTER TABLE routine_exercises "
        "DROP CONSTRAINT IF EXISTS routine_exercises_routine_id_fkey"
    )
    op.execute(
        "ALTER TABLE program_days "
        "DROP CONSTRAINT IF EXISTS program_days_routine_id_fkey"
    )
    op.execute(
        "ALTER TABLE workout_sessions "
        "DROP CONSTRAINT IF EXISTS workout_sessions_routine_id_fkey"
    )

    op.execute("ALTER TABLE routine_exercises RENAME COLUMN routine_id TO plan_id")
    op.execute("ALTER TABLE program_days RENAME COLUMN routine_id TO plan_id")
    op.execute("ALTER TABLE workout_sessions RENAME COLUMN routine_id TO plan_id")

    op.execute("ALTER TABLE routine_exercises RENAME TO planned_exercises")
    op.execute("ALTER TABLE workout_routines RENAME TO workout_plans")

    op.execute(
        "ALTER TABLE planned_exercises "
        "ADD CONSTRAINT planned_exercises_plan_id_fkey "
        "FOREIGN KEY (plan_id) REFERENCES workout_plans(id) ON DELETE CASCADE"
    )
    op.execute(
        "ALTER TABLE program_days "
        "ADD CONSTRAINT program_days_plan_id_fkey "
        "FOREIGN KEY (plan_id) REFERENCES workout_plans(id) ON DELETE SET NULL"
    )
    op.execute(
        "ALTER TABLE workout_sessions "
        "ADD CONSTRAINT workout_sessions_plan_id_fkey "
        "FOREIGN KEY (plan_id) REFERENCES workout_plans(id) ON DELETE SET NULL"
    )

    op.execute(
        "CREATE INDEX ix_workout_plans_user_id ON workout_plans (user_id)"
    )
