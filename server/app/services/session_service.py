import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.planned_exercise import PlannedExercise
from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.schemas.session import (
    SessionCreate,
    SessionOut,
    SessionUpdate,
    SetLogCreate,
    SetLogOut,
    SetLogUpdate,
)


async def create_session(
    db: AsyncSession, user_id: uuid.UUID, req: SessionCreate
) -> SessionOut:
    session = WorkoutSession(user_id=user_id, **req.model_dump())
    db.add(session)
    await db.flush()

    if req.plan_id:
        pe_result = await db.execute(
            select(PlannedExercise)
            .where(PlannedExercise.plan_id == req.plan_id)
            .order_by(PlannedExercise.order)
        )
        planned_exercises = pe_result.scalars().all()
        for pe in planned_exercises:
            for set_num in range(1, (pe.target_sets or 3) + 1):
                db.add(
                    SetLog(
                        session_id=session.id,
                        exercise_id=pe.exercise_id,
                        set_number=set_num,
                        reps=pe.target_reps or 8,
                        weight=pe.target_weight,
                        completed=False,
                    )
                )

    await db.commit()
    return await get_session(db, user_id, session.id)


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

    exercise_context: dict[uuid.UUID, tuple] = {}
    exercise_order: dict[uuid.UUID, int] = {}
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
            exercise_order[pe.exercise_id] = pe.order

    set_logs_out = []
    for sl in sorted(
        s.set_logs,
        key=lambda x: (exercise_order.get(x.exercise_id, 999), x.set_number),
    ):
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


async def update_session(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID, req: SessionUpdate
) -> SessionOut:
    result = await db.execute(
        select(WorkoutSession).where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
    )
    s = result.scalar_one_or_none()
    if not s:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Session not found"
        )
    if req.status is not None:
        s.status = req.status
    if req.duration_seconds is not None:
        s.duration_seconds = req.duration_seconds
    if req.note is not None:
        s.note = req.note
    await db.commit()
    return await get_session(db, user_id, session_id)


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


async def update_set_log(
    db: AsyncSession,
    user_id: uuid.UUID,
    session_id: uuid.UUID,
    set_id: uuid.UUID,
    req: SetLogUpdate,
) -> SetLogOut:
    session_result = await db.execute(
        select(WorkoutSession).where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
    )
    if not session_result.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Session not found"
        )

    set_result = await db.execute(
        select(SetLog).where(
            SetLog.id == set_id,
            SetLog.session_id == session_id,
        )
    )
    sl = set_result.scalar_one_or_none()
    if not sl:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Set not found"
        )

    if req.reps is not None:
        sl.reps = req.reps
    if req.weight is not None:
        sl.weight = req.weight
    if req.completed is not None:
        sl.completed = req.completed
        if req.completed and sl.completed_at is None:
            sl.completed_at = datetime.datetime.now(datetime.timezone.utc)
        elif not req.completed:
            sl.completed_at = None

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
