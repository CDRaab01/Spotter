"""RPE + set types (migration 0014): bounds, warm-up exclusion semantics, and
DELETE /sessions/{id}/sets/{set_id}."""

import datetime
import uuid


async def _free_session(auth_client) -> str:
    resp = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    assert resp.status_code == 201
    return resp.json()["id"]


async def _routine_session(auth_client, exercise, weight=135.0, sets=3, reps=8):
    routine_resp = await auth_client.post(
        "/routines",
        json={
            "name": "Set Types Routine",
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
    assert routine_resp.status_code == 201
    sess = await auth_client.post(
        "/sessions",
        json={"routine_id": routine_resp.json()["id"], "date": str(datetime.date.today())},
    )
    assert sess.status_code == 201
    return sess.json()


# ── Create / update with rpe + set_type ────────────────────────────────────


async def test_add_set_with_rpe_and_set_type(auth_client, exercise):
    session_id = await _free_session(auth_client)
    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 1,
            "reps": 8,
            "weight": 135.0,
            "rpe": 8.5,
            "set_type": "warmup",
        },
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["rpe"] == 8.5
    assert data["set_type"] == "warmup"


async def test_add_set_defaults_to_normal(auth_client, exercise):
    session_id = await _free_session(auth_client)
    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8},
    )
    assert resp.status_code == 201
    assert resp.json()["set_type"] == "normal"
    assert resp.json()["rpe"] is None


async def test_update_set_rpe_and_type(auth_client, exercise):
    session_id = await _free_session(auth_client)
    add = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8, "weight": 100.0},
    )
    set_id = add.json()["id"]
    resp = await auth_client.patch(
        f"/sessions/{session_id}/sets/{set_id}",
        json={"rpe": 9.0, "set_type": "amrap"},
    )
    assert resp.status_code == 200
    assert resp.json()["rpe"] == 9.0
    assert resp.json()["set_type"] == "amrap"


async def test_rpe_out_of_bounds_rejected(auth_client, exercise):
    session_id = await _free_session(auth_client)
    for bad_rpe in (0.5, 10.5):
        resp = await auth_client.post(
            f"/sessions/{session_id}/sets",
            json={
                "exercise_id": str(exercise.id),
                "set_number": 1,
                "reps": 8,
                "rpe": bad_rpe,
            },
        )
        assert resp.status_code == 422, f"rpe {bad_rpe} should be rejected"


async def test_unknown_set_type_rejected(auth_client, exercise):
    session_id = await _free_session(auth_client)
    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 1,
            "reps": 8,
            "set_type": "bogus",
        },
    )
    assert resp.status_code == 422


# ── Warm-up exclusion: volume ──────────────────────────────────────────────


async def test_warmup_sets_do_not_count_toward_muscle_group_volume(auth_client, exercise):
    session = await _routine_session(auth_client, exercise, weight=100.0, sets=2, reps=5)
    session_id = session["id"]
    # Complete both working sets.
    for sl in session["set_logs"]:
        await auth_client.patch(
            f"/sessions/{session_id}/sets/{sl['id']}", json={"completed": True}
        )
    # A completed warm-up set at the same load must not add sets or volume.
    warm = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 3,
            "reps": 5,
            "weight": 100.0,
            "set_type": "warmup",
            "completed": True,
        },
    )
    assert warm.status_code == 201

    resp = await auth_client.get(f"/sessions/{session_id}")
    groups = resp.json()["muscle_groups"]
    assert len(groups) == 1
    assert groups[0]["sets"] == 2  # not 3 — the warm-up is excluded
    # 2 working sets x 5 reps x 100 lb in kg, one decimal.
    assert groups[0]["volume"] == round(2 * 5 * 100 * 0.453592, 1)


# ── Warm-up exclusion: progression engine ──────────────────────────────────


async def test_warmup_sets_do_not_feed_progression(auth_client, exercise):
    # Session 1: all working sets completed at 135 → next suggestion should be
    # add_weight +5 (legs). A heavier completed warm-up (200 lb) and an *incomplete*
    # warm-up are both logged; if warm-ups leaked into the engine the working weight
    # would read 200 / the session would read as a miss.
    s1 = await _routine_session(auth_client, exercise, weight=135.0, sets=3, reps=8)
    for sl in s1["set_logs"]:
        await auth_client.patch(
            f"/sessions/{s1['id']}/sets/{sl['id']}", json={"completed": True}
        )
    await auth_client.post(
        f"/sessions/{s1['id']}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 4,
            "reps": 3,
            "weight": 200.0,
            "set_type": "warmup",
            "completed": True,
        },
    )
    await auth_client.post(
        f"/sessions/{s1['id']}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 5,
            "reps": 3,
            "weight": 60.0,
            "set_type": "warmup",
            "completed": False,
        },
    )

    s2 = await _routine_session(auth_client, exercise, weight=135.0, sets=3, reps=8)
    resp = await auth_client.get(f"/sessions/{s2['id']}/prior-bests")
    assert resp.status_code == 200
    bests = resp.json()
    assert len(bests) == 1
    best = bests[0]
    # add_weight from the 135 working sets (+5 lower body), not from the 200 warm-up
    # and not a "missed reps" hold from the incomplete warm-up.
    assert best["action"] == "add_weight"
    assert best["suggested_weight"] == 140.0
    assert all(sl["set_type"] != "warmup" for sl in best["last_sets"])


# ── DELETE /sessions/{id}/sets/{set_id} ────────────────────────────────────


async def test_delete_set(auth_client, exercise):
    session_id = await _free_session(auth_client)
    add = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8, "weight": 100.0},
    )
    set_id = add.json()["id"]

    resp = await auth_client.delete(f"/sessions/{session_id}/sets/{set_id}")
    assert resp.status_code == 204
    assert (await auth_client.get(f"/sessions/{session_id}")).json()["set_logs"] == []

    # Already gone.
    resp2 = await auth_client.delete(f"/sessions/{session_id}/sets/{set_id}")
    assert resp2.status_code == 404


async def test_delete_set_unknown_session_returns_404(auth_client):
    resp = await auth_client.delete(f"/sessions/{uuid.uuid4()}/sets/{uuid.uuid4()}")
    assert resp.status_code == 404


async def test_delete_set_on_completed_session_returns_409(auth_client, exercise):
    session_id = await _free_session(auth_client)
    add = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8},
    )
    set_id = add.json()["id"]
    await auth_client.patch(f"/sessions/{session_id}", json={"status": "completed"})

    resp = await auth_client.delete(f"/sessions/{session_id}/sets/{set_id}")
    assert resp.status_code == 409


async def test_cannot_delete_another_users_set(client, exercise):
    async def register_and_get_token(email):
        r = await client.post(
            "/auth/register",
            json={"name": "User", "email": email, "password": "pass1234"},
        )
        return r.json()["access_token"]

    uid1, uid2 = uuid.uuid4().hex[:8], uuid.uuid4().hex[:8]
    token1 = await register_and_get_token(f"ds1_{uid1}@test.com")
    token2 = await register_and_get_token(f"ds2_{uid2}@test.com")

    client.headers["Authorization"] = f"Bearer {token1}"
    create = await client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]
    add = await client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8},
    )
    set_id = add.json()["id"]

    client.headers["Authorization"] = f"Bearer {token2}"
    resp = await client.delete(f"/sessions/{session_id}/sets/{set_id}")
    assert resp.status_code == 404

    # Still there for the owner.
    client.headers["Authorization"] = f"Bearer {token1}"
    assert len((await client.get(f"/sessions/{session_id}")).json()["set_logs"]) == 1
