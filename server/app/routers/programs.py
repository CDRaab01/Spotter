import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.program import ProgramCreate, ProgramDayOut, ProgramDaysUpdate, ProgramOut, ProgramUpdate
from app.security import get_current_user
from app.services.program_service import (
    create_program,
    delete_program,
    get_next_day,
    get_program,
    list_programs,
    replace_days,
    update_program,
)

router = APIRouter(prefix="/programs", tags=["programs"])


@router.get("", response_model=list[ProgramOut])
async def list_programs_endpoint(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await list_programs(db, current_user.id)


@router.post("", response_model=ProgramOut, status_code=201)
async def create_program_endpoint(
    req: ProgramCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await create_program(db, current_user.id, req)


@router.get("/active/next", response_model=ProgramDayOut | None)
async def get_next_day_endpoint(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_next_day(db, current_user.id)


@router.get("/{program_id}", response_model=ProgramOut)
async def get_program_endpoint(
    program_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_program(db, current_user.id, program_id)


@router.patch("/{program_id}", response_model=ProgramOut)
async def update_program_endpoint(
    program_id: uuid.UUID,
    req: ProgramUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await update_program(db, current_user.id, program_id, req)


@router.delete("/{program_id}", status_code=204)
async def delete_program_endpoint(
    program_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await delete_program(db, current_user.id, program_id)


@router.put("/{program_id}/days", response_model=ProgramOut)
async def replace_days_endpoint(
    program_id: uuid.UUID,
    req: ProgramDaysUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await replace_days(db, current_user.id, program_id, req)
