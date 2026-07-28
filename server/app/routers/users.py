from datetime import datetime, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response, status
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.body_metric import BodyMetric
from app.models.user import User
from app.models.workout_routine import WorkoutRoutine
from app.models.workout_program import WorkoutProgram
from app.models.workout_session import WorkoutSession
from app.schemas.user import (
    TRAINING_PROFILE_FIELDS,
    TrainingProfileOut,
    TrainingProfileUpdate,
    UserOut,
)
from app.security import get_current_user

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/me", response_model=UserOut)
async def get_me(
    current_user: Annotated[User, Depends(get_current_user)],
):
    return current_user


@router.get("/me/profile", response_model=TrainingProfileOut)
async def get_training_profile(
    current_user: Annotated[User, Depends(get_current_user)],
):
    """The signed-in user's persisted training profile (all-null when never set)."""
    return current_user


@router.patch("/me/profile", response_model=TrainingProfileOut)
@limiter.limit("30/minute")
async def update_training_profile(
    request: Request,
    req: TrainingProfileUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Partially update the training profile and return the whole updated profile.

    Omitted fields are left untouched; a field sent as an empty string (or null)
    is cleared. `profile_updated_at` is stamped whenever anything is written, so
    a never-filled profile is distinguishable from a deliberately cleared one.
    """
    updates = req.model_dump(exclude_unset=True)
    for field in TRAINING_PROFILE_FIELDS:
        if field not in updates:
            continue  # omitted ≠ cleared
        value = updates[field]
        value = value.strip() if isinstance(value, str) else None
        setattr(current_user, field, value or None)
    if updates:
        current_user.profile_updated_at = datetime.now(timezone.utc)
        await db.commit()
        await db.refresh(current_user)
    return current_user


@router.post("/reset", status_code=status.HTTP_204_NO_CONTENT)
async def reset_account(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Wipe all of the current user's data while keeping the account (login) intact.

    Deletes every plan, session, set, program, and body metric the user owns and
    clears their saved training profile, leaving them in a clean first-run state. Child rows
    (set_logs, planned_exercises, program_days) are removed by their ON DELETE CASCADE;
    sessions are deleted before routines because `workout_sessions.routine_id` has no DB-level
    cascade. The User row, credentials, and reset-token fields are untouched.
    """
    uid = current_user.id
    await db.execute(delete(WorkoutSession).where(WorkoutSession.user_id == uid))
    await db.execute(delete(WorkoutProgram).where(WorkoutProgram.user_id == uid))
    await db.execute(delete(WorkoutRoutine).where(WorkoutRoutine.user_id == uid))
    await db.execute(delete(BodyMetric).where(BodyMetric.user_id == uid))
    for field in TRAINING_PROFILE_FIELDS:
        setattr(current_user, field, None)
    current_user.profile_updated_at = None
    await db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
