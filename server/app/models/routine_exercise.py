import uuid

from sqlalchemy import Boolean, Float, ForeignKey, Integer
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class RoutineExercise(Base):
    __tablename__ = "routine_exercises"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    routine_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("workout_routines.id", ondelete="CASCADE")
    )
    exercise_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("exercises.id"))
    target_sets: Mapped[int] = mapped_column(Integer)
    target_reps: Mapped[int] = mapped_column(Integer)
    target_weight: Mapped[float | None] = mapped_column(Float, nullable=True)
    is_bodyweight: Mapped[bool] = mapped_column(Boolean, default=False)
    order: Mapped[int] = mapped_column(Integer, default=0)
    superset_group: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # Per-exercise rest between sets in seconds (bounds in app/limits.py); null = app default.
    rest_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)

    routine = relationship("WorkoutRoutine", back_populates="routine_exercises", lazy="raise")
    exercise = relationship("Exercise", lazy="raise")
