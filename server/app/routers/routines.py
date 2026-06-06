import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.routine import RoutineCreate, RoutineOut, RoutineUpdate, RoutineExercisesUpdate
from app.security import get_current_user
from app.services.routine_service import (
    create_routine,
    delete_routine,
    get_routine,
    get_user_routines,
    rename_routine,
    update_routine_exercises,
)

router = APIRouter(prefix="/routines", tags=["routines"])


@router.get("", response_model=list[RoutineOut])
async def list_routines(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_user_routines(db, current_user.id)


@router.post("", response_model=RoutineOut, status_code=201)
async def new_routine(
    req: RoutineCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await create_routine(db, current_user.id, req)


@router.get("/{routine_id}", response_model=RoutineOut)
async def get_one_routine(
    routine_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_routine(db, current_user.id, routine_id)


@router.patch("/{routine_id}", response_model=RoutineOut)
async def update_routine(
    routine_id: uuid.UUID,
    req: RoutineUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await rename_routine(db, current_user.id, routine_id, req)


@router.delete("/{routine_id}", status_code=204)
async def remove_routine(
    routine_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await delete_routine(db, current_user.id, routine_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.put("/{routine_id}/exercises", response_model=RoutineOut)
async def replace_routine_exercises(
    routine_id: uuid.UUID,
    req: RoutineExercisesUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await update_routine_exercises(db, current_user.id, routine_id, req)
