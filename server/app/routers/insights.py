"""GET /insights — proactive coaching signals (stalled lifts + PRs this week)."""

from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.user import User
from app.schemas.insights import InsightsOut
from app.security import get_current_user
from app.services.insights_service import get_insights

router = APIRouter(tags=["insights"])


@router.get("/insights", response_model=InsightsOut)
@limiter.limit("30/minute")
async def insights(
    request: Request,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_insights(db, current_user.id)
