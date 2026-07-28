"""GET /insights — stalled-lift detection + PRs this week."""

import datetime

TODAY = datetime.date.today()


async def _routine_id(auth_client, exercise, weight=100.0, sets=2, reps=5) -> str:
    resp = await auth_client.post(
        "/routines",
        json={
            "name": "Insights Routine",
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
    assert resp.status_code == 201
    return resp.json()["id"]


async def _missed_session(auth_client, routine_id, date):
    """A completed session where only the first of two seeded sets was done —
    the engine's 'real miss' signal."""
    sess = await auth_client.post(
        "/sessions", json={"routine_id": routine_id, "date": str(date)}
    )
    assert sess.status_code == 201
    data = sess.json()
    first_set = data["set_logs"][0]
    await auth_client.patch(
        f"/sessions/{data['id']}/sets/{first_set['id']}", json={"completed": True}
    )
    await auth_client.patch(f"/sessions/{data['id']}", json={"status": "completed"})
    return data["id"]


async def _log_completed_set(auth_client, exercise_id, date, weight, set_type="normal"):
    sess = await auth_client.post("/sessions", json={"date": str(date)})
    session_id = sess.json()["id"]
    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={
            "exercise_id": exercise_id,
            "set_number": 1,
            "reps": 5,
            "weight": weight,
            "completed": True,
            "set_type": set_type,
        },
    )
    assert resp.status_code == 201
    await auth_client.patch(f"/sessions/{session_id}", json={"status": "completed"})


# ── Baseline / auth ────────────────────────────────────────────────────────


async def test_insights_requires_auth(client):
    assert (await client.get("/insights")).status_code == 401


async def test_insights_empty_user(auth_client):
    resp = await auth_client.get("/insights")
    assert resp.status_code == 200
    assert resp.json() == {"stalled": [], "prs_this_week": 0}


# ── Stall detection ────────────────────────────────────────────────────────


async def test_three_missed_sessions_at_same_weight_reads_stalled(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise, weight=100.0)
    for offset in (4, 3, 2):  # three consecutive misses, oldest first
        await _missed_session(auth_client, routine_id, TODAY - datetime.timedelta(days=offset))

    resp = await auth_client.get("/insights")
    assert resp.status_code == 200
    stalled = resp.json()["stalled"]
    assert len(stalled) == 1
    entry = stalled[0]
    assert entry["exercise_id"] == str(exercise.id)
    assert entry["exercise_name"] == exercise.name
    assert entry["sessions_stuck"] == 3
    assert entry["last_weight"] == 100.0


async def test_two_misses_not_yet_stalled(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise, weight=100.0)
    for offset in (3, 2):
        await _missed_session(auth_client, routine_id, TODAY - datetime.timedelta(days=offset))

    resp = await auth_client.get("/insights")
    assert resp.json()["stalled"] == []


async def test_successful_last_session_is_not_stalled(auth_client, exercise):
    routine_id = await _routine_id(auth_client, exercise, weight=100.0)
    for offset in (4, 3):
        await _missed_session(auth_client, routine_id, TODAY - datetime.timedelta(days=offset))
    # Latest session: everything completed at the rep goal → the streak is over.
    sess = await auth_client.post(
        "/sessions",
        json={"routine_id": routine_id, "date": str(TODAY - datetime.timedelta(days=2))},
    )
    for sl in sess.json()["set_logs"]:
        await auth_client.patch(
            f"/sessions/{sess.json()['id']}/sets/{sl['id']}", json={"completed": True}
        )
    await auth_client.patch(f"/sessions/{sess.json()['id']}", json={"status": "completed"})

    resp = await auth_client.get("/insights")
    assert resp.json()["stalled"] == []


# ── PRs this week ──────────────────────────────────────────────────────────


async def test_pr_this_week_counted(auth_client, exercise):
    last_week = TODAY - datetime.timedelta(days=TODAY.weekday() + 3)
    await _log_completed_set(auth_client, str(exercise.id), last_week, 100.0)
    await _log_completed_set(auth_client, str(exercise.id), TODAY, 105.0)

    resp = await auth_client.get("/insights")
    assert resp.json()["prs_this_week"] == 1


async def test_no_pr_when_below_prior_best(auth_client, exercise):
    last_week = TODAY - datetime.timedelta(days=TODAY.weekday() + 3)
    await _log_completed_set(auth_client, str(exercise.id), last_week, 100.0)
    await _log_completed_set(auth_client, str(exercise.id), TODAY, 95.0)

    resp = await auth_client.get("/insights")
    assert resp.json()["prs_this_week"] == 0


async def test_warmup_sets_never_make_a_pr(auth_client, exercise):
    last_week = TODAY - datetime.timedelta(days=TODAY.weekday() + 3)
    await _log_completed_set(auth_client, str(exercise.id), last_week, 100.0)
    # This week's only heavier set is a warm-up — no PR.
    await _log_completed_set(auth_client, str(exercise.id), TODAY, 150.0, set_type="warmup")

    resp = await auth_client.get("/insights")
    assert resp.json()["prs_this_week"] == 0


async def test_first_ever_exercise_is_not_a_pr(auth_client, exercise):
    # No prior best to beat: a brand-new lift this week doesn't count.
    await _log_completed_set(auth_client, str(exercise.id), TODAY, 135.0)
    resp = await auth_client.get("/insights")
    assert resp.json()["prs_this_week"] == 0
