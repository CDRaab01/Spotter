import datetime
import uuid

from sqlalchemy import Date, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class WorkoutSession(Base):
    __tablename__ = "workout_sessions"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    plan_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("workout_plans.id"), nullable=True
    )
    date: Mapped[datetime.date] = mapped_column(Date)
    status: Mapped[str] = mapped_column(String(20), default="in_progress")
    duration_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)

    user = relationship("User", back_populates="sessions", lazy="raise")
    plan = relationship("WorkoutPlan", back_populates="sessions", lazy="raise")
    set_logs = relationship(
        "SetLog",
        back_populates="session",
        cascade="all, delete-orphan",
        lazy="raise",
    )
