"""Proactive coaching signals (GET /insights).

Two read-only signals derived from the user's logged training:

* ``stalled`` — exercises where the progression engine's stall detection
  (:func:`app.progression.stalled_sessions`, the same code path that powers the
  prior-bests deload suggestion) says the user has been stuck for at least
  ``DELOAD_STALL_SESSIONS`` consecutive completed sessions at the same weight.
* ``prs_this_week`` — how many exercises have a completed working-set weight this
  week (Mon → today) that beats their prior all-time best.

Warm-up sets are excluded everywhere — they are ramp-up work, not performance.
Aggregated in Python over recent sessions, which is ample for personal-use data
(the get_prior_bests precedent).
"""

import datetime
import uuid
from collections import defaultdict

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.exercise import Exercise
from app.models.routine_exercise import RoutineExercise
from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.progression import DELOAD_STALL_SESSIONS, SessionHistory, SetResult, stalled_sessions
from app.schemas.insights import InsightsOut, StalledExercise

# How many recent completed sessions per exercise the stall scan looks back over:
# the current session plus the engine's stall window.
_STALL_LOOKBACK_SESSIONS = DELOAD_STALL_SESSIONS + 2


async def get_insights(db: AsyncSession, user_id: uuid.UUID) -> InsightsOut:
    return InsightsOut(
        stalled=await _stalled_exercises(db, user_id),
        prs_this_week=await count_prs_this_week(db, user_id, datetime.date.today()),
    )


async def _stalled_exercises(
    db: AsyncSession, user_id: uuid.UUID
) -> list[StalledExercise]:
    # Exercises with any completed working set in a completed session.
    ex_rows = await db.execute(
        select(SetLog.exercise_id, Exercise.name)
        .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
        .join(Exercise, SetLog.exercise_id == Exercise.id)
        .where(
            WorkoutSession.user_id == user_id,
            WorkoutSession.status == "completed",
            SetLog.set_type != "warmup",
        )
        .distinct()
        .order_by(Exercise.name)
    )

    stalled: list[StalledExercise] = []
    for exercise_id, exercise_name in ex_rows.all():
        entry = await _stall_for_exercise(db, user_id, exercise_id, exercise_name)
        if entry is not None:
            stalled.append(entry)
    return stalled


async def _stall_for_exercise(
    db: AsyncSession,
    user_id: uuid.UUID,
    exercise_id: uuid.UUID,
    exercise_name: str,
) -> StalledExercise | None:
    # Recent completed sessions containing this exercise, most recent first.
    sess_rows = await db.execute(
        select(WorkoutSession.id, WorkoutSession.date, WorkoutSession.routine_id)
        .join(SetLog, SetLog.session_id == WorkoutSession.id)
        .where(
            WorkoutSession.user_id == user_id,
            WorkoutSession.status == "completed",
            SetLog.exercise_id == exercise_id,
            SetLog.set_type != "warmup",
        )
        .group_by(WorkoutSession.id, WorkoutSession.date, WorkoutSession.routine_id)
        .order_by(WorkoutSession.date.desc())
        .limit(_STALL_LOOKBACK_SESSIONS)
    )
    recent = sess_rows.all()
    if not recent:
        return None

    sids = [row[0] for row in recent]
    set_rows = await db.execute(
        select(SetLog)
        .where(
            SetLog.session_id.in_(sids),
            SetLog.exercise_id == exercise_id,
            SetLog.set_type != "warmup",
        )
        .order_by(SetLog.set_number)
    )
    sets_by_session: dict[uuid.UUID, list[SetResult]] = defaultdict(list)
    for sl in set_rows.scalars().all():
        sets_by_session[sl.session_id].append(
            SetResult(reps=sl.reps, weight=sl.weight, completed=sl.completed)
        )

    history = [
        SessionHistory(date=sdate, sets=sets_by_session.get(sid, []))
        for sid, sdate, _ in recent
    ]

    # The rep goal comes from the most recent session's routine, matching how
    # get_prior_bests feeds the engine; absent → the linear fallback.
    target_reps: int | None = None
    latest_routine_id = recent[0][2]
    if latest_routine_id is not None:
        target_reps = (
            await db.execute(
                select(RoutineExercise.target_reps).where(
                    RoutineExercise.routine_id == latest_routine_id,
                    RoutineExercise.exercise_id == exercise_id,
                )
            )
        ).scalar_one_or_none()

    stuck = stalled_sessions(target_reps, history[0].sets, history[1:])
    if stuck < DELOAD_STALL_SESSIONS:
        return None

    weights = [s.weight for s in history[0].sets if s.weight is not None]
    return StalledExercise(
        exercise_id=exercise_id,
        exercise_name=exercise_name,
        sessions_stuck=stuck,
        last_weight=max(weights) if weights else None,
    )


async def count_prs_this_week(
    db: AsyncSession, user_id: uuid.UUID, today: datetime.date
) -> int:
    """Exercises whose best completed working-set weight Mon→today beats their
    prior all-time best before this week. First-ever exposures don't count — with
    no prior best there is nothing to beat."""
    monday = today - datetime.timedelta(days=today.weekday())

    def _best_weights(*extra_where):
        return (
            select(SetLog.exercise_id, func.max(SetLog.weight))
            .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
            .where(
                WorkoutSession.user_id == user_id,
                SetLog.completed == True,  # noqa: E712
                SetLog.weight.is_not(None),
                SetLog.set_type != "warmup",
                *extra_where,
            )
            .group_by(SetLog.exercise_id)
        )

    this_week = dict(
        (await db.execute(_best_weights(WorkoutSession.date >= monday))).all()
    )
    prior_best = dict(
        (await db.execute(_best_weights(WorkoutSession.date < monday))).all()
    )
    return sum(
        1
        for exercise_id, weight in this_week.items()
        if exercise_id in prior_best and weight > prior_best[exercise_id]
    )
