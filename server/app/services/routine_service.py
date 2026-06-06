import uuid

from fastapi import HTTPException, status
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.routine_exercise import RoutineExercise
from app.models.workout_routine import WorkoutRoutine
from app.schemas.routine import RoutineCreate, RoutineExerciseOut, RoutineExercisesUpdate, RoutineOut, RoutineUpdate


def _routine_to_out(routine: WorkoutRoutine) -> RoutineOut:
    exercises = [
        RoutineExerciseOut(
            id=re.id,
            exercise_id=re.exercise_id,
            target_sets=re.target_sets,
            target_reps=re.target_reps,
            target_weight=re.target_weight,
            is_bodyweight=re.is_bodyweight,
            order=re.order,
            superset_group=re.superset_group,
            exercise_name=re.exercise.name if re.exercise else None,
        )
        for re in routine.routine_exercises
    ]
    return RoutineOut(
        id=routine.id,
        user_id=routine.user_id,
        name=routine.name,
        source=routine.source,
        created_at=routine.created_at,
        exercises=exercises,
    )


async def get_user_routines(db: AsyncSession, user_id: uuid.UUID) -> list[RoutineOut]:
    result = await db.execute(
        select(WorkoutRoutine)
        .where(WorkoutRoutine.user_id == user_id)
        .options(
            selectinload(WorkoutRoutine.routine_exercises).selectinload(RoutineExercise.exercise)
        )
        .order_by(WorkoutRoutine.created_at.desc())
    )
    routines = list(result.scalars().all())
    return [_routine_to_out(r) for r in routines]


async def create_routine(
    db: AsyncSession, user_id: uuid.UUID, req: RoutineCreate
) -> RoutineOut:
    routine = WorkoutRoutine(user_id=user_id, name=req.name, source=req.source)
    db.add(routine)
    await db.flush()
    for ex in req.exercises:
        re = RoutineExercise(routine_id=routine.id, **ex.model_dump())
        db.add(re)
    await db.commit()
    result = await db.execute(
        select(WorkoutRoutine)
        .where(WorkoutRoutine.id == routine.id)
        .options(
            selectinload(WorkoutRoutine.routine_exercises).selectinload(RoutineExercise.exercise)
        )
    )
    return _routine_to_out(result.scalar_one())


async def get_routine(
    db: AsyncSession, user_id: uuid.UUID, routine_id: uuid.UUID
) -> RoutineOut:
    result = await db.execute(
        select(WorkoutRoutine)
        .where(WorkoutRoutine.id == routine_id, WorkoutRoutine.user_id == user_id)
        .options(
            selectinload(WorkoutRoutine.routine_exercises).selectinload(RoutineExercise.exercise)
        )
    )
    routine = result.scalar_one_or_none()
    if not routine:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Routine not found")
    return _routine_to_out(routine)


async def delete_routine(
    db: AsyncSession, user_id: uuid.UUID, routine_id: uuid.UUID
) -> None:
    result = await db.execute(
        select(WorkoutRoutine).where(
            WorkoutRoutine.id == routine_id, WorkoutRoutine.user_id == user_id
        )
    )
    routine = result.scalar_one_or_none()
    if not routine:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Routine not found")
    await db.delete(routine)
    await db.commit()


async def rename_routine(
    db: AsyncSession, user_id: uuid.UUID, routine_id: uuid.UUID, req: RoutineUpdate
) -> RoutineOut:
    result = await db.execute(
        select(WorkoutRoutine)
        .where(WorkoutRoutine.id == routine_id, WorkoutRoutine.user_id == user_id)
        .options(
            selectinload(WorkoutRoutine.routine_exercises).selectinload(RoutineExercise.exercise)
        )
    )
    routine = result.scalar_one_or_none()
    if not routine:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Routine not found")
    routine.name = req.name
    await db.commit()
    await db.refresh(routine)
    result2 = await db.execute(
        select(WorkoutRoutine)
        .where(WorkoutRoutine.id == routine_id)
        .options(
            selectinload(WorkoutRoutine.routine_exercises).selectinload(RoutineExercise.exercise)
        )
    )
    return _routine_to_out(result2.scalar_one())


async def update_routine_exercises(
    db: AsyncSession,
    user_id: uuid.UUID,
    routine_id: uuid.UUID,
    req: RoutineExercisesUpdate,
) -> RoutineOut:
    result = await db.execute(
        select(WorkoutRoutine).where(
            WorkoutRoutine.id == routine_id, WorkoutRoutine.user_id == user_id
        )
    )
    routine = result.scalar_one_or_none()
    if not routine:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Routine not found")

    await db.execute(delete(RoutineExercise).where(RoutineExercise.routine_id == routine.id))
    await db.flush()

    for ex in req.exercises:
        re = RoutineExercise(routine_id=routine.id, **ex.model_dump())
        db.add(re)

    await db.commit()

    result2 = await db.execute(
        select(WorkoutRoutine)
        .where(WorkoutRoutine.id == routine_id)
        .options(
            selectinload(WorkoutRoutine.routine_exercises).selectinload(RoutineExercise.exercise)
        )
    )
    return _routine_to_out(result2.scalar_one())
