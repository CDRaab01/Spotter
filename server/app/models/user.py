import uuid

from sqlalchemy import String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(255))
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255))
    settings: Mapped[str | None] = mapped_column(Text, nullable=True)

    plans = relationship("WorkoutPlan", back_populates="user", lazy="raise")
    sessions = relationship("WorkoutSession", back_populates="user", lazy="raise")
    metrics = relationship("BodyMetric", back_populates="user", lazy="raise")
