import datetime
import uuid

from pydantic import BaseModel


class SessionCreate(BaseModel):
    plan_id: uuid.UUID | None = None
    date: datetime.date
    note: str | None = None


class SessionUpdate(BaseModel):
    status: str | None = None
    duration_seconds: int | None = None
    note: str | None = None
    exercise_notes: dict[str, str] | None = None


class SetLogCreate(BaseModel):
    exercise_id: uuid.UUID
    set_number: int
    reps: int
    weight: float | None = None
    completed: bool = False


class SetLogUpdate(BaseModel):
    reps: int | None = None
    weight: float | None = None
    completed: bool | None = None


class SetLogOut(BaseModel):
    id: uuid.UUID
    session_id: uuid.UUID
    exercise_id: uuid.UUID
    set_number: int
    reps: int
    weight: float | None = None
    completed: bool = False
    completed_at: datetime.datetime | None = None
    # Enriched fields — populated when the session has an associated plan
    exercise_name: str | None = None
    target_sets: int | None = None
    target_reps: int | None = None
    target_weight: float | None = None


class SessionOut(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    plan_id: uuid.UUID | None
    plan_name: str | None = None
    date: datetime.date
    status: str
    duration_seconds: int | None
    note: str | None
    exercise_notes: dict[str, str] | None = None
    set_logs: list[SetLogOut] = []


class ExercisePrior(BaseModel):
    exercise_id: uuid.UUID
    exercise_name: str | None = None
    reps: int
    weight: float | None = None
    date: datetime.date
