import datetime
import uuid

from pydantic import BaseModel


class CalendarEntry(BaseModel):
    session_id: uuid.UUID
    date: datetime.date
    routine_name: str | None
    status: str
    set_count: int
