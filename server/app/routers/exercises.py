from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.exercise import Exercise
from app.models.user import User
from app.schemas.exercise import ExerciseOut
from app.security import get_current_user

router = APIRouter(prefix="/exercises", tags=["exercises"])


@router.get("", response_model=list[ExerciseOut])
async def list_exercises(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    search: str | None = Query(default=None),
):
    stmt = select(Exercise).order_by(Exercise.name)
    if search and search.strip():
        stmt = stmt.where(Exercise.name.ilike(f"%{search.strip()}%"))
    result = await db.execute(stmt)
    return result.scalars().all()
