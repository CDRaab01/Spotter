import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.planned_exercise import PlannedExercise
from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.schemas.session import SessionCreate, SessionOut, SetLogCreate, SetLogOut


async def create_session(
    db: AsyncSession, user_id: uuid.UUID, req: SessionCreate
) -> SessionOut:
    session = WorkoutSession(user_id=user_id, **req.model_dump())
    db.add(session)
    await db.commit()
    result = await db.execute(
        select(WorkoutSession)
        .where(WorkoutSession.id == session.id)
        .options(selectinload(WorkoutSession.set_logs))
    )
    s = result.scalar_one()
    return SessionOut(
        id=s.id,
        user_id=s.user_id,
        plan_id=s.plan_id,
        date=s.date,
        status=s.status,
        duration_seconds=s.duration_seconds,
        note=s.note,
        set_logs=[],
    )


async def get_session(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> SessionOut:
    result = await db.execute(
        select(WorkoutSession)
        .where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
        .options(selectinload(WorkoutSession.set_logs))
    )
    s = result.scalar_one_or_none()
    if not s:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Session not found"
        )

    # Load planned exercise context to enrich set logs with names and targets
    exercise_context: dict[uuid.UUID, tuple] = {}
    if s.plan_id:
        pe_result = await db.execute(
            select(PlannedExercise)
            .where(PlannedExercise.plan_id == s.plan_id)
            .options(selectinload(PlannedExercise.exercise))
        )
        for pe in pe_result.scalars().all():
            exercise_context[pe.exercise_id] = (
                pe.exercise.name,
                pe.target_sets,
                pe.target_reps,
                pe.target_weight,
            )

    set_logs_out = []
    for sl in s.set_logs:
        ctx = exercise_context.get(sl.exercise_id, (None, None, None, None))
        set_logs_out.append(
            SetLogOut(
                id=sl.id,
                session_id=sl.session_id,
                exercise_id=sl.exercise_id,
                set_number=sl.set_number,
                reps=sl.reps,
                weight=sl.weight,
                completed=sl.completed,
                completed_at=sl.completed_at,
                exercise_name=ctx[0],
                target_sets=ctx[1],
                target_reps=ctx[2],
                target_weight=ctx[3],
            )
        )

    return SessionOut(
        id=s.id,
        user_id=s.user_id,
        plan_id=s.plan_id,
        date=s.date,
        status=s.status,
        duration_seconds=s.duration_seconds,
        note=s.note,
        set_logs=set_logs_out,
    )


async def add_set(
    db: AsyncSession, session_id: uuid.UUID, req: SetLogCreate
) -> SetLogOut:
    sl = SetLog(session_id=session_id, **req.model_dump())
    db.add(sl)
    await db.commit()
    await db.refresh(sl)
    return SetLogOut(
        id=sl.id,
        session_id=sl.session_id,
        exercise_id=sl.exercise_id,
        set_number=sl.set_number,
        reps=sl.reps,
        weight=sl.weight,
        completed=sl.completed,
        completed_at=sl.completed_at,
    )
