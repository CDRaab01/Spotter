import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.exercise import Exercise
from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.schemas.progress import ExerciseProgressPoint, TrackedExercise


async def get_tracked_exercises(
    db: AsyncSession, user_id: uuid.UUID
) -> list[TrackedExercise]:
    result = await db.execute(
        select(SetLog.exercise_id, Exercise.name)
        .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
        .join(Exercise, SetLog.exercise_id == Exercise.id)
        .where(WorkoutSession.user_id == user_id, SetLog.completed == True)  # noqa: E712
        .distinct()
        .order_by(Exercise.name)
    )
    return [TrackedExercise(exercise_id=row[0], exercise_name=row[1]) for row in result]


async def get_exercise_progress(
    db: AsyncSession, user_id: uuid.UUID, exercise_id: uuid.UUID
) -> list[ExerciseProgressPoint]:
    result = await db.execute(
        select(
            WorkoutSession.date,
            func.max(SetLog.weight).label("max_weight"),
            func.max(SetLog.reps).label("max_reps"),
        )
        .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
        .where(
            WorkoutSession.user_id == user_id,
            SetLog.exercise_id == exercise_id,
            SetLog.completed == True,  # noqa: E712
        )
        .group_by(WorkoutSession.date)
        .order_by(WorkoutSession.date)
    )
    return [
        ExerciseProgressPoint(date=row[0], max_weight=row[1], max_reps=row[2])
        for row in result
    ]
