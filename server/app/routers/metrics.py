from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.user import User
from app.schemas.metric import BodyMetricCreate, BodyMetricOut
from app.security import get_current_user
from app.services.metric_service import add_weight_metric, get_weight_metrics

router = APIRouter(prefix="/metrics", tags=["metrics"])


@router.get("/weight", response_model=list[BodyMetricOut])
async def list_weight(
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await get_weight_metrics(db, current_user.id)


@router.post("/weight", response_model=BodyMetricOut, status_code=201)
async def add_weight(
    req: BodyMetricCreate,
    current_user: Annotated[User, Depends(get_current_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await add_weight_metric(db, current_user.id, req)
