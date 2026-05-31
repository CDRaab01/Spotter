import datetime
import uuid

from pydantic import BaseModel


class BodyMetricCreate(BaseModel):
    date: datetime.date
    weight: float
    bodyfat: float | None = None


class BodyMetricOut(BodyMetricCreate):
    id: uuid.UUID
    user_id: uuid.UUID
    model_config = {"from_attributes": True}
