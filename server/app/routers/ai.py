from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.user import User
from app.schemas.ai import AcceptProgramRequest, ChatRequest, ChatResponse
from app.schemas.program import ProgramOut
from app.security import get_current_user
from app.services.ai.client import chat
from app.services.ai.program_persist import accept_program

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
