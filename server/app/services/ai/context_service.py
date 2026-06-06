"""Builds a compact, trusted training-history summary for the AI coach.

The system prompt describes detailed adaptive coaching, but the model can only
act on data it can see. This service derives that data server-side from the
database (never the client) and returns a short text block that is injected into
the system prompt as trusted `user_context`. Kept intentionally token-bounded.
"""

import datetime
import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.body_metric import BodyMetric
from app.models.set_log import SetLog
from app.models.workout_plan import WorkoutPlan
from app.models.workout_program import WorkoutProgram
from app.models.workout_session import WorkoutSession

# Bounds that keep the injected context small regardless of history size.
_MAX_SESSIONS = 5
_MAX_EXERCISES = 8


async def build_user_context(db: AsyncSession, user_id: uuid.UUID) -> str:
    """Return a short markdown summary of the user's training state.

    Always returns a block — even a brand-new user gets an `Athlete status` line so
    the coach knows to be welcoming rather than launching into plan generation. The
    leading line tells the model how new the athlete is and how to pitch its reply.
    """
    sessions = await _recent_sessions(db, user_id)
    active_line = await _active_program_line(db, user_id)
    # Only surface the latest plan name when there's no active program — for a program
    # user the most-recent plan is just one day's plan, which reads as misleading.
    plan_line = None if active_line else await _current_plan_line(db, user_id)
    weight_line = await _bodyweight_line(db, user_id)
    completed_count = await _count_completed_sessions(db, user_id)

    lines: list[str] = [
        _athlete_status_line(completed_count, has_program=active_line is not None)
    ]

    if active_line:
        lines.append(active_line)
    elif plan_line:
        lines.append(plan_line)
    if weight_line:
        lines.append(weight_line)

    if sessions:
        lines.append(
            "The following is the athlete's recent logged training data "
            "(source of truth — prefer it over anything stated in chat)."
        )
        lines.append(f"Workouts logged in the last 30 days: {len(sessions)}.")
        lines.append("Recent sessions (most recent first):")
        for s in sessions[:_MAX_SESSIONS]:
            lines.append(f"- {_format_session(s)}")
        last_perf = _last_performance(sessions)
        if last_perf:
            lines.append("Most recent completed working sets per exercise:")
            for name, text in last_perf[:_MAX_EXERCISES]:
                lines.append(f"- {name}: {text}")

    return "\n".join(lines)


def _athlete_status_line(completed_count: int, has_program: bool) -> str:
    """One-line training-stage signal so the coach pitches its reply correctly."""
    if completed_count == 0 and not has_program:
        return (
            "Athlete status: new — no workouts logged yet and no active program. "
            "Be welcoming; ask about their goal before suggesting anything."
        )
    if completed_count == 0:
        return (
            "Athlete status: early — has an active program but no completed workouts "
            "yet. Be encouraging; nudge them to get the first session in."
        )
    if completed_count <= 4:
        return (
            f"Athlete status: early — {completed_count} workout(s) completed so far. "
            "Encourage consistency and reference their progress."
        )
    return (
        f"Athlete status: established — {completed_count} workouts completed. "
        "Check in on how training is going; do not pitch a new program unless asked."
    )


async def _active_program_line(db: AsyncSession, user_id: uuid.UUID) -> str | None:
    """The user's active program plus the next suggested day, or None."""
    result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.user_id == user_id, WorkoutProgram.is_active.is_(True))
        .limit(1)
    )
    program = result.scalar_one_or_none()
    if not program:
        return None
    # Reuse the next-day rotation logic from program_service (imported locally to
    # avoid a module-load cycle with the AI service package).
    from app.services.program_service import get_next_day

    next_day = await get_next_day(db, user_id)
    suffix = f" (next suggested day: {next_day.label})" if next_day else ""
    return (
        f'Active program: "{program.name}"{suffix}. The athlete already has an active '
        "program — do not offer to create a new one unless they ask."
    )


async def _count_completed_sessions(db: AsyncSession, user_id: uuid.UUID) -> int:
    """All-time count of completed sessions, for the training-stage signal."""
    result = await db.execute(
        select(func.count(WorkoutSession.id)).where(
            WorkoutSession.user_id == user_id,
            WorkoutSession.status == "completed",
        )
    )
    return result.scalar() or 0


async def build_current_session_context(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> str | None:
    """Trusted summary of the workout currently in progress, for in-workout chat.

    Returns None if the session doesn't exist or isn't the user's. Advice-only:
    the coach is told what's happening but cannot edit the log.
    """
    result = await db.execute(
        select(WorkoutSession)
        .where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
        .options(selectinload(WorkoutSession.set_logs).selectinload(SetLog.exercise))
    )
    session = result.scalar_one_or_none()
    if session is None:
        return None

    # Group set logs by exercise, preserving completion progress.
    by_exercise: dict[uuid.UUID, list[SetLog]] = {}
    for sl in session.set_logs:
        by_exercise.setdefault(sl.exercise_id, []).append(sl)

    lines: list[str] = [
        "The athlete is CURRENTLY in an active workout (in progress right now). "
        "Use this live state when answering; give concise, actionable coaching."
    ]
    for sets in by_exercise.values():
        sets.sort(key=lambda s: s.set_number)
        name = sets[0].exercise.name if sets[0].exercise else "Unknown"
        done = sum(1 for s in sets if s.completed)
        last = next(
            (s for s in reversed(sets) if s.completed),
            None,
        )
        if last is not None and last.weight is not None:
            last_txt = f"; last completed {last.reps}@{last.weight:g} lb"
        elif last is not None:
            last_txt = f"; last completed {last.reps} reps bodyweight"
        else:
            last_txt = ""
        lines.append(f"- {name}: {done}/{len(sets)} sets done{last_txt}")
    return "\n".join(lines)


async def _recent_sessions(
    db: AsyncSession, user_id: uuid.UUID
) -> list[WorkoutSession]:
    cutoff = datetime.date.today() - datetime.timedelta(days=30)
    result = await db.execute(
        select(WorkoutSession)
        .where(
            WorkoutSession.user_id == user_id,
            WorkoutSession.date >= cutoff,
        )
        .options(
            selectinload(WorkoutSession.set_logs).selectinload(SetLog.exercise)
        )
        .order_by(WorkoutSession.date.desc())
    )
    return list(result.scalars().all())


async def _current_plan_line(db: AsyncSession, user_id: uuid.UUID) -> str | None:
    result = await db.execute(
        select(WorkoutPlan)
        .where(WorkoutPlan.user_id == user_id)
        .order_by(WorkoutPlan.created_at.desc())
        .limit(1)
    )
    plan = result.scalar_one_or_none()
    if not plan:
        return None
    return f"Current plan: \"{plan.name}\" (source: {plan.source})."


async def _bodyweight_line(db: AsyncSession, user_id: uuid.UUID) -> str | None:
    result = await db.execute(
        select(BodyMetric)
        .where(BodyMetric.user_id == user_id)
        .order_by(BodyMetric.date.desc())
        .limit(2)
    )
    rows = list(result.scalars().all())
    if not rows:
        return None
    latest = rows[0]
    line = f"Latest bodyweight: {latest.weight:g} lb (on {latest.date.isoformat()})."
    if len(rows) > 1:
        delta = latest.weight - rows[1].weight
        if abs(delta) >= 0.1:
            direction = "up" if delta > 0 else "down"
            line += f" Trend: {direction} {abs(delta):g} lb since {rows[1].date.isoformat()}."
    return line


def _format_session(s: WorkoutSession) -> str:
    total = len(s.set_logs)
    completed = sum(1 for sl in s.set_logs if sl.completed)
    parts = [s.date.isoformat(), f"{completed}/{total} sets completed"]
    if s.duration_seconds:
        parts.append(f"{s.duration_seconds // 60} min")
    if s.status:
        parts.append(s.status)
    return ", ".join(parts)


def _last_performance(sessions: list[WorkoutSession]) -> list[tuple[str, str]]:
    """Most recent completed set per exercise across the recent sessions.

    `sessions` is already ordered most-recent-first, so the first completed set
    we encounter for an exercise is its latest performance.
    """
    seen: dict[uuid.UUID, tuple[str, str]] = {}
    for s in sessions:
        for sl in s.set_logs:
            if not sl.completed or sl.exercise_id in seen:
                continue
            name = sl.exercise.name if sl.exercise else "Unknown"
            if sl.weight is not None:
                text = f"{sl.reps} reps @ {sl.weight:g} lb (on {s.date.isoformat()})"
            else:
                text = f"{sl.reps} reps bodyweight (on {s.date.isoformat()})"
            seen[sl.exercise_id] = (name, text)
    return list(seen.values())
