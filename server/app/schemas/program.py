import uuid

from pydantic import BaseModel


class ProgramDayIn(BaseModel):
    routine_id: uuid.UUID | None = None
    label: str
    order: int = 0


class ProgramDayOut(BaseModel):
    id: uuid.UUID
    routine_id: uuid.UUID | None
    label: str
    order: int
    routine_name: str | None = None


class ProgramCreate(BaseModel):
    name: str
    days: list[ProgramDayIn] = []


class ProgramUpdate(BaseModel):
    name: str | None = None
    is_active: bool | None = None


class ProgramDaysUpdate(BaseModel):
    days: list[ProgramDayIn]


class ProgramOut(BaseModel):
    id: uuid.UUID
    name: str
    is_active: bool
    days: list[ProgramDayOut] = []
