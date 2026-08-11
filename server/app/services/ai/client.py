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
    MAX_PROGRAM_DAYS,
    PROGRAM_DAY_LABEL_MAX_LEN,
    PROGRAM_NAME_MAX_LEN,
    PROGRAM_WEEKS_BOUNDS,
    REPS_BOUNDS,
    ROUTINE_NAME_MAX_LEN,
    SETS_BOUNDS,
    clamp_int,
    clamp_weight,
)
from app.models.exercise import Exercise
from app.models.set_log import SetLog
from app.models.user import User
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
    SuggestedProfileUpdate,
    SuggestedRoutine,
    SuggestedProgram,
    SuggestedProgramDay,
)
from app.schemas.routine import RoutineExerciseIn
from app.schemas.user import TRAINING_PROFILE_FIELDS, TRAINING_PROFILE_MAX_LENS
from app.services.ai.context_service import (
    build_current_session_context,
    build_exercise_catalog,
    build_user_context,
)
from app.services.ai.prompts import build_messages, validate_request, validate_response

logger = logging.getLogger(__name__)


async def lm_completion(messages: list[dict], temperature: float = 0.7) -> str:
    """POST ``messages`` to LM Studio and return the raw reply text.

    The single LM transport for chat, the post-workout debrief, and the weekly
    recap, so the error mapping lives in one place: HTTP error status → 502,
    timeout → 504, unreachable → 503, malformed body → 502.
    """
    async with httpx.AsyncClient(timeout=settings.lm_studio_timeout) as client:
        try:
            resp = await client.post(
                f"{settings.lm_studio_base_url}/chat/completions",
                json={
                    "model": settings.lm_studio_model,
                    "messages": messages,
                    "temperature": temperature,
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
    return raw_reply


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

    raw_reply = await lm_completion(messages)
    # Invariant: exactly one *workout* suggestion per reply, plus an optional
    # profile update. Among the workout suggestions the precedence is unchanged —
    # adjustment (only meaningful with a live session in context) wins over
    # program, which wins over a single plan.
    #
    # The profile update is deliberately OUTSIDE that contest: learning a durable
    # fact about the athlete's setup ("I bought a squat rack") is not a workout
    # suggestion, and forcing exactly-one would mean the user applies the
    # equipment change and then has to re-ask for the program they wanted in the
    # same breath. It is extracted independently and may accompany any of them.
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
    suggested_profile_update = (
        await _extract_profile_update(raw_reply, db, user_id) if user_id else None
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
        elif suggested_profile_update:
            clean_reply = (
                "I can update your saved training profile — review the change below "
                "and confirm to apply it."
            )
    return ChatResponse(
        reply=clean_reply,
        suggested_routine=suggested_routine,
        suggested_program=suggested_program,
        suggested_adjustment=suggested_adjustment,
        suggested_profile_update=suggested_profile_update,
    )


async def _merged_context(
    db: AsyncSession,
    user_id: uuid.UUID | None,
    client_profile: str | None,
    current_session_id: uuid.UUID | None = None,
) -> str | None:
    """Combine the server-derived (trusted) profile + training history with the
    client's self-reported profile. The DB-derived block — which now leads with the
    user's persisted training profile (equipment/experience/goal/age/limitations) —
    is authoritative; the client string is still treated as stated preferences only
    and is never promoted. When a workout is in progress, a trusted live-session
    block is prepended so the coach is aware of it."""
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


def _candidate_json_blocks(raw_reply: str) -> list[str]:
    """Every JSON object in the reply, fenced blocks first, else a bare object.

    A reply may now legitimately carry TWO blocks (a workout suggestion and a
    profile update), so "the first block" is no longer a safe proxy for "the
    block I want" — see _first_valid_block.
    """
    candidates = [
        c for c in re.findall(r"```(?:json)?\s*(.*?)\s*```", raw_reply, re.DOTALL)
        if c.strip().startswith("{")
    ]
    if candidates:
        return candidates
    bare = _extract_json_block(raw_reply)
    return [bare] if bare is not None else []


def _first_valid_block(raw_reply: str, model):
    """First JSON block in the reply that validates as ``model``, else None.

    Order-independent by design: the workout extractors used to validate only the
    first block, which silently dropped the workout suggestion whenever the model
    emitted the profile-update block ahead of it. Prompt ordering is guidance, not
    a guarantee — local models reorder freely — so selection is by SHAPE, not
    position. A block of the wrong shape is skipped, never fatal.
    """
    for json_str in _candidate_json_blocks(raw_reply):
        try:
            return model.model_validate(json.loads(json_str))
        except Exception:
            continue
    return None


def _extract_keyed_json_block(raw_reply: str, key: str) -> dict | None:
    """Return the object stored under top-level ``key`` in a JSON block, or None.

    Unlike ``_extract_json_block`` (first block in the reply wins), this scans
    EVERY fenced block. That is what lets a profile update ride alongside a
    plan/program block in the same reply — they are independent suggestion
    types, not competing ones. Malformed blocks are skipped, not fatal.
    """
    candidates = re.findall(r"```(?:json)?\s*(.*?)\s*```", raw_reply, re.DOTALL)
    if not candidates:
        # No fenced block — fall back to a bare object, same as the sibling helper.
        bare = _extract_json_block(raw_reply)
        candidates = [bare] if bare is not None else []
    for candidate in candidates:
        if not candidate.strip().startswith("{"):
            continue
        try:
            data = json.loads(candidate)
        except ValueError:
            continue
        if isinstance(data, dict) and isinstance(data.get(key), dict):
            return data[key]
    return None


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
    draft = _first_valid_block(raw_reply, AiPlanDraft)
    if draft is None:
        return None

    resolved = await _resolve_exercises(draft.exercises, db)
    if not resolved:
        return None
    # Clamp the untrusted name so the eventual POST /routines can't be rejected
    # for length (same philosophy as the value clamps above).
    name = (draft.name.strip() or "AI Routine")[:ROUTINE_NAME_MAX_LEN]
    return SuggestedRoutine(name=name, exercises=resolved)


async def _extract_program(raw_reply: str, db: AsyncSession) -> SuggestedProgram | None:
    """Extract a multi-day program from the reply, or None if it isn't one.

    A program JSON has a top-level ``days`` array; a single-plan JSON (top-level
    ``exercises``) fails ``AiProgramDraft`` validation and returns None so the
    caller can fall back to ``_extract_plan``.
    """
    draft = _first_valid_block(raw_reply, AiProgramDraft)
    if draft is None:
        return None

    days: list[SuggestedProgramDay] = []
    for i, day in enumerate(draft.days[:MAX_PROGRAM_DAYS]):
        was_rest = len(day.exercises) == 0
        resolved = await _resolve_exercises(day.exercises, db)
        # Keep genuine rest days (no exercises to begin with); drop days whose
        # exercises all failed to resolve (mirrors plan behavior).
        if not resolved and not was_rest:
            continue
        # Clamp the untrusted label into the schema cap (blank → positional name)
        # so the day survives SuggestedProgramDay validation and the later accept.
        label = (day.label.strip() or f"Day {i + 1}")[:PROGRAM_DAY_LABEL_MAX_LEN]
        days.append(
            SuggestedProgramDay(label=label, exercises=resolved, order=i)
        )

    # Need at least one day that actually trains something.
    if not any(d.exercises for d in days):
        return None

    # Periodization fields are untrusted model output: clamp weeks into bounds and
    # drop deload_week entirely unless it lands inside the (clamped) mesocycle.
    weeks = clamp_int(draft.weeks, PROGRAM_WEEKS_BOUNDS) if draft.weeks is not None else None
    deload_week = draft.deload_week
    if weeks is None or deload_week is None or not (1 <= deload_week <= weeks):
        deload_week = None
    name = (draft.name.strip() or "AI Program")[:PROGRAM_NAME_MAX_LEN]
    return SuggestedProgram(
        name=name, days=days, weeks=weeks, deload_week=deload_week
    )


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
    draft = _first_valid_block(raw_reply, AiAdjustmentDraft)
    if draft is None:
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


async def _extract_profile_update(
    raw_reply: str, db: AsyncSession, user_id: uuid.UUID
) -> SuggestedProfileUpdate | None:
    """Extract a proposed training-profile change from the reply, or None.

    The model is untrusted, so this layer takes the same posture as the plan and
    adjustment extractors: unknown keys are dropped, values are stripped, and an
    over-long value is **clamped** (truncated to the column's length) rather than
    dropping the whole suggestion, exactly as out-of-bounds numbers are clamped
    elsewhere.

    A proposal that matches what is already stored is not a proposal. Every
    field is diffed against the user's CURRENT saved profile
    (case/whitespace-insensitive) and dropped when it is already the stored
    value; if nothing differs afterwards this returns None, so the user never
    sees a card that would change nothing.

    Returns None when: there is no ``profile_update`` block, the JSON is
    malformed, every profile field is null/blank, or ``summary`` is missing or
    blank (the card would have nothing to say).

    This is a *suggestion* only. It is persisted exclusively when the user
    confirms the card, which re-uses the existing ``PATCH /users/me/profile`` —
    that endpoint's partial semantics (an omitted key is unchanged) are already
    exactly this payload's shape, which is why no apply endpoint is added here.
    """
    raw = _extract_keyed_json_block(raw_reply, "profile_update")
    if raw is None:
        return None

    summary = raw.get("summary")
    summary = summary.strip() if isinstance(summary, str) else ""
    if not summary:
        return None

    current = await _current_training_profile(db, user_id)
    fields: dict[str, str] = {}
    for name in TRAINING_PROFILE_FIELDS:  # unknown keys are ignored by construction
        value = raw.get(name)
        if not isinstance(value, str):
            continue
        value = value.strip()[: TRAINING_PROFILE_MAX_LENS[name]].strip()
        if not value:
            continue
        stored = (current.get(name) or "").strip()
        if value.casefold() == stored.casefold():
            logger.info("Profile update: %r already stored — dropping field", name)
            continue
        fields[name] = value

    if not fields:
        return None
    return SuggestedProfileUpdate(summary=summary, **fields)


async def _current_training_profile(
    db: AsyncSession, user_id: uuid.UUID
) -> dict[str, str | None]:
    """The user's stored training profile, so a proposal can be diffed against it.

    Read straight off the `users` row — the same source
    ``context_service._training_profile_block`` shows the model — so "propose only
    a genuine change" is judged against what the model was actually told.
    """
    result = await db.execute(
        select(*(getattr(User, field) for field in TRAINING_PROFILE_FIELDS)).where(
            User.id == user_id
        )
    )
    row = result.first()
    return dict(zip(TRAINING_PROFILE_FIELDS, row)) if row is not None else {}


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
