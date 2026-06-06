import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class WorkoutRoutine(Base):
    __tablename__ = "workout_routines"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    name: Mapped[str] = mapped_column(String(255))
    source: Mapped[str] = mapped_column(String(20), default="manual")
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    user = relationship("User", back_populates="routines", lazy="raise")
    routine_exercises = relationship(
        "RoutineExercise",
        back_populates="routine",
        cascade="all, delete-orphan",
        lazy="raise",
    )
    sessions = relationship("WorkoutSession", back_populates="routine", lazy="raise")
