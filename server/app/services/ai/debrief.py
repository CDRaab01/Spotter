"""Post-workout AI debrief (POST /ai/sessions/{id}/debrief).

Builds a compact, server-derived (trusted) summary of a just-completed session —
per-exercise completed working sets, a comparison against the previous session of
each exercise, PR flags, and muscle groups — and has LM Studio narrate it in a
coach's voice. Read-only: the model sees a summary and returns prose; it has no
write path. Kept inside services/ai/ with the other guardrailed LM callers; the
reply passes through validate_response like every chat reply.
"""

import uuid
from collections import defaultdict

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.exercise import Exercise
from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.progression import estimate_1rm
from app.services.ai.client import lm_completion
from app.services.ai.prompts import DEBRIEF_PROMPT, validate_response


def _fmt_set(sl: SetLog) -> str:
    if sl.weight is None:
        return f"{sl.reps}xBW"
    return f"{sl.reps}x{sl.weight:g}"


def _working(sets: list[SetLog]) -> list[SetLog]:
    """Completed working sets only — warm-ups are ramp-up work, not performance."""
    return [sl for sl in sets if sl.completed and sl.set_type != "warmup"]


async def build_debrief_context(
    db: AsyncSession, user_id: uuid.UUID, session: WorkoutSession
) -> str:
    """The trusted session summary injected after DEBRIEF_PROMPT."""
    completed = _working(list(session.set_logs))

    exercise_ids = {sl.exercise_id for sl in session.set_logs}
    names: dict[uuid.UUID, str] = {}
    groups: dict[uuid.UUID, str | None] = {}
    if exercise_ids:
        ex_result = await db.execute(select(Exercise).where(Exercise.id.in_(exercise_ids)))
        for ex in ex_result.scalars().all():
            names[ex.id] = ex.name
            groups[ex.id] = ex.muscle_group

    duration_txt = (
        f" ({session.duration_seconds // 60} min)" if session.duration_seconds else ""
    )
    trained_groups = sorted({g for sl in completed if (g := groups.get(sl.exercise_id))})
    lines = [f"Workout on {session.date.isoformat()}{duration_txt}."]
    if trained_groups:
        lines.append(f"Muscle groups trained: {', '.join(trained_groups)}.")

    by_exercise: dict[uuid.UUID, list[SetLog]] = defaultdict(list)
    for sl in completed:
        by_exercise[sl.exercise_id].append(sl)

    for exercise_id, sets in by_exercise.items():
        sets.sort(key=lambda s: s.set_number)
        name = names.get(exercise_id, "Unknown")
        sets_txt = ", ".join(_fmt_set(sl) for sl in sets)
        line = f"- {name}: {len(sets)} working sets — {sets_txt}"

        prev_line, is_pr = await _previous_and_pr(db, user_id, session, exercise_id, sets)
        if prev_line:
            line += f"; {prev_line}"
        if is_pr:
            line += ". NEW PR this session!"
        lines.append(line + ".")

    if not by_exercise:
        lines.append("No working sets were completed.")
    return "\n".join(lines)


async def _previous_and_pr(
    db: AsyncSession,
    user_id: uuid.UUID,
    session: WorkoutSession,
    exercise_id: uuid.UUID,
    current_sets: list[SetLog],
) -> tuple[str | None, bool]:
    """The previous session's best working set for this exercise (as a text
    fragment), plus a PR flag: the current best-set e1RM beats every prior
    session's. First-ever exposure to an exercise is not called a PR — there is
    no prior best to beat."""
    rows = await db.execute(
        select(SetLog, WorkoutSession.date)
        .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
        .where(
            WorkoutSession.user_id == user_id,
            WorkoutSession.id != session.id,
            WorkoutSession.date <= session.date,
            SetLog.exercise_id == exercise_id,
            SetLog.completed == True,  # noqa: E712
            SetLog.set_type != "warmup",
        )
        .order_by(WorkoutSession.date.desc(), SetLog.set_number)
    )
    prior = rows.all()
    if not prior:
        return None, False

    # Previous session = the most recent prior date; its best set by load.
    prev_date = prior[0][1]
    prev_sets = [sl for sl, d in prior if d == prev_date]
    best_prev = max(prev_sets, key=lambda s: (s.weight or 0.0, s.reps))
    prev_line = f"last time ({prev_date.isoformat()}): best {_fmt_set(best_prev)}"

    prior_best_e1rm = max(
        (estimate_1rm(sl.weight, sl.reps) for sl, _ in prior if sl.weight is not None),
        default=None,
    )
    current_e1rm = max(
        (estimate_1rm(sl.weight, sl.reps) for sl in current_sets if sl.weight is not None),
        default=None,
    )
    is_pr = (
        current_e1rm is not None
        and prior_best_e1rm is not None
        and current_e1rm > prior_best_e1rm
    )
    return prev_line, is_pr


async def debrief_session(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> str:
    result = await db.execute(
        select(WorkoutSession)
        .where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
        .options(selectinload(WorkoutSession.set_logs))
    )
    session = result.scalar_one_or_none()
    if session is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")
    if session.status != "completed":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Session is not completed"
        )

    context = await build_debrief_context(db, user_id, session)
    messages = [
        {"role": "system", "content": DEBRIEF_PROMPT},
        {"role": "user", "content": f"Here is the workout summary:\n{context}"},
    ]
    return validate_response(await lm_completion(messages))
