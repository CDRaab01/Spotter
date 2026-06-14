import pytest


@pytest.mark.asyncio
async def test_start_cardio_session(auth_client):
    resp = await auth_client.post(
        "/cardio/sessions",
        json={"program_id": "c25k", "week_number": 1, "day_number": 1},
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["program_id"] == "c25k"
    assert body["week_number"] == 1
    assert body["day_number"] == 1
    assert body["status"] == "in_progress"
    assert body["total_elapsed_sec"] == 0
    assert body["completed_at"] is None
    assert body["id"]


@pytest.mark.asyncio
async def test_free_run_session_has_no_week_day(auth_client):
    resp = await auth_client.post("/cardio/sessions", json={"program_id": "free_run"})
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["week_number"] is None
    assert body["day_number"] is None


@pytest.mark.asyncio
async def test_update_progress_and_complete_stamps_completed_at(auth_client):
    created = (
        await auth_client.post(
            "/cardio/sessions",
            json={"program_id": "c25k", "week_number": 1, "day_number": 1},
        )
    ).json()
    sid = created["id"]

    # Progress update mid-run.
    resp = await auth_client.patch(
        f"/cardio/sessions/{sid}", json={"total_elapsed_sec": 120}
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["total_elapsed_sec"] == 120
    assert resp.json()["completed_at"] is None

    # Completion stamps completed_at.
    resp = await auth_client.patch(
        f"/cardio/sessions/{sid}",
        json={"status": "completed", "total_elapsed_sec": 1860},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "completed"
    assert body["total_elapsed_sec"] == 1860
    assert body["completed_at"] is not None


@pytest.mark.asyncio
async def test_list_filters_by_program(auth_client):
    await auth_client.post(
        "/cardio/sessions",
        json={"program_id": "c25k", "week_number": 1, "day_number": 1},
    )
    await auth_client.post("/cardio/sessions", json={"program_id": "free_run"})

    all_resp = await auth_client.get("/cardio/sessions")
    assert all_resp.status_code == 200
    assert len(all_resp.json()) >= 2

    c25k_resp = await auth_client.get("/cardio/sessions", params={"program_id": "c25k"})
    assert c25k_resp.status_code == 200
    assert all(s["program_id"] == "c25k" for s in c25k_resp.json())
    assert len(c25k_resp.json()) >= 1


@pytest.mark.asyncio
async def test_invalid_status_rejected(auth_client):
    created = (
        await auth_client.post("/cardio/sessions", json={"program_id": "free_run"})
    ).json()
    resp = await auth_client.patch(
        f"/cardio/sessions/{created['id']}", json={"status": "bogus"}
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_negative_elapsed_rejected(auth_client):
    created = (
        await auth_client.post("/cardio/sessions", json={"program_id": "free_run"})
    ).json()
    resp = await auth_client.patch(
        f"/cardio/sessions/{created['id']}", json={"total_elapsed_sec": -5}
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_cardio_session_requires_auth(client):
    resp = await client.get("/cardio/sessions")
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_cannot_update_another_users_session(client):
    # User A starts a session.
    import uuid

    a = await client.post(
        "/auth/register",
        json={
            "name": "A",
            "email": f"a_{uuid.uuid4().hex[:8]}@spotter.com",
            "password": "Testpass123!",
        },
    )
    a_token = a.json()["access_token"]
    created = await client.post(
        "/cardio/sessions",
        json={"program_id": "free_run"},
        headers={"Authorization": f"Bearer {a_token}"},
    )
    sid = created.json()["id"]

    # User B can't touch it.
    b = await client.post(
        "/auth/register",
        json={
            "name": "B",
            "email": f"b_{uuid.uuid4().hex[:8]}@spotter.com",
            "password": "Testpass123!",
        },
    )
    b_token = b.json()["access_token"]
    resp = await client.patch(
        f"/cardio/sessions/{sid}",
        json={"status": "completed"},
        headers={"Authorization": f"Bearer {b_token}"},
    )
    assert resp.status_code == 404
