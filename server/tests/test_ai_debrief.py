"""POST /ai/sessions/{id}/debrief — post-workout AI recap (LM mocked)."""

import datetime
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import httpx


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


async def _completed_session(auth_client, exercise, weight=135.0) -> str:
    sess = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = sess.json()["id"]
    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 1,
            "reps": 8,
            "weight": weight,
            "completed": True,
        },
    )
    assert resp.status_code == 201
    await auth_client.patch(
        f"/sessions/{session_id}", json={"status": "completed", "duration_seconds": 2400}
    )
    return session_id


async def test_debrief_happy_path(auth_client, exercise):
    session_id = await _completed_session(auth_client, exercise)
    mock_resp = _mock_lm_response("Great session — solid pressing today. Next time add 5 lb.")

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_post = AsyncMock(return_value=mock_resp)
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        resp = await auth_client.post(f"/ai/sessions/{session_id}/debrief")

    assert resp.status_code == 200
    assert "Great session" in resp.json()["debrief"]
    # The trusted context reached the model: the exercise's completed working set.
    sent = mock_post.call_args.kwargs["json"]["messages"]
    context = next(m["content"] for m in sent if m["role"] == "user")
    assert exercise.name in context
    assert "8x135" in context


async def test_debrief_lm_down_returns_503(auth_client, exercise):
    session_id = await _completed_session(auth_client, exercise)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(
            side_effect=httpx.ConnectError("connection refused")
        )
        resp = await auth_client.post(f"/ai/sessions/{session_id}/debrief")
    assert resp.status_code == 503


async def test_debrief_in_progress_session_returns_409(auth_client):
    sess = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    resp = await auth_client.post(f"/ai/sessions/{sess.json()['id']}/debrief")
    assert resp.status_code == 409


async def test_debrief_unknown_session_returns_404(auth_client):
    resp = await auth_client.post(f"/ai/sessions/{uuid.uuid4()}/debrief")
    assert resp.status_code == 404


async def test_debrief_cross_user_returns_404(client, exercise):
    async def register_and_get_token(email):
        r = await client.post(
            "/auth/register",
            json={"name": "User", "email": email, "password": "pass1234"},
        )
        return r.json()["access_token"]

    uid1, uid2 = uuid.uuid4().hex[:8], uuid.uuid4().hex[:8]
    token1 = await register_and_get_token(f"db1_{uid1}@test.com")
    token2 = await register_and_get_token(f"db2_{uid2}@test.com")

    client.headers["Authorization"] = f"Bearer {token1}"
    session_id = await _completed_session(client, exercise)

    client.headers["Authorization"] = f"Bearer {token2}"
    resp = await client.post(f"/ai/sessions/{session_id}/debrief")
    assert resp.status_code == 404


async def test_debrief_requires_auth(client):
    resp = await client.post(f"/ai/sessions/{uuid.uuid4()}/debrief")
    assert resp.status_code == 401
