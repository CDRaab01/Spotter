"""Cross-app workout-status endpoint (read-only).

``GET /workouts?date=`` lets the sister app "Plate" learn whether the user trained on a date, so
Plate can apply its training-day nutrition bump. ``GET /workouts?start=&end=`` is the additive
range form (ROADMAP2 Tier 2 #1b): the week/month-shaped read behind Plate's weekly coach framing
and any future digest. Authenticated with a cross-app token (shared secret, keyed on email — see
``get_cross_app_user``), distinct from Spotter's own auth. Exposes only the small
:class:`WorkoutDayOut` / :class:`WorkoutRangeOut` contracts, never internal session detail.
"""
import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.user import User
from app.schemas.workout import WorkoutDayOut, WorkoutRangeOut
from app.security import get_cross_app_user
from app.services.workout_service import get_workout_day, get_workout_range

router = APIRouter(prefix="/workouts", tags=["workouts"])


@router.get("", response_model=WorkoutDayOut | WorkoutRangeOut)
@limiter.limit("60/minute")
async def workout_status(
    request: Request,
    current_user: Annotated[User, Depends(get_cross_app_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    date: datetime.date | None = Query(None, description="Single day to report"),
    start: datetime.date | None = Query(None, description="Range start (with end)"),
    end: datetime.date | None = Query(None, description="Range end (with start)"),
):
    """Exactly one form: ``?date=`` (the original single-day contract, unchanged) or
    ``?start=&end=`` (the range summary)."""
    if date is not None and (start is not None or end is not None):
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, "pass either date or start+end, not both"
        )
    if date is not None:
        return await get_workout_day(db, current_user.id, date)
    if start is not None and end is not None:
        return await get_workout_range(db, current_user.id, start, end)
    raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "pass date or start+end")
