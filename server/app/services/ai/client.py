import json
import logging
import re

import httpx
from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.exercise import Exercise
from app.schemas.ai import AiPlanDraft, ChatRequest, ChatResponse, SuggestedPlan
from app.schemas.plan import PlannedExerciseIn
from app.services.ai.prompts import build_messages, validate_request, validate_response

logger = logging.getLogger(__name__)


async def chat(req: ChatRequest, db: AsyncSession) -> ChatResponse:
    last_user = next(
        (m.content for m in reversed(req.messages) if m.role == "user"), ""
    )
    error = validate_request(last_user)
    if error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=error
        )

    history = [m.model_dump() for m in req.messages[:-1]]
    messages = build_messages(history, last_user)

    async with httpx.AsyncClient(timeout=60.0) as client:
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
        resolved.append(
            PlannedExerciseIn(
                exercise_id=exercise_id,
                target_sets=ex.target_sets,
                target_reps=ex.target_reps,
                target_weight=ex.target_weight,
                is_bodyweight=ex.is_bodyweight,
                order=ex.order,
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
