import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.workout_session import WorkoutSession
from app.schemas.calendar import CalendarEntry


async def get_calendar(
    db: AsyncSession,
    user_id: uuid.UUID,
    from_date: datetime.date,
    to_date: datetime.date,
) -> list[CalendarEntry]:
    result = await db.execute(
        select(WorkoutSession)
        .where(
            WorkoutSession.user_id == user_id,
            WorkoutSession.date >= from_date,
            WorkoutSession.date <= to_date,
        )
        .options(
            selectinload(WorkoutSession.set_logs),
            selectinload(WorkoutSession.plan),
        )
    )
    sessions = result.scalars().all()
    return [
        CalendarEntry(
            session_id=s.id,
            date=s.date,
            plan_name=s.plan.name if s.plan else None,
            status=s.status,
            set_count=len(s.set_logs),
        )
        for s in sessions
    ]
