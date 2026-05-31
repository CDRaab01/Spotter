import datetime
import uuid

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class SetLog(Base):
    __tablename__ = "set_logs"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    session_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("workout_sessions.id", ondelete="CASCADE")
    )
    exercise_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("exercises.id"))
    set_number: Mapped[int] = mapped_column(Integer)
    reps: Mapped[int] = mapped_column(Integer)
    weight: Mapped[float | None] = mapped_column(Float, nullable=True)
    completed: Mapped[bool] = mapped_column(Boolean, default=False)
    completed_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    session = relationship("WorkoutSession", back_populates="set_logs", lazy="raise")
    exercise = relationship("Exercise", lazy="raise")
