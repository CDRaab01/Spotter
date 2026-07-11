"""Cross-app workout-status lookups (read-only, consumed by the sister app "Plate").

Answers "did this user train on <date>?" by counting *completed* strength and cardio sessions for
the day. Strength sessions carry a local ``date`` column; cardio sessions are timestamped, so we
match their ``completed_at`` against the day's UTC window. Read-only — no writes here.
"""
import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.cardio_session import CardioSession
from app.models.workout_session import WorkoutSession
from app.schemas.workout import (
    WorkoutDayOut,
    WorkoutRangeDayOut,
    WorkoutRangeOut,
    WorkoutRangeTotalsOut,
)

# A consumer asking for more than a month of days is a different feature (bulk export), not a
# training-week/-month summary — cap mirrors Cookbook's plan_service MAX_RANGE_DAYS discipline.
MAX_RANGE_DAYS = 31


async def get_workout_day(
    db: AsyncSession, user_id: uuid.UUID, day: datetime.date
) -> WorkoutDayOut:
    strength = await db.scalar(
        select(func.count())
        .select_from(WorkoutSession)
        .where(
            WorkoutSession.user_id == user_id,
            WorkoutSession.date == day,
            WorkoutSession.status == "completed",
        )
    )

    # Cardio has no date column — match the day's UTC window on completed_at.
    start = datetime.datetime(day.year, day.month, day.day, tzinfo=datetime.timezone.utc)
    end = start + datetime.timedelta(days=1)
    cardio = await db.scalar(
        select(func.count())
        .select_from(CardioSession)
        .where(
            CardioSession.user_id == user_id,
            CardioSession.status == "completed",
            CardioSession.completed_at >= start,
            CardioSession.completed_at < end,
        )
    )

    strength = strength or 0
    cardio = cardio or 0
    return WorkoutDayOut(
        date=day,
        trained=(strength + cardio) > 0,
        strength_sessions=strength,
        cardio_sessions=cardio,
    )


async def get_workout_range(
    db: AsyncSession, user_id: uuid.UUID, start: datetime.date, end: datetime.date
) -> WorkoutRangeOut:
    """Completed training grouped by day over [start, end] — the range form of
    :func:`get_workout_day` (ROADMAP2 Tier 2 #1b). Sparse: days with nothing completed are
    omitted; ``totals.days_trained`` counts the days that appear."""
    if end < start:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "end must be on or after start")
    if (end - start).days + 1 > MAX_RANGE_DAYS:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, f"range is capped at {MAX_RANGE_DAYS} days"
        )

    strength_rows = (
        await db.execute(
            select(WorkoutSession.date, func.count())
            .where(
                WorkoutSession.user_id == user_id,
                WorkoutSession.date >= start,
                WorkoutSession.date <= end,
                WorkoutSession.status == "completed",
            )
            .group_by(WorkoutSession.date)
        )
    ).all()

    # Cardio has no date column — bucket completed_at (UTC) into days, same convention as the
    # single-day lookup above.
    window_start = datetime.datetime(start.year, start.month, start.day, tzinfo=datetime.timezone.utc)
    window_end = datetime.datetime(
        end.year, end.month, end.day, tzinfo=datetime.timezone.utc
    ) + datetime.timedelta(days=1)
    cardio_rows = (
        await db.execute(
            select(func.date(CardioSession.completed_at), func.count())
            .where(
                CardioSession.user_id == user_id,
                CardioSession.status == "completed",
                CardioSession.completed_at >= window_start,
                CardioSession.completed_at < window_end,
            )
            .group_by(func.date(CardioSession.completed_at))
        )
    ).all()

    by_day: dict[datetime.date, list[int]] = {}
    for day, count in strength_rows:
        by_day.setdefault(day, [0, 0])[0] = int(count)
    for day, count in cardio_rows:
        day_date = day if isinstance(day, datetime.date) else datetime.date.fromisoformat(str(day))
        by_day.setdefault(day_date, [0, 0])[1] = int(count)

    days = [
        WorkoutRangeDayOut(date=day, strength_sessions=s, cardio_sessions=c)
        for day, (s, c) in sorted(by_day.items())
    ]
    return WorkoutRangeOut(
        start=start,
        end=end,
        days=days,
        totals=WorkoutRangeTotalsOut(
            days_trained=len(days),
            strength_sessions=sum(d.strength_sessions for d in days),
            cardio_sessions=sum(d.cardio_sessions for d in days),
        ),
    )
