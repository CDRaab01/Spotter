import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.progress import ExerciseProgressPoint, TrackedExercise
from app.security import get_current_user
from app.services.progress_service import get_exercise_progress, get_tracked_exercises

router = APIRouter(prefix="/progress", tags=["progress"])


@router.get("/exercises", response_model=list[TrackedExercise])
async def list_tracked_exercises(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_tracked_exercises(db, current_user.id)


@router.get("/exercises/{exercise_id}", response_model=list[ExerciseProgressPoint])
async def exercise_progress(
    exercise_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_exercise_progress(db, current_user.id, exercise_id)
