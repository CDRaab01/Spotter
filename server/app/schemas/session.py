import datetime
import uuid
from typing import Literal

from pydantic import BaseModel, Field

from app.limits import REPS_BOUNDS, RPE_BOUNDS, WEIGHT_BOUNDS_LB

# Mirrors app.limits.SET_TYPES — Literal needs the values spelled out for static typing.
SetType = Literal["normal", "warmup", "drop", "failure", "amrap"]


class SessionCreate(BaseModel):
    routine_id: uuid.UUID | None = None
    date: datetime.date
    note: str | None = None


class SessionUpdate(BaseModel):
    status: str | None = None
    duration_seconds: int | None = Field(default=None, ge=0)
    note: str | None = None
    exercise_notes: dict[str, str] | None = None


class SetLogCreate(BaseModel):
    exercise_id: uuid.UUID
    set_number: int = Field(ge=1)
    reps: int = Field(ge=REPS_BOUNDS[0], le=REPS_BOUNDS[1])
    weight: float | None = Field(
        default=None, ge=WEIGHT_BOUNDS_LB[0], le=WEIGHT_BOUNDS_LB[1]
    )
    completed: bool = False
    rpe: float | None = Field(default=None, ge=RPE_BOUNDS[0], le=RPE_BOUNDS[1])
    set_type: SetType = "normal"


class SetLogUpdate(BaseModel):
    reps: int | None = Field(default=None, ge=REPS_BOUNDS[0], le=REPS_BOUNDS[1])
    weight: float | None = Field(
        default=None, ge=WEIGHT_BOUNDS_LB[0], le=WEIGHT_BOUNDS_LB[1]
    )
    completed: bool | None = None
    rpe: float | None = Field(default=None, ge=RPE_BOUNDS[0], le=RPE_BOUNDS[1])
    set_type: SetType | None = None


class SetLogOut(BaseModel):
    id: uuid.UUID
    session_id: uuid.UUID
    exercise_id: uuid.UUID
    set_number: int
    reps: int
    weight: float | None = None
    completed: bool = False
    completed_at: datetime.datetime | None = None
    rpe: float | None = None
    set_type: str = "normal"
    # Enriched fields — populated when the session has an associated plan
    exercise_name: str | None = None
    target_sets: int | None = None
    target_reps: int | None = None
    target_weight: float | None = None
    superset_group: int | None = None


class MuscleGroupSummary(BaseModel):
    muscle_group: str
    sets: int
    volume: float  # in kg; client converts to preferred unit


class SessionOut(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    routine_id: uuid.UUID | None
    routine_name: str | None = None
    date: datetime.date
    status: str
    duration_seconds: int | None
    note: str | None
    exercise_notes: dict[str, str] | None = None
    set_logs: list[SetLogOut] = []
    muscle_groups: list[MuscleGroupSummary] = []
    # True when this session was seeded during its program's scheduled deload week
    # (computed at read time — see program_service.is_deload_day).
    is_deload: bool = False


class ExerciseSummary(BaseModel):
    exercise_name: str
    completed_sets: int
    total_sets: int


class SessionSummary(BaseModel):
    id: uuid.UUID
    date: datetime.date
    routine_name: str | None = None
    status: str
    duration_seconds: int | None = None
    total_sets: int
    completed_sets: int
    exercises: list[ExerciseSummary] = []


class ExercisePrior(BaseModel):
    exercise_id: uuid.UUID
    exercise_name: str | None = None
    reps: int
    weight: float | None = None
    date: datetime.date
    last_sets: list[SetLogOut] = []
    # Progression-aware suggestion for the upcoming session (None for bodyweight)
    suggested_weight: float | None = None
    suggested_reason: str | None = None
    # Progressive-overload engine (ROADMAP2 T3 #1): the rep goal, the action the client renders
    # (add_weight | add_reps | hold | deload | bodyweight), the best-set estimated 1RM, and a PR flag.
    suggested_reps: int | None = None
    action: str | None = None
    e1rm: float | None = None
    is_pr: bool = False
