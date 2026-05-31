import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.plan import PlanCreate, PlanOut
from app.security import get_current_user
from app.services.plan_service import create_plan, get_plan, get_user_plans

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
