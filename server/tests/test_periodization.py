"""Program periodization (migration 0015): current_week math, weeks/deload_week
validation, activation stamping, and deload-week session seeding."""

import datetime

from app.services.program_service import current_week

D0 = datetime.date(2026, 7, 6)  # a Monday


# ── current_week (pure math) ───────────────────────────────────────────────


def test_current_week_none_without_both_fields():
    assert current_week(None, 4, D0) is None
    assert current_week(D0, None, D0) is None
    assert current_week(None, None, D0) is None


def test_current_week_first_week():
    assert current_week(D0, 4, D0) == 1
    assert current_week(D0, 4, D0 + datetime.timedelta(days=6)) == 1


def test_current_week_advances_weekly():
    assert current_week(D0, 4, D0 + datetime.timedelta(days=7)) == 2
    assert current_week(D0, 4, D0 + datetime.timedelta(days=13)) == 2
    assert current_week(D0, 4, D0 + datetime.timedelta(days=21)) == 4


def test_current_week_cycles_after_mesocycle():
    # Mesocycles repeat: week `weeks` rolls back over to week 1.
    assert current_week(D0, 4, D0 + datetime.timedelta(days=28)) == 1
    assert current_week(D0, 4, D0 + datetime.timedelta(days=35)) == 2
    assert current_week(D0, 4, D0 + datetime.timedelta(days=8 * 7)) == 1


def test_current_week_one_week_program_always_week_one():
    assert current_week(D0, 1, D0 + datetime.timedelta(days=100)) == 1


# ── API: program metadata + validation ─────────────────────────────────────


async def _routine_id(auth_client, exercise, weight=100.0, sets=3, reps=5) -> str:
    resp = await auth_client.post(
        "/routines",
        json={
            "name": "Periodized Routine",
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
    assert resp.status_code == 201
    return resp.json()["id"]


async def _create_program(auth_client, routine_id, **extra):
    body = {
        "name": "Block Program",
        "days": [{"routine_id": routine_id, "label": "Day 1", "order": 0}],
        **extra,
    }
    return await auth_client.post("/programs", json=body)


async def test_create_program_with_periodization_fields(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise)
    resp = await _create_program(
        auth_client,
        routine_id,
        source="preset",
        description="6-week block, deload last week",
        weeks=6,
        deload_week=6,
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["source"] == "preset"
    assert data["description"] == "6-week block, deload last week"
    assert data["weeks"] == 6
    assert data["deload_week"] == 6
    assert data["created_at"] is not None
    # Not activated yet: no anchor, no computed week.
    assert data["started_on"] is None
    assert data["current_week"] is None
    assert data["is_deload_week"] is False


async def test_deload_week_requires_weeks(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise)
    resp = await _create_program(auth_client, routine_id, deload_week=4)
    assert resp.status_code == 422


async def test_deload_week_must_be_within_weeks(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise)
    resp = await _create_program(auth_client, routine_id, weeks=4, deload_week=5)
    assert resp.status_code == 422


async def test_weeks_out_of_bounds_rejected(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise)
    resp = await _create_program(auth_client, routine_id, weeks=53)
    assert resp.status_code == 422


async def test_activation_stamps_started_on_and_computes_week(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise)
    program_id = (
        await _create_program(auth_client, routine_id, weeks=4, deload_week=4)
    ).json()["id"]

    resp = await auth_client.patch(f"/programs/{program_id}", json={"is_active": True})
    assert resp.status_code == 200
    data = resp.json()
    assert data["is_active"] is True
    assert data["started_on"] == str(datetime.date.today())
    assert data["current_week"] == 1
    assert data["is_deload_week"] is False

    # Re-activating keeps the original anchor.
    again = await auth_client.patch(f"/programs/{program_id}", json={"is_active": True})
    assert again.json()["started_on"] == str(datetime.date.today())


async def test_immediate_deload_week_flagged(auth_client, exercise):
    # weeks=1, deload_week=1: the program starts in its deload week.
    routine_id = await _routine_id(auth_client, exercise)
    program_id = (
        await _create_program(auth_client, routine_id, weeks=1, deload_week=1)
    ).json()["id"]
    resp = await auth_client.patch(f"/programs/{program_id}", json={"is_active": True})
    assert resp.json()["is_deload_week"] is True


# ── Deload-week session seeding ────────────────────────────────────────────


async def _activated_program(auth_client, routine_id, weeks=4, deload_week=2) -> str:
    program_id = (
        await _create_program(auth_client, routine_id, weeks=weeks, deload_week=deload_week)
    ).json()["id"]
    resp = await auth_client.patch(f"/programs/{program_id}", json={"is_active": True})
    assert resp.status_code == 200
    return program_id


async def test_deload_week_session_seeds_fewer_lighter_sets(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise, weight=100.0, sets=3, reps=5)
    await _activated_program(auth_client, routine_id, weeks=4, deload_week=2)

    # started_on = today, so today+7 lands in week 2 — the deload.
    deload_date = datetime.date.today() + datetime.timedelta(days=7)
    resp = await auth_client.post(
        "/sessions", json={"routine_id": routine_id, "date": str(deload_date)}
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["is_deload"] is True
    # ceil(3 * 0.6) = 2 sets at 100 * 0.9 = 90 (already plate-friendly).
    assert len(data["set_logs"]) == 2
    assert all(sl["weight"] == 90.0 for sl in data["set_logs"])


async def test_non_deload_week_session_unchanged(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise, weight=100.0, sets=3, reps=5)
    await _activated_program(auth_client, routine_id, weeks=4, deload_week=2)

    resp = await auth_client.post(
        "/sessions", json={"routine_id": routine_id, "date": str(datetime.date.today())}
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["is_deload"] is False
    assert len(data["set_logs"]) == 3
    assert all(sl["weight"] == 100.0 for sl in data["set_logs"])


async def test_programless_routine_session_unchanged(auth_client, exercise):
    # A routine not linked to any active program never deloads.
    routine_id = await _routine_id(auth_client, exercise, weight=100.0, sets=3, reps=5)
    resp = await auth_client.post(
        "/sessions",
        json={
            "routine_id": routine_id,
            "date": str(datetime.date.today() + datetime.timedelta(days=7)),
        },
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["is_deload"] is False
    assert len(data["set_logs"]) == 3
    assert all(sl["weight"] == 100.0 for sl in data["set_logs"])
