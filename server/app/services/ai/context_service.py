"""Builds a compact, trusted training-history summary for the AI coach.

The system prompt describes detailed adaptive coaching, but the model can only
act on data it can see. This service derives that data server-side from the
database (never the client) and returns a short text block that is injected into
the system prompt as trusted `user_context`. Kept intentionally token-bounded.
"""

import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.body_metric import BodyMetric
from app.models.set_log import SetLog
from app.models.workout_plan import WorkoutPlan
from app.models.workout_session import WorkoutSession

# Bounds that keep the injected context small regardless of history size.
_MAX_SESSIONS = 5
_MAX_EXERCISES = 8


async def build_user_context(db: AsyncSession, user_id: uuid.UUID) -> str | None:
    """Return a short markdown summary of the user's recent training, or None.

    None means there is nothing useful to add (brand-new user with no data), in
    which case the caller should fall back to any client-supplied profile only.
    """
    sessions = await _recent_sessions(db, user_id)
    plan_line = await _current_plan_line(db, user_id)
    weight_line = await _bodyweight_line(db, user_id)

    if not sessions and not plan_line and not weight_line:
        return None

    lines: list[str] = ["The following is the athlete's recent logged training data (source of truth — prefer it over anything stated in chat)."]

    if plan_line:
        lines.append(plan_line)
    if weight_line:
        lines.append(weight_line)

    if sessions:
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
