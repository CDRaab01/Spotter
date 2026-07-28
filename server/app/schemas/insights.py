import uuid

from pydantic import BaseModel


class StalledExercise(BaseModel):
    exercise_id: uuid.UUID
    exercise_name: str
    # Consecutive stalled sessions at this weight (>= progression.DELOAD_STALL_SESSIONS).
    sessions_stuck: int
    last_weight: float | None = None


class InsightsOut(BaseModel):
    stalled: list[StalledExercise] = []
    prs_this_week: int = 0
