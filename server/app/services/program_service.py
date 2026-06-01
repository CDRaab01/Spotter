import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.program_day import ProgramDay
from app.models.workout_plan import WorkoutPlan
from app.models.workout_program import WorkoutProgram
from app.models.workout_session import WorkoutSession
from app.schemas.program import (
    ProgramCreate,
    ProgramDayOut,
    ProgramDaysUpdate,
    ProgramOut,
    ProgramUpdate,
)


async def _to_out(program: WorkoutProgram) -> ProgramOut:
    day_outs = []
    for day in program.days:
        plan_name: str | None = None
        if hasattr(day, "plan") and day.plan is not None:
            try:
                plan_name = day.plan.name
            except Exception:
                pass
        day_outs.append(
            ProgramDayOut(
                id=day.id,
                plan_id=day.plan_id,
                label=day.label,
                order=day.order,
                plan_name=plan_name,
            )
        )
    return ProgramOut(
        id=program.id,
        name=program.name,
        is_active=program.is_active,
        days=day_outs,
    )


async def list_programs(db: AsyncSession, user_id: uuid.UUID) -> list[ProgramOut]:
    result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.user_id == user_id)
        .options(selectinload(WorkoutProgram.days).selectinload(ProgramDay.plan))
    )
    return [await _to_out(p) for p in result.scalars().all()]


async def create_program(
    db: AsyncSession, user_id: uuid.UUID, req: ProgramCreate
) -> ProgramOut:
    program = WorkoutProgram(user_id=user_id, name=req.name)
    db.add(program)
    await db.flush()
    for d in req.days:
        db.add(ProgramDay(program_id=program.id, **d.model_dump()))
    await db.commit()
    return await get_program(db, user_id, program.id)


async def get_program(
    db: AsyncSession, user_id: uuid.UUID, program_id: uuid.UUID
) -> ProgramOut:
    result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.id == program_id, WorkoutProgram.user_id == user_id)
        .options(selectinload(WorkoutProgram.days).selectinload(ProgramDay.plan))
    )
    program = result.scalar_one_or_none()
    if not program:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Program not found")
    return await _to_out(program)


async def update_program(
    db: AsyncSession, user_id: uuid.UUID, program_id: uuid.UUID, req: ProgramUpdate
) -> ProgramOut:
    result = await db.execute(
        select(WorkoutProgram).where(
            WorkoutProgram.id == program_id, WorkoutProgram.user_id == user_id
        )
    )
    program = result.scalar_one_or_none()
    if not program:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Program not found")

    if req.name is not None:
        program.name = req.name
    if req.is_active is not None:
        if req.is_active:
            # Deactivate all other programs for this user first
            all_result = await db.execute(
                select(WorkoutProgram).where(
                    WorkoutProgram.user_id == user_id,
                    WorkoutProgram.is_active == True,  # noqa: E712
                )
            )
            for p in all_result.scalars().all():
                p.is_active = False
        program.is_active = req.is_active

    await db.commit()
    return await get_program(db, user_id, program_id)


async def delete_program(
    db: AsyncSession, user_id: uuid.UUID, program_id: uuid.UUID
) -> None:
    result = await db.execute(
        select(WorkoutProgram).where(
            WorkoutProgram.id == program_id, WorkoutProgram.user_id == user_id
        )
    )
    program = result.scalar_one_or_none()
    if not program:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Program not found")
    await db.delete(program)
    await db.commit()


async def replace_days(
    db: AsyncSession, user_id: uuid.UUID, program_id: uuid.UUID, req: ProgramDaysUpdate
) -> ProgramOut:
    result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.id == program_id, WorkoutProgram.user_id == user_id)
        .options(selectinload(WorkoutProgram.days))
    )
    program = result.scalar_one_or_none()
    if not program:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Program not found")

    for day in list(program.days):
        await db.delete(day)
    await db.flush()

    for d in req.days:
        db.add(ProgramDay(program_id=program_id, **d.model_dump()))

    await db.commit()
    return await get_program(db, user_id, program_id)


async def get_next_day(
    db: AsyncSession, user_id: uuid.UUID
) -> ProgramDayOut | None:
    active_result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.user_id == user_id, WorkoutProgram.is_active == True)  # noqa: E712
        .options(selectinload(WorkoutProgram.days).selectinload(ProgramDay.plan))
    )
    program = active_result.scalar_one_or_none()
    if not program or not program.days:
        return None

    # Find last completed session and which program day it corresponds to
    last_session_result = await db.execute(
        select(WorkoutSession)
        .where(WorkoutSession.user_id == user_id, WorkoutSession.status == "completed")
        .order_by(WorkoutSession.date.desc())
        .limit(1)
    )
    last_session = last_session_result.scalar_one_or_none()

    days = sorted(program.days, key=lambda d: d.order)
    if not last_session or not last_session.plan_id:
        next_day = days[0]
    else:
        matching_index = next(
            (i for i, d in enumerate(days) if d.plan_id == last_session.plan_id),
            None,
        )
        if matching_index is None:
            next_day = days[0]
        else:
            next_day = days[(matching_index + 1) % len(days)]

    plan_name: str | None = None
    if next_day.plan:
        plan_name = next_day.plan.name

    return ProgramDayOut(
        id=next_day.id,
        plan_id=next_day.plan_id,
        label=next_day.label,
        order=next_day.order,
        plan_name=plan_name,
    )
