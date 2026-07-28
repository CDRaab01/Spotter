import uuid
from datetime import datetime

from pydantic import BaseModel, Field

# The writable training-profile fields, in the order they are rendered into the
# trusted AI context. Single source of truth for the router's partial update and
# for reset_account's clear.
TRAINING_PROFILE_FIELDS = (
    "equipment",
    "experience",
    "goal",
    "age_group",
    "limitations",
)

# Max lengths mirror the `users` columns added in migration 0016.
EQUIPMENT_MAX_LEN = 255
EXPERIENCE_MAX_LEN = 32
GOAL_MAX_LEN = 32
AGE_GROUP_MAX_LEN = 32
LIMITATIONS_MAX_LEN = 2000

# Field → max length. The write schema below *rejects* an over-long value (422 —
# it came from our own client), while the AI extraction layer *clamps* one (the
# model is untrusted and one long string must not drop the whole suggestion).
# Both read the same numbers from here so they can never drift apart.
TRAINING_PROFILE_MAX_LENS = {
    "equipment": EQUIPMENT_MAX_LEN,
    "experience": EXPERIENCE_MAX_LEN,
    "goal": GOAL_MAX_LEN,
    "age_group": AGE_GROUP_MAX_LEN,
    "limitations": LIMITATIONS_MAX_LEN,
}


class UserOut(BaseModel):
    id: uuid.UUID
    name: str
    email: str

    model_config = {"from_attributes": True}


class TrainingProfileOut(BaseModel):
    """The user's persisted training profile. Every field may be null (unset)."""

    equipment: str | None = None
    experience: str | None = None
    goal: str | None = None
    age_group: str | None = None
    limitations: str | None = None
    profile_updated_at: datetime | None = None

    model_config = {"from_attributes": True}


class TrainingProfileUpdate(BaseModel):
    """Partial update of the training profile.

    "Omitted" and "cleared" must not collapse, so the router works off
    ``model_fields_set`` (via ``model_dump(exclude_unset=True)``) rather than
    None-checks:

    - a field **absent** from the request body is left unchanged;
    - a field sent as ``""`` (or whitespace, or an explicit ``null``) is
      **cleared** to NULL.

    Lengths match the columns; over-long values are rejected with 422 rather
    than silently truncated by the database.
    """

    equipment: str | None = Field(default=None, max_length=EQUIPMENT_MAX_LEN)
    experience: str | None = Field(default=None, max_length=EXPERIENCE_MAX_LEN)
    goal: str | None = Field(default=None, max_length=GOAL_MAX_LEN)
    age_group: str | None = Field(default=None, max_length=AGE_GROUP_MAX_LEN)
    limitations: str | None = Field(default=None, max_length=LIMITATIONS_MAX_LEN)
