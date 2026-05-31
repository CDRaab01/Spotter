import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.calendar import CalendarEntry
from app.security import get_current_user
from app.services.calendar_service import get_calendar

router = APIRouter(prefix="/calendar", tags=["calendar"])


@router.get("", response_model=list[CalendarEntry])
async def calendar(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    from_date: datetime.date = Query(..., alias="from"),
    to_date: datetime.date = Query(..., alias="to"),
):
    return await get_calendar(db, current_user.id, from_date, to_date)
