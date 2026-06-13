import datetime
import uuid
from collections import defaultdict

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.exercise import Exercise
from app.models.routine_exercise import RoutineExercise
from app.models.set_log import SetLog
from app.models.workout_routine import WorkoutRoutine
from app.models.workout_session import WorkoutSession
from app.limits import clamp_weight
from app.schemas.session import (
    ExercisePrior,
    ExerciseSummary,
    MuscleGroupSummary,
    SessionCreate,
    SessionOut,
    SessionSummary,
    SessionUpdate,
    SetLogCreate,
    SetLogOut,
    SetLogUpdate,
)

# Muscle groups that take larger linear-progression jumps (bigger, stronger muscles).
_LOWER_BODY_GROUPS = {"legs", "quads", "hamstrings", "glutes", "calves", "back"}


def suggest_next_weight(
    last_weight: float | None,
    last_sets: list[SetLogOut],
    muscle_group: str | None,
) -> tuple[float | None, str | None]:
    """Progression-aware next-weight suggestion from the prior session's sets.

    Pure function (no I/O) so it can be unit-tested directly. Uses linear
    progression: if every prior working set was completed, bump the load by one
    step (+5 lb lower body / +2.5 lb upper); otherwise hold. Bodyweight returns
    no weight suggestion. Result is clamped to the sanity bounds.
    """
    if last_weight is None or not last_sets:
        return None, "Bodyweight — add reps before adding load." if last_weight is None else None

    all_completed = all(sl.completed for sl in last_sets)
    if not all_completed:
        return last_weight, "Missed reps last time — repeat this weight before adding load."

    group = (muscle_group or "").lower()
    step = 5.0 if group in _LOWER_BODY_GROUPS else 2.5
    suggested = clamp_weight(last_weight + step)
    if suggested is not None and suggested <= last_weight:
        return last_weight, "At the upper weight limit — hold and add reps."
    return suggested, f"Completed all sets last time — add {step:g} lb."


async def create_session(
    db: AsyncSession, user_id: uuid.UUID, req: SessionCreate
) -> SessionOut:
    session = WorkoutSession(user_id=user_id, **req.model_dump())
    db.add(session)
    await db.flush()

    if req.routine_id:
        routine_check = await db.execute(
            select(WorkoutRoutine).where(
                WorkoutRoutine.id == req.routine_id,
                WorkoutRoutine.user_id == user_id,
            )
        )
        if routine_check.scalar_one_or_none() is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Routine not found")

        pe_result = await db.execute(
            select(RoutineExercise)
            .where(RoutineExercise.routine_id == req.routine_id)
            .order_by(RoutineExercise.order)
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

    routine_name: str | None = None
    exercise_context: dict[uuid.UUID, tuple] = {}
    exercise_order: dict[uuid.UUID, int] = {}
    if s.routine_id:
        pe_result = await db.execute(
            select(RoutineExercise)
            .where(RoutineExercise.routine_id == s.routine_id)
            .options(selectinload(RoutineExercise.exercise))
        )
        for pe in pe_result.scalars().all():
            exercise_context[pe.exercise_id] = (
                pe.exercise.name,
                pe.target_sets,
                pe.target_reps,
                pe.target_weight,
                pe.exercise.muscle_group,
                pe.superset_group,
            )
            exercise_order[pe.exercise_id] = pe.order

        routine_result = await db.execute(
            select(WorkoutRoutine).where(WorkoutRoutine.id == s.routine_id)
        )
        routine = routine_result.scalar_one_or_none()
        if routine:
            routine_name = routine.name

    # Exercises in the session but not in the routine (ad-hoc additions, AI swaps)
    # still need a name + muscle group, or they render nameless and vanish from the
    # muscle-group summary. Targets stay None — there was never a plan for them.
    missing_ids = {
        sl.exercise_id for sl in s.set_logs if sl.exercise_id not in exercise_context
    }
    if missing_ids:
        ex_result = await db.execute(
            select(Exercise).where(Exercise.id.in_(missing_ids))
        )
        for ex in ex_result.scalars().all():
            exercise_context[ex.id] = (ex.name, None, None, None, ex.muscle_group, None)

    set_logs_out = []
    for sl in sorted(
        s.set_logs,
        key=lambda x: (exercise_order.get(x.exercise_id, 999), x.set_number),
    ):
        ctx = exercise_context.get(sl.exercise_id, (None, None, None, None, None, None))
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
                superset_group=ctx[5] if len(ctx) > 5 else None,
            )
        )

    # Aggregate completed sets by muscle group (plan sessions only)
    muscle_group_sets: dict[str, int] = defaultdict(int)
    muscle_group_volume: dict[str, float] = defaultdict(float)
    for sl in s.set_logs:
        if sl.completed:
            ctx = exercise_context.get(sl.exercise_id, (None, None, None, None, None, None))
            mg = ctx[4] if len(ctx) > 4 else None
            if mg:
                muscle_group_sets[mg] += 1
                if sl.weight:
                    muscle_group_volume[mg] += sl.reps * (sl.weight * 0.453592)

    muscle_groups_out = [
        MuscleGroupSummary(
            muscle_group=mg,
            sets=muscle_group_sets[mg],
            volume=round(muscle_group_volume.get(mg, 0.0), 1),
        )
        for mg in sorted(muscle_group_sets)
    ]

    return SessionOut(
        id=s.id,
        user_id=s.user_id,
        routine_id=s.routine_id,
        routine_name=routine_name,
        date=s.date,
        status=s.status,
        duration_seconds=s.duration_seconds,
        note=s.note,
        exercise_notes=s.exercise_notes,
        set_logs=set_logs_out,
        muscle_groups=muscle_groups_out,
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


async def delete_session(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> None:
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
    await db.delete(s)
    await db.commit()


async def add_set(
    db: AsyncSession, session_id: uuid.UUID, req: SetLogCreate
) -> SetLogOut:
    ex_result = await db.execute(
        select(Exercise).where(Exercise.id == req.exercise_id)
    )
    if ex_result.scalar_one_or_none() is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Exercise not found"
        )
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

    # Load exercise names + muscle groups in one query
    exercise_names: dict[uuid.UUID, str] = {}
    exercise_muscle: dict[uuid.UUID, str | None] = {}
    ex_result = await db.execute(
        select(Exercise).where(Exercise.id.in_(exercise_ids))
    )
    for ex in ex_result.scalars().all():
        exercise_names[ex.id] = ex.name
        exercise_muscle[ex.id] = ex.muscle_group

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
        if not row:
            continue

        # Fetch the most recent prior session's full set list for this exercise
        prior_session_result = await db.execute(
            select(WorkoutSession.id)
            .join(SetLog, SetLog.session_id == WorkoutSession.id)
            .where(
                WorkoutSession.user_id == user_id,
                WorkoutSession.id != session_id,
                SetLog.exercise_id == exercise_id,
                SetLog.completed == True,  # noqa: E712
            )
            .order_by(WorkoutSession.date.desc())
            .limit(1)
        )
        prior_session_id = prior_session_result.scalar_one_or_none()

        last_sets: list[SetLogOut] = []
        if prior_session_id:
            last_sets_result = await db.execute(
                select(SetLog)
                .where(
                    SetLog.session_id == prior_session_id,
                    SetLog.exercise_id == exercise_id,
                    SetLog.completed == True,  # noqa: E712
                )
                .order_by(SetLog.set_number)
            )
            last_sets = [
                SetLogOut(
                    id=sl.id,
                    session_id=sl.session_id,
                    exercise_id=sl.exercise_id,
                    set_number=sl.set_number,
                    reps=sl.reps,
                    weight=sl.weight,
                    completed=sl.completed,
                    completed_at=sl.completed_at,
                )
                for sl in last_sets_result.scalars().all()
            ]

        suggested_weight, suggested_reason = suggest_next_weight(
            last_weight=row[1],
            last_sets=last_sets,
            muscle_group=exercise_muscle.get(exercise_id),
        )

        priors.append(
            ExercisePrior(
                exercise_id=exercise_id,
                exercise_name=exercise_names.get(exercise_id),
                reps=row[0],
                weight=row[1],
                date=row[2],
                last_sets=last_sets,
                suggested_weight=suggested_weight,
                suggested_reason=suggested_reason,
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

    # Bulk-load routine names
    routine_ids = list({s.routine_id for s in sessions if s.routine_id is not None})
    routine_names: dict[uuid.UUID, str] = {}
    if routine_ids:
        routine_result = await db.execute(
            select(WorkoutRoutine).where(WorkoutRoutine.id.in_(routine_ids))
        )
        for r in routine_result.scalars().all():
            routine_names[r.id] = r.name

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
                routine_name=routine_names.get(s.routine_id) if s.routine_id else None,
                status=s.status,
                duration_seconds=s.duration_seconds,
                total_sets=total_sets,
                completed_sets=completed_sets,
                exercises=exercise_summaries,
            )
        )

    return summaries
