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


async def test_add_set_with_unknown_exercise_returns_404(auth_client):
    """A bogus exercise_id must return a clean 404, not a 500 FK error."""
    create = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]

    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(uuid.uuid4()), "set_number": 1, "reps": 8},
    )
    assert resp.status_code == 404


async def test_delete_session(auth_client, exercise):
    create = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]
    await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8, "weight": 100.0},
    )

    resp = await auth_client.delete(f"/sessions/{session_id}")
    assert resp.status_code == 204

    # It's gone (and its set logs were cascade-deleted, no orphan rows).
    assert (await auth_client.get(f"/sessions/{session_id}")).status_code == 404


async def test_delete_nonexistent_session_returns_404(auth_client):
    resp = await auth_client.delete(f"/sessions/{uuid.uuid4()}")
    assert resp.status_code == 404


async def test_cannot_delete_another_users_session(client):
    async def register_and_get_token(email):
        r = await client.post(
            "/auth/register",
            json={"name": "User", "email": email, "password": "pass1234"},
        )
        return r.json()["access_token"]

    uid1, uid2 = uuid.uuid4().hex[:8], uuid.uuid4().hex[:8]
    token1 = await register_and_get_token(f"d1_{uid1}@test.com")
    token2 = await register_and_get_token(f"d2_{uid2}@test.com")

    client.headers["Authorization"] = f"Bearer {token1}"
    create = await client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]

    client.headers["Authorization"] = f"Bearer {token2}"
    resp = await client.delete(f"/sessions/{session_id}")
    assert resp.status_code == 404


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


async def test_session_with_routine_pre_populates_set_logs(auth_client, exercise):
    # Create a routine with one exercise (3 sets)
    routine_resp = await auth_client.post(
        "/routines",
        json={
            "name": "Test Routine",
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
    assert routine_resp.status_code == 201
    routine_id = routine_resp.json()["id"]

    # Start a session from that routine
    session_resp = await auth_client.post(
        "/sessions",
        json={"routine_id": routine_id, "date": str(datetime.date.today())},
    )
    assert session_resp.status_code == 201
    data = session_resp.json()

    assert len(data["set_logs"]) == 3
    assert all(sl["reps"] == 8 for sl in data["set_logs"])
    assert all(sl["weight"] == 135.0 for sl in data["set_logs"])
    assert all(not sl["completed"] for sl in data["set_logs"])


async def _make_routine_and_session(auth_client, exercise) -> tuple[str, dict]:
    routine_resp = await auth_client.post(
        "/routines",
        json={
            "name": "Test Routine",
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
    routine_id = routine_resp.json()["id"]
    sess_resp = await auth_client.post(
        "/sessions", json={"routine_id": routine_id, "date": str(datetime.date.today())}
    )
    return routine_id, sess_resp.json()


async def test_prior_bests_empty_for_first_session(auth_client, exercise):
    _, session = await _make_routine_and_session(auth_client, exercise)
    resp = await auth_client.get(f"/sessions/{session['id']}/prior-bests")
    assert resp.status_code == 200
    assert resp.json() == []


async def test_prior_bests_returns_data_after_completed_prior_session(
    auth_client, exercise
):
    _, s1 = await _make_routine_and_session(auth_client, exercise)
    # Complete the first set of session 1
    first_set_id = s1["set_logs"][0]["id"]
    await auth_client.patch(
        f"/sessions/{s1['id']}/sets/{first_set_id}", json={"completed": True}
    )

    # Start a second session from the same routine
    _, s2 = await _make_routine_and_session(auth_client, exercise)
    resp = await auth_client.get(f"/sessions/{s2['id']}/prior-bests")
    assert resp.status_code == 200
    bests = resp.json()
    assert len(bests) == 1
    assert bests[0]["exercise_id"] == str(exercise.id)
    assert bests[0]["reps"] == 8
    assert bests[0]["weight"] == 135.0


async def test_exercise_notes_patch_and_retrieve(auth_client, exercise):
    _, session = await _make_routine_and_session(auth_client, exercise)
    exercise_id = str(exercise.id)

    patch_resp = await auth_client.patch(
        f"/sessions/{session['id']}",
        json={"exercise_notes": {exercise_id: "Keep elbows tucked"}},
    )
    assert patch_resp.status_code == 200
    assert patch_resp.json()["exercise_notes"][exercise_id] == "Keep elbows tucked"

    get_resp = await auth_client.get(f"/sessions/{session['id']}")
    assert get_resp.json()["exercise_notes"][exercise_id] == "Keep elbows tucked"


async def test_session_includes_routine_name(auth_client, exercise):
    _, session = await _make_routine_and_session(auth_client, exercise)
    resp = await auth_client.get(f"/sessions/{session['id']}")
    assert resp.json()["routine_name"] == "Test Routine"


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


async def test_session_includes_muscle_groups_after_completed_sets(auth_client, exercise):
    _, session = await _make_routine_and_session(auth_client, exercise)
    for sl in session["set_logs"]:
        await auth_client.patch(
            f"/sessions/{session['id']}/sets/{sl['id']}", json={"completed": True}
        )
    resp = await auth_client.get(f"/sessions/{session['id']}")
    assert resp.status_code == 200
    muscle_groups = resp.json().get("muscle_groups", [])
    assert len(muscle_groups) > 0
    assert muscle_groups[0]["muscle_group"] == exercise.muscle_group
    assert muscle_groups[0]["sets"] == 3


async def test_prior_bests_includes_last_sets_after_prior_session(auth_client, exercise):
    _, s1 = await _make_routine_and_session(auth_client, exercise)
    for sl in s1["set_logs"][:2]:
        await auth_client.patch(
            f"/sessions/{s1['id']}/sets/{sl['id']}", json={"completed": True}
        )

    _, s2 = await _make_routine_and_session(auth_client, exercise)
    resp = await auth_client.get(f"/sessions/{s2['id']}/prior-bests")
    assert resp.status_code == 200
    bests = resp.json()
    assert len(bests) == 1
    last_sets = bests[0]["last_sets"]
    assert len(last_sets) == 2
    assert all(sl["completed"] is True for sl in last_sets)


async def test_set_log_superset_group_propagated_from_routine(auth_client, exercise):
    routine_resp = await auth_client.post(
        "/routines",
        json={
            "name": "Superset Routine",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 2,
                    "target_reps": 10,
                    "is_bodyweight": False,
                    "order": 0,
                    "superset_group": 1,
                }
            ],
        },
    )
    assert routine_resp.status_code == 201
    routine_id = routine_resp.json()["id"]

    sess_resp = await auth_client.post(
        "/sessions", json={"routine_id": routine_id, "date": str(datetime.date.today())}
    )
    assert sess_resp.status_code == 201
    set_logs = sess_resp.json()["set_logs"]
    assert len(set_logs) == 2
    assert all(sl["superset_group"] == 1 for sl in set_logs)
