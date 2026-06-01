import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.exercise import Exercise
from app.models.planned_exercise import PlannedExercise
from app.models.set_log import SetLog
from app.models.workout_plan import WorkoutPlan
from app.models.workout_session import WorkoutSession
from app.schemas.session import (
    ExercisePrior,
    ExerciseSummary,
    SessionCreate,
    SessionOut,
    SessionSummary,
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

    plan_name: str | None = None
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

        plan_result = await db.execute(
            select(WorkoutPlan).where(WorkoutPlan.id == s.plan_id)
        )
        plan = plan_result.scalar_one_or_none()
        if plan:
            plan_name = plan.name

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
        plan_name=plan_name,
        date=s.date,
        status=s.status,
        duration_seconds=s.duration_seconds,
        note=s.note,
        exercise_notes=s.exercise_notes,
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
    if req.exercise_notes is not None:
        s.exercise_notes = req.exercise_notes
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


async def get_prior_bests(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> list[ExercisePrior]:
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

    exercise_id_result = await db.execute(
        select(SetLog.exercise_id).where(SetLog.session_id == session_id).distinct()
    )
    exercise_ids = [row[0] for row in exercise_id_result]
    if not exercise_ids:
        return []

    # Load exercise names in one query
    exercise_names: dict[uuid.UUID, str] = {}
    ex_result = await db.execute(
        select(Exercise).where(Exercise.id.in_(exercise_ids))
    )
    for ex in ex_result.scalars().all():
        exercise_names[ex.id] = ex.name

    priors: list[ExercisePrior] = []
    for exercise_id in exercise_ids:
        # Most recent completed set for this exercise in a prior session for this user
        row_result = await db.execute(
            select(SetLog.reps, SetLog.weight, WorkoutSession.date)
            .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
            .where(
                WorkoutSession.user_id == user_id,
                WorkoutSession.id != session_id,
                SetLog.exercise_id == exercise_id,
                SetLog.completed == True,  # noqa: E712
            )
            .order_by(WorkoutSession.date.desc(), SetLog.set_number.desc())
            .limit(1)
        )
        row = row_result.first()
        if row:
            priors.append(
                ExercisePrior(
                    exercise_id=exercise_id,
                    exercise_name=exercise_names.get(exercise_id),
                    reps=row[0],
                    weight=row[1],
                    date=row[2],
                )
            )

    return priors


async def list_sessions(
    db: AsyncSession, user_id: uuid.UUID
) -> list[SessionSummary]:
    result = await db.execute(
        select(WorkoutSession)
        .where(WorkoutSession.user_id == user_id)
        .options(selectinload(WorkoutSession.set_logs))
        .order_by(WorkoutSession.date.desc())
    )
    sessions = list(result.scalars().all())

    if not sessions:
        return []

    # Bulk-load plan names
    plan_ids = list({s.plan_id for s in sessions if s.plan_id is not None})
    plan_names: dict[uuid.UUID, str] = {}
    if plan_ids:
        plan_result = await db.execute(
            select(WorkoutPlan).where(WorkoutPlan.id.in_(plan_ids))
        )
        for p in plan_result.scalars().all():
            plan_names[p.id] = p.name

    # Bulk-load exercise names
    all_exercise_ids: set[uuid.UUID] = set()
    for s in sessions:
        for sl in s.set_logs:
            all_exercise_ids.add(sl.exercise_id)

    exercise_names: dict[uuid.UUID, str] = {}
    if all_exercise_ids:
        ex_result = await db.execute(
            select(Exercise).where(Exercise.id.in_(all_exercise_ids))
        )
        for ex in ex_result.scalars().all():
            exercise_names[ex.id] = ex.name

    summaries: list[SessionSummary] = []
    for s in sessions:
        # Group set_logs by exercise
        exercise_sets: dict[uuid.UUID, list] = {}
        for sl in s.set_logs:
            exercise_sets.setdefault(sl.exercise_id, []).append(sl)

        exercise_summaries: list[ExerciseSummary] = []
        for ex_id, sets in exercise_sets.items():
            exercise_summaries.append(
                ExerciseSummary(
                    exercise_name=exercise_names.get(ex_id, "Unknown"),
                    completed_sets=sum(1 for sl in sets if sl.completed),
                    total_sets=len(sets),
                )
            )

        total_sets = sum(len(sets) for sets in exercise_sets.values())
        completed_sets = sum(
            1 for s2 in s.set_logs if s2.completed
        )

        summaries.append(
            SessionSummary(
                id=s.id,
                date=s.date,
                plan_name=plan_names.get(s.plan_id) if s.plan_id else None,
                status=s.status,
                duration_seconds=s.duration_seconds,
                total_sets=total_sets,
                completed_sets=completed_sets,
                exercises=exercise_summaries,
            )
        )

    return summaries
