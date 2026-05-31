import datetime
import uuid

from sqlalchemy import Date, Float, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class BodyMetric(Base):
    __tablename__ = "body_metrics"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    date: Mapped[datetime.date] = mapped_column(Date)
    weight: Mapped[float] = mapped_column(Float)
    bodyfat: Mapped[float | None] = mapped_column(Float, nullable=True)

    user = relationship("User", back_populates="metrics", lazy="raise")
