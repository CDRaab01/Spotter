import datetime
import uuid
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from app.limits import PROGRAM_WEEKS_BOUNDS, REPS_BOUNDS, SETS_BOUNDS, WEIGHT_BOUNDS_LB
from app.schemas.program import _check_deload_week
from app.schemas.routine import RoutineExerciseIn


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    messages: list[ChatMessage]
    user_context: str | None = None
    # When the user is chatting from within an active workout, the client passes the
    # in-progress session id so the server can inject a trusted "workout in progress"
    # context block. The AI may then propose a session adjustment, but it is only
    # persisted via POST /ai/sessions/{id}/adjust on explicit user accept.
    current_session_id: uuid.UUID | None = None


class AiPlanExercise(BaseModel):
    exercise_id: str  # human-readable name in LLM output; resolved to UUID server-side
    target_sets: int
    target_reps: int
    target_weight: float | None = None
    is_bodyweight: bool = False
    order: int = 0


class AiPlanDraft(BaseModel):
    name: str
    source: str = "ai"
    exercises: list[AiPlanExercise]


class SuggestedRoutine(BaseModel):
    name: str
    exercises: list[RoutineExerciseIn]


# ── Multi-day program generation ────────────────────────────────────────────


class AiProgramDay(BaseModel):
    label: str
    exercises: list[AiPlanExercise] = []  # empty list = rest day


class AiProgramDraft(BaseModel):
    name: str
    source: str = "ai"
    days: list[AiProgramDay]
    # Optional periodization from the model (untrusted): mesocycle length + which
    # 1-based week is the deload. Clamped/validated by _extract_program, never here.
    weeks: int | None = None
    deload_week: int | None = None


class SuggestedProgramDay(BaseModel):
    label: str
    exercises: list[RoutineExerciseIn] = []  # resolved + clamped; empty = rest day
    order: int = 0


class SuggestedProgram(BaseModel):
    name: str
    days: list[SuggestedProgramDay]
    # Clamped by extraction: weeks within PROGRAM_WEEKS_BOUNDS, deload_week None
    # unless it falls inside 1..weeks.
    weeks: int | None = None
    deload_week: int | None = None


class AcceptProgramRequest(BaseModel):
    """Client echoes back the suggested program it showed; the server re-validates
    bounds via RoutineExerciseIn (and the weeks/deload_week Field + validator
    below) before persisting."""
    name: str
    days: list[SuggestedProgramDay]
    weeks: int | None = Field(
        default=None, ge=PROGRAM_WEEKS_BOUNDS[0], le=PROGRAM_WEEKS_BOUNDS[1]
    )
    deload_week: int | None = Field(default=None, ge=1)
    description: str | None = None
    source: Literal["ai", "preset", "manual"] = "ai"
    # False = save the program without touching the currently active one.
    activate: bool = True

    @model_validator(mode="after")
    def _validate_deload(self) -> "AcceptProgramRequest":
        _check_deload_week(self.weeks, self.deload_week)
        return self


# ── Live workout adjustments ────────────────────────────────────────────────


class AiAdjustmentAction(BaseModel):
    """One action as emitted by the LLM (untrusted; exercise names, raw values)."""

    type: Literal["swap", "adjust_weight", "remove", "add"]
    exercise: str  # human-readable name; resolved to UUID server-side
    new_exercise: str | None = None  # swap only
    sets: int | None = None
    reps: int | None = None
    weight: float | None = None  # None = bodyweight (swap/add) — never inferred
    summary: str = ""


class AiAdjustmentDraft(BaseModel):
    """Top-level `actions` key discriminates this from plan (`exercises`) and
    program (`days`) JSON, so the three extractors stay mutually exclusive."""

    actions: list[AiAdjustmentAction]


class SuggestedAdjustmentAction(BaseModel):
    """A resolved, clamped action. Echoed back on apply, where the Field bounds
    re-validate it (422) — same trick as AcceptProgramRequest."""

    type: Literal["swap", "adjust_weight", "remove", "add"]
    exercise_id: uuid.UUID
    exercise_name: str
    new_exercise_id: uuid.UUID | None = None
    new_exercise_name: str | None = None
    sets: int | None = Field(default=None, ge=SETS_BOUNDS[0], le=SETS_BOUNDS[1])
    reps: int | None = Field(default=None, ge=REPS_BOUNDS[0], le=REPS_BOUNDS[1])
    weight: float | None = Field(
        default=None, ge=WEIGHT_BOUNDS_LB[0], le=WEIGHT_BOUNDS_LB[1]
    )
    summary: str


class SuggestedAdjustment(BaseModel):
    actions: list[SuggestedAdjustmentAction]


class ApplyAdjustmentRequest(BaseModel):
    """Client echoes back the adjustment it showed; bounds re-validate on the way in."""

    actions: list[SuggestedAdjustmentAction]
    apply_to_routine: bool = True


class ChatResponse(BaseModel):
    reply: str
    suggested_routine: SuggestedRoutine | None = None
    suggested_program: SuggestedProgram | None = None
    suggested_adjustment: SuggestedAdjustment | None = None


# ── Post-workout debrief + weekly recap ─────────────────────────────────────


class DebriefOut(BaseModel):
    debrief: str


class WeeklyRecapStats(BaseModel):
    strength_sessions: int = 0
    cardio_sessions: int = 0
    total_volume_lb: float = 0.0
    active_minutes: int = 0
    prs: int = 0
    # First vs last bodyweight metric in the window; null with fewer than 2 points.
    bodyweight_delta_lb: float | None = None


class WeeklyRecapOut(BaseModel):
    week_start: datetime.date
    stats: WeeklyRecapStats
    # Best-effort LM narrative over the stats — null when LM Studio is unavailable
    # (the numbers are always server-computed regardless).
    narrative: str | None = None
