from pydantic import BaseModel

from app.schemas.plan import PlannedExerciseIn


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    messages: list[ChatMessage]
    user_context: str | None = None


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


class ChatResponse(BaseModel):
    reply: str
    suggested_plan: SuggestedPlan | None = None
