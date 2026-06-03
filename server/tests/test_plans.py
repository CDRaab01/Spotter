import uuid


async def _create_plan(auth_client, exercise_id: str, name: str = "Test Plan") -> dict:
    resp = await auth_client.post(
        "/plans",
        json={
            "name": name,
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


async def test_delete_plan_returns_204(auth_client, exercise):
    plan = await _create_plan(auth_client, str(exercise.id))
    resp = await auth_client.delete(f"/plans/{plan['id']}")
    assert resp.status_code == 204


async def test_deleted_plan_not_found(auth_client, exercise):
    plan = await _create_plan(auth_client, str(exercise.id))
    await auth_client.delete(f"/plans/{plan['id']}")
    resp = await auth_client.get(f"/plans/{plan['id']}")
    assert resp.status_code == 404


async def test_delete_nonexistent_plan_returns_404(auth_client):
    resp = await auth_client.delete(f"/plans/{uuid.uuid4()}")
    assert resp.status_code == 404


async def test_delete_plan_with_sessions_succeeds(auth_client, exercise):
    """Deleting a plan that a session references must not 500.

    Regression test for workout_sessions.plan_id lacking ON DELETE SET NULL.
    The session is detached (plan_id nulled), not deleted.
    """
    import datetime

    plan = await _create_plan(auth_client, str(exercise.id))

    session = await auth_client.post(
        "/sessions", json={"date": str(datetime.date.today()), "plan_id": plan["id"]}
    )
    assert session.status_code == 201, session.text
    session_id = session.json()["id"]

    resp = await auth_client.delete(f"/plans/{plan['id']}")
    assert resp.status_code == 204, resp.text

    # The session survives, with its plan link cleared.
    got = await auth_client.get(f"/sessions/{session_id}")
    assert got.status_code == 200, got.text
    assert got.json()["plan_id"] is None



async def test_rename_plan(auth_client, exercise):
    plan = await _create_plan(auth_client, str(exercise.id), name="Old Name")
    resp = await auth_client.patch(f"/plans/{plan['id']}", json={"name": "New Name"})
    assert resp.status_code == 200
    assert resp.json()["name"] == "New Name"


async def test_rename_plan_preserves_exercises(auth_client, exercise):
    plan = await _create_plan(auth_client, str(exercise.id))
    resp = await auth_client.patch(f"/plans/{plan['id']}", json={"name": "Renamed"})
    assert resp.status_code == 200
    assert len(resp.json()["exercises"]) == 1


async def test_rename_nonexistent_plan_returns_404(auth_client):
    resp = await auth_client.patch(f"/plans/{uuid.uuid4()}", json={"name": "X"})
    assert resp.status_code == 404


async def test_other_users_plan_not_accessible(auth_client, client, exercise):
    """A plan created by user A should not be deletable by user B."""
    plan = await _create_plan(auth_client, str(exercise.id))

    # Register a second user and get their token
    uid = __import__("uuid").uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={
            "name": "Other User",
            "email": f"other_{uid}@spotter.com",
            "password": "Testpass123!",
        },
    )
    assert resp.status_code == 201
    other_token = resp.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {other_token}"

    resp = await client.delete(f"/plans/{plan['id']}")
    assert resp.status_code == 404


async def test_plan_exercise_superset_group_roundtrip(auth_client, exercise):
    resp = await auth_client.post(
        "/plans",
        json={
            "name": "Superset Test",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 3,
                    "target_reps": 8,
                    "is_bodyweight": False,
                    "order": 0,
                    "superset_group": 2,
                }
            ],
        },
    )
    assert resp.status_code == 201
    plan_id = resp.json()["id"]

    detail = await auth_client.get(f"/plans/{plan_id}")
    assert detail.status_code == 200
    assert detail.json()["exercises"][0]["superset_group"] == 2
