import datetime
from unittest.mock import AsyncMock, MagicMock, patch


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


async def test_current_session_id_injects_live_workout_context(auth_client, exercise):
    """When chatting from within a workout, the system prompt gains a trusted
    'active workout in progress' block derived from the DB."""
    create = await auth_client.post("/sessions", json={"date": str(datetime.date.today())})
    session_id = create.json()["id"]
    await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8, "weight": 135.0},
    )

    mock_post = AsyncMock(return_value=_mock_lm_response("Keep your core braced."))
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        resp = await auth_client.post(
            "/ai/chat",
            json={
                "messages": [{"role": "user", "content": "how's my form?"}],
                "current_session_id": session_id,
            },
        )

    assert resp.status_code == 200
    sent = mock_post.call_args.kwargs["json"]
    system_msg = sent["messages"][0]["content"]
    assert "active workout" in system_msg.lower()
    assert exercise.name in system_msg


async def test_chat_without_session_id_has_no_live_block(auth_client):
    mock_post = AsyncMock(return_value=_mock_lm_response("Sure, what do you need?"))
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "hi"}]},
        )
    assert resp.status_code == 200
    system_msg = mock_post.call_args.kwargs["json"]["messages"][0]["content"]
    assert "active workout" not in system_msg.lower()
