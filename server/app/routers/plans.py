import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.plan import PlanCreate, PlanOut, PlanUpdate
from app.security import get_current_user
from app.services.plan_service import (
    create_plan,
    delete_plan,
    get_plan,
    get_user_plans,
    rename_plan,
)

router = APIRouter(prefix="/plans", tags=["plans"])


@router.get("", response_model=list[PlanOut])
async def list_plans(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_user_plans(db, current_user.id)


@router.post("", response_model=PlanOut, status_code=201)
async def new_plan(
    req: PlanCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await create_plan(db, current_user.id, req)


@router.get("/{plan_id}", response_model=PlanOut)
async def get_one_plan(
    plan_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_plan(db, current_user.id, plan_id)


@router.patch("/{plan_id}", response_model=PlanOut)
async def update_plan(
    plan_id: uuid.UUID,
    req: PlanUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await rename_plan(db, current_user.id, plan_id, req)


@router.delete("/{plan_id}", status_code=204)
async def remove_plan(
    plan_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await delete_plan(db, current_user.id, plan_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
