import json
from unittest.mock import AsyncMock, MagicMock, patch


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


async def test_conversational_reply_has_no_suggested_plan(auth_client):
    """A plain text reply with no JSON block returns suggested_plan = null."""
    mock_resp = _mock_lm_response("What equipment do you have available?")
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "I want to get fit"}]},
        )
    assert resp.status_code == 200
    assert resp.json()["suggested_plan"] is None


async def test_valid_plan_json_populates_suggested_plan(auth_client, exercise):
    """A reply containing a valid JSON plan returns suggested_plan with resolved UUIDs."""
    plan_json = json.dumps(
        {
            "name": "Beginner Full Body",
            "source": "ai",
            "exercises": [
                {
                    "exercise_id": exercise.name,
                    "target_sets": 3,
                    "target_reps": 8,
                    "target_weight": 135.0,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        }
    )
    lm_response = f"Here is your plan:\n```json\n{plan_json}\n```\nProgress linearly."

    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a beginner plan"}]},
        )

    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_plan"] is not None
    assert data["suggested_plan"]["name"] == "Beginner Full Body"
    exercises = data["suggested_plan"]["exercises"]
    assert len(exercises) == 1
    assert exercises[0]["exercise_id"] == str(exercise.id)
    assert exercises[0]["target_sets"] == 3
    assert exercises[0]["target_reps"] == 8


async def test_invalid_json_in_plan_block_is_ignored(auth_client):
    """Malformed JSON in a code block leaves suggested_plan null and doesn't crash."""
    lm_response = "Here's your plan:\n```json\n{invalid json: true,\n```\n"
    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a plan"}]},
        )
    assert resp.status_code == 200
    assert resp.json()["suggested_plan"] is None


async def test_plan_with_unknown_exercise_returns_none(auth_client):
    """If all exercises in the JSON plan are unresolvable, suggested_plan is null."""
    plan_json = json.dumps(
        {
            "name": "Mystery Plan",
            "source": "ai",
            "exercises": [
                {
                    "exercise_id": "ZZZ Definitely Not An Exercise XYZ999",
                    "target_sets": 3,
                    "target_reps": 8,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        }
    )
    lm_response = f"```json\n{plan_json}\n```"
    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a plan"}]},
        )
    assert resp.status_code == 200
    assert resp.json()["suggested_plan"] is None


async def test_plan_reply_still_includes_text(auth_client, exercise):
    """When a plan is extracted, the reply field still contains the text portion."""
    plan_json = json.dumps(
        {
            "name": "Test Plan",
            "source": "ai",
            "exercises": [
                {
                    "exercise_id": exercise.name,
                    "target_sets": 3,
                    "target_reps": 10,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        }
    )
    lm_response = f"```json\n{plan_json}\n```\nProgress by adding 5 lb each session."
    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a plan"}]},
        )
    assert resp.status_code == 200
    data = resp.json()
    assert "Progress" in data["reply"]
    assert data["suggested_plan"] is not None
