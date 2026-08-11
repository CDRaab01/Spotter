import datetime
import uuid
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from app.limits import (
    MAX_PROGRAM_DAYS,
    PROGRAM_DAY_LABEL_MAX_LEN,
    PROGRAM_NAME_MAX_LEN,
    PROGRAM_WEEKS_BOUNDS,
)

ProgramSource = Literal["manual", "preset", "ai"]


def _check_deload_week(weeks: int | None, deload_week: int | None) -> None:
    """A deload week only makes sense inside a defined mesocycle: it requires
    ``weeks`` in the same payload and must fall within 1..weeks (422 otherwise)."""
    if deload_week is None:
        return
    if weeks is None:
        raise ValueError("deload_week requires weeks to be set")
    if not (1 <= deload_week <= weeks):
        raise ValueError("deload_week must be between 1 and weeks")


class ProgramDayIn(BaseModel):
    routine_id: uuid.UUID | None = None
    label: str = Field(min_length=1, max_length=PROGRAM_DAY_LABEL_MAX_LEN)
    order: int = 0


class ProgramDayOut(BaseModel):
    id: uuid.UUID
    routine_id: uuid.UUID | None
    label: str
    order: int
    routine_name: str | None = None


class ProgramCreate(BaseModel):
    name: str = Field(min_length=1, max_length=PROGRAM_NAME_MAX_LEN)
    days: list[ProgramDayIn] = Field(default_factory=list, max_length=MAX_PROGRAM_DAYS)
    source: ProgramSource = "manual"
    description: str | None = None
    weeks: int | None = Field(
        default=None, ge=PROGRAM_WEEKS_BOUNDS[0], le=PROGRAM_WEEKS_BOUNDS[1]
    )
    deload_week: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def _validate_deload(self) -> "ProgramCreate":
        _check_deload_week(self.weeks, self.deload_week)
        return self


class ProgramUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=PROGRAM_NAME_MAX_LEN)
    is_active: bool | None = None
    source: ProgramSource | None = None
    description: str | None = None
    weeks: int | None = Field(
        default=None, ge=PROGRAM_WEEKS_BOUNDS[0], le=PROGRAM_WEEKS_BOUNDS[1]
    )
    deload_week: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def _validate_deload(self) -> "ProgramUpdate":
        _check_deload_week(self.weeks, self.deload_week)
        return self


class ProgramDaysUpdate(BaseModel):
    days: list[ProgramDayIn] = Field(max_length=MAX_PROGRAM_DAYS)


class ProgramOut(BaseModel):
    id: uuid.UUID
    name: str
    is_active: bool
    days: list[ProgramDayOut] = []
    created_at: datetime.datetime | None = None
    started_on: datetime.date | None = None
    source: str = "manual"
    description: str | None = None
    weeks: int | None = None
    deload_week: int | None = None
    # Computed at read time (program_service.current_week): the 1-based week the
    # program is in today (mesocycles cycle) and whether that week is the deload.
    current_week: int | None = None
    is_deload_week: bool = False
