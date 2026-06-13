"""End-to-end tests for POST /ai/sessions/{id}/adjust — the Apply button.

Core invariant under test everywhere: completed sets are immutable history; only
incomplete sets are ever created, modified, or deleted.
"""

import datetime
import uuid

import pytest_asyncio

from app.database import AsyncSessionLocal
from app.models.exercise import Exercise


@pytest_asyncio.fixture
async def second_exercise():
    async with AsyncSessionLocal() as session:
        ex = Exercise(
            name=f"Test Incline Press {uuid.uuid4().hex[:6]}",
            muscle_group="chest",
            equipment="dumbbell",
        )
        session.add(ex)
        await session.commit()
        await session.refresh(ex)
        return ex


async def _create_routine(auth_client, exercise_id: str) -> dict:
    resp = await auth_client.post(
        "/routines",
        json={
            "name": "Adjust Test Routine",
            "source": "manual",
            "exercises": [
                {
                    "exercise_id": exercise_id,
                    "target_sets": 3,
                    "target_reps": 8,
                    "target_weight": 135.0,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _start_session(auth_client, routine_id: str) -> dict:
    resp = await auth_client.post(
        "/sessions",
        json={"routine_id": routine_id, "date": str(datetime.date.today())},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _complete_first_set(auth_client, session: dict) -> dict:
    first = session["set_logs"][0]
    resp = await auth_client.patch(
        f"/sessions/{session['id']}/sets/{first['id']}",
        json={"completed": True},
    )
    assert resp.status_code == 200, resp.text
    return first


def _swap_action(exercise, second_exercise, weight: float = 40.0) -> dict:
    return {
        "type": "swap",
        "exercise_id": str(exercise.id),
        "exercise_name": exercise.name,
        "new_exercise_id": str(second_exercise.id),
        "new_exercise_name": second_exercise.name,
        "weight": weight,
        "summary": "Swap to dumbbells",
    }


async def test_swap_replaces_only_incomplete_sets(
    auth_client, exercise, second_exercise
):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])
    completed = await _complete_first_set(auth_client, session)

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [_swap_action(exercise, second_exercise)],
            "apply_to_routine": False,
        },
    )
    assert resp.status_code == 200, resp.text
    logs = resp.json()["set_logs"]

    # The completed set survives untouched, still on the original exercise.
    old = [sl for sl in logs if sl["exercise_id"] == str(exercise.id)]
    assert len(old) == 1
    assert old[0]["id"] == completed["id"]
    assert old[0]["completed"] is True
    assert old[0]["weight"] == 135.0

    # The two incomplete sets became the new exercise at the proposed weight.
    new = [sl for sl in logs if sl["exercise_id"] == str(second_exercise.id)]
    assert len(new) == 2
    assert all(sl["completed"] is False for sl in new)
    assert all(sl["weight"] == 40.0 for sl in new)
    assert sorted(sl["set_number"] for sl in new) == [1, 2]
    # Enrichment fix: the swapped-in exercise still gets a display name.
    assert all(sl["exercise_name"] == second_exercise.name for sl in new)


async def test_adjust_weight_touches_only_incomplete_sets(auth_client, exercise):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])
    completed = await _complete_first_set(auth_client, session)

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [
                {
                    "type": "adjust_weight",
                    "exercise_id": str(exercise.id),
                    "exercise_name": exercise.name,
                    "weight": 115.0,
                    "summary": "Drop to 115",
                }
            ],
            "apply_to_routine": False,
        },
    )
    assert resp.status_code == 200, resp.text
    logs = resp.json()["set_logs"]
    for sl in logs:
        if sl["id"] == completed["id"]:
            assert sl["weight"] == 135.0  # history intact
        else:
            assert sl["weight"] == 115.0


async def test_remove_deletes_only_incomplete_sets(auth_client, exercise):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])
    completed = await _complete_first_set(auth_client, session)

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [
                {
                    "type": "remove",
                    "exercise_id": str(exercise.id),
                    "exercise_name": exercise.name,
                    "summary": "Drop it",
                }
            ],
            "apply_to_routine": False,
        },
    )
    assert resp.status_code == 200, resp.text
    logs = resp.json()["set_logs"]
    assert len(logs) == 1
    assert logs[0]["id"] == completed["id"]


async def test_add_appends_sets(auth_client, exercise, second_exercise):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [
                {
                    "type": "add",
                    "exercise_id": str(second_exercise.id),
                    "exercise_name": second_exercise.name,
                    "sets": 2,
                    "reps": 12,
                    "weight": 25.0,
                    "summary": "Add incline press",
                }
            ],
            "apply_to_routine": False,
        },
    )
    assert resp.status_code == 200, resp.text
    added = [
        sl
        for sl in resp.json()["set_logs"]
        if sl["exercise_id"] == str(second_exercise.id)
    ]
    assert len(added) == 2
    assert all(sl["reps"] == 12 and sl["weight"] == 25.0 for sl in added)
    assert sorted(sl["set_number"] for sl in added) == [1, 2]


async def test_remove_then_add_back_works_in_order(auth_client, exercise):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [
                {
                    "type": "remove",
                    "exercise_id": str(exercise.id),
                    "exercise_name": exercise.name,
                    "summary": "out",
                },
                {
                    "type": "add",
                    "exercise_id": str(exercise.id),
                    "exercise_name": exercise.name,
                    "sets": 2,
                    "reps": 5,
                    "weight": 95.0,
                    "summary": "back in lighter",
                },
            ],
            "apply_to_routine": False,
        },
    )
    assert resp.status_code == 200, resp.text
    logs = resp.json()["set_logs"]
    assert len(logs) == 2
    assert all(sl["reps"] == 5 and sl["weight"] == 95.0 for sl in logs)


async def test_apply_to_routine_propagates_swap(
    auth_client, exercise, second_exercise
):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [_swap_action(exercise, second_exercise)],
            "apply_to_routine": True,
        },
    )
    assert resp.status_code == 200, resp.text

    routine_out = (await auth_client.get(f"/routines/{routine['id']}")).json()
    assert len(routine_out["exercises"]) == 1
    ex = routine_out["exercises"][0]
    assert ex["exercise_id"] == str(second_exercise.id)
    assert ex["target_weight"] == 40.0
    # Carried scheme from the original routine row.
    assert ex["target_sets"] == 3
    assert ex["target_reps"] == 8


async def test_apply_to_routine_false_leaves_routine_untouched(
    auth_client, exercise, second_exercise
):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])

    await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [_swap_action(exercise, second_exercise)],
            "apply_to_routine": False,
        },
    )

    routine_out = (await auth_client.get(f"/routines/{routine['id']}")).json()
    assert routine_out["exercises"][0]["exercise_id"] == str(exercise.id)


async def test_routine_half_skipped_for_session_only_exercise(
    auth_client, exercise, second_exercise
):
    """Removing an ad-hoc exercise (in the session, not the routine) must not
    error or alter the routine."""
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])
    await auth_client.post(
        f"/sessions/{session['id']}/sets",
        json={
            "exercise_id": str(second_exercise.id),
            "set_number": 1,
            "reps": 10,
            "weight": 20.0,
        },
    )

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [
                {
                    "type": "remove",
                    "exercise_id": str(second_exercise.id),
                    "exercise_name": second_exercise.name,
                    "summary": "drop the extra",
                }
            ],
            "apply_to_routine": True,
        },
    )
    assert resp.status_code == 200, resp.text
    assert all(
        sl["exercise_id"] != str(second_exercise.id)
        for sl in resp.json()["set_logs"]
    )
    routine_out = (await auth_client.get(f"/routines/{routine['id']}")).json()
    assert len(routine_out["exercises"]) == 1


async def test_completed_session_returns_409(auth_client, exercise):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])
    await auth_client.patch(
        f"/sessions/{session['id']}", json={"status": "completed"}
    )

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [
                {
                    "type": "remove",
                    "exercise_id": str(exercise.id),
                    "exercise_name": exercise.name,
                    "summary": "x",
                }
            ],
        },
    )
    assert resp.status_code == 409


async def test_foreign_session_returns_404(client, auth_client, exercise):
    """A second user cannot adjust someone else's session."""
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])

    other = await client.post(
        "/auth/register",
        json={
            "name": "Intruder",
            "email": f"intruder_{uuid.uuid4().hex[:8]}@spotter.com",
            "password": "Testpass123!",
        },
    )
    token = other.json()["access_token"]
    resp = await client.post(
        f"/ai/sessions/{session['id']}/adjust",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "actions": [
                {
                    "type": "remove",
                    "exercise_id": str(exercise.id),
                    "exercise_name": exercise.name,
                    "summary": "x",
                }
            ],
        },
    )
    assert resp.status_code == 404


async def test_unknown_exercise_id_returns_404(auth_client, exercise):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [
                {
                    "type": "remove",
                    "exercise_id": str(uuid.uuid4()),
                    "exercise_name": "Ghost Lift",
                    "summary": "x",
                }
            ],
        },
    )
    assert resp.status_code == 404


async def test_out_of_bounds_echo_returns_422(auth_client, exercise):
    """The client echo is re-validated by schema bounds — a tampered weight fails."""
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])

    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={
            "actions": [
                {
                    "type": "adjust_weight",
                    "exercise_id": str(exercise.id),
                    "exercise_name": exercise.name,
                    "weight": 5000.0,
                    "summary": "x",
                }
            ],
        },
    )
    assert resp.status_code == 422


async def test_too_many_actions_returns_422(auth_client, exercise):
    routine = await _create_routine(auth_client, str(exercise.id))
    session = await _start_session(auth_client, routine["id"])

    action = {
        "type": "adjust_weight",
        "exercise_id": str(exercise.id),
        "exercise_name": exercise.name,
        "weight": 100.0,
        "summary": "x",
    }
    resp = await auth_client.post(
        f"/ai/sessions/{session['id']}/adjust",
        json={"actions": [action] * 7},
    )
    assert resp.status_code == 422
