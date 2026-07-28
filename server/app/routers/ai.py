import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.user import User
from app.schemas.ai import (
    AcceptProgramRequest,
    ApplyAdjustmentRequest,
    ChatRequest,
    ChatResponse,
    DebriefOut,
    WeeklyRecapOut,
)
from app.schemas.program import ProgramOut
from app.schemas.session import SessionOut
from app.security import get_current_user
from app.services.ai.adjustment_apply import apply_adjustment
from app.services.ai.client import chat
from app.services.ai.debrief import debrief_session
from app.services.ai.program_persist import accept_program
from app.services.ai.recap import weekly_recap

router = APIRouter(prefix="/ai", tags=["ai"])


@router.post("/chat", response_model=ChatResponse)
@limiter.limit("20/minute")
async def ai_chat(
    request: Request,
    req: ChatRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await chat(req, db, current_user.id)


@router.post("/programs/accept", response_model=ProgramOut, status_code=201)
@limiter.limit("20/minute")
async def accept_ai_program(
    request: Request,
    req: AcceptProgramRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await accept_program(db, current_user.id, req)


@router.post("/sessions/{session_id}/adjust", response_model=SessionOut)
@limiter.limit("20/minute")
async def apply_ai_adjustment(
    request: Request,
    session_id: uuid.UUID,
    req: ApplyAdjustmentRequest,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Persist a user-accepted AI workout adjustment (the Apply button)."""
    return await apply_adjustment(db, current_user.id, session_id, req)


@router.post("/sessions/{session_id}/debrief", response_model=DebriefOut)
@limiter.limit("20/minute")
async def ai_debrief(
    request: Request,
    session_id: uuid.UUID,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """AI recap of a just-completed workout (409 unless the session is completed)."""
    return DebriefOut(debrief=await debrief_session(db, current_user.id, session_id))


@router.get("/recap/weekly", response_model=WeeklyRecapOut)
@limiter.limit("5/minute")
async def ai_weekly_recap(
    request: Request,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    """Mon→today stats (always server-computed) + a best-effort LM narrative."""
    return await weekly_recap(db, current_user.id)
