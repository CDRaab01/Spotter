import datetime
import uuid

from pydantic import BaseModel


class PlannedExerciseIn(BaseModel):
    exercise_id: uuid.UUID
    target_sets: int
    target_reps: int
    target_weight: float | None = None
    is_bodyweight: bool = False
    order: int = 0


class PlanCreate(BaseModel):
    name: str
    source: str = "manual"
    exercises: list[PlannedExerciseIn] = []


class PlanUpdate(BaseModel):
    name: str


class PlannedExerciseOut(PlannedExerciseIn):
    id: uuid.UUID
    model_config = {"from_attributes": True}


class PlanOut(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    name: str
    source: str
    created_at: datetime.datetime
    exercises: list[PlannedExerciseOut] = []
    model_config = {"from_attributes": True}
