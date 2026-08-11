import datetime
import uuid


async def _make_routine(auth_client, exercise_id: str) -> str:
    resp = await auth_client.post(
        "/routines",
        json={
            "name": f"Routine {uuid.uuid4().hex[:4]}",
            "exercises": [
                {
                    "exercise_id": exercise_id,
                    "target_sets": 3,
                    "target_reps": 8,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        },
    )
    assert resp.status_code == 201
    return resp.json()["id"]


# ── CRUD ──────────────────────────────────────────────────────────────────────

async def test_create_program(auth_client, exercise):
    routine_id = await _make_routine(auth_client, str(exercise.id))
    resp = await auth_client.post(
        "/programs",
        json={
            "name": "PPL",
            "days": [
                {"routine_id": routine_id, "label": "Push", "order": 0},
                {"routine_id": None, "label": "Rest", "order": 1},
            ],
        },
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["name"] == "PPL"
    assert len(data["days"]) == 2
    assert data["is_active"] is False


async def test_list_programs(auth_client):
    await auth_client.post("/programs", json={"name": "Alpha", "days": []})
    await auth_client.post("/programs", json={"name": "Beta", "days": []})

    resp = await auth_client.get("/programs")
    assert resp.status_code == 200
    names = [p["name"] for p in resp.json()]
    assert "Alpha" in names and "Beta" in names


async def test_get_program(auth_client, exercise):
    routine_id = await _make_routine(auth_client, str(exercise.id))
    create = await auth_client.post(
        "/programs",
        json={"name": "Full Body", "days": [{"routine_id": routine_id, "label": "Day A", "order": 0}]},
    )
    prog_id = create.json()["id"]

    resp = await auth_client.get(f"/programs/{prog_id}")
    assert resp.status_code == 200
    data = resp.json()
    assert data["id"] == prog_id
    assert len(data["days"]) == 1
    assert data["days"][0]["label"] == "Day A"


async def test_get_nonexistent_program_returns_404(auth_client):
    resp = await auth_client.get(f"/programs/{uuid.uuid4()}")
    assert resp.status_code == 404


async def test_update_program_name(auth_client):
    create = await auth_client.post("/programs", json={"name": "Old Name", "days": []})
    prog_id = create.json()["id"]

    resp = await auth_client.patch(f"/programs/{prog_id}", json={"name": "New Name"})
    assert resp.status_code == 200
    assert resp.json()["name"] == "New Name"


async def test_activate_program_deactivates_others(auth_client):
    r1 = await auth_client.post("/programs", json={"name": "Prog1", "days": []})
    r2 = await auth_client.post("/programs", json={"name": "Prog2", "days": []})
    pid1, pid2 = r1.json()["id"], r2.json()["id"]

    await auth_client.patch(f"/programs/{pid1}", json={"is_active": True})
    # Activating prog2 should deactivate prog1
    await auth_client.patch(f"/programs/{pid2}", json={"is_active": True})

    p1 = (await auth_client.get(f"/programs/{pid1}")).json()
    p2 = (await auth_client.get(f"/programs/{pid2}")).json()
    assert p1["is_active"] is False
    assert p2["is_active"] is True


async def test_delete_program(auth_client):
    create = await auth_client.post("/programs", json={"name": "Temp", "days": []})
    prog_id = create.json()["id"]

    assert (await auth_client.delete(f"/programs/{prog_id}")).status_code == 204
    assert (await auth_client.get(f"/programs/{prog_id}")).status_code == 404


async def test_delete_nonexistent_program_returns_404(auth_client):
    resp = await auth_client.delete(f"/programs/{uuid.uuid4()}")
    assert resp.status_code == 404


async def test_replace_program_days(auth_client, exercise):
    routine_id = await _make_routine(auth_client, str(exercise.id))
    create = await auth_client.post(
        "/programs",
        json={"name": "Prog", "days": [{"label": "Old Day", "order": 0}]},
    )
    prog_id = create.json()["id"]

    resp = await auth_client.put(
        f"/programs/{prog_id}/days",
        json={
            "days": [
                {"label": "Push", "order": 0},
                {"routine_id": routine_id, "label": "Pull", "order": 1},
            ]
        },
    )
    assert resp.status_code == 200
    labels = [d["label"] for d in resp.json()["days"]]
    assert labels == ["Push", "Pull"]


# ── Next-day scheduling ───────────────────────────────────────────────────────

async def test_get_next_day_no_active_program_returns_null(auth_client):
    resp = await auth_client.get("/programs/active/next")
    assert resp.status_code == 200
    assert resp.json() is None


async def test_get_next_day_with_no_prior_session_returns_first_day(auth_client, exercise):
    routine_id = await _make_routine(auth_client, str(exercise.id))
    create = await auth_client.post(
        "/programs",
        json={
            "name": "PPL",
            "days": [
                {"routine_id": routine_id, "label": "Push", "order": 0},
                {"label": "Pull", "order": 1},
            ],
        },
    )
    prog_id = create.json()["id"]
    await auth_client.patch(f"/programs/{prog_id}", json={"is_active": True})

    resp = await auth_client.get("/programs/active/next")
    assert resp.status_code == 200
    assert resp.json()["label"] == "Push"


async def test_get_next_day_cycles_after_completed_session(auth_client, exercise):
    routine_a = await _make_routine(auth_client, str(exercise.id))
    routine_b = await _make_routine(auth_client, str(exercise.id))
    create = await auth_client.post(
        "/programs",
        json={
            "name": "AB",
            "days": [
                {"routine_id": routine_a, "label": "Day A", "order": 0},
                {"routine_id": routine_b, "label": "Day B", "order": 1},
            ],
        },
    )
    prog_id = create.json()["id"]
    await auth_client.patch(f"/programs/{prog_id}", json={"is_active": True})

    # Create and complete a session from routine A
    sess = await auth_client.post(
        "/sessions", json={"routine_id": routine_a, "date": str(datetime.date.today())}
    )
    sess_id = sess.json()["id"]
    await auth_client.patch(
        f"/sessions/{sess_id}", json={"status": "completed", "duration_seconds": 1800}
    )

    resp = await auth_client.get("/programs/active/next")
    assert resp.status_code == 200
    # Day A matched the last session → next is Day B
    assert resp.json()["label"] == "Day B"


async def test_get_next_day_skips_rest_day(auth_client, exercise):
    # A workout followed by a rest day (no routine). After the workout, the next *day* would be the
    # rest day — but a rest day has no routine and can never be completed, so it must be auto-skipped
    # to the next actual workout (here, the workout itself again) rather than stall "next up" on it.
    routine_id = await _make_routine(auth_client, str(exercise.id))
    create = await auth_client.post(
        "/programs",
        json={
            "name": "Workout + Rest",
            "days": [
                {"routine_id": routine_id, "label": "Workout", "order": 0},
                {"routine_id": None, "label": "Rest", "order": 1},
            ],
        },
    )
    prog_id = create.json()["id"]
    await auth_client.patch(f"/programs/{prog_id}", json={"is_active": True})

    sess = await auth_client.post(
        "/sessions", json={"routine_id": routine_id, "date": str(datetime.date.today())}
    )
    sess_id = sess.json()["id"]
    await auth_client.patch(
        f"/sessions/{sess_id}", json={"status": "completed", "duration_seconds": 1800}
    )

    resp = await auth_client.get("/programs/active/next")
    assert resp.status_code == 200
    # The rest day at order 1 is skipped; the next workout is the Workout day again.
    assert resp.json()["label"] == "Workout"
    assert resp.json()["routine_id"] == routine_id


# ── Day routine ownership ─────────────────────────────────────────────────────

async def test_create_program_with_nonexistent_routine_returns_422(auth_client):
    resp = await auth_client.post(
        "/programs",
        json={
            "name": "Bad",
            "days": [{"routine_id": str(uuid.uuid4()), "label": "Push", "order": 0}],
        },
    )
    assert resp.status_code == 422


async def test_create_program_with_other_users_routine_returns_422(auth_client, client, exercise):
    # auth_client and client share the underlying HTTP client; swapping the
    # Authorization header switches the acting user.
    routine_id = await _make_routine(auth_client, str(exercise.id))

    uid = uuid.uuid4().hex[:8]
    reg = await client.post(
        "/auth/register",
        json={"name": "User2", "email": f"u2_{uid}@test.com", "password": "Testpass123!"},
    )
    client.headers["Authorization"] = f"Bearer {reg.json()['access_token']}"

    resp = await client.post(
        "/programs",
        json={"name": "Steal", "days": [{"routine_id": routine_id, "label": "Push", "order": 0}]},
    )
    assert resp.status_code == 422


async def test_replace_days_with_nonexistent_routine_returns_422(auth_client, exercise):
    routine_id = await _make_routine(auth_client, str(exercise.id))
    create = await auth_client.post(
        "/programs",
        json={"name": "Prog", "days": [{"routine_id": routine_id, "label": "Day A", "order": 0}]},
    )
    prog_id = create.json()["id"]

    resp = await auth_client.put(
        f"/programs/{prog_id}/days",
        json={"days": [{"routine_id": str(uuid.uuid4()), "label": "Ghost", "order": 0}]},
    )
    assert resp.status_code == 422
    # The original days survive the rejected replace.
    days = (await auth_client.get(f"/programs/{prog_id}")).json()["days"]
    assert [d["label"] for d in days] == ["Day A"]


async def test_replace_days_with_other_users_routine_returns_422(auth_client, client, exercise):
    routine_id = await _make_routine(auth_client, str(exercise.id))

    uid = uuid.uuid4().hex[:8]
    reg = await client.post(
        "/auth/register",
        json={"name": "User3", "email": f"u3_{uid}@test.com", "password": "Testpass123!"},
    )
    client.headers["Authorization"] = f"Bearer {reg.json()['access_token']}"

    create = await client.post("/programs", json={"name": "Mine", "days": []})
    prog_id = create.json()["id"]
    resp = await client.put(
        f"/programs/{prog_id}/days",
        json={"days": [{"routine_id": routine_id, "label": "Push", "order": 0}]},
    )
    assert resp.status_code == 422


async def test_create_all_rest_day_program_still_allowed(auth_client):
    resp = await auth_client.post(
        "/programs",
        json={
            "name": "Recovery Week",
            "days": [
                {"routine_id": None, "label": "Rest 1", "order": 0},
                {"routine_id": None, "label": "Rest 2", "order": 1},
            ],
        },
    )
    assert resp.status_code == 201


# ── Length / count caps ───────────────────────────────────────────────────────

async def test_program_name_over_255_returns_422(auth_client):
    resp = await auth_client.post("/programs", json={"name": "x" * 256, "days": []})
    assert resp.status_code == 422


async def test_program_name_at_255_is_accepted(auth_client):
    resp = await auth_client.post("/programs", json={"name": "x" * 255, "days": []})
    assert resp.status_code == 201


async def test_empty_program_name_returns_422(auth_client):
    resp = await auth_client.post("/programs", json={"name": "", "days": []})
    assert resp.status_code == 422


async def test_day_label_over_100_returns_422(auth_client):
    resp = await auth_client.post(
        "/programs",
        json={"name": "Prog", "days": [{"label": "y" * 101, "order": 0}]},
    )
    assert resp.status_code == 422


async def test_more_than_14_days_returns_422(auth_client):
    days = [{"label": f"Day {i}", "order": i} for i in range(15)]
    resp = await auth_client.post("/programs", json={"name": "Too Long", "days": days})
    assert resp.status_code == 422


async def test_14_days_is_accepted(auth_client):
    days = [{"label": f"Day {i}", "order": i} for i in range(14)]
    resp = await auth_client.post("/programs", json={"name": "Two Weeks", "days": days})
    assert resp.status_code == 201
    assert len(resp.json()["days"]) == 14


# ── Access control ────────────────────────────────────────────────────────────

async def test_cannot_access_other_users_program(auth_client, client):
    create = await auth_client.post("/programs", json={"name": "Private", "days": []})
    prog_id = create.json()["id"]

    uid = uuid.uuid4().hex[:8]
    reg = await client.post(
        "/auth/register",
        json={"name": "User2", "email": f"u2_{uid}@test.com", "password": "Testpass123!"},
    )
    other_token = reg.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {other_token}"

    resp = await client.get(f"/programs/{prog_id}")
    assert resp.status_code == 404
