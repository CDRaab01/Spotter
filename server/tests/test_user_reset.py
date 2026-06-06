import datetime
import uuid


async def _seed_user_data(auth_client, exercise_id: str):
    """Create a routine, a session with a logged set, and a body metric for the user."""
    routine = await auth_client.post(
        "/routines",
        json={
            "name": "Push Day",
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
    assert routine.status_code == 201, routine.text

    session = await auth_client.post(
        "/sessions", json={"date": str(datetime.date.today())}
    )
    assert session.status_code == 201, session.text
    session_id = session.json()["id"]
    set_resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": exercise_id, "set_number": 1, "reps": 8, "weight": 135.0},
    )
    assert set_resp.status_code in (200, 201), set_resp.text

    metric = await auth_client.post(
        "/metrics/weight", json={"date": str(datetime.date.today()), "weight": 180.0}
    )
    assert metric.status_code == 201, metric.text


async def test_reset_wipes_all_user_data(auth_client, exercise):
    await _seed_user_data(auth_client, str(exercise.id))

    # Sanity: the data exists before reset.
    assert len((await auth_client.get("/routines")).json()) == 1
    assert len((await auth_client.get("/sessions")).json()) == 1
    assert len((await auth_client.get("/metrics/weight")).json()) == 1

    resp = await auth_client.post("/users/reset")
    assert resp.status_code == 204, resp.text

    # Everything is gone.
    assert (await auth_client.get("/routines")).json() == []
    assert (await auth_client.get("/sessions")).json() == []
    assert (await auth_client.get("/metrics/weight")).json() == []


async def test_reset_keeps_account(auth_client, exercise):
    me_before = await auth_client.get("/users/me")
    assert me_before.status_code == 200
    email = me_before.json()["email"]

    await _seed_user_data(auth_client, str(exercise.id))
    await auth_client.post("/users/reset")

    # The account (login) is preserved: the same token still resolves the same user.
    me_after = await auth_client.get("/users/me")
    assert me_after.status_code == 200
    assert me_after.json()["email"] == email


async def test_reset_only_affects_current_user(client, exercise):
    # User A registers and seeds data.
    a_id = uuid.uuid4().hex[:8]
    reg_a = await client.post(
        "/auth/register",
        json={"name": "A", "email": f"a_{a_id}@spotter.com", "password": "Testpass123!"},
    )
    token_a = reg_a.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {token_a}"
    await _seed_user_data(client, str(exercise.id))

    # User B registers, seeds data, and resets.
    b_id = uuid.uuid4().hex[:8]
    reg_b = await client.post(
        "/auth/register",
        json={"name": "B", "email": f"b_{b_id}@spotter.com", "password": "Testpass123!"},
    )
    token_b = reg_b.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {token_b}"
    await _seed_user_data(client, str(exercise.id))
    assert (await client.post("/users/reset")).status_code == 204
    assert (await client.get("/routines")).json() == []

    # User A's data is untouched.
    client.headers["Authorization"] = f"Bearer {token_a}"
    assert len((await client.get("/routines")).json()) == 1


async def test_reset_requires_auth(client):
    resp = await client.post("/users/reset")
    assert resp.status_code == 401
