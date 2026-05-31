import datetime
import uuid

from pydantic import BaseModel


class SessionCreate(BaseModel):
    plan_id: uuid.UUID | None = None
    date: datetime.date
    note: str | None = None


class SetLogCreate(BaseModel):
    exercise_id: uuid.UUID
    set_number: int
    reps: int
    weight: float | None = None
    completed: bool = False


class SetLogOut(SetLogCreate):
    id: uuid.UUID
    session_id: uuid.UUID
    completed_at: datetime.datetime | None = None
    model_config = {"from_attributes": True}


class SessionOut(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    plan_id: uuid.UUID | None
    date: datetime.date
    status: str
    duration_seconds: int | None
    note: str | None
    set_logs: list[SetLogOut] = []
    model_config = {"from_attributes": True}
