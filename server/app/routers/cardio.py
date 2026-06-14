import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.cardio import CardioSessionCreate, CardioSessionOut, CardioSessionUpdate
from app.security import get_current_user
from app.services.cardio_service import (
    create_cardio_session,
    list_cardio_sessions,
    update_cardio_session,
)

router = APIRouter(prefix="/cardio", tags=["cardio"])

# NOTE: program *definitions* (Couch to 5K, Free Run) ship static client-side, so
# there is deliberately no `GET /cardio/programs` — only the user's session records
# are persisted server-side.


@router.get("/sessions", response_model=list[CardioSessionOut])
async def list_sessions_endpoint(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    program_id: Annotated[str | None, Query()] = None,
):
    return await list_cardio_sessions(db, current_user.id, program_id)


@router.post("/sessions", response_model=CardioSessionOut, status_code=201)
async def start_session_endpoint(
    req: CardioSessionCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await create_cardio_session(db, current_user.id, req)


@router.patch("/sessions/{session_id}", response_model=CardioSessionOut)
async def update_session_endpoint(
    session_id: uuid.UUID,
    req: CardioSessionUpdate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await update_cardio_session(db, current_user.id, session_id, req)
