import uuid

from pydantic import BaseModel

from app.schemas.plan import PlannedExerciseIn


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    messages: list[ChatMessage]
    user_context: str | None = None
    # When the user is chatting from within an active workout, the client passes the
    # in-progress session id so the server can inject a trusted "workout in progress"
    # context block. Advice-only — the AI does not edit the log.
    current_session_id: uuid.UUID | None = None
    # Optional explicit routing hint. "generate" steers the turn to the larger plan
    # model (e.g. first-run auto-generate). Any other value / None = normal chat; the
    # server still falls back to a keyword heuristic to detect generation requests.
    intent: str | None = None


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


class SuggestedPlan(BaseModel):
    name: str
    exercises: list[PlannedExerciseIn]


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
    exercises: list[PlannedExerciseIn] = []  # resolved + clamped; empty = rest day
    order: int = 0


class SuggestedProgram(BaseModel):
    name: str
    days: list[SuggestedProgramDay]


class AcceptProgramRequest(BaseModel):
    """Client echoes back the suggested program it showed; the server re-validates
    bounds via PlannedExerciseIn before persisting."""
    name: str
    days: list[SuggestedProgramDay]


class ChatResponse(BaseModel):
    reply: str
    suggested_plan: SuggestedPlan | None = None
    suggested_program: SuggestedProgram | None = None
