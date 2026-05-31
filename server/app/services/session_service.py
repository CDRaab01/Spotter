import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.set_log import SetLog
from app.models.workout_session import WorkoutSession
from app.schemas.session import SessionCreate, SetLogCreate


async def create_session(
    db: AsyncSession, user_id: uuid.UUID, req: SessionCreate
) -> WorkoutSession:
    session = WorkoutSession(user_id=user_id, **req.model_dump())
    db.add(session)
    await db.commit()
    result = await db.execute(
        select(WorkoutSession)
        .where(WorkoutSession.id == session.id)
        .options(selectinload(WorkoutSession.set_logs))
    )
    return result.scalar_one()


async def get_session(
    db: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> WorkoutSession:
    result = await db.execute(
        select(WorkoutSession)
        .where(
            WorkoutSession.id == session_id,
            WorkoutSession.user_id == user_id,
        )
        .options(selectinload(WorkoutSession.set_logs))
    )
    s = result.scalar_one_or_none()
    if not s:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Session not found"
        )
    return s


async def add_set(
    db: AsyncSession, session_id: uuid.UUID, req: SetLogCreate
) -> SetLog:
    sl = SetLog(session_id=session_id, **req.model_dump())
    db.add(sl)
    await db.commit()
    await db.refresh(sl)
    return sl
