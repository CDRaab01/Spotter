import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.planned_exercise import PlannedExercise
from app.models.workout_plan import WorkoutPlan
from app.schemas.plan import PlanCreate


async def get_user_plans(db: AsyncSession, user_id: uuid.UUID) -> list[WorkoutPlan]:
    result = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.user_id == user_id)
        .options(selectinload(WorkoutPlan.planned_exercises))
        .order_by(WorkoutPlan.created_at.desc())
    )
    return list(result.scalars().all())


async def create_plan(
    db: AsyncSession, user_id: uuid.UUID, req: PlanCreate
) -> WorkoutPlan:
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
        .options(selectinload(WorkoutPlan.planned_exercises))
    )
    return result.scalar_one()


async def get_plan(
    db: AsyncSession, user_id: uuid.UUID, plan_id: uuid.UUID
) -> WorkoutPlan:
    result = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.id == plan_id, WorkoutPlan.user_id == user_id)
        .options(selectinload(WorkoutPlan.planned_exercises))
    )
    plan = result.scalar_one_or_none()
    if not plan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Plan not found")
    return plan
