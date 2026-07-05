import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.exercise import Exercise
from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.progression import estimate_1rm as _epley_1rm
from app.schemas.progress import (
    ExerciseProgressPoint,
    PersonalRecord,
    TrackedExercise,
)


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


async def get_personal_records(
    db: AsyncSession, user_id: uuid.UUID
) -> list[PersonalRecord]:
    """Per-exercise personal records from completed, weighted sets.

    Tracks the heaviest weight (with the reps and date it was hit), the best
    estimated 1RM, and the best single-set volume (reps x weight). Bodyweight
    sets (no weight) are skipped since they have no load-based PR. Aggregated in
    Python over the user's completed sets, which is ample for personal-use data.
    """
    result = await db.execute(
        select(
            SetLog.exercise_id,
            Exercise.name,
            SetLog.reps,
            SetLog.weight,
            WorkoutSession.date,
        )
        .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
        .join(Exercise, SetLog.exercise_id == Exercise.id)
        .where(
            WorkoutSession.user_id == user_id,
            SetLog.completed == True,  # noqa: E712
            SetLog.weight.is_not(None),
        )
        .order_by(Exercise.name)
    )

    records: dict[uuid.UUID, dict] = {}
    for exercise_id, name, reps, weight, date in result:
        est = _epley_1rm(weight, reps)
        volume = weight * reps
        rec = records.get(exercise_id)
        if rec is None:
            records[exercise_id] = {
                "exercise_id": exercise_id,
                "exercise_name": name,
                "max_weight": weight,
                "max_weight_reps": reps,
                "best_est_1rm": est,
                "best_volume": volume,
                "achieved_on": date,
            }
            continue
        if weight > rec["max_weight"]:
            rec["max_weight"] = weight
            rec["max_weight_reps"] = reps
            rec["achieved_on"] = date
        if est > rec["best_est_1rm"]:
            rec["best_est_1rm"] = est
        if volume > rec["best_volume"]:
            rec["best_volume"] = volume

    return [
        PersonalRecord(
            exercise_id=r["exercise_id"],
            exercise_name=r["exercise_name"],
            max_weight=r["max_weight"],
            max_weight_reps=r["max_weight_reps"],
            best_est_1rm=round(r["best_est_1rm"], 1),
            best_volume=round(r["best_volume"], 1),
            achieved_on=r["achieved_on"],
        )
        for r in records.values()
    ]
