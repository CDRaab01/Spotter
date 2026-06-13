import uuid
from typing import Literal

from pydantic import BaseModel, Field

from app.limits import REPS_BOUNDS, SETS_BOUNDS, WEIGHT_BOUNDS_LB
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


class SuggestedProgramDay(BaseModel):
    label: str
    exercises: list[RoutineExerciseIn] = []  # resolved + clamped; empty = rest day
    order: int = 0


class SuggestedProgram(BaseModel):
    name: str
    days: list[SuggestedProgramDay]


class AcceptProgramRequest(BaseModel):
    """Client echoes back the suggested program it showed; the server re-validates
    bounds via RoutineExerciseIn before persisting."""
    name: str
    days: list[SuggestedProgramDay]


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
