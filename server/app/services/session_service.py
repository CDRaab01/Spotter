import datetime
import math
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
from app.limits import DELOAD_SET_FACTOR, DELOAD_WEIGHT_FACTOR, clamp_weight
from app.progression import (
    LOWER_BODY_GROUPS,
    SessionHistory,
    SetResult,
    suggest_progression,
)
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
from app.services import program_service

# Number of recent sessions per exercise the progression engine looks back over (stall/PR).
_PROGRESSION_HISTORY_SESSIONS = 5


def _deload_weight(weight: float | None) -> float | None:
    """A deload-week seed load: weight * DELOAD_WEIGHT_FACTOR, rounded to the
    nearest 2.5 lb (plate-friendly) and clamped into bounds. None (bodyweight)
    stays None."""
    if weight is None:
        return None
    return clamp_weight(round(weight * DELOAD_WEIGHT_FACTOR / 2.5) * 2.5)


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
    step = 5.0 if group in LOWER_BODY_GROUPS else 2.5
    suggested = clamp_weight(last_weight + step)
    if suggested is not None and suggested <= last_weight:
        return last_weight, "At the upper weight limit — hold and add reps."
    return suggested, f"Completed all sets last time — add {step:g} lb."


async def create_session(
    db: AsyncSession, user_id: uuid.UUID, req: SessionCreate
) -> SessionOut:
    # Validate the referenced routine BEFORE inserting the session. Flushing a session
    # that points at a non-existent routine_id trips the FK constraint and surfaces as a
    # 500 instead of this 404.
    if req.routine_id:
        routine_check = await db.execute(
            select(WorkoutRoutine).where(
                WorkoutRoutine.id == req.routine_id,
                WorkoutRoutine.user_id == user_id,
            )
        )
        if routine_check.scalar_one_or_none() is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Routine not found")

    session = WorkoutSession(user_id=user_id, **req.model_dump())
    db.add(session)
    await db.flush()

    if req.routine_id:
        pe_result = await db.execute(
            select(RoutineExercise)
            .where(RoutineExercise.routine_id == req.routine_id)
            .order_by(RoutineExercise.order)
        )
        planned_exercises = pe_result.scalars().all()
        # Scheduled deload: when this routine's active program is in its deload week,
        # seed fewer sets at a lighter load so the week self-programs.
        is_deload = await program_service.is_deload_day(db, user_id, req.routine_id, req.date)
        for pe in planned_exercises:
            target_sets = pe.target_sets or 3
            weight = pe.target_weight
            if is_deload:
                target_sets = math.ceil(target_sets * DELOAD_SET_FACTOR)
                weight = _deload_weight(weight)
            for set_num in range(1, target_sets + 1):
                db.add(
                    SetLog(
                        session_id=session.id,
                        exercise_id=pe.exercise_id,
                        set_number=set_num,
                        reps=pe.target_reps or 8,
                        weight=weight,
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
                rpe=sl.rpe,
                set_type=sl.set_type,
                exercise_name=ctx[0],
                target_sets=ctx[1],
                target_reps=ctx[2],
                target_weight=ctx[3],
                superset_group=ctx[5] if len(ctx) > 5 else None,
            )
        )

    # Aggregate completed sets by muscle group (plan sessions only). Warm-up sets are
    # ramp-up work, not working sets — they never count toward sets or volume.
    muscle_group_sets: dict[str, int] = defaultdict(int)
    muscle_group_volume: dict[str, float] = defaultdict(float)
    for sl in s.set_logs:
        if sl.completed and sl.set_type != "warmup":
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

    # Computed at read time from the same helper create_session seeds with, so the
    # flag stays correct even if the program's schedule is edited afterwards.
    is_deload = (
        await program_service.is_deload_day(db, user_id, s.routine_id, s.date)
        if s.routine_id
        else False
    )

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
        is_deload=is_deload,
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
        rpe=sl.rpe,
        set_type=sl.set_type,
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
    if req.rpe is not None:
        sl.rpe = req.rpe
    if req.set_type is not None:
        sl.set_type = req.set_type
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
        rpe=sl.rpe,
        set_type=sl.set_type,
    )


async def delete_set_log(
    db: AsyncSession,
    user_id: uuid.UUID,
    session_id: uuid.UUID,
    set_id: uuid.UUID,
) -> None:
    session_result = await db.execute(
        select(WorkoutSession).where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
    )
    session = session_result.scalar_one_or_none()
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Session not found"
        )
    # A finished session is immutable history (mirrors the adjustment-apply guard).
    if session.status != "in_progress":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Session is not in progress"
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
    await db.delete(sl)
    await db.commit()


async def get_prior_bests(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> list[ExercisePrior]:
    session_result = await db.execute(
        select(WorkoutSession).where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
    )
    session = session_result.scalar_one_or_none()
    if session is None:
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
    ex_result = await db.execute(select(Exercise).where(Exercise.id.in_(exercise_ids)))
    for ex in ex_result.scalars().all():
        exercise_names[ex.id] = ex.name
        exercise_muscle[ex.id] = ex.muscle_group

    # The routine's prescription (target_reps + bodyweight flag) per exercise — the double-progression
    # threshold. Absent (freeform session or exercise not in the routine) → the engine degrades to
    # plain linear progression.
    routine_targets: dict[uuid.UUID, tuple[int | None, bool]] = {}
    if session.routine_id is not None:
        re_rows = await db.execute(
            select(
                RoutineExercise.exercise_id,
                RoutineExercise.target_reps,
                RoutineExercise.is_bodyweight,
            ).where(RoutineExercise.routine_id == session.routine_id)
        )
        routine_targets = {ex_id: (tr, bw) for ex_id, tr, bw in re_rows.all()}

    priors: list[ExercisePrior] = []
    for exercise_id in exercise_ids:
        # The most recent prior sessions that include this exercise (most recent first). We fetch
        # ALL their sets — completed AND incomplete — because a miss (an incomplete set) is exactly
        # the stall/deload signal the engine reasons over. Warm-up sets are excluded throughout:
        # an unticked or light ramp-up set must never read as a stalled/completed working set.
        sess_rows = await db.execute(
            select(WorkoutSession.id, WorkoutSession.date)
            .join(SetLog, SetLog.session_id == WorkoutSession.id)
            .where(
                WorkoutSession.user_id == user_id,
                WorkoutSession.id != session_id,
                SetLog.exercise_id == exercise_id,
                SetLog.set_type != "warmup",
            )
            .group_by(WorkoutSession.id, WorkoutSession.date)
            .order_by(WorkoutSession.date.desc())
            .limit(_PROGRESSION_HISTORY_SESSIONS)
        )
        recent_sessions = sess_rows.all()
        if not recent_sessions:
            continue

        sids = [s[0] for s in recent_sessions]
        set_rows = await db.execute(
            select(SetLog)
            .where(
                SetLog.session_id.in_(sids),
                SetLog.exercise_id == exercise_id,
                SetLog.set_type != "warmup",
            )
            .order_by(SetLog.set_number)
        )
        sets_by_session: dict[uuid.UUID, list[SetLog]] = defaultdict(list)
        for sl in set_rows.scalars().all():
            sets_by_session[sl.session_id].append(sl)

        # Engine history, most recent first (all sets, so misses are visible).
        history = [
            SessionHistory(
                date=sdate,
                sets=[
                    SetResult(reps=sl.reps, weight=sl.weight, completed=sl.completed)
                    for sl in sets_by_session.get(sid, [])
                ],
            )
            for sid, sdate in recent_sessions
        ]

        # Display "prior best": the most recent session that actually completed a set (the response's
        # reps/weight/date + last_sets). If nothing was ever completed, there's nothing to show.
        display: tuple[int, float | None, datetime.date, list[SetLogOut]] | None = None
        for sid, sdate in recent_sessions:
            completed = [sl for sl in sets_by_session.get(sid, []) if sl.completed]
            if completed:
                top = max(completed, key=lambda s: s.set_number)
                display = (
                    top.reps,
                    top.weight,
                    sdate,
                    [
                        SetLogOut(
                            id=sl.id,
                            session_id=sl.session_id,
                            exercise_id=sl.exercise_id,
                            set_number=sl.set_number,
                            reps=sl.reps,
                            weight=sl.weight,
                            completed=sl.completed,
                            completed_at=sl.completed_at,
                            rpe=sl.rpe,
                            set_type=sl.set_type,
                        )
                        for sl in completed
                    ],
                )
                break
        if display is None:
            continue
        disp_reps, disp_weight, disp_date, disp_last_sets = display

        target_reps, is_bw = routine_targets.get(exercise_id, (None, False))
        sugg = suggest_progression(
            target_reps=target_reps,
            last_sets=history[0].sets,
            exercise_history=history[1:],
            muscle_group=exercise_muscle.get(exercise_id),
            is_bodyweight=is_bw,
        )

        priors.append(
            ExercisePrior(
                exercise_id=exercise_id,
                exercise_name=exercise_names.get(exercise_id),
                reps=disp_reps,
                weight=disp_weight,
                date=disp_date,
                last_sets=disp_last_sets,
                suggested_weight=sugg.suggested_weight,
                suggested_reason=sugg.reason,
                suggested_reps=sugg.suggested_reps,
                action=sugg.action,
                e1rm=round(sugg.e1rm, 1) if sugg.e1rm is not None else None,
                is_pr=sugg.is_pr,
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
