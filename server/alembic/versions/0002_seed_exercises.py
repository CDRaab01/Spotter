"""seed exercises

Revision ID: 0002
Revises: 0001
Create Date: 2026-06-01
"""

import uuid

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None

EXERCISES = [
    # Barbell
    ("Barbell Back Squat", "legs", "barbell"),
    ("Barbell Front Squat", "legs", "barbell"),
    ("Conventional Deadlift", "back", "barbell"),
    ("Romanian Deadlift", "hamstrings", "barbell"),
    ("Bench Press", "chest", "barbell"),
    ("Incline Bench Press", "chest", "barbell"),
    ("Overhead Press", "shoulders", "barbell"),
    ("Barbell Row", "back", "barbell"),
    ("Barbell Curl", "biceps", "barbell"),
    ("Close-Grip Bench Press", "triceps", "barbell"),
    ("Good Morning", "hamstrings", "barbell"),
    ("Rack Pull", "back", "barbell"),
    # Dumbbell
    ("Dumbbell Bench Press", "chest", "dumbbell"),
    ("Dumbbell Row", "back", "dumbbell"),
    ("Dumbbell Shoulder Press", "shoulders", "dumbbell"),
    ("Dumbbell Romanian Deadlift", "hamstrings", "dumbbell"),
    ("Dumbbell Curl", "biceps", "dumbbell"),
    ("Dumbbell Lateral Raise", "shoulders", "dumbbell"),
    ("Dumbbell Overhead Tricep Extension", "triceps", "dumbbell"),
    ("Goblet Squat", "legs", "dumbbell"),
    ("Dumbbell Reverse Lunge", "legs", "dumbbell"),
    # Bodyweight
    ("Push-Up", "chest", "bodyweight"),
    ("Pull-Up", "back", "bodyweight"),
    ("Dip", "triceps", "bodyweight"),
    ("Bodyweight Squat", "legs", "bodyweight"),
    ("Lunge", "legs", "bodyweight"),
    ("Glute Bridge", "glutes", "bodyweight"),
    ("Plank", "core", "bodyweight"),
    ("Hollow Hold", "core", "bodyweight"),
    ("Mountain Climber", "core", "bodyweight"),
    # Cable / Machine
    ("Lat Pulldown", "back", "cable"),
    ("Seated Cable Row", "back", "cable"),
    ("Cable Curl", "biceps", "cable"),
    ("Leg Press", "legs", "machine"),
    ("Leg Curl", "hamstrings", "machine"),
    ("Leg Extension", "quads", "machine"),
]


def upgrade() -> None:
    conn = op.get_bind()
    for name, muscle_group, equipment in EXERCISES:
        conn.execute(
            sa.text(
                "INSERT INTO exercises (id, name, muscle_group, equipment) "
                "VALUES (:id, :name, :muscle_group, :equipment) "
                "ON CONFLICT (name) DO NOTHING"
            ),
            {
                "id": str(uuid.uuid4()),
                "name": name,
                "muscle_group": muscle_group,
                "equipment": equipment,
            },
        )


def downgrade() -> None:
    conn = op.get_bind()
    names = [name for name, _, _ in EXERCISES]
    conn.execute(
        sa.text("DELETE FROM exercises WHERE name = ANY(:names)"),
        {"names": names},
    )
