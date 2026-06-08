import json
import logging
import re
import uuid

import httpx
from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.limits import REPS_BOUNDS, SETS_BOUNDS, clamp_int, clamp_weight
from app.models.exercise import Exercise
from app.schemas.ai import (
    AiPlanDraft,
    AiPlanExercise,
    AiProgramDraft,
    ChatRequest,
    ChatResponse,
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
    # Check the new message and every prior user turn — injection can be embedded
    # in earlier history entries to bypass a guard that only checks the latest turn.
    for msg in req.messages:
        if msg.role == "user":
            error = validate_request(msg.content)
            if error:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=error
                )

    history = [m.model_dump() for m in req.messages[:-1]]
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

    data = resp.json()
    raw_reply = data["choices"][0]["message"]["content"]
    # Prefer a multi-day program when the model emitted one; otherwise fall back to a
    # single-session plan. Never return both.
    suggested_program = await _extract_program(raw_reply, db)
    suggested_routine = None if suggested_program else await _extract_plan(raw_reply, db)
    # The structured routine/program JSON is surfaced via the Save card — strip it from the
    # chat text so the bubble shows only the prose. If the model returned nothing but JSON,
    # fall back to a short prompt pointing at the Save card.
    clean_reply = validate_response(_strip_structured_blocks(raw_reply))
    if not clean_reply:
        if suggested_program:
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
