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
from app.schemas.ai import AiPlanDraft, ChatRequest, ChatResponse, SuggestedPlan
from app.schemas.plan import PlannedExerciseIn
from app.services.ai.context_service import build_user_context
from app.services.ai.prompts import build_messages, validate_request, validate_response

logger = logging.getLogger(__name__)


async def chat(
    req: ChatRequest, db: AsyncSession, user_id: uuid.UUID | None = None
) -> ChatResponse:
    last_user = next(
        (m.content for m in reversed(req.messages) if m.role == "user"), ""
    )
    error = validate_request(last_user)
    if error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=error
        )

    history = [m.model_dump() for m in req.messages[:-1]]
    user_context = await _merged_context(db, user_id, req.user_context)
    messages = build_messages(history, last_user, user_context)

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
    clean_reply = validate_response(raw_reply)
    suggested_plan = await _extract_plan(raw_reply, db)
    return ChatResponse(reply=clean_reply, suggested_plan=suggested_plan)


async def _merged_context(
    db: AsyncSession, user_id: uuid.UUID | None, client_profile: str | None
) -> str | None:
    """Combine the server-derived (trusted) training history with the client's
    self-reported profile. The DB history is authoritative; the client string is
    treated as stated preferences only."""
    history = await build_user_context(db, user_id) if user_id else None
    profile = client_profile.strip() if client_profile else None
    if history and profile:
        return f"{history}\n\nAthlete-stated profile/preferences:\n{profile}"
    return history or profile


async def _extract_plan(raw_reply: str, db: AsyncSession) -> SuggestedPlan | None:
    # Look for a fenced JSON code block first
    match = re.search(r"```(?:json)?\s*(.*?)\s*```", raw_reply, re.DOTALL)
    if match:
        json_str = match.group(1)
    else:
        # Fall back to bare JSON object
        match = re.search(r"\{.*\}", raw_reply, re.DOTALL)
        if not match:
            return None
        json_str = match.group(0)

    if not json_str.strip().startswith("{"):
        return None

    try:
        data = json.loads(json_str)
        draft = AiPlanDraft.model_validate(data)
    except Exception:
        return None

    resolved: list[PlannedExerciseIn] = []
    for ex in draft.exercises:
        exercise_id = await _resolve_exercise(ex.exercise_id, db)
        if exercise_id is None:
            logger.info("Could not resolve exercise name: %r — skipping", ex.exercise_id)
            continue
        # The LLM is untrusted — cap absurd values into the sanity bounds rather
        # than letting one bad number reject the whole plan (per CLAUDE.md guardrails).
        resolved.append(
            PlannedExerciseIn(
                exercise_id=exercise_id,
                target_sets=clamp_int(ex.target_sets, SETS_BOUNDS),
                target_reps=clamp_int(ex.target_reps, REPS_BOUNDS),
                target_weight=None if ex.is_bodyweight else clamp_weight(ex.target_weight),
                is_bodyweight=ex.is_bodyweight,
                order=max(0, ex.order),
            )
        )

    if not resolved:
        return None

    return SuggestedPlan(name=draft.name, exercises=resolved)


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
