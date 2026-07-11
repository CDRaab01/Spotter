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


class WorkoutRangeDayOut(BaseModel):
    """One active day inside a range — days with no completed sessions are omitted (sparse)."""

    date: datetime.date
    strength_sessions: int
    cardio_sessions: int


class WorkoutRangeTotalsOut(BaseModel):
    days_trained: int
    strength_sessions: int
    cardio_sessions: int


class WorkoutRangeOut(BaseModel):
    """Training over a date range (ROADMAP2 Tier 2 #1b) — the week-shaped read that lets Plate's
    coach frame a whole training week ("trained 4 of the last 7 days"), and any future digest
    aggregate a month. Same deliberately-small-contract philosophy as `WorkoutDayOut`: counts
    only, never session detail."""

    start: datetime.date
    end: datetime.date
    days: list[WorkoutRangeDayOut]
    totals: WorkoutRangeTotalsOut
