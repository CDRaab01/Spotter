import datetime
import uuid

from pydantic import BaseModel


class TrackedExercise(BaseModel):
    exercise_id: uuid.UUID
    exercise_name: str


class ExerciseProgressPoint(BaseModel):
    date: datetime.date
    max_weight: float | None = None
    max_reps: int


class PersonalRecord(BaseModel):
    exercise_id: uuid.UUID
    exercise_name: str
    max_weight: float
    max_weight_reps: int
    best_est_1rm: float
    best_volume: float
    achieved_on: datetime.date
