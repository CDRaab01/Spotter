import datetime
import uuid

from pydantic import BaseModel, Field

from app.limits import BODYFAT_BOUNDS, BODY_WEIGHT_BOUNDS_LB


class BodyMetricCreate(BaseModel):
    date: datetime.date
    weight: float = Field(ge=BODY_WEIGHT_BOUNDS_LB[0], le=BODY_WEIGHT_BOUNDS_LB[1])
    bodyfat: float | None = Field(
        default=None, ge=BODYFAT_BOUNDS[0], le=BODYFAT_BOUNDS[1]
    )


class BodyMetricOut(BodyMetricCreate):
    id: uuid.UUID
    user_id: uuid.UUID
    model_config = {"from_attributes": True}
