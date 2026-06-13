"""Apply a user-accepted AI workout adjustment.

The AI proposes; the user disposes. This service runs only when the user taps
Apply on an adjustment card, and it is the ONLY write path for AI-originated
session edits — kept inside services/ai/ so the guardrail surface stays
auditable in one place.

Invariants:
- Only the in-progress session's INCOMPLETE sets are ever mutated; completed
  sets are immutable history.
- The whole adjustment (live half + optional routine half) is one transaction
  with a single commit — no partial application.
- Echoed values were re-validated by the ApplyAdjustmentRequest schema bounds.
"""

import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.limits import MAX_ADJUSTMENT_ACTIONS
from app.models.exercise import Exercise
from app.models.routine_exercise import RoutineExercise
from app.models.set_log import SetLog
from app.models.workout_routine import WorkoutRoutine
from app.models.workout_session import WorkoutSession
from app.schemas.ai import ApplyAdjustmentRequest, SuggestedAdjustmentAction
from app.schemas.session import SessionOut
from app.services import session_service


async def apply_adjustment(
    db: AsyncSession,
    user_id: uuid.UUID,
    session_id: uuid.UUID,
    req: ApplyAdjustmentRequest,
) -> SessionOut:
    if len(req.actions) > MAX_ADJUSTMENT_ACTIONS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"At most {MAX_ADJUSTMENT_ACTIONS} actions per adjustment",
        )

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
    if session.status != "in_progress":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Session is not in progress"
        )

    # Every referenced exercise must exist (mirrors the add_set 404 guard).
    referenced: set[uuid.UUID] = set()
    for action in req.actions:
        referenced.add(action.exercise_id)
        if action.new_exercise_id is not None:
            referenced.add(action.new_exercise_id)
    if referenced:
        found = {
            row[0]
            for row in await db.execute(
                select(Exercise.id).where(Exercise.id.in_(referenced))
            )
        }
        if found != referenced:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Exercise not found"
            )

    logs_result = await db.execute(
        select(SetLog).where(SetLog.session_id == session_id)
    )
    logs: list[SetLog] = list(logs_result.scalars().all())

    # Process actions in order against the evolving in-memory state, so e.g.
    # "remove Bench" followed by "add Bench" behaves as the card reads.
    for action in req.actions:
        logs = await _apply_live(db, session_id, logs, action)

    if req.apply_to_routine and session.routine_id is not None:
        await _apply_to_routine(db, user_id, session.routine_id, req.actions)

    await db.commit()
    return await session_service.get_session(db, user_id, session_id)


async def _apply_live(
    db: AsyncSession,
    session_id: uuid.UUID,
    logs: list[SetLog],
    action: SuggestedAdjustmentAction,
) -> list[SetLog]:
    """Mutate the live session's set logs for one action. Incomplete sets only."""
    incomplete = [
        sl for sl in logs if sl.exercise_id == action.exercise_id and not sl.completed
    ]

    if action.type == "swap":
        if not incomplete:
            return logs  # nothing left to swap — silently a no-op
        assert action.new_exercise_id is not None  # schema-guaranteed for swap
        count = action.sets or len(incomplete)
        base = _max_set_number(logs, action.new_exercise_id)
        for sl in incomplete:
            await db.delete(sl)
            logs.remove(sl)
        for i in range(count):
            template = incomplete[min(i, len(incomplete) - 1)]
            new_log = SetLog(
                session_id=session_id,
                exercise_id=action.new_exercise_id,
                set_number=base + i + 1,
                reps=action.reps or template.reps,
                # action.weight is authoritative: None means the new movement is
                # bodyweight — never inherit the old exercise's load.
                weight=action.weight,
                completed=False,
            )
            db.add(new_log)
            logs.append(new_log)

    elif action.type == "adjust_weight":
        for sl in incomplete:
            sl.weight = action.weight
            if action.reps is not None:
                sl.reps = action.reps

    elif action.type == "remove":
        for sl in incomplete:
            await db.delete(sl)
            logs.remove(sl)

    elif action.type == "add":
        count = action.sets or 3
        reps = action.reps or 8
        base = _max_set_number(logs, action.exercise_id)
        for i in range(count):
            new_log = SetLog(
                session_id=session_id,
                exercise_id=action.exercise_id,
                set_number=base + i + 1,
                reps=reps,
                weight=action.weight,
                completed=False,
            )
            db.add(new_log)
            logs.append(new_log)

    return logs


def _max_set_number(logs: list[SetLog], exercise_id: uuid.UUID) -> int:
    return max(
        (sl.set_number for sl in logs if sl.exercise_id == exercise_id), default=0
    )


async def _apply_to_routine(
    db: AsyncSession,
    user_id: uuid.UUID,
    routine_id: uuid.UUID,
    actions: list[SuggestedAdjustmentAction],
) -> None:
    """Propagate the adjustment to the session's routine so future workouts (and
    the program days referencing this routine) pick it up. Actions targeting an
    exercise the routine doesn't contain are skipped — the session may hold
    ad-hoc exercises that were never part of the routine."""
    routine_result = await db.execute(
        select(WorkoutRoutine).where(
            WorkoutRoutine.id == routine_id,
            WorkoutRoutine.user_id == user_id,
        )
    )
    if routine_result.scalar_one_or_none() is None:
        return  # foreign/missing routine: never touch it

    rows_result = await db.execute(
        select(RoutineExercise).where(RoutineExercise.routine_id == routine_id)
    )
    rows: list[RoutineExercise] = list(rows_result.scalars().all())

    for action in actions:
        row = next((r for r in rows if r.exercise_id == action.exercise_id), None)

        if action.type == "swap":
            if row is None:
                continue
            assert action.new_exercise_id is not None
            row.exercise_id = action.new_exercise_id
            if action.sets is not None:
                row.target_sets = action.sets
            if action.reps is not None:
                row.target_reps = action.reps
            # Same convention as the live half: weight is authoritative, None = bodyweight.
            row.target_weight = action.weight
            row.is_bodyweight = action.weight is None

        elif action.type == "adjust_weight":
            if row is None:
                continue
            row.target_weight = action.weight
            if action.reps is not None:
                row.target_reps = action.reps

        elif action.type == "remove":
            if row is None:
                continue
            await db.delete(row)
            rows.remove(row)

        elif action.type == "add":
            if row is not None:
                continue  # already in the routine — nothing to add
            new_row = RoutineExercise(
                routine_id=routine_id,
                exercise_id=action.exercise_id,
                target_sets=action.sets or 3,
                target_reps=action.reps or 8,
                target_weight=action.weight,
                is_bodyweight=action.weight is None,
                order=max((r.order for r in rows), default=-1) + 1,
            )
            db.add(new_row)
            rows.append(new_row)
