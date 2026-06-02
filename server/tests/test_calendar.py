import datetime


async def test_calendar_returns_200_no_sessions(auth_client):
    resp = await auth_client.get("/calendar?from=2026-01-01&to=2026-12-31")
    assert resp.status_code == 200
    assert resp.json() == []


async def test_calendar_returns_200_with_session_no_plan(auth_client, exercise):
    session_resp = await auth_client.post(
        "/sessions",
        json={"date": "2026-06-01"},
    )
    assert session_resp.status_code == 201

    resp = await auth_client.get("/calendar?from=2026-01-01&to=2026-12-31")
    assert resp.status_code == 200
    entries = resp.json()
    assert len(entries) == 1
    assert entries[0]["plan_name"] is None
    assert entries[0]["date"] == "2026-06-01"


async def test_calendar_returns_200_with_session_with_plan(auth_client, exercise):
    plan_resp = await auth_client.post(
        "/plans",
        json={
            "name": "Test Plan",
            "source": "manual",
            "exercises": [
                {"exercise_id": str(exercise.id), "target_sets": 3, "target_reps": 8}
            ],
        },
    )
    assert plan_resp.status_code == 201
    plan_id = plan_resp.json()["id"]

    session_resp = await auth_client.post(
        "/sessions",
        json={"plan_id": plan_id, "date": "2026-06-02"},
    )
    assert session_resp.status_code == 201

    resp = await auth_client.get("/calendar?from=2026-01-01&to=2026-12-31")
    assert resp.status_code == 200
    entries = resp.json()
    matching = [e for e in entries if e["date"] == "2026-06-02"]
    assert len(matching) >= 1
    assert matching[0]["plan_name"] == "Test Plan"


async def test_calendar_filters_by_date_range(auth_client):
    await auth_client.post("/sessions", json={"date": "2025-01-01"})

    resp = await auth_client.get("/calendar?from=2026-01-01&to=2026-12-31")
    assert resp.status_code == 200
    entries = resp.json()
    for entry in entries:
        assert entry["date"] >= "2026-01-01"
        assert entry["date"] <= "2026-12-31"


async def test_calendar_unauthenticated_blocked(client):
    resp = await client.get("/calendar?from=2026-01-01&to=2026-12-31")
    assert resp.status_code == 401
