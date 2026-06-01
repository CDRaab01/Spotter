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
