"""Cross-app workout-status endpoint (read-only).

``GET /workouts?date=`` lets the sister app "Plate" learn whether the user trained on a date, so
Plate can apply its training-day nutrition bump. Authenticated with a cross-app token (shared
secret, keyed on email — see ``get_cross_app_user``), distinct from Spotter's own auth. Exposes
only the small :class:`WorkoutDayOut` contract, never internal session detail.
"""
import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.user import User
from app.schemas.workout import WorkoutDayOut
from app.security import get_cross_app_user
from app.services.workout_service import get_workout_day

router = APIRouter(prefix="/workouts", tags=["workouts"])


@router.get("", response_model=WorkoutDayOut)
@limiter.limit("60/minute")
async def workout_day(
    request: Request,
    current_user: Annotated[User, Depends(get_cross_app_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    date: datetime.date = Query(..., description="The day to report training status for"),
):
    return await get_workout_day(db, current_user.id, date)
