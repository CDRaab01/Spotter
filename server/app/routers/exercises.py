import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status
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


@router.get("/{exercise_id}", response_model=ExerciseOut)
async def get_exercise(
    exercise_id: str,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """One catalog exercise with its detail content (instructions + secondary muscles).

    The path param is parsed by hand so a malformed id reads as "no such exercise"
    (404) rather than a validation error — the catalog is shared reference data and
    an unknown/invalid id means the same thing to the client either way.
    """
    try:
        parsed_id = uuid.UUID(exercise_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Exercise not found")
    result = await db.execute(select(Exercise).where(Exercise.id == parsed_id))
    exercise = result.scalar_one_or_none()
    if exercise is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Exercise not found")
    return exercise
