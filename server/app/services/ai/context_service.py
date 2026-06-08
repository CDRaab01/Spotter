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
from app.models.exercise import Exercise
from app.models.set_log import SetLog
from app.models.workout_routine import WorkoutRoutine
from app.models.workout_session import WorkoutSession

# Bounds that keep the injected context small regardless of history size.
_MAX_SESSIONS = 5
_MAX_EXERCISES = 8


async def build_exercise_catalog(db: AsyncSession) -> str | None:
    """Return the app's exercise library, grouped by muscle, as trusted context.

    The model used to recommend exercises that aren't in the seeded catalog (face
    pulls, hip thrusts, hammer curls, etc.); those names fail name-resolution and
    get silently dropped, collapsing a 5-6 lift day down to the one or two that
    happened to match. Injecting the real catalog (always in sync with the DB)
    constrains the model to names that will actually resolve, so full workouts
    survive extraction. Returns None only if the catalog is empty.
    """
    result = await db.execute(
        select(Exercise.name, Exercise.muscle_group).order_by(
            Exercise.muscle_group, Exercise.name
        )
    )
    rows = list(result.all())
    if not rows:
        return None

    by_group: dict[str, list[str]] = {}
    for name, group in rows:
        by_group.setdefault(group or "other", []).append(name)

    lines = [
        "Choose exercises ONLY from this library, using the exact name shown. These "
        "are the only exercises the app can track — any exercise you name that is not "
        "on this list is silently dropped from the plan, leaving an incomplete workout. "
        "If the ideal movement isn't listed, pick the closest available substitute that IS."
    ]
    for group in sorted(by_group):
        lines.append(f"- {group}: {', '.join(by_group[group])}")
    return "\n".join(lines)


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
        select(WorkoutRoutine)
        .where(WorkoutRoutine.user_id == user_id)
        .order_by(WorkoutRoutine.created_at.desc())
        .limit(1)
    )
    routine = result.scalar_one_or_none()
    if not routine:
        return None
    return f"Current routine: \"{routine.name}\" (source: {routine.source})."


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
