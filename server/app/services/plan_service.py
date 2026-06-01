import uuid

from fastapi import HTTPException, status
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.planned_exercise import PlannedExercise
from app.models.workout_plan import WorkoutPlan
from app.schemas.plan import PlanCreate, PlanOut, PlanUpdate, PlannedExerciseOut, PlannedExercisesUpdate


def _plan_to_out(plan: WorkoutPlan) -> PlanOut:
    exercises = [
        PlannedExerciseOut(
            id=pe.id,
            exercise_id=pe.exercise_id,
            target_sets=pe.target_sets,
            target_reps=pe.target_reps,
            target_weight=pe.target_weight,
            is_bodyweight=pe.is_bodyweight,
            order=pe.order,
            exercise_name=pe.exercise.name if pe.exercise else None,
        )
        for pe in plan.planned_exercises
    ]
    return PlanOut(
        id=plan.id,
        user_id=plan.user_id,
        name=plan.name,
        source=plan.source,
        created_at=plan.created_at,
        exercises=exercises,
    )


async def get_user_plans(db: AsyncSession, user_id: uuid.UUID) -> list[PlanOut]:
    result = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.user_id == user_id)
        .options(
            selectinload(WorkoutPlan.planned_exercises).selectinload(PlannedExercise.exercise)
        )
        .order_by(WorkoutPlan.created_at.desc())
    )
    plans = list(result.scalars().all())
    return [_plan_to_out(p) for p in plans]


async def create_plan(
    db: AsyncSession, user_id: uuid.UUID, req: PlanCreate
) -> PlanOut:
    plan = WorkoutPlan(user_id=user_id, name=req.name, source=req.source)
    db.add(plan)
    await db.flush()
    for ex in req.exercises:
        pe = PlannedExercise(plan_id=plan.id, **ex.model_dump())
        db.add(pe)
    await db.commit()
    result = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.id == plan.id)
        .options(
            selectinload(WorkoutPlan.planned_exercises).selectinload(PlannedExercise.exercise)
        )
    )
    return _plan_to_out(result.scalar_one())


async def get_plan(
    db: AsyncSession, user_id: uuid.UUID, plan_id: uuid.UUID
) -> PlanOut:
    result = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.id == plan_id, WorkoutPlan.user_id == user_id)
        .options(
            selectinload(WorkoutPlan.planned_exercises).selectinload(PlannedExercise.exercise)
        )
    )
    plan = result.scalar_one_or_none()
    if not plan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Plan not found")
    return _plan_to_out(plan)


async def delete_plan(
    db: AsyncSession, user_id: uuid.UUID, plan_id: uuid.UUID
) -> None:
    result = await db.execute(
        select(WorkoutPlan).where(
            WorkoutPlan.id == plan_id, WorkoutPlan.user_id == user_id
        )
    )
    plan = result.scalar_one_or_none()
    if not plan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Plan not found")
    await db.delete(plan)
    await db.commit()


async def rename_plan(
    db: AsyncSession, user_id: uuid.UUID, plan_id: uuid.UUID, req: PlanUpdate
) -> PlanOut:
    result = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.id == plan_id, WorkoutPlan.user_id == user_id)
        .options(
            selectinload(WorkoutPlan.planned_exercises).selectinload(PlannedExercise.exercise)
        )
    )
    plan = result.scalar_one_or_none()
    if not plan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Plan not found")
    plan.name = req.name
    await db.commit()
    await db.refresh(plan)
    # Re-fetch to get eager-loaded exercises
    result2 = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.id == plan_id)
        .options(
            selectinload(WorkoutPlan.planned_exercises).selectinload(PlannedExercise.exercise)
        )
    )
    return _plan_to_out(result2.scalar_one())


async def update_plan_exercises(
    db: AsyncSession,
    user_id: uuid.UUID,
    plan_id: uuid.UUID,
    req: PlannedExercisesUpdate,
) -> PlanOut:
    result = await db.execute(
        select(WorkoutPlan).where(
            WorkoutPlan.id == plan_id, WorkoutPlan.user_id == user_id
        )
    )
    plan = result.scalar_one_or_none()
    if not plan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Plan not found")

    await db.execute(delete(PlannedExercise).where(PlannedExercise.plan_id == plan.id))
    await db.flush()

    for ex in req.exercises:
        pe = PlannedExercise(plan_id=plan.id, **ex.model_dump())
        db.add(pe)

    await db.commit()

    result2 = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.id == plan_id)
        .options(
            selectinload(WorkoutPlan.planned_exercises).selectinload(PlannedExercise.exercise)
        )
    )
    return _plan_to_out(result2.scalar_one())
