import uuid

from sqlalchemy import String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class Exercise(Base):
    __tablename__ = "exercises"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    muscle_group: Mapped[str | None] = mapped_column(String(100), nullable=True)
    equipment: Mapped[str | None] = mapped_column(String(100), nullable=True)
    # Form cues + execution steps (seed content backfilled by migration 0013).
    instructions: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Comma-separated lowercase muscle names beyond muscle_group (e.g. "front delts, triceps").
    secondary_muscles: Mapped[str | None] = mapped_column(String(255), nullable=True)
