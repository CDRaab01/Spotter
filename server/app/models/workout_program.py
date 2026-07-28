import datetime
import uuid

from sqlalchemy import Boolean, Date, DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class WorkoutProgram(Base):
    __tablename__ = "workout_programs"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    name: Mapped[str] = mapped_column(String(255))
    is_active: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True, server_default=func.now()
    )
    # Where the program came from: manual | preset | ai.
    source: Mapped[str] = mapped_column(String(16), default="manual", server_default="manual")
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Periodization: mesocycle length in weeks + which week (1-based) is the scheduled
    # deload. Both optional — a program without them has no week structure.
    weeks: Mapped[int | None] = mapped_column(Integer, nullable=True)
    deload_week: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # Stamped on first activation; anchors current_week (mesocycles cycle from here).
    started_on: Mapped[datetime.date | None] = mapped_column(Date, nullable=True)

    days = relationship(
        "ProgramDay",
        back_populates="program",
        cascade="all, delete-orphan",
        lazy="raise",
        order_by="ProgramDay.order",
    )
