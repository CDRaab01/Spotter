from typing import Annotated

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.body_metric import BodyMetric
from app.models.user import User
from app.models.workout_plan import WorkoutPlan
from app.models.workout_program import WorkoutProgram
from app.models.workout_session import WorkoutSession
from app.schemas.user import UserOut
from app.security import get_current_user

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/me", response_model=UserOut)
async def get_me(
    current_user: Annotated[User, Depends(get_current_user)],
):
    return current_user


@router.post("/reset", status_code=status.HTTP_204_NO_CONTENT)
async def reset_account(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Wipe all of the current user's data while keeping the account (login) intact.

    Deletes every plan, session, set, program, and body metric the user owns and
    clears their saved settings, leaving them in a clean first-run state. Child rows
    (set_logs, planned_exercises, program_days) are removed by their ON DELETE CASCADE;
    sessions are deleted before plans because `workout_sessions.plan_id` has no DB-level
    cascade. The User row, credentials, and reset-token fields are untouched.
    """
    uid = current_user.id
    await db.execute(delete(WorkoutSession).where(WorkoutSession.user_id == uid))
    await db.execute(delete(WorkoutProgram).where(WorkoutProgram.user_id == uid))
    await db.execute(delete(WorkoutPlan).where(WorkoutPlan.user_id == uid))
    await db.execute(delete(BodyMetric).where(BodyMetric.user_id == uid))
    current_user.settings = None
    await db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
