import datetime
import uuid

from pydantic import BaseModel, Field

from app.limits import REPS_BOUNDS, SETS_BOUNDS, WEIGHT_BOUNDS_LB


class PlannedExerciseIn(BaseModel):
    exercise_id: uuid.UUID
    target_sets: int = Field(ge=SETS_BOUNDS[0], le=SETS_BOUNDS[1])
    target_reps: int = Field(ge=REPS_BOUNDS[0], le=REPS_BOUNDS[1])
    target_weight: float | None = Field(
        default=None, ge=WEIGHT_BOUNDS_LB[0], le=WEIGHT_BOUNDS_LB[1]
    )
    is_bodyweight: bool = False
    order: int = Field(default=0, ge=0)
    superset_group: int | None = Field(default=None, ge=0)


class PlanCreate(BaseModel):
    name: str
    source: str = "manual"
    exercises: list[PlannedExerciseIn] = []


class PlanUpdate(BaseModel):
    name: str


class PlannedExerciseOut(PlannedExerciseIn):
    id: uuid.UUID
    exercise_name: str | None = None
    model_config = {"from_attributes": True}


class PlannedExercisesUpdate(BaseModel):
    exercises: list[PlannedExerciseIn]


class PlanOut(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    name: str
    source: str
    created_at: datetime.datetime
    exercises: list[PlannedExerciseOut] = []
    model_config = {"from_attributes": True}
