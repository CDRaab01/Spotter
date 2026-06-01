import uuid

from pydantic import BaseModel


class ExerciseOut(BaseModel):
    id: uuid.UUID
    name: str
    muscle_group: str | None = None
    equipment: str | None = None
    model_config = {"from_attributes": True}
