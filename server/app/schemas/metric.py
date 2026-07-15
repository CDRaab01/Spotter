import datetime
import uuid

from pydantic import BaseModel, Field

from app.limits import BODYFAT_BOUNDS, BODY_WEIGHT_BOUNDS_LB, MEASUREMENT_BOUNDS

_M_LO, _M_HI = MEASUREMENT_BOUNDS


class BodyMetricCreate(BaseModel):
    date: datetime.date
    weight: float = Field(ge=BODY_WEIGHT_BOUNDS_LB[0], le=BODY_WEIGHT_BOUNDS_LB[1])
    bodyfat: float | None = Field(default=None, ge=BODYFAT_BOUNDS[0], le=BODYFAT_BOUNDS[1])
    # Optional tape measurements (client sends its unit; server just stores the number).
    neck: float | None = Field(default=None, ge=_M_LO, le=_M_HI)
    chest: float | None = Field(default=None, ge=_M_LO, le=_M_HI)
    waist: float | None = Field(default=None, ge=_M_LO, le=_M_HI)
    hips: float | None = Field(default=None, ge=_M_LO, le=_M_HI)
    arm: float | None = Field(default=None, ge=_M_LO, le=_M_HI)
    thigh: float | None = Field(default=None, ge=_M_LO, le=_M_HI)


class BodyMetricOut(BodyMetricCreate):
    id: uuid.UUID
    user_id: uuid.UUID
    model_config = {"from_attributes": True}
