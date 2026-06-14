import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.cardio_session import CardioSession
from app.schemas.cardio import CardioSessionCreate, CardioSessionOut, CardioSessionUpdate


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
