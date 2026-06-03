import uuid
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.session import (
    ExercisePrior,
    SessionCreate,
    SessionOut,
    SessionSummary,
    SessionUpdate,
    SetLogCreate,
    SetLogOut,
    SetLogUpdate,
)
from app.security import get_current_user
from app.services.session_service import (
    add_set,
    create_session,
    delete_session,
    get_prior_bests,
    get_session,
    list_sessions,
    update_session,
    update_set_log,
)

router = APIRouter(prefix="/sessions", tags=["sessions"])


@router.get("", response_model=list[SessionSummary])
async def get_sessions(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await list_sessions(db, current_user.id)


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


@router.patch("/{session_id}", response_model=SessionOut)
async def update_one_session(
    session_id: uuid.UUID,
    req: SessionUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await update_session(db, current_user.id, session_id, req)


@router.post("/{session_id}/sets", response_model=SetLogOut, status_code=201)
async def log_set(
    session_id: uuid.UUID,
    req: SetLogCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await get_session(db, current_user.id, session_id)
    return await add_set(db, session_id, req)


@router.patch("/{session_id}/sets/{set_id}", response_model=SetLogOut)
async def update_set(
    session_id: uuid.UUID,
    set_id: uuid.UUID,
    req: SetLogUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await update_set_log(db, current_user.id, session_id, set_id, req)


@router.delete("/{session_id}", status_code=204)
async def delete_one_session(
    session_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await delete_session(db, current_user.id, session_id)


@router.get("/{session_id}/prior-bests", response_model=list[ExercisePrior])
async def prior_bests(
    session_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_prior_bests(db, current_user.id, session_id)
