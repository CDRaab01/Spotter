import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.cardio_session import CardioSession
from app.schemas.cardio import (
    CardioManualCreate,
    CardioSessionCreate,
    CardioSessionOut,
    CardioSessionUpdate,
)

# Sentinel program id for after-the-fact manual entries (not a guided/free live run).
MANUAL_PROGRAM_ID = "manual"


def _utcnow() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


async def create_cardio_session(
    db: AsyncSession, user_id: uuid.UUID, req: CardioSessionCreate
) -> CardioSessionOut:
    session = CardioSession(
        user_id=user_id,
        program_id=req.program_id,
        week_number=req.week_number,
        day_number=req.day_number,
        status="in_progress",
        total_elapsed_sec=0,
    )
    db.add(session)
    await db.commit()
    await db.refresh(session)
    return CardioSessionOut.model_validate(session)


async def create_manual_cardio_session(
    db: AsyncSession, user_id: uuid.UUID, req: CardioManualCreate
) -> CardioSessionOut:
    """Create a *completed* manual cardio session (a walk/run logged after the fact).

    Unlike a live run this skips the in_progress lifecycle entirely: it lands as ``completed``
    with ``completed_at`` anchored to the given date (noon UTC, so it buckets onto the intended
    calendar day regardless of the viewer's timezone) so it counts toward history/streak/stats
    exactly like a completed guided/free run.
    """
    day = req.date or _utcnow().date()
    # Anchor at noon UTC so day-boundary conversions (client display, cross-app /workouts
    # bucketing) resolve to the intended calendar day rather than drifting a day either way.
    completed_at = datetime.datetime(
        day.year, day.month, day.day, 12, 0, 0, tzinfo=datetime.timezone.utc
    )
    session = CardioSession(
        user_id=user_id,
        program_id=MANUAL_PROGRAM_ID,
        week_number=None,
        day_number=None,
        started_at=completed_at,
        completed_at=completed_at,
        status="completed",
        total_elapsed_sec=req.duration_sec,
        activity_type=req.activity_type,
        distance_meters=req.distance_meters,
    )
    db.add(session)
    await db.commit()
    await db.refresh(session)
    return CardioSessionOut.model_validate(session)


async def list_cardio_sessions(
    db: AsyncSession, user_id: uuid.UUID, program_id: str | None = None
) -> list[CardioSessionOut]:
    stmt = select(CardioSession).where(CardioSession.user_id == user_id)
    if program_id is not None:
        stmt = stmt.where(CardioSession.program_id == program_id)
    stmt = stmt.order_by(CardioSession.started_at.desc())
    result = await db.execute(stmt)
    return [CardioSessionOut.model_validate(s) for s in result.scalars().all()]


async def _get_owned(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> CardioSession:
    result = await db.execute(
        select(CardioSession).where(
            CardioSession.id == session_id, CardioSession.user_id == user_id
        )
    )
    session = result.scalar_one_or_none()
    if not session:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Cardio session not found"
        )
    return session


async def update_cardio_session(
    db: AsyncSession,
    user_id: uuid.UUID,
    session_id: uuid.UUID,
    req: CardioSessionUpdate,
) -> CardioSessionOut:
    session = await _get_owned(db, user_id, session_id)

    if req.total_elapsed_sec is not None:
        session.total_elapsed_sec = req.total_elapsed_sec
    if req.status is not None:
        session.status = req.status
        # Stamp/clear the completion time as the session finishes or reopens so the
        # overview's "Completed <date>" line has a real timestamp to render.
        if req.status == "completed":
            session.completed_at = session.completed_at or _utcnow()
        elif req.status == "in_progress":
            session.completed_at = None

    await db.commit()
    await db.refresh(session)
    return CardioSessionOut.model_validate(session)
