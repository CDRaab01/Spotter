import datetime


async def test_list_sessions_requires_auth(client):
    """GET /sessions without auth returns 401."""
    resp = await client.get("/sessions")
    assert resp.status_code == 401


async def test_list_sessions_empty_for_new_user(auth_client):
    """A fresh user has no sessions."""
    resp = await auth_client.get("/sessions")
    assert resp.status_code == 200
    assert resp.json() == []


async def test_list_sessions_returns_created_session(auth_client):
    """Creating a session then listing returns it."""
    create_resp = await auth_client.post(
        "/sessions",
        json={"date": str(datetime.date.today())},
    )
    assert create_resp.status_code == 201
    session_id = create_resp.json()["id"]

    list_resp = await auth_client.get("/sessions")
    assert list_resp.status_code == 200
    data = list_resp.json()
    assert len(data) >= 1
    ids = [s["id"] for s in data]
    assert session_id in ids


async def test_list_sessions_includes_exercise_summary(auth_client, exercise):
    """After logging a set, GET /sessions includes that exercise in the summary."""
    # Create a routine + session
    routine_resp = await auth_client.post(
        "/routines",
        json={
            "name": "History Test Routine",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 3,
                    "target_reps": 8,
                    "target_weight": 135.0,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        },
    )
    assert routine_resp.status_code == 201
    routine_id = routine_resp.json()["id"]

    session_resp = await auth_client.post(
        "/sessions",
        json={"routine_id": routine_id, "date": str(datetime.date.today())},
    )
    assert session_resp.status_code == 201
    session = session_resp.json()
    session_id = session["id"]

    # Complete the first set
    first_set_id = session["set_logs"][0]["id"]
    await auth_client.patch(
        f"/sessions/{session_id}/sets/{first_set_id}",
        json={"completed": True},
    )

    list_resp = await auth_client.get("/sessions")
    assert list_resp.status_code == 200
    data = list_resp.json()

    session_summary = next(s for s in data if s["id"] == session_id)
    assert session_summary["total_sets"] == 3
    assert session_summary["completed_sets"] == 1
    assert len(session_summary["exercises"]) == 1
    assert session_summary["exercises"][0]["exercise_name"] == exercise.name
    assert session_summary["exercises"][0]["total_sets"] == 3
    assert session_summary["exercises"][0]["completed_sets"] == 1
