import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.program_day import ProgramDay
from app.models.workout_program import WorkoutProgram
from app.models.workout_routine import WorkoutRoutine
from app.models.workout_session import WorkoutSession
from app.schemas.program import (
    ProgramCreate,
    ProgramDayOut,
    ProgramDaysUpdate,
    ProgramOut,
    ProgramUpdate,
)


def current_week(
    started_on: datetime.date | None, weeks: int | None, today: datetime.date
) -> int | None:
    """The 1-based week a program is in on ``today``, or None when the program has
    no week structure (either field unset). Mesocycles cycle: week ``weeks`` rolls
    back over to week 1, so a 4-week block repeats indefinitely from ``started_on``.
    Pure so the math is unit-testable in isolation."""
    if started_on is None or weeks is None:
        return None
    return ((today - started_on).days // 7) % weeks + 1


async def is_deload_day(
    db: AsyncSession,
    user_id: uuid.UUID,
    routine_id: uuid.UUID,
    on_date: datetime.date,
) -> bool:
    """True when ``routine_id`` belongs to the user's ACTIVE program and that
    program is in its scheduled deload week on ``on_date``. Shared by session
    creation (seed fewer/lighter sets) and session reads (SessionOut.is_deload) so
    the two can never disagree."""
    result = await db.execute(
        select(WorkoutProgram)
        .join(ProgramDay, ProgramDay.program_id == WorkoutProgram.id)
        .where(
            WorkoutProgram.user_id == user_id,
            WorkoutProgram.is_active == True,  # noqa: E712
            ProgramDay.routine_id == routine_id,
        )
        .limit(1)
    )
    program = result.scalar_one_or_none()
    if program is None or program.deload_week is None:
        return False
    return current_week(program.started_on, program.weeks, on_date) == program.deload_week


async def _verify_routines_owned(
    db: AsyncSession, user_id: uuid.UUID, days: list
) -> None:
    """Ensure every non-null routine_id in ``days`` is a routine owned by the caller.

    Without this, a foreign or random UUID either links another user's routine into
    the program or trips the FK constraint as a 500; both become a clean 422.
    """
    ids = {d.routine_id for d in days if d.routine_id is not None}
    if not ids:
        return
    found = await db.execute(
        select(WorkoutRoutine.id).where(
            WorkoutRoutine.id.in_(ids), WorkoutRoutine.user_id == user_id
        )
    )
    missing = ids - {row[0] for row in found.all()}
    if missing:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Routine not found",
        )


async def _to_out(program: WorkoutProgram) -> ProgramOut:
    day_outs = []
    for day in program.days:
        routine_name: str | None = None
        if hasattr(day, "routine") and day.routine is not None:
            try:
                routine_name = day.routine.name
            except Exception:
                pass
        day_outs.append(
            ProgramDayOut(
                id=day.id,
                routine_id=day.routine_id,
                label=day.label,
                order=day.order,
                routine_name=routine_name,
            )
        )
    week = current_week(program.started_on, program.weeks, datetime.date.today())
    return ProgramOut(
        id=program.id,
        name=program.name,
        is_active=program.is_active,
        days=day_outs,
        created_at=program.created_at,
        started_on=program.started_on,
        source=program.source,
        description=program.description,
        weeks=program.weeks,
        deload_week=program.deload_week,
        current_week=week,
        is_deload_week=program.deload_week is not None and week == program.deload_week,
    )


async def list_programs(db: AsyncSession, user_id: uuid.UUID) -> list[ProgramOut]:
    result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.user_id == user_id)
        .options(selectinload(WorkoutProgram.days).selectinload(ProgramDay.routine))
    )
    return [await _to_out(p) for p in result.scalars().all()]


async def create_program(
    db: AsyncSession, user_id: uuid.UUID, req: ProgramCreate, commit: bool = True
) -> ProgramOut:
    """Create a program with its days. ``commit=False`` only flushes, letting a
    caller (accept_program) batch several writes into one transaction."""
    await _verify_routines_owned(db, user_id, req.days)
    program = WorkoutProgram(
        user_id=user_id,
        name=req.name,
        source=req.source,
        description=req.description,
        weeks=req.weeks,
        deload_week=req.deload_week,
    )
    db.add(program)
    await db.flush()
    for d in req.days:
        db.add(ProgramDay(program_id=program.id, **d.model_dump()))
    if commit:
        await db.commit()
    else:
        await db.flush()
    return await get_program(db, user_id, program.id)


async def get_program(
    db: AsyncSession, user_id: uuid.UUID, program_id: uuid.UUID
) -> ProgramOut:
    result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.id == program_id, WorkoutProgram.user_id == user_id)
        .options(selectinload(WorkoutProgram.days).selectinload(ProgramDay.routine))
    )
    program = result.scalar_one_or_none()
    if not program:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Program not found")
    return await _to_out(program)


async def update_program(
    db: AsyncSession,
    user_id: uuid.UUID,
    program_id: uuid.UUID,
    req: ProgramUpdate,
    commit: bool = True,
) -> ProgramOut:
    result = await db.execute(
        select(WorkoutProgram).where(
            WorkoutProgram.id == program_id, WorkoutProgram.user_id == user_id
        )
    )
    program = result.scalar_one_or_none()
    if not program:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Program not found")

    if req.name is not None:
        program.name = req.name
    if req.source is not None:
        program.source = req.source
    if req.description is not None:
        program.description = req.description
    if req.weeks is not None:
        program.weeks = req.weeks
    if req.deload_week is not None:
        program.deload_week = req.deload_week
    if req.is_active is not None:
        if req.is_active:
            # Deactivate all other programs for this user first
            all_result = await db.execute(
                select(WorkoutProgram).where(
                    WorkoutProgram.user_id == user_id,
                    WorkoutProgram.is_active == True,  # noqa: E712
                )
            )
            for p in all_result.scalars().all():
                p.is_active = False
            # First activation anchors the week counter (current_week counts from here).
            if program.started_on is None:
                program.started_on = datetime.date.today()
        program.is_active = req.is_active

    if commit:
        await db.commit()
    else:
        await db.flush()
    return await get_program(db, user_id, program_id)


async def delete_program(
    db: AsyncSession, user_id: uuid.UUID, program_id: uuid.UUID
) -> None:
    result = await db.execute(
        select(WorkoutProgram).where(
            WorkoutProgram.id == program_id, WorkoutProgram.user_id == user_id
        )
    )
    program = result.scalar_one_or_none()
    if not program:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Program not found")
    await db.delete(program)
    await db.commit()


async def replace_days(
    db: AsyncSession, user_id: uuid.UUID, program_id: uuid.UUID, req: ProgramDaysUpdate
) -> ProgramOut:
    result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.id == program_id, WorkoutProgram.user_id == user_id)
        .options(selectinload(WorkoutProgram.days))
    )
    program = result.scalar_one_or_none()
    if not program:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Program not found")

    await _verify_routines_owned(db, user_id, req.days)

    for day in list(program.days):
        await db.delete(day)
    await db.flush()

    for d in req.days:
        db.add(ProgramDay(program_id=program_id, **d.model_dump()))

    await db.commit()
    db.expire_all()
    return await get_program(db, user_id, program_id)


async def get_next_day(
    db: AsyncSession, user_id: uuid.UUID
) -> ProgramDayOut | None:
    active_result = await db.execute(
        select(WorkoutProgram)
        .where(WorkoutProgram.user_id == user_id, WorkoutProgram.is_active == True)  # noqa: E712
        .options(selectinload(WorkoutProgram.days).selectinload(ProgramDay.routine))
    )
    program = active_result.scalar_one_or_none()
    if not program or not program.days:
        return None

    # Find last completed session and which program day it corresponds to
    last_session_result = await db.execute(
        select(WorkoutSession)
        .where(WorkoutSession.user_id == user_id, WorkoutSession.status == "completed")
        .order_by(WorkoutSession.date.desc())
        .limit(1)
    )
    last_session = last_session_result.scalar_one_or_none()

    days = sorted(program.days, key=lambda d: d.order)
    n = len(days)
    if not last_session or not last_session.routine_id:
        start = 0
    else:
        # Find the last day whose routine matches. When multiple days share a routine
        # (e.g. two "Full Body" days) we pick the last matching occurrence so the
        # rotation advances past the most recently used position, not always from
        # the first occurrence. This is a best-effort heuristic; a future improvement
        # is to store program_day_id on WorkoutSession for exact tracking.
        matching_index = None
        for i, d in enumerate(days):
            if d.routine_id == last_session.routine_id:
                matching_index = i
        start = 0 if matching_index is None else (matching_index + 1) % n

    # Auto-skip rest days: the "next day" suggestion is the next actual workout. A rest day has no
    # routine, so it can never be "completed" — if it were returned here it would sit as an
    # unadvanceable "next up" and the program would appear stuck on it. Walk forward from `start`
    # to the first day that has a routine; if the whole program is rest days, keep `start`.
    next_day = days[start]
    for step in range(n):
        candidate = days[(start + step) % n]
        if candidate.routine_id is not None:
            next_day = candidate
            break

    routine_name: str | None = None
    if next_day.routine:
        routine_name = next_day.routine.name

    return ProgramDayOut(
        id=next_day.id,
        routine_id=next_day.routine_id,
        label=next_day.label,
        order=next_day.order,
        routine_name=routine_name,
    )
