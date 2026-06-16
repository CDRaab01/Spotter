"""Cross-app workout-status lookups (read-only, consumed by the sister app "Plate").

Answers "did this user train on <date>?" by counting *completed* strength and cardio sessions for
the day. Strength sessions carry a local ``date`` column; cardio sessions are timestamped, so we
match their ``completed_at`` against the day's UTC window. Read-only — no writes here.
"""
import datetime
import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.cardio_session import CardioSession
from app.models.workout_session import WorkoutSession
from app.schemas.workout import WorkoutDayOut


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
