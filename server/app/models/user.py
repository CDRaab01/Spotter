import uuid
from datetime import datetime

from sqlalchemy import DateTime, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(255))
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255))
    reset_token: Mapped[str | None] = mapped_column(String(64), nullable=True)
    reset_token_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # Training profile (migration 0016). Persisted server-side so the AI coach gets
    # it as trusted context on every call, instead of depending on the client
    # attaching an onboarding string it may never have collected.
    equipment: Mapped[str | None] = mapped_column(String(255), nullable=True)
    experience: Mapped[str | None] = mapped_column(String(32), nullable=True)
    goal: Mapped[str | None] = mapped_column(String(32), nullable=True)
    age_group: Mapped[str | None] = mapped_column(String(32), nullable=True)
    limitations: Mapped[str | None] = mapped_column(Text, nullable=True)
    profile_updated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    routines = relationship("WorkoutRoutine", back_populates="user", lazy="raise")
    sessions = relationship("WorkoutSession", back_populates="user", lazy="raise")
    metrics = relationship("BodyMetric", back_populates="user", lazy="raise")
    cardio_sessions = relationship("CardioSession", back_populates="user", lazy="raise")
