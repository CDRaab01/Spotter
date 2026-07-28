"""GET /exercises/{id} — catalog detail (instructions + secondary muscles, migration 0013)."""

import uuid


async def _seeded_bench_press(auth_client) -> dict:
    resp = await auth_client.get("/exercises?search=Bench Press")
    assert resp.status_code == 200
    match = next((e for e in resp.json() if e["name"] == "Bench Press"), None)
    assert match is not None, "seeded catalog missing Bench Press — did migrations run?"
    return match


async def test_detail_returns_backfilled_content_for_seeded_exercise(auth_client):
    bench = await _seeded_bench_press(auth_client)
    resp = await auth_client.get(f"/exercises/{bench['id']}")
    assert resp.status_code == 200
    data = resp.json()
    assert data["name"] == "Bench Press"
    assert data["instructions"] and len(data["instructions"]) > 50
    assert "triceps" in data["secondary_muscles"]


async def test_detail_unknown_id_returns_404(auth_client):
    resp = await auth_client.get(f"/exercises/{uuid.uuid4()}")
    assert resp.status_code == 404


async def test_detail_invalid_id_returns_404(auth_client):
    resp = await auth_client.get("/exercises/not-a-uuid")
    assert resp.status_code == 404


async def test_detail_requires_auth(client):
    resp = await client.get(f"/exercises/{uuid.uuid4()}")
    assert resp.status_code == 401


async def test_list_still_works_and_carries_detail_fields(auth_client):
    resp = await auth_client.get("/exercises")
    assert resp.status_code == 200
    data = resp.json()
    assert len(data) > 0
    assert "instructions" in data[0]
    assert "secondary_muscles" in data[0]
