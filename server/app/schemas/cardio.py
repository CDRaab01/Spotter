import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

# Valid lifecycle states for a cardio session.
CARDIO_STATUSES = {"in_progress", "completed", "abandoned"}

# Valid manual-entry activity types (a manual log is free-form: just walk vs run).
CARDIO_ACTIVITY_TYPES = {"walk", "run"}

# A single run can't reasonably exceed a few hours; bound the elapsed counter so a
# bad client value can't persist nonsense (mirrors the sanity-bounds convention).
MAX_ELAPSED_SEC = 6 * 60 * 60

# Sanity ceiling for a manually-entered distance (meters). ~200 km covers an ultra;
# anything beyond is a bad client value, not a real session.
MAX_DISTANCE_METERS = 200_000


class CardioSessionCreate(BaseModel):
    program_id: str = Field(min_length=1, max_length=50)
    week_number: int | None = Field(default=None, ge=1, le=52)
    day_number: int | None = Field(default=None, ge=1, le=7)


class CardioManualCreate(BaseModel):
    """Log a walk/run after the fact — creates a *completed* session directly.

    Distinct from the live-run create path (:class:`CardioSessionCreate`): the client sends a
    finished duration (and optional distance/date), not a program day to start timing.
    """

    activity_type: str = Field(min_length=1, max_length=10)
    duration_sec: int = Field(gt=0, le=MAX_ELAPSED_SEC)
    distance_meters: int | None = Field(default=None, ge=0, le=MAX_DISTANCE_METERS)
    # Calendar date the activity happened (local to the user). Defaults to today when omitted.
    date: datetime.date | None = None

    @field_validator("activity_type")
    @classmethod
    def _validate_activity_type(cls, v: str) -> str:
        v = v.strip().lower()
        if v not in CARDIO_ACTIVITY_TYPES:
            raise ValueError(
                f"activity_type must be one of {sorted(CARDIO_ACTIVITY_TYPES)}"
            )
        return v


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
    activity_type: str | None = None
    distance_meters: int | None = None

    model_config = {"from_attributes": True}
