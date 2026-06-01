"""add workout_programs, program_days tables and superset_group column

Revision ID: 0005
Revises: 0004
Create Date: 2026-06-01
"""

import uuid

import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "workout_programs",
        sa.Column("id", sa.UUID(), primary_key=True, nullable=False),
        sa.Column(
            "user_id",
            sa.UUID(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default="false"),
    )
    op.create_index("ix_workout_programs_user_id", "workout_programs", ["user_id"])

    op.create_table(
        "program_days",
        sa.Column("id", sa.UUID(), primary_key=True, nullable=False),
        sa.Column(
            "program_id",
            sa.UUID(),
            sa.ForeignKey("workout_programs.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "plan_id",
            sa.UUID(),
            sa.ForeignKey("workout_plans.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("label", sa.String(100), nullable=False),
        sa.Column("order", sa.Integer(), nullable=False, server_default="0"),
    )
    op.create_index("ix_program_days_program_id", "program_days", ["program_id"])

    op.add_column(
        "planned_exercises",
        sa.Column("superset_group", sa.Integer(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("planned_exercises", "superset_group")
    op.drop_index("ix_program_days_program_id", table_name="program_days")
    op.drop_table("program_days")
    op.drop_index("ix_workout_programs_user_id", table_name="workout_programs")
    op.drop_table("workout_programs")
