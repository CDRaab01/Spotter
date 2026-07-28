import datetime
import uuid

from pydantic import BaseModel, Field

from app.limits import REPS_BOUNDS, REST_SECONDS_BOUNDS, SETS_BOUNDS, WEIGHT_BOUNDS_LB


class RoutineExerciseIn(BaseModel):
    exercise_id: uuid.UUID
    target_sets: int = Field(ge=SETS_BOUNDS[0], le=SETS_BOUNDS[1])
    target_reps: int = Field(ge=REPS_BOUNDS[0], le=REPS_BOUNDS[1])
    target_weight: float | None = Field(
        default=None, ge=WEIGHT_BOUNDS_LB[0], le=WEIGHT_BOUNDS_LB[1]
    )
    is_bodyweight: bool = False
    order: int = Field(default=0, ge=0)
    superset_group: int | None = Field(default=None, ge=0)
    rest_seconds: int | None = Field(
        default=None, ge=REST_SECONDS_BOUNDS[0], le=REST_SECONDS_BOUNDS[1]
    )


class RoutineCreate(BaseModel):
    name: str
    source: str = "manual"
    exercises: list[RoutineExerciseIn] = []


class RoutineUpdate(BaseModel):
    name: str


class RoutineExerciseOut(RoutineExerciseIn):
    id: uuid.UUID
    exercise_name: str | None = None
    model_config = {"from_attributes": True}


class RoutineExercisesUpdate(BaseModel):
    exercises: list[RoutineExerciseIn]


class RoutineOut(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    name: str
    source: str
    created_at: datetime.datetime
    exercises: list[RoutineExerciseOut] = []
    model_config = {"from_attributes": True}
