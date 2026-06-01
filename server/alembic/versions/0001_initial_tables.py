"""initial tables

Revision ID: 0001
Revises:
Create Date: 2026-06-01

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSON, UUID

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("email", sa.String(255), nullable=False),
        sa.Column("hashed_password", sa.String(255), nullable=False),
        sa.Column("settings", sa.Text(), nullable=True),
    )
    op.create_index("ix_users_email", "users", ["email"], unique=True)

    op.create_table(
        "exercises",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("muscle_group", sa.String(100), nullable=True),
        sa.Column("equipment", sa.String(100), nullable=True),
    )
    op.create_index("ix_exercises_name", "exercises", ["name"], unique=True)

    op.create_table(
        "workout_plans",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("source", sa.String(20), nullable=False, server_default="manual"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
        ),
    )

    op.create_table(
        "planned_exercises",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "plan_id",
            UUID(as_uuid=True),
            sa.ForeignKey("workout_plans.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "exercise_id",
            UUID(as_uuid=True),
            sa.ForeignKey("exercises.id"),
            nullable=False,
        ),
        sa.Column("target_sets", sa.Integer(), nullable=False),
        sa.Column("target_reps", sa.Integer(), nullable=False),
        sa.Column("target_weight", sa.Float(), nullable=True),
        sa.Column(
            "is_bodyweight", sa.Boolean(), nullable=False, server_default="false"
        ),
        sa.Column("order", sa.Integer(), nullable=False, server_default="0"),
    )

    op.create_table(
        "workout_sessions",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "plan_id",
            UUID(as_uuid=True),
            sa.ForeignKey("workout_plans.id"),
            nullable=True,
        ),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column(
            "status", sa.String(20), nullable=False, server_default="in_progress"
        ),
        sa.Column("duration_seconds", sa.Integer(), nullable=True),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("exercise_notes", JSON(), nullable=True),
    )

    op.create_table(
        "set_logs",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "session_id",
            UUID(as_uuid=True),
            sa.ForeignKey("workout_sessions.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "exercise_id",
            UUID(as_uuid=True),
            sa.ForeignKey("exercises.id"),
            nullable=False,
        ),
        sa.Column("set_number", sa.Integer(), nullable=False),
        sa.Column("reps", sa.Integer(), nullable=False),
        sa.Column("weight", sa.Float(), nullable=True),
        sa.Column("completed", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
    )

    op.create_table(
        "body_metrics",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("weight", sa.Float(), nullable=False),
        sa.Column("bodyfat", sa.Float(), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("body_metrics")
    op.drop_table("set_logs")
    op.drop_table("workout_sessions")
    op.drop_table("planned_exercises")
    op.drop_table("workout_plans")
    op.drop_index("ix_exercises_name", "exercises")
    op.drop_table("exercises")
    op.drop_index("ix_users_email", "users")
    op.drop_table("users")
