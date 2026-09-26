"""Equipment inventory (GET/PUT/DELETE /users/me/equipment) and what it changes downstream.

The reported bugs: suggested increases like "+2 lb" that no plate makes, and weight changes that
ignore a barbell being loaded on both sides. The inventory records what the user actually owns;
every load the server suggests or seeds is then one that equipment can make (app/loading.py).
"""

import datetime
import uuid

import pytest_asyncio
from sqlalchemy import select

from app.database import AsyncSessionLocal
from app.models.exercise import Exercise
from app.services.ai.context_service import build_user_context

TODAY = datetime.date.today()

DEFAULT_LB = {
    "unit": "lb",
    "bars": [45.0, 35.0, 15.0],
    "plates": [
        {"weight": 45.0, "pairs": 6},
        {"weight": 35.0, "pairs": 1},
        {"weight": 25.0, "pairs": 2},
        {"weight": 10.0, "pairs": 2},
        {"weight": 5.0, "pairs": 2},
        {"weight": 2.5, "pairs": 2},
    ],
    "dumbbells": [float(w) for w in range(5, 105, 5)],
    "stack_step": 5.0,
}

MICROPLATES = {
    "unit": "lb",
    "bars": [45.0],
    "plates": [
        {"weight": 45.0, "pairs": 4},
        {"weight": 10.0, "pairs": 2},
        {"weight": 5.0, "pairs": 2},
        {"weight": 2.5, "pairs": 2},
        {"weight": 1.25, "pairs": 1},
    ],
    "dumbbells": [],
    "stack_step": None,
}


async def _catalog_exercise(name: str, muscle_group: str, equipment: str) -> Exercise:
    async with AsyncSessionLocal() as session:
        row = (await session.execute(select(Exercise).where(Exercise.name == name))).scalar()
        if row is None:
            row = Exercise(name=name, muscle_group=muscle_group, equipment=equipment)
            session.add(row)
            await session.commit()
            await session.refresh(row)
        return row


@pytest_asyncio.fixture
async def bench_press():
    return await _catalog_exercise("Bench Press", "chest", "barbell")


async def _user_id(auth_client) -> uuid.UUID:
    return uuid.UUID((await auth_client.get("/users/me")).json()["id"])


async def _routine(auth_client, exercise, weight, reps=8, sets=3) -> str:
    resp = await auth_client.post(
        "/routines",
        json={
            "name": f"Equipment {uuid.uuid4().hex[:6]}",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": sets,
                    "target_reps": reps,
                    "target_weight": weight,
                    "is_bodyweight": False,
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


async def _complete_all(auth_client, session, reps, weight):
    for sl in session["set_logs"]:
        resp = await auth_client.patch(
            f"/sessions/{session['id']}/sets/{sl['id']}",
            json={"completed": True, "reps": reps, "weight": weight},
        )
        assert resp.status_code == 200, resp.text


async def _bench_suggestion(auth_client, bench_press) -> dict:
    """Bench 3×8 at 115 completed, then the next session's progression suggestion."""
    routine_id = await _routine(auth_client, bench_press, 115.0)
    first = await _session(auth_client, routine_id, TODAY - datetime.timedelta(days=2))
    await _complete_all(auth_client, first, 8, 115.0)
    second = await _session(auth_client, routine_id)
    resp = await auth_client.get(f"/sessions/{second['id']}/prior-bests")
    assert resp.status_code == 200, resp.text
    return resp.json()[0]


# ── API ───────────────────────────────────────────────────────────────────────


async def test_unset_inventory_returns_the_default(auth_client):
    resp = await auth_client.get("/users/me/equipment")
    assert resp.status_code == 200, resp.text
    assert resp.json() == {"configured": False, "inventory": DEFAULT_LB}


async def test_put_normalises_and_persists(auth_client):
    resp = await auth_client.put(
        "/users/me/equipment",
        json={
            "unit": "lb",
            "bars": [25, 45, 45],
            "plates": [
                {"weight": 10, "pairs": 1},
                {"weight": 45, "pairs": 2},
                {"weight": 10, "pairs": 1},  # merged with the first 10s
                {"weight": 35, "pairs": 0},  # not owned → dropped
            ],
            "dumbbells": [30, 10, 20, 10],
            "stack_step": 10,
        },
    )
    assert resp.status_code == 200, resp.text
    expected = {
        "configured": True,
        "inventory": {
            "unit": "lb",
            "bars": [45.0, 25.0],
            "plates": [{"weight": 45.0, "pairs": 2}, {"weight": 10.0, "pairs": 2}],
            "dumbbells": [10.0, 20.0, 30.0],
            "stack_step": 10.0,
        },
    }
    assert resp.json() == expected
    assert (await auth_client.get("/users/me/equipment")).json() == expected


async def test_invalid_inventory_rejected(auth_client):
    bad_bodies = [
        {"unit": "stone"},
        {"plates": [{"weight": 0, "pairs": 1}]},
        {"plates": [{"weight": 45, "pairs": 21}]},
        {"bars": [45, 35, 25, 20, 15, 10, 5]},  # more than 6 bars
        {"dumbbells": [250]},
        {"stack_step": -5},
    ]
    for body in bad_bodies:
        resp = await auth_client.put("/users/me/equipment", json=body)
        assert resp.status_code == 422, body


async def test_delete_goes_back_to_the_default(auth_client):
    await auth_client.put("/users/me/equipment", json=MICROPLATES)
    resp = await auth_client.delete("/users/me/equipment")
    assert resp.status_code == 200, resp.text
    assert resp.json() == {"configured": False, "inventory": DEFAULT_LB}


async def test_account_reset_clears_the_inventory(auth_client):
    await auth_client.put("/users/me/equipment", json=MICROPLATES)
    assert (await auth_client.post("/users/reset")).status_code == 204
    assert (await auth_client.get("/users/me/equipment")).json()["configured"] is False


async def test_equipment_requires_auth(client):
    assert (await client.get("/users/me/equipment")).status_code == 401
    assert (await client.put("/users/me/equipment", json=MICROPLATES)).status_code == 401


# ── Progression suggestions ───────────────────────────────────────────────────


async def test_default_equipment_turns_the_bench_bump_into_a_plate_pair(auth_client, bench_press):
    """The reported bug: 115 → 117.5 ("+2 lb"). On the default gym it is 120, 2.5 a side."""
    best = await _bench_suggestion(auth_client, bench_press)
    assert best["action"] == "add_weight"
    assert best["suggested_weight"] == 120.0
    assert "2.5 lb per side" in best["suggested_reason"]


async def test_microplates_allow_the_small_jump(auth_client, bench_press):
    await auth_client.put("/users/me/equipment", json=MICROPLATES)
    best = await _bench_suggestion(auth_client, bench_press)
    assert best["suggested_weight"] == 117.5
    assert "1.25 lb per side" in best["suggested_reason"]


# ── Session seeding ───────────────────────────────────────────────────────────


async def test_unloadable_prescription_seeds_the_load_below(auth_client, bench_press):
    routine_id = await _routine(auth_client, bench_press, 117.5)
    session = await _session(auth_client, routine_id)
    assert {sl["weight"] for sl in session["set_logs"]} == {115.0}


async def test_loadable_prescription_is_untouched(auth_client, bench_press):
    await auth_client.put("/users/me/equipment", json=MICROPLATES)
    routine_id = await _routine(auth_client, bench_press, 117.5)
    session = await _session(auth_client, routine_id)
    assert {sl["weight"] for sl in session["set_logs"]} == {117.5}


async def test_prescription_below_the_lightest_bar_is_not_rounded_up(auth_client, bench_press):
    await auth_client.put("/users/me/equipment", json=MICROPLATES)  # 45 lb bar only
    routine_id = await _routine(auth_client, bench_press, 35.0)
    session = await _session(auth_client, routine_id)
    assert {sl["weight"] for sl in session["set_logs"]} == {35.0}


async def test_deload_week_seed_is_loadable(auth_client, bench_press):
    routine_id = await _routine(auth_client, bench_press, 115.0, sets=3)
    program = await auth_client.post(
        "/programs",
        json={
            "name": "Loadable Block",
            "days": [{"routine_id": routine_id, "label": "Day 1", "order": 0}],
            "weeks": 4,
            "deload_week": 2,
        },
    )
    assert program.status_code == 201, program.text
    activate = await auth_client.patch(
        f"/programs/{program.json()['id']}", json={"is_active": True}
    )
    assert activate.status_code == 200, activate.text

    session = await _session(auth_client, routine_id, TODAY + datetime.timedelta(days=7))
    assert session["is_deload"] is True
    # 115 × 0.9 = 103.5: the old 2.5 rounding gave 102.5, which a 45 lb bar can't make.
    assert {sl["weight"] for sl in session["set_logs"]} == {105.0}


# ── Coach context ─────────────────────────────────────────────────────────────


async def test_saved_inventory_reaches_the_coach(auth_client):
    await auth_client.put("/users/me/equipment", json=MICROPLATES)
    async with AsyncSessionLocal() as session:
        context = await build_user_context(session, await _user_id(auth_client))
    assert context is not None
    assert "Loadable weights: 45 lb bar" in context
    assert "a barbell moves in 2.5 lb steps (1.25 per side)" in context


async def test_default_inventory_is_not_claimed_to_the_coach(auth_client):
    """The default is the server's assumption, not something the athlete said."""
    await auth_client.patch("/users/me/profile", json={"equipment": "dumbbells up to 50lb"})
    async with AsyncSessionLocal() as session:
        context = await build_user_context(session, await _user_id(auth_client))
    assert "Loadable weights" not in context


def test_prompt_requires_loadable_prescriptions():
    from app.services.ai.prompts import SYSTEM_PROMPT

    assert "**Loadable weights.**" in SYSTEM_PROMPT
    assert "twice the smallest plate" in SYSTEM_PROMPT
