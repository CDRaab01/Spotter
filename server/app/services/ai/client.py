import json
import logging
import re
import uuid

import httpx
from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.limits import (
    MAX_ADJUSTMENT_ACTIONS,
    REPS_BOUNDS,
    SETS_BOUNDS,
    clamp_int,
    clamp_weight,
)
from app.models.exercise import Exercise
from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.schemas.ai import (
    AiAdjustmentDraft,
    AiPlanDraft,
    AiPlanExercise,
    AiProgramDraft,
    ChatRequest,
    ChatResponse,
    SuggestedAdjustment,
    SuggestedAdjustmentAction,
    SuggestedRoutine,
    SuggestedProgram,
    SuggestedProgramDay,
)
from app.schemas.routine import RoutineExerciseIn
from app.services.ai.context_service import (
    build_current_session_context,
    build_exercise_catalog,
    build_user_context,
)
from app.services.ai.prompts import build_messages, validate_request, validate_response

logger = logging.getLogger(__name__)


async def chat(
    req: ChatRequest, db: AsyncSession, user_id: uuid.UUID | None = None
) -> ChatResponse:
    last_user = next(
        (m.content for m in reversed(req.messages) if m.role == "user"), ""
    )
    # Hard-reject only the NEW turn. Prior user turns are screened too — injection can
    # be embedded in earlier history entries — but a blocked one is dropped from the
    # history instead of failing the request: clients resend the whole transcript, so
    # rejecting on history would permanently 422 every conversation that ever
    # contained a blocked phrase. Dropped turns never reach the model either way.
    error = validate_request(last_user)
    if error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=error
        )

    history = [
        m.model_dump()
        for m in req.messages[:-1]
        if not (m.role == "user" and validate_request(m.content))
    ]
    user_context = await _merged_context(
        db, user_id, req.user_context, req.current_session_id
    )
    exercise_catalog = await build_exercise_catalog(db)
    messages = build_messages(history, last_user, user_context, exercise_catalog)

    async with httpx.AsyncClient(timeout=settings.lm_studio_timeout) as client:
        try:
            resp = await client.post(
                f"{settings.lm_studio_base_url}/chat/completions",
                json={
                    "model": settings.lm_studio_model,
                    "messages": messages,
                    "temperature": 0.7,
                },
            )
            resp.raise_for_status()
        except httpx.HTTPStatusError as e:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"LM Studio returned {e.response.status_code}",
            )
        except httpx.TimeoutException:
            # Distinct from "unreachable": LM Studio answered the connection but didn't
            # finish in time (commonly a cold-start model load on the first request).
            raise HTTPException(
                status_code=status.HTTP_504_GATEWAY_TIMEOUT,
                detail="LM Studio timed out — the model may still be loading. Try again.",
            )
        except httpx.RequestError:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="LM Studio is not reachable",
            )

    try:
        data = resp.json()
        raw_reply = data["choices"][0]["message"]["content"]
        if not isinstance(raw_reply, str):
            raise TypeError("content is not a string")
    except (ValueError, KeyError, IndexError, TypeError):
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="LM Studio returned a malformed response",
        )
    # Exactly one suggestion type per reply: adjustment (only meaningful with a live
    # session in context) wins over program, which wins over a single plan.
    suggested_adjustment = (
        await _extract_adjustment(raw_reply, db, user_id, req.current_session_id)
        if (user_id and req.current_session_id)
        else None
    )
    suggested_program = (
        None if suggested_adjustment else await _extract_program(raw_reply, db)
    )
    suggested_routine = (
        None
        if (suggested_adjustment or suggested_program)
        else await _extract_plan(raw_reply, db)
    )
    # The structured JSON is surfaced via its card — strip it from the chat text so the
    # bubble shows only the prose. If the model returned nothing but JSON, fall back to
    # a short prompt pointing at the card.
    clean_reply = validate_response(_strip_structured_blocks(raw_reply))
    if not clean_reply:
        if suggested_adjustment:
            clean_reply = (
                "I've suggested a change to this workout — review it below and tap Apply."
            )
        elif suggested_program:
            clean_reply = (
                "I've put together a multi-day program for you — review the days below "
                "and tap Save Program to add it."
            )
        elif suggested_routine:
            clean_reply = "I've put together a routine for you — tap Save Routine to add it."
    return ChatResponse(
        reply=clean_reply,
        suggested_routine=suggested_routine,
        suggested_program=suggested_program,
        suggested_adjustment=suggested_adjustment,
    )


async def _merged_context(
    db: AsyncSession,
    user_id: uuid.UUID | None,
    client_profile: str | None,
    current_session_id: uuid.UUID | None = None,
) -> str | None:
    """Combine the server-derived (trusted) training history with the client's
    self-reported profile. The DB history is authoritative; the client string is
    treated as stated preferences only. When a workout is in progress, a trusted
    live-session block is prepended so the coach is aware of it."""
    history = await build_user_context(db, user_id) if user_id else None
    profile = client_profile.strip() if client_profile else None
    live = (
        await build_current_session_context(db, user_id, current_session_id)
        if (user_id and current_session_id)
        else None
    )
    parts = [p for p in (live, history) if p]
    base = "\n\n".join(parts) if parts else None
    if base and profile:
        return f"{base}\n\nAthlete-stated profile/preferences:\n{profile}"
    return base or profile


def _strip_structured_blocks(reply: str) -> str:
    """Remove plan/program JSON from a reply so the chat bubble shows only prose.

    Drops fenced ``` blocks (the JSON the model emits for a plan/program) and a
    leading bare JSON object, then collapses the leftover blank lines.
    """
    # Remove fenced code blocks (```json ... ``` or plain ``` ... ```).
    text = re.sub(r"```.*?```", "", reply, flags=re.DOTALL).strip()
    # Remove a leading bare JSON object (the _extract_json_block fallback case) by
    # matching braces, so trailing prose containing a "}" isn't swallowed.
    if text.startswith("{"):
        depth = 0
        for i, ch in enumerate(text):
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    text = text[i + 1 :]
                    break
    # Collapse 3+ newlines left behind into a clean paragraph break.
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def _extract_json_block(raw_reply: str) -> str | None:
    """Pull the first JSON object out of an LLM reply (fenced block preferred)."""
    match = re.search(r"```(?:json)?\s*(.*?)\s*```", raw_reply, re.DOTALL)
    if match:
        json_str = match.group(1)
    else:
        match = re.search(r"\{.*\}", raw_reply, re.DOTALL)
        if not match:
            return None
        json_str = match.group(0)
    return json_str if json_str.strip().startswith("{") else None


async def _resolve_exercises(
    draft_exercises: list[AiPlanExercise], db: AsyncSession
) -> list[RoutineExerciseIn]:
    """Resolve LLM exercise names to UUIDs and clamp values into sanity bounds.

    The LLM is untrusted — cap absurd values rather than letting one bad number
    reject the whole routine, and skip exercises that don't resolve to a real row.
    """
    resolved: list[RoutineExerciseIn] = []
    for ex in draft_exercises:
        exercise_id = await _resolve_exercise(ex.exercise_id, db)
        if exercise_id is None:
            logger.info("Could not resolve exercise name: %r — skipping", ex.exercise_id)
            continue
        resolved.append(
            RoutineExerciseIn(
                exercise_id=exercise_id,
                target_sets=clamp_int(ex.target_sets, SETS_BOUNDS),
                target_reps=clamp_int(ex.target_reps, REPS_BOUNDS),
                target_weight=None if ex.is_bodyweight else clamp_weight(ex.target_weight),
                is_bodyweight=ex.is_bodyweight,
                order=max(0, ex.order),
            )
        )
    return resolved


async def _extract_plan(raw_reply: str, db: AsyncSession) -> SuggestedRoutine | None:
    json_str = _extract_json_block(raw_reply)
    if json_str is None:
        return None
    try:
        data = json.loads(json_str)
        draft = AiPlanDraft.model_validate(data)
    except Exception:
        return None

    resolved = await _resolve_exercises(draft.exercises, db)
    if not resolved:
        return None
    return SuggestedRoutine(name=draft.name, exercises=resolved)


async def _extract_program(raw_reply: str, db: AsyncSession) -> SuggestedProgram | None:
    """Extract a multi-day program from the reply, or None if it isn't one.

    A program JSON has a top-level ``days`` array; a single-plan JSON (top-level
    ``exercises``) fails ``AiProgramDraft`` validation and returns None so the
    caller can fall back to ``_extract_plan``.
    """
    json_str = _extract_json_block(raw_reply)
    if json_str is None:
        return None
    try:
        data = json.loads(json_str)
        draft = AiProgramDraft.model_validate(data)
    except Exception:
        return None

    days: list[SuggestedProgramDay] = []
    for i, day in enumerate(draft.days):
        was_rest = len(day.exercises) == 0
        resolved = await _resolve_exercises(day.exercises, db)
        # Keep genuine rest days (no exercises to begin with); drop days whose
        # exercises all failed to resolve (mirrors plan behavior).
        if not resolved and not was_rest:
            continue
        days.append(
            SuggestedProgramDay(label=day.label, exercises=resolved, order=i)
        )

    # Need at least one day that actually trains something.
    if not any(d.exercises for d in days):
        return None
    return SuggestedProgram(name=draft.name, days=days)


async def _extract_adjustment(
    raw_reply: str,
    db: AsyncSession,
    user_id: uuid.UUID,
    session_id: uuid.UUID,
) -> SuggestedAdjustment | None:
    """Extract a live-workout adjustment from the reply, or None if it isn't one.

    The LLM is untrusted: names are resolved against the catalog, swap/adjust/remove
    actions must target an exercise actually in the session, values are clamped, and
    the action count is capped. The result is a *suggestion* only — it is persisted
    exclusively by POST /ai/sessions/{id}/adjust on explicit user accept.
    """
    json_str = _extract_json_block(raw_reply)
    if json_str is None:
        return None
    try:
        data = json.loads(json_str)
        draft = AiAdjustmentDraft.model_validate(data)
    except Exception:
        return None

    # Exercises actually in the live session (ownership-filtered).
    result = await db.execute(
        select(SetLog.exercise_id)
        .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
        .where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
        .distinct()
    )
    session_exercise_ids = {str(row[0]) for row in result}
    if not session_exercise_ids:
        return None

    actions: list[SuggestedAdjustmentAction] = []
    for raw in draft.actions[:MAX_ADJUSTMENT_ACTIONS]:
        resolved = await _resolve_exercise_with_name(raw.exercise, db)
        if resolved is None:
            logger.info("Adjustment: could not resolve %r — skipping", raw.exercise)
            continue
        exercise_id, exercise_name = resolved

        # swap / adjust_weight / remove must target an exercise in the session.
        if raw.type != "add" and exercise_id not in session_exercise_ids:
            logger.info(
                "Adjustment: %r (%s) is not in the live session — skipping",
                raw.exercise,
                raw.type,
            )
            continue

        new_exercise_id: str | None = None
        new_exercise_name: str | None = None
        if raw.type == "swap":
            new_resolved = (
                await _resolve_exercise_with_name(raw.new_exercise, db)
                if raw.new_exercise
                else None
            )
            if new_resolved is None or new_resolved[0] == exercise_id:
                logger.info(
                    "Adjustment: swap target %r unresolvable or same — skipping",
                    raw.new_exercise,
                )
                continue
            new_exercise_id, new_exercise_name = new_resolved
        if raw.type == "adjust_weight" and raw.weight is None:
            continue
        if raw.type == "add" and (raw.sets is None or raw.reps is None):
            raw.sets = raw.sets or 3
            raw.reps = raw.reps or 8

        action = SuggestedAdjustmentAction(
            type=raw.type,
            exercise_id=exercise_id,
            exercise_name=exercise_name,
            new_exercise_id=new_exercise_id,
            new_exercise_name=new_exercise_name,
            sets=clamp_int(raw.sets, SETS_BOUNDS) if raw.sets is not None else None,
            reps=clamp_int(raw.reps, REPS_BOUNDS) if raw.reps is not None else None,
            weight=clamp_weight(raw.weight),
            summary=raw.summary.strip() or _default_summary(raw.type, exercise_name, new_exercise_name, raw.weight),
        )
        actions.append(action)

    if not actions:
        return None
    return SuggestedAdjustment(actions=actions)


def _default_summary(
    action_type: str,
    exercise_name: str,
    new_exercise_name: str | None,
    weight: float | None,
) -> str:
    """Server-side fallback so the card never shows a blank line."""
    weight_txt = f" at {weight:g} lb" if weight is not None else ""
    if action_type == "swap":
        return f"Swap {exercise_name} for {new_exercise_name}{weight_txt}"
    if action_type == "adjust_weight":
        return f"Change {exercise_name} to{weight_txt or ' a lighter weight'}"
    if action_type == "remove":
        return f"Remove {exercise_name}"
    return f"Add {exercise_name}{weight_txt}"


async def _resolve_exercise_with_name(
    name: str, db: AsyncSession
) -> tuple[str, str] | None:
    """Like _resolve_exercise but also returns the canonical catalog name."""
    stmt = select(Exercise.id, Exercise.name).where(Exercise.name.ilike(name))
    row = (await db.execute(stmt)).first()
    if row is None:
        stmt = (
            select(Exercise.id, Exercise.name)
            .where(Exercise.name.ilike(f"%{name}%"))
            .limit(1)
        )
        row = (await db.execute(stmt)).first()
    return (str(row[0]), row[1]) if row else None


async def _resolve_exercise(name: str, db: AsyncSession) -> str | None:
    # Exact case-insensitive match
    stmt = select(Exercise.id).where(Exercise.name.ilike(name))
    result = await db.execute(stmt)
    row = result.scalar_one_or_none()
    if row:
        return str(row)

    # Partial match fallback
    stmt = select(Exercise.id).where(Exercise.name.ilike(f"%{name}%")).limit(1)
    result = await db.execute(stmt)
    row = result.scalar_one_or_none()
    return str(row) if row else None
