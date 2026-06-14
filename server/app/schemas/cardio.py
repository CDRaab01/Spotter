import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

# Valid lifecycle states for a cardio session.
CARDIO_STATUSES = {"in_progress", "completed", "abandoned"}

# A single run can't reasonably exceed a few hours; bound the elapsed counter so a
# bad client value can't persist nonsense (mirrors the sanity-bounds convention).
MAX_ELAPSED_SEC = 6 * 60 * 60


class CardioSessionCreate(BaseModel):
    program_id: str = Field(min_length=1, max_length=50)
    week_number: int | None = Field(default=None, ge=1, le=52)
    day_number: int | None = Field(default=None, ge=1, le=7)


class CardioSessionUpdate(BaseModel):
    status: str | None = None
    total_elapsed_sec: int | None = Field(default=None, ge=0, le=MAX_ELAPSED_SEC)

    @field_validator("status")
    @classmethod
    def _validate_status(cls, v: str | None) -> str | None:
        if v is not None and v not in CARDIO_STATUSES:
            raise ValueError(f"status must be one of {sorted(CARDIO_STATUSES)}")
        return v


class CardioSessionOut(BaseModel):
    id: uuid.UUID
    program_id: str
    week_number: int | None
    day_number: int | None
    started_at: datetime.datetime
    completed_at: datetime.datetime | None
    status: str
    total_elapsed_sec: int

    model_config = {"from_attributes": True}
