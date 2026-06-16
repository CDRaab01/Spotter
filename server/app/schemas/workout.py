"""Cross-app workout-status schema (read-only, consumed by the sister app "Plate").

Plate's nutrition targets get a training-day bump when the user trained, so it asks Spotter
"did this user train on <date>?". This is a deliberately small, stable contract — Spotter's
internals can change freely as long as this shape holds.
"""
import datetime

from pydantic import BaseModel


class WorkoutDayOut(BaseModel):
    """Whether the user trained on a given date, and a light breakdown.

    ``trained`` is the headline signal Plate keys off; the counts let it (or a future caller)
    reason about how much, without exposing any of Spotter's internal session detail.
    """

    date: datetime.date
    trained: bool
    strength_sessions: int
    cardio_sessions: int
