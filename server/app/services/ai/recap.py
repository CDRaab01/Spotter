"""In-app weekly recap (GET /ai/recap/weekly).

Aggregates the user's Monday→today training numbers server-side (ALWAYS — the
model never computes stats), then asks LM Studio for a short narrative over
them. The narrative is strictly best-effort: any LM failure (unreachable,
timeout, error status, malformed body) degrades to ``narrative: null`` and the
endpoint still returns 200 with the numbers — the dragonfly weekly-digest
degrade philosophy. Lives in services/ai/ because it is an LM caller; the reply
passes through validate_response like every other one.
"""

import datetime
import logging
import uuid

from fastapi import HTTPException
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.body_metric import BodyMetric
from app.models.cardio_session import CardioSession
from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.schemas.ai import WeeklyRecapOut, WeeklyRecapStats
from app.services.ai.client import lm_completion
from app.services.ai.prompts import RECAP_PROMPT, validate_response
from app.services.insights_service import count_prs_this_week

logger = logging.getLogger(__name__)


async def weekly_recap(db: AsyncSession, user_id: uuid.UUID) -> WeeklyRecapOut:
    today = datetime.date.today()
    week_start = today - datetime.timedelta(days=today.weekday())  # Monday
    stats = await _compute_stats(db, user_id, week_start, today)
    return WeeklyRecapOut(
        week_start=week_start,
        stats=stats,
        narrative=await _narrate(stats),
    )


async def _compute_stats(
    db: AsyncSession, user_id: uuid.UUID, week_start: datetime.date, today: datetime.date
) -> WeeklyRecapStats:
    strength_sessions = (
        await db.scalar(
            select(func.count())
            .select_from(WorkoutSession)
            .where(
                WorkoutSession.user_id == user_id,
                WorkoutSession.status == "completed",
                WorkoutSession.date >= week_start,
            )
        )
        or 0
    )

    # Cardio has no date column — bucket completed_at into the week's UTC window
    # (the workout_service cross-app convention).
    window_start = datetime.datetime(
        week_start.year, week_start.month, week_start.day, tzinfo=datetime.timezone.utc
    )
    cardio_where = (
        CardioSession.user_id == user_id,
        CardioSession.status == "completed",
        CardioSession.completed_at >= window_start,
    )
    cardio_sessions = (
        await db.scalar(select(func.count()).select_from(CardioSession).where(*cardio_where))
        or 0
    )
    cardio_seconds = (
        await db.scalar(
            select(func.coalesce(func.sum(CardioSession.total_elapsed_sec), 0)).where(
                *cardio_where
            )
        )
        or 0
    )

    total_volume = (
        await db.scalar(
            select(func.coalesce(func.sum(SetLog.reps * SetLog.weight), 0.0))
            .select_from(SetLog)
            .join(WorkoutSession, SetLog.session_id == WorkoutSession.id)
            .where(
                WorkoutSession.user_id == user_id,
                WorkoutSession.date >= week_start,
                SetLog.completed == True,  # noqa: E712
                SetLog.weight.is_not(None),
                SetLog.set_type != "warmup",  # working sets only
            )
        )
        or 0.0
    )

    strength_seconds = (
        await db.scalar(
            select(func.coalesce(func.sum(WorkoutSession.duration_seconds), 0)).where(
                WorkoutSession.user_id == user_id,
                WorkoutSession.status == "completed",
                WorkoutSession.date >= week_start,
            )
        )
        or 0
    )

    metric_rows = await db.execute(
        select(BodyMetric.weight)
        .where(BodyMetric.user_id == user_id, BodyMetric.date >= week_start)
        .order_by(BodyMetric.date, BodyMetric.id)
    )
    weights = [row[0] for row in metric_rows.all()]
    bodyweight_delta = round(weights[-1] - weights[0], 1) if len(weights) >= 2 else None

    return WeeklyRecapStats(
        strength_sessions=strength_sessions,
        cardio_sessions=cardio_sessions,
        total_volume_lb=round(float(total_volume), 1),
        active_minutes=(strength_seconds + cardio_seconds) // 60,
        prs=await count_prs_this_week(db, user_id, today),
        bodyweight_delta_lb=bodyweight_delta,
    )


async def _narrate(stats: WeeklyRecapStats) -> str | None:
    """Best-effort LM narrative — None on any failure, never an error."""
    delta_txt = (
        f"{stats.bodyweight_delta_lb:+g} lb"
        if stats.bodyweight_delta_lb is not None
        else "not tracked"
    )
    summary = (
        f"Strength sessions: {stats.strength_sessions}. "
        f"Cardio sessions: {stats.cardio_sessions}. "
        f"Total volume lifted: {stats.total_volume_lb:g} lb. "
        f"Active minutes: {stats.active_minutes}. "
        f"PRs this week: {stats.prs}. "
        f"Bodyweight change: {delta_txt}."
    )
    messages = [
        {"role": "system", "content": RECAP_PROMPT},
        {"role": "user", "content": f"This week's stats so far:\n{summary}"},
    ]
    try:
        return validate_response(await lm_completion(messages)) or None
    except HTTPException as e:
        logger.info("Weekly recap narrative skipped — LM unavailable (%s)", e.detail)
        return None
