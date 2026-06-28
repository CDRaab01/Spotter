"""Regression tests for database errors that used to surface as 500s.

Found by property-based (Schemathesis) fuzzing: requests referencing a non-existent
foreign key, or carrying a value Postgres can't store (a NUL byte in text), tripped a
DB error that wasn't caught and returned 500. They must now return clean 4xx.
"""
import uuid

import datetime


async def _create_routine(auth_client, exercise_id: str) -> dict:
    resp = await auth_client.post(
        "/routines",
        json={
            "name": "Reg Test",
            "source": "manual",
            "exercises": [
                {"exercise_id": exercise_id, "target_sets": 3, "target_reps": 8, "order": 0}
            ],
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def test_start_session_with_unknown_routine_returns_404(auth_client):
    resp = await auth_client.post(
        "/sessions",
        json={"date": str(datetime.date.today()), "routine_id": str(uuid.uuid4())},
    )
    assert resp.status_code == 404, resp.text


async def test_create_routine_with_unknown_exercise_returns_404(auth_client):
    resp = await auth_client.post(
        "/routines",
        json={
            "name": "Bad",
            "source": "manual",
            "exercises": [
                {"exercise_id": str(uuid.uuid4()), "target_sets": 3, "target_reps": 8, "order": 0}
            ],
        },
    )
    assert resp.status_code == 404, resp.text


async def test_update_routine_exercises_with_unknown_exercise_returns_404(auth_client, exercise):
    routine = await _create_routine(auth_client, str(exercise.id))
    resp = await auth_client.put(
        f"/routines/{routine['id']}/exercises",
        json={"exercises": [{"exercise_id": str(uuid.uuid4()), "target_sets": 3, "target_reps": 5, "order": 0}]},
    )
    assert resp.status_code == 404, resp.text


async def test_rename_routine_with_nul_byte_returns_422(auth_client, exercise):
    """A NUL byte in text is rejected by Postgres (DataError) -> 422, not 500."""
    routine = await _create_routine(auth_client, str(exercise.id))
    resp = await auth_client.patch(f"/routines/{routine['id']}", json={"name": "bad\x00name"})
    assert resp.status_code == 422, resp.text
