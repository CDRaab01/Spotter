"""Persist an AI-suggested multi-day program.

Creates one WorkoutRoutine per non-rest day, a WorkoutProgram linking them as
ProgramDays, and activates it (clearing any other active program). Reuses the
existing routine/program services so validation and bounds stay in one place.

Note: create_routine / create_program / update_program each commit independently,
so this is not a single transaction. A mid-sequence failure can leave orphan AI
routines; the caller surfaces the error and the user can retry. A fully atomic
batch path is a possible follow-up.
"""

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

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
                    name=f"{req.name} — {day.label}",
                    source="ai",
                    exercises=day.exercises,
                ),
            )
            routine_id = routine.id
        day_ins.append(ProgramDayIn(routine_id=routine_id, label=day.label, order=i))

    program = await program_service.create_program(
        db, user_id, ProgramCreate(name=req.name, days=day_ins)
    )
    # Activate the new program (clears other actives).
    return await program_service.update_program(
        db, user_id, program.id, ProgramUpdate(is_active=True)
    )
