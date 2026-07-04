"""Per-user data export (ROADMAP T3 #6) — the honest "what if I leave the ecosystem" backstop.

Gathers every row the signed-in user owns into one JSON-serializable document. Columns are
serialized generically (introspection over ``__table__.columns``) so this keeps working as the
schema grows; secret columns on the user row are redacted. The seeded ``exercises`` catalog is
shared reference data, not user-owned, so it is excluded (routines/sets reference it by id).
Read-only, own-session auth.
"""

import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.body_metric import BodyMetric
from app.models.cardio_session import CardioSession
from app.models.program_day import ProgramDay
from app.models.routine_exercise import RoutineExercise
from app.models.set_log import SetLog
from app.models.workout_program import WorkoutProgram
from app.models.workout_routine import WorkoutRoutine
from app.models.workout_session import WorkoutSession

EXPORT_SCHEMA_VERSION = 1

# Never leave the server in an export — auth secrets, not user content.
_USER_REDACT = frozenset({"hashed_password", "reset_token", "reset_token_expires_at"})


def _jsonify(value):
    if isinstance(value, (datetime.datetime, datetime.date)):
        return value.isoformat()
    if isinstance(value, uuid.UUID):
        return str(value)
    return value  # str/int/float/bool/None + JSON columns (already list/dict)


def _row(obj, *, exclude: frozenset = frozenset()) -> dict:
    return {
        c.name: _jsonify(getattr(obj, c.name))
        for c in obj.__table__.columns
        if c.name not in exclude
    }


async def _all(db: AsyncSession, stmt) -> list:
    return list((await db.execute(stmt)).scalars().all())


async def build_export(db: AsyncSession, user) -> dict:
    """Assemble the full export document for ``user``. Children (routine exercises, program days,
    set logs) are fetched by parent id so the export is complete without ORM relationships."""
    routines = await _all(db, select(WorkoutRoutine).where(WorkoutRoutine.user_id == user.id))
    routine_ids = [r.id for r in routines]
    programs = await _all(db, select(WorkoutProgram).where(WorkoutProgram.user_id == user.id))
    program_ids = [p.id for p in programs]
    sessions = await _all(db, select(WorkoutSession).where(WorkoutSession.user_id == user.id))
    session_ids = [s.id for s in sessions]

    routine_exercises = (
        await _all(db, select(RoutineExercise).where(RoutineExercise.routine_id.in_(routine_ids)))
        if routine_ids
        else []
    )
    program_days = (
        await _all(db, select(ProgramDay).where(ProgramDay.program_id.in_(program_ids)))
        if program_ids
        else []
    )
    set_logs = (
        await _all(db, select(SetLog).where(SetLog.session_id.in_(session_ids)))
        if session_ids
        else []
    )

    return {
        "app": "spotter",
        "schema_version": EXPORT_SCHEMA_VERSION,
        "exported_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "user": _row(user, exclude=_USER_REDACT),
        "workout_routines": [_row(r) for r in routines],
        "routine_exercises": [_row(e) for e in routine_exercises],
        "workout_programs": [_row(p) for p in programs],
        "program_days": [_row(d) for d in program_days],
        "workout_sessions": [_row(s) for s in sessions],
        "set_logs": [_row(sl) for sl in set_logs],
        "body_metrics": [
            _row(m) for m in await _all(db, select(BodyMetric).where(BodyMetric.user_id == user.id))
        ],
        "cardio_sessions": [
            _row(c)
            for c in await _all(db, select(CardioSession).where(CardioSession.user_id == user.id))
        ],
    }
