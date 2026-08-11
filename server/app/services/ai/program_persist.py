"""Persist an AI-suggested multi-day program.

Creates one WorkoutRoutine per non-rest day, a WorkoutProgram linking them as
ProgramDays (carrying source/description and any weeks/deload_week
periodization), and — unless ``activate=False`` — activates it (clearing any
other active program). Reuses the existing routine/program services so
validation and bounds stay in one place.

The whole accept is ONE transaction: every service call runs with
``commit=False`` (flush-only) and a single commit lands at the end, so a
mid-sequence failure rolls back on session close and can never leave orphan
AI routines behind.
"""

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.limits import ROUTINE_NAME_MAX_LEN
from app.schemas.ai import AcceptProgramRequest
from app.schemas.routine import RoutineCreate
from app.schemas.program import ProgramCreate, ProgramDayIn, ProgramOut, ProgramUpdate
from app.services import routine_service, program_service


async def accept_program(
    db: AsyncSession, user_id: uuid.UUID, req: AcceptProgramRequest
) -> ProgramOut:
    day_ins: list[ProgramDayIn] = []
    for i, day in enumerate(req.days):
        routine_id: uuid.UUID | None = None
        if day.exercises:
            routine = await routine_service.create_routine(
                db,
                user_id,
                RoutineCreate(
                    # Truncate: name (≤255) + label (≤100) can compose past the
                    # routine name column's 255 — a 500 without the slice.
                    name=f"{req.name} — {day.label}"[:ROUTINE_NAME_MAX_LEN],
                    source="ai",
                    exercises=day.exercises,
                ),
                commit=False,
            )
            routine_id = routine.id
        day_ins.append(ProgramDayIn(routine_id=routine_id, label=day.label, order=i))

    program = await program_service.create_program(
        db,
        user_id,
        ProgramCreate(
            name=req.name,
            days=day_ins,
            source=req.source,
            description=req.description,
            weeks=req.weeks,
            deload_week=req.deload_week,
        ),
        commit=False,
    )
    if req.activate:
        # Activate the new program (clears other actives, stamps started_on).
        program = await program_service.update_program(
            db, user_id, program.id, ProgramUpdate(is_active=True), commit=False
        )
    await db.commit()
    return program
