"""Movement-family seeding through ``POST /sessions`` (see ``app/movement_families.py``).

A session's starting load is ``max(routine prescription, family-derived)`` where the derivation
reads the user's *completed*, non-warm-up history on related lifts within the last
``FAMILY_EVIDENCE_DAYS``. These tests pin every rule of that sentence end to end.
"""

import datetime
import uuid

import pytest_asyncio
from sqlalchemy import select

from app.database import AsyncSessionLocal
from app.limits import FAMILY_EVIDENCE_DAYS
from app.models.exercise import Exercise

TODAY = datetime.date.today()


async def _catalog_exercise(name: str, muscle_group: str, equipment: str) -> Exercise:
    """Get-or-create a seeded-catalog exercise by name: the scratch DB may be migrated (row
    exists) or ``create_all``-fresh (row missing), and ``name`` is unique either way."""
    async with AsyncSessionLocal() as session:
        row = (await session.execute(select(Exercise).where(Exercise.name == name))).scalar()
        if row is None:
            row = Exercise(name=name, muscle_group=muscle_group, equipment=equipment)
            session.add(row)
            await session.commit()
            await session.refresh(row)
        return row


@pytest_asyncio.fixture
async def barbell_row():
    return await _catalog_exercise("Barbell Row", "back", "barbell")


@pytest_asyncio.fixture
async def dumbbell_row():
    return await _catalog_exercise("Dumbbell Row", "back", "dumbbell")


@pytest_asyncio.fixture
async def bench_press():
    return await _catalog_exercise("Bench Press", "chest", "barbell")


async def _routine(auth_client, exercise, weight, reps=8, sets=2, is_bodyweight=False) -> str:
    resp = await auth_client.post(
        "/routines",
        json={
            "name": f"R {uuid.uuid4().hex[:6]}",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": sets,
                    "target_reps": reps,
                    "target_weight": weight,
                    "is_bodyweight": is_bodyweight,
                    "order": 0,
                }
            ],
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


async def _session(auth_client, routine_id, date=TODAY) -> dict:
    resp = await auth_client.post("/sessions", json={"routine_id": routine_id, "date": str(date)})
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _lift(auth_client, exercise, weight, reps, date=TODAY, set_type="normal"):
    """Start a session on a one-exercise routine and log one working set at ``weight``."""
    routine_id = await _routine(auth_client, exercise, weight, reps=reps, sets=1)
    session = await _session(auth_client, routine_id, date)
    set_id = session["set_logs"][0]["id"]
    resp = await auth_client.patch(
        f"/sessions/{session['id']}/sets/{set_id}",
        json={"completed": True, "weight": weight, "reps": reps, "set_type": set_type},
    )
    assert resp.status_code == 200, resp.text
    return session


def _weights(session):
    return sorted({sl["weight"] for sl in session["set_logs"]})


# Barbell Row 5@100 → e1RM 116.7 → Dumbbell Row (0.45) at 8 reps → 41.4 → floored to 40.
EXPECTED_DB_ROW = 40.0


async def test_sibling_history_lifts_a_low_prescription(auth_client, barbell_row, dumbbell_row):
    await _lift(auth_client, barbell_row, 100.0, 5)
    session = await _session(auth_client, await _routine(auth_client, dumbbell_row, 10.0))
    assert _weights(session) == [EXPECTED_DB_ROW]


async def test_prescription_is_never_lowered(auth_client, barbell_row, dumbbell_row):
    await _lift(auth_client, barbell_row, 100.0, 5)
    session = await _session(auth_client, await _routine(auth_client, dumbbell_row, 60.0))
    assert _weights(session) == [60.0]


async def test_warm_up_sets_are_not_evidence(auth_client, barbell_row, dumbbell_row):
    await _lift(auth_client, barbell_row, 100.0, 5, set_type="warmup")
    session = await _session(auth_client, await _routine(auth_client, dumbbell_row, 10.0))
    assert _weights(session) == [10.0]


async def test_uncompleted_seeded_sets_are_not_evidence(auth_client, barbell_row, dumbbell_row):
    barbell_session = await _lift(auth_client, barbell_row, 100.0, 5)
    db_routine = await _routine(auth_client, dumbbell_row, 10.0)
    first = await _session(auth_client, db_routine)
    assert _weights(first) == [EXPECTED_DB_ROW]  # seeded at 40, never lifted
    # Remove the real evidence: if the uncompleted 40 lb seed counted as history, the next
    # session would still read 40 — a derived load feeding itself with no lifting behind it.
    resp = await auth_client.delete(f"/sessions/{barbell_session['id']}")
    assert resp.status_code in (200, 204), resp.text
    second = await _session(auth_client, db_routine)
    assert _weights(second) == [10.0]


async def test_history_outside_the_window_is_ignored(auth_client, barbell_row, dumbbell_row):
    old = TODAY - datetime.timedelta(days=FAMILY_EVIDENCE_DAYS + 1)
    await _lift(auth_client, barbell_row, 100.0, 5, date=old)
    session = await _session(auth_client, await _routine(auth_client, dumbbell_row, 10.0))
    assert _weights(session) == [10.0]


async def test_window_is_relative_to_the_session_date(auth_client, barbell_row, dumbbell_row):
    old = TODAY - datetime.timedelta(days=FAMILY_EVIDENCE_DAYS + 10)
    await _lift(auth_client, barbell_row, 100.0, 5, date=old)
    routine_id = await _routine(auth_client, dumbbell_row, 10.0)
    backdated = await _session(auth_client, routine_id, date=old + datetime.timedelta(days=3))
    assert _weights(backdated) == [EXPECTED_DB_ROW]


async def test_own_recent_history_carries_forward(auth_client, dumbbell_row):
    await _lift(auth_client, dumbbell_row, 45.0, 8)
    session = await _session(auth_client, await _routine(auth_client, dumbbell_row, 10.0))
    assert _weights(session) == [45.0]


async def test_unrelated_family_does_not_seed(auth_client, bench_press, dumbbell_row):
    await _lift(auth_client, bench_press, 200.0, 5)
    session = await _session(auth_client, await _routine(auth_client, dumbbell_row, 10.0))
    assert _weights(session) == [10.0]


async def test_another_users_history_is_ignored(client, barbell_row, dumbbell_row):
    async def token(email):
        r = await client.post(
            "/auth/register", json={"name": "U", "email": email, "password": "pass1234"}
        )
        return r.json()["access_token"]

    client.headers["Authorization"] = f"Bearer {await token(f'a_{uuid.uuid4().hex[:8]}@t.com')}"
    await _lift(client, barbell_row, 100.0, 5)
    client.headers["Authorization"] = f"Bearer {await token(f'b_{uuid.uuid4().hex[:8]}@t.com')}"
    session = await _session(client, await _routine(client, dumbbell_row, 10.0))
    assert _weights(session) == [10.0]


async def test_bodyweight_row_stays_unloaded(auth_client, barbell_row, dumbbell_row):
    await _lift(auth_client, barbell_row, 100.0, 5)
    routine_id = await _routine(auth_client, dumbbell_row, None, is_bodyweight=True)
    session = await _session(auth_client, routine_id)
    assert [sl["weight"] for sl in session["set_logs"]] == [None, None]


async def test_missing_prescription_is_filled_from_evidence(auth_client, barbell_row, dumbbell_row):
    await _lift(auth_client, barbell_row, 100.0, 5)
    routine_id = await _routine(auth_client, dumbbell_row, None)  # weighted lift, no target
    session = await _session(auth_client, routine_id)
    assert _weights(session) == [EXPECTED_DB_ROW]


async def test_no_family_exercise_is_unchanged(auth_client, exercise, barbell_row):
    await _lift(auth_client, barbell_row, 100.0, 5)
    session = await _session(auth_client, await _routine(auth_client, exercise, 135.0))
    assert _weights(session) == [135.0]


async def test_deload_applies_after_the_family_seed(auth_client, barbell_row, dumbbell_row):
    await _lift(auth_client, barbell_row, 100.0, 5)
    routine_id = await _routine(auth_client, dumbbell_row, 10.0, sets=3)
    program = await auth_client.post(
        "/programs",
        json={
            "name": "Block",
            "days": [{"routine_id": routine_id, "label": "Day 1", "order": 0}],
            "weeks": 4,
            "deload_week": 2,
        },
    )
    assert program.status_code == 201, program.text
    activate = await auth_client.patch(f"/programs/{program.json()['id']}", json={"is_active": True})
    assert activate.status_code == 200
    session = await _session(auth_client, routine_id, date=TODAY + datetime.timedelta(days=7))
    assert session["is_deload"] is True
    # ceil(3 × 0.6) = 2 sets at round(40 × 0.9 / 2.5) × 2.5 = 35.
    assert len(session["set_logs"]) == 2
    assert _weights(session) == [35.0]
