"""seed accessory exercises

Expands the exercise library with the accessory/isolation work a realistic
session needs — direct arm work, side/rear delts, calves, and abs, plus more
chest/back/leg accessories. The original 0002 seed was compound-heavy, which
left the AI coach with almost nothing to fill out a 4-6 lift day (and any
accessory it named off-catalog was silently dropped at resolution). With these
seeded, full Push/Pull/Legs days resolve instead of collapsing to 2-3 lifts.

Revision ID: 0009
Revises: 0008
Create Date: 2026-06-08
"""

import uuid

import sqlalchemy as sa
from alembic import op

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None

EXERCISES = [
    # Chest accessories
    ("Decline Bench Press", "chest", "barbell"),
    ("Dumbbell Incline Press", "chest", "dumbbell"),
    ("Dumbbell Fly", "chest", "dumbbell"),
    ("Cable Crossover", "chest", "cable"),
    ("Pec Deck", "chest", "machine"),
    # Back accessories
    ("Chin-Up", "back", "bodyweight"),
    ("Inverted Row", "back", "bodyweight"),
    ("T-Bar Row", "back", "barbell"),
    ("Chest-Supported Row", "back", "dumbbell"),
    ("Straight-Arm Pulldown", "back", "cable"),
    ("Dumbbell Shrug", "back", "dumbbell"),
    # Shoulder accessories
    ("Arnold Press", "shoulders", "dumbbell"),
    ("Dumbbell Front Raise", "shoulders", "dumbbell"),
    ("Cable Lateral Raise", "shoulders", "cable"),
    ("Rear Delt Fly", "shoulders", "dumbbell"),
    ("Face Pull", "shoulders", "cable"),
    ("Upright Row", "shoulders", "barbell"),
    ("Pike Push-Up", "shoulders", "bodyweight"),
    # Biceps
    ("Hammer Curl", "biceps", "dumbbell"),
    ("Incline Dumbbell Curl", "biceps", "dumbbell"),
    ("Preacher Curl", "biceps", "barbell"),
    ("Concentration Curl", "biceps", "dumbbell"),
    # Triceps
    ("Tricep Pushdown", "triceps", "cable"),
    ("Overhead Cable Tricep Extension", "triceps", "cable"),
    ("Skull Crusher", "triceps", "barbell"),
    ("Tricep Kickback", "triceps", "dumbbell"),
    ("Bench Dip", "triceps", "bodyweight"),
    # Legs / glutes
    ("Bulgarian Split Squat", "legs", "dumbbell"),
    ("Walking Lunge", "legs", "dumbbell"),
    ("Step-Up", "legs", "dumbbell"),
    ("Hack Squat", "legs", "machine"),
    ("Box Squat", "legs", "barbell"),
    ("Hip Thrust", "glutes", "barbell"),
    ("Cable Glute Kickback", "glutes", "cable"),
    # Hamstrings
    ("Seated Leg Curl", "hamstrings", "machine"),
    ("Nordic Curl", "hamstrings", "bodyweight"),
    # Calves
    ("Standing Calf Raise", "calves", "machine"),
    ("Seated Calf Raise", "calves", "machine"),
    ("Dumbbell Calf Raise", "calves", "dumbbell"),
    # Core / abs
    ("Hanging Leg Raise", "core", "bodyweight"),
    ("Cable Crunch", "core", "cable"),
    ("Russian Twist", "core", "bodyweight"),
    ("Bicycle Crunch", "core", "bodyweight"),
    ("Crunch", "core", "bodyweight"),
    ("Ab Wheel Rollout", "core", "bodyweight"),
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
