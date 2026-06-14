import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


def _utcnow() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


class CardioSession(Base):
    """A user-performed cardio run (Couch to 5K guided day, or a Free Run).

    The cardio *program definitions* live client-side (static, bundled) — only the
    user's session records are persisted here so they can drive Resume / history /
    completion dates and later feed the AI coach.
    """

    __tablename__ = "cardio_sessions"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    # Client-side program identifier (e.g. "c25k", "free_run"); not an FK.
    program_id: Mapped[str] = mapped_column(String(50))
    # Null for Free Run; set for guided programs.
    week_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    day_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    started_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow
    )
    completed_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    status: Mapped[str] = mapped_column(String(20), default="in_progress")
    total_elapsed_sec: Mapped[int] = mapped_column(Integer, default=0)

    user = relationship("User", back_populates="cardio_sessions", lazy="raise")
