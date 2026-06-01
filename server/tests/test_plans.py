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
