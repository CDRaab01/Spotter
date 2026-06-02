"""add missing FK and composite indexes

Revision ID: 0004
Revises: 0003
Create Date: 2026-06-01
"""

from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # workout_sessions: user_id lookups + (user_id, date) for calendar/streak queries
    op.create_index("ix_workout_sessions_user_id", "workout_sessions", ["user_id"])
    op.create_index(
        "ix_workout_sessions_user_date",
        "workout_sessions",
        ["user_id", "date"],
    )

    # workout_plans: user_id lookups
    op.create_index("ix_workout_plans_user_id", "workout_plans", ["user_id"])

    # body_metrics: user_id lookups + (user_id, date) for ordered metric queries
    op.create_index("ix_body_metrics_user_id", "body_metrics", ["user_id"])
    op.create_index(
        "ix_body_metrics_user_date",
        "body_metrics",
        ["user_id", "date"],
    )

    # set_logs: session_id for set loading, exercise_id for progress queries
    op.create_index("ix_set_logs_session_id", "set_logs", ["session_id"])
    op.create_index("ix_set_logs_exercise_id", "set_logs", ["exercise_id"])
    # composite for the progress_service "completed sets per exercise" query
    op.create_index(
        "ix_set_logs_exercise_completed",
        "set_logs",
        ["exercise_id", "completed"],
    )


def downgrade() -> None:
    op.drop_index("ix_set_logs_exercise_completed", table_name="set_logs")
    op.drop_index("ix_set_logs_exercise_id", table_name="set_logs")
    op.drop_index("ix_set_logs_session_id", table_name="set_logs")
    op.drop_index("ix_body_metrics_user_date", table_name="body_metrics")
    op.drop_index("ix_body_metrics_user_id", table_name="body_metrics")
    op.drop_index("ix_workout_plans_user_id", table_name="workout_plans")
    op.drop_index("ix_workout_sessions_user_date", table_name="workout_sessions")
    op.drop_index("ix_workout_sessions_user_id", table_name="workout_sessions")
