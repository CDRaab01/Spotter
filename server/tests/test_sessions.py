import datetime
import uuid


async def test_create_free_session(auth_client):
    resp = await auth_client.post(
        "/sessions",
        json={"date": str(datetime.date.today())},
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["status"] == "in_progress"
    assert data["set_logs"] == []


async def test_get_session(auth_client):
    create = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]

    resp = await auth_client.get(f"/sessions/{session_id}")
    assert resp.status_code == 200
    assert resp.json()["id"] == session_id


async def test_get_session_not_found_returns_404(auth_client):
    resp = await auth_client.get(f"/sessions/{uuid.uuid4()}")
    assert resp.status_code == 404


async def test_add_set(auth_client, exercise):
    create = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]

    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 1,
            "reps": 8,
            "weight": 135.0,
            "completed": False,
        },
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["reps"] == 8
    assert data["weight"] == 135.0
    assert data["completed"] is False


async def test_toggle_set_completion(auth_client, exercise):
    create = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]

    add = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 5, "completed": False},
    )
    set_id = add.json()["id"]

    resp = await auth_client.patch(
        f"/sessions/{session_id}/sets/{set_id}",
        json={"completed": True},
    )
    assert resp.status_code == 200
    assert resp.json()["completed"] is True

    # Toggle back off
    resp2 = await auth_client.patch(
        f"/sessions/{session_id}/sets/{set_id}",
        json={"completed": False},
    )
    assert resp2.json()["completed"] is False


async def test_edit_set_reps_and_weight(auth_client, exercise):
    create = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]

    add = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8, "weight": 100.0},
    )
    set_id = add.json()["id"]

    resp = await auth_client.patch(
        f"/sessions/{session_id}/sets/{set_id}",
        json={"reps": 10, "weight": 110.0},
    )
    assert resp.status_code == 200
    assert resp.json()["reps"] == 10
    assert resp.json()["weight"] == 110.0


async def test_finish_session(auth_client):
    create = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]

    resp = await auth_client.patch(
        f"/sessions/{session_id}",
        json={"status": "completed", "duration_seconds": 3600},
    )
    assert resp.status_code == 200
    assert resp.json()["status"] == "completed"
    assert resp.json()["duration_seconds"] == 3600


async def test_session_with_plan_pre_populates_set_logs(auth_client, exercise):
    # Create a plan with one exercise (3 sets)
    plan_resp = await auth_client.post(
        "/plans",
        json={
            "name": "Test Plan",
            "source": "manual",
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
    assert plan_resp.status_code == 201
    plan_id = plan_resp.json()["id"]

    # Start a session from that plan
    session_resp = await auth_client.post(
        "/sessions",
        json={"plan_id": plan_id, "date": str(datetime.date.today())},
    )
    assert session_resp.status_code == 201
    data = session_resp.json()

    assert len(data["set_logs"]) == 3
    assert all(sl["reps"] == 8 for sl in data["set_logs"])
    assert all(sl["weight"] == 135.0 for sl in data["set_logs"])
    assert all(not sl["completed"] for sl in data["set_logs"])


async def _make_plan_and_session(auth_client, exercise) -> tuple[str, dict]:
    plan_resp = await auth_client.post(
        "/plans",
        json={
            "name": "Test Plan",
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
    plan_id = plan_resp.json()["id"]
    sess_resp = await auth_client.post(
        "/sessions", json={"plan_id": plan_id, "date": str(datetime.date.today())}
    )
    return plan_id, sess_resp.json()


async def test_prior_bests_empty_for_first_session(auth_client, exercise):
    _, session = await _make_plan_and_session(auth_client, exercise)
    resp = await auth_client.get(f"/sessions/{session['id']}/prior-bests")
    assert resp.status_code == 200
    assert resp.json() == []


async def test_prior_bests_returns_data_after_completed_prior_session(
    auth_client, exercise
):
    _, s1 = await _make_plan_and_session(auth_client, exercise)
    # Complete the first set of session 1
    first_set_id = s1["set_logs"][0]["id"]
    await auth_client.patch(
        f"/sessions/{s1['id']}/sets/{first_set_id}", json={"completed": True}
    )

    # Start a second session from the same plan
    _, s2 = await _make_plan_and_session(auth_client, exercise)
    resp = await auth_client.get(f"/sessions/{s2['id']}/prior-bests")
    assert resp.status_code == 200
    bests = resp.json()
    assert len(bests) == 1
    assert bests[0]["exercise_id"] == str(exercise.id)
    assert bests[0]["reps"] == 8
    assert bests[0]["weight"] == 135.0


async def test_exercise_notes_patch_and_retrieve(auth_client, exercise):
    _, session = await _make_plan_and_session(auth_client, exercise)
    exercise_id = str(exercise.id)

    patch_resp = await auth_client.patch(
        f"/sessions/{session['id']}",
        json={"exercise_notes": {exercise_id: "Keep elbows tucked"}},
    )
    assert patch_resp.status_code == 200
    assert patch_resp.json()["exercise_notes"][exercise_id] == "Keep elbows tucked"

    get_resp = await auth_client.get(f"/sessions/{session['id']}")
    assert get_resp.json()["exercise_notes"][exercise_id] == "Keep elbows tucked"


async def test_session_includes_plan_name(auth_client, exercise):
    _, session = await _make_plan_and_session(auth_client, exercise)
    resp = await auth_client.get(f"/sessions/{session['id']}")
    assert resp.json()["plan_name"] == "Test Plan"


async def test_prior_bests_404_for_unknown_session(auth_client):
    resp = await auth_client.get(f"/sessions/{uuid.uuid4()}/prior-bests")
    assert resp.status_code == 404


async def test_cannot_access_another_users_session(client, exercise):
    # Register two users
    async def register_and_get_token(email):
        r = await client.post(
            "/auth/register",
            json={"name": "User", "email": email, "password": "pass1234"},
        )
        return r.json()["access_token"]

    uid1, uid2 = uuid.uuid4().hex[:8], uuid.uuid4().hex[:8]
    token1 = await register_and_get_token(f"u1_{uid1}@test.com")
    token2 = await register_and_get_token(f"u2_{uid2}@test.com")

    # User 1 creates a session
    client.headers["Authorization"] = f"Bearer {token1}"
    create = await client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]

    # User 2 tries to access it
    client.headers["Authorization"] = f"Bearer {token2}"
    resp = await client.get(f"/sessions/{session_id}")
    assert resp.status_code == 404
