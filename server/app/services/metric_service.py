import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.body_metric import BodyMetric
from app.schemas.metric import BodyMetricCreate


async def get_weight_metrics(
    db: AsyncSession, user_id: uuid.UUID
) -> list[BodyMetric]:
    result = await db.execute(
        select(BodyMetric)
        .where(BodyMetric.user_id == user_id)
        .order_by(BodyMetric.date)
    )
    return list(result.scalars().all())


async def add_weight_metric(
    db: AsyncSession, user_id: uuid.UUID, req: BodyMetricCreate
) -> BodyMetric:
    bm = BodyMetric(user_id=user_id, **req.model_dump())
    db.add(bm)
    await db.commit()
    await db.refresh(bm)
    return bm
