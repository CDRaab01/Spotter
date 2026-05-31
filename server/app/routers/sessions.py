import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.session import SessionCreate, SessionOut, SetLogCreate, SetLogOut
from app.security import get_current_user
from app.services.session_service import add_set, create_session, get_session

router = APIRouter(prefix="/sessions", tags=["sessions"])


@router.post("", response_model=SessionOut, status_code=201)
async def start_session(
    req: SessionCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await create_session(db, current_user.id, req)


@router.get("/{session_id}", response_model=SessionOut)
async def get_one_session(
    session_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_session(db, current_user.id, session_id)


@router.post("/{session_id}/sets", response_model=SetLogOut, status_code=201)
async def log_set(
    session_id: uuid.UUID,
    req: SetLogCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await get_session(db, current_user.id, session_id)
    return await add_set(db, session_id, req)
