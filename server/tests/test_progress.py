import datetime


async def test_records_requires_auth(client):
    """GET /progress/records without auth returns 401."""
    resp = await client.get("/progress/records")
    assert resp.status_code == 401


async def test_records_empty_for_new_user(auth_client):
    """A fresh user has no personal records."""
    resp = await auth_client.get("/progress/records")
    assert resp.status_code == 200
    assert resp.json() == []


async def _routine_with_exercise(auth_client, exercise, sets=3, reps=8, weight=135.0):
    routine_resp = await auth_client.post(
        "/routines",
        json={
            "name": "PR Test Routine",
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
    return routine_resp.json()["id"]


async def test_records_computes_weight_1rm_and_volume(auth_client, exercise):
    """Records reflect the heaviest weight, best Epley 1RM, and best set volume
    across completed sets, ignoring incomplete ones."""
    routine_id = await _routine_with_exercise(auth_client, exercise)
    session_resp = await auth_client.post(
        "/sessions",
        json={"routine_id": routine_id, "date": str(datetime.date.today())},
    )
    assert session_resp.status_code == 201
    session = session_resp.json()
    session_id = session["id"]
    set_ids = [sl["id"] for sl in session["set_logs"]]
    assert len(set_ids) == 3

    # set 0: 135 x 8 (best 1RM = 171.0), set 1: 145 x 5 (heaviest weight),
    # set 2: 125 x 10 (best volume = 1250)
    plan = [(135.0, 8, True), (145.0, 5, True), (125.0, 10, True)]
    for set_id, (w, r, done) in zip(set_ids, plan):
        resp = await auth_client.patch(
            f"/sessions/{session_id}/sets/{set_id}",
            json={"weight": w, "reps": r, "completed": done},
        )
        assert resp.status_code == 200

    resp = await auth_client.get("/progress/records")
    assert resp.status_code == 200
    records = resp.json()
    assert len(records) == 1
    rec = records[0]
    assert rec["exercise_name"] == exercise.name
    assert rec["max_weight"] == 145.0
    assert rec["max_weight_reps"] == 5
    assert rec["best_est_1rm"] == 171.0  # 135 * (1 + 8/30)
    assert rec["best_volume"] == 1250.0  # 125 * 10
    assert rec["achieved_on"] == str(datetime.date.today())


async def test_exercise_progress_est_1rm_is_best_per_set(auth_client, exercise):
    """The est-1RM trend point is the best per-set Epley of the day — NOT max_weight combined
    with max_reps from different sets (which would over-report)."""
    routine_id = await _routine_with_exercise(auth_client, exercise)
    session_resp = await auth_client.post(
        "/sessions",
        json={"routine_id": routine_id, "date": str(datetime.date.today())},
    )
    session = session_resp.json()
    session_id = session["id"]
    set_ids = [sl["id"] for sl in session["set_logs"]]

    # 135x8 → e1rm 171.0 (best); 145x5 → 169.2 (heaviest weight); 125x10 → 166.7 (most reps).
    plan = [(135.0, 8), (145.0, 5), (125.0, 10)]
    for set_id, (w, r) in zip(set_ids, plan):
        await auth_client.patch(
            f"/sessions/{session_id}/sets/{set_id}",
            json={"weight": w, "reps": r, "completed": True},
        )

    resp = await auth_client.get(f"/progress/exercises/{exercise.id}")
    assert resp.status_code == 200
    points = resp.json()
    assert len(points) == 1
    point = points[0]
    assert point["max_weight"] == 145.0
    assert point["max_reps"] == 10
    # Best per-set Epley is 171.0; the naive max_weight(145) x max_reps(10) would be 193.3.
    assert point["est_1rm"] == 171.0


async def test_records_ignore_incomplete_sets(auth_client, exercise):
    """A heavier but uncompleted set does not count toward records."""
    routine_id = await _routine_with_exercise(auth_client, exercise)
    session_resp = await auth_client.post(
        "/sessions",
        json={"routine_id": routine_id, "date": str(datetime.date.today())},
    )
    session = session_resp.json()
    session_id = session["id"]
    set_ids = [sl["id"] for sl in session["set_logs"]]

    # Only the first set is completed (100 lb); a heavier 200 lb set is left undone.
    await auth_client.patch(
        f"/sessions/{session_id}/sets/{set_ids[0]}",
        json={"weight": 100.0, "reps": 8, "completed": True},
    )
    await auth_client.patch(
        f"/sessions/{session_id}/sets/{set_ids[1]}",
        json={"weight": 200.0, "reps": 8, "completed": False},
    )

    resp = await auth_client.get("/progress/records")
    records = resp.json()
    assert len(records) == 1
    assert records[0]["max_weight"] == 100.0
