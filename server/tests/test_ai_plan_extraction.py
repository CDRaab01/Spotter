import json
from unittest.mock import AsyncMock, MagicMock, patch


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


async def test_conversational_reply_has_no_suggested_routine(auth_client):
    """A plain text reply with no JSON block returns suggested_routine = null."""
    mock_resp = _mock_lm_response("What equipment do you have available?")
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "I want to get fit"}]},
        )
    assert resp.status_code == 200
    assert resp.json()["suggested_routine"] is None


async def test_valid_routine_json_populates_suggested_routine(auth_client, exercise):
    """A reply containing a valid JSON routine returns suggested_routine with resolved UUIDs."""
    routine_json = json.dumps(
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
    lm_response = f"Here is your routine:\n```json\n{routine_json}\n```\nProgress linearly."

    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a beginner routine"}]},
        )

    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_routine"] is not None
    assert data["suggested_routine"]["name"] == "Beginner Full Body"
    exercises = data["suggested_routine"]["exercises"]
    assert len(exercises) == 1
    assert exercises[0]["exercise_id"] == str(exercise.id)
    assert exercises[0]["target_sets"] == 3
    assert exercises[0]["target_reps"] == 8


async def test_invalid_json_in_routine_block_is_ignored(auth_client):
    """Malformed JSON in a code block leaves suggested_routine null and doesn't crash."""
    lm_response = "Here's your routine:\n```json\n{invalid json: true,\n```\n"
    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a routine"}]},
        )
    assert resp.status_code == 200
    assert resp.json()["suggested_routine"] is None


async def test_routine_with_unknown_exercise_returns_none(auth_client):
    """If all exercises in the JSON routine are unresolvable, suggested_routine is null."""
    routine_json = json.dumps(
        {
            "name": "Mystery Routine",
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
    lm_response = f"```json\n{routine_json}\n```"
    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a routine"}]},
        )
    assert resp.status_code == 200
    assert resp.json()["suggested_routine"] is None


async def test_absurd_ai_values_are_clamped_not_dropped(auth_client, exercise):
    """The LLM is untrusted: out-of-bounds sets/reps/weight are clamped into the
    sanity bounds rather than rejecting the whole routine."""
    routine_json = json.dumps(
        {
            "name": "Absurd Routine",
            "source": "ai",
            "exercises": [
                {
                    "exercise_id": exercise.name,
                    "target_sets": 999,
                    "target_reps": 9999,
                    "target_weight": 100000.0,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        }
    )
    lm_response = f"```json\n{routine_json}\n```\nProgress linearly."
    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a routine"}]},
        )
    assert resp.status_code == 200
    routine = resp.json()["suggested_routine"]
    assert routine is not None
    ex = routine["exercises"][0]
    assert ex["target_sets"] == 10       # SETS_BOUNDS max
    assert ex["target_reps"] == 50       # REPS_BOUNDS max
    assert ex["target_weight"] == 600.0  # WEIGHT_BOUNDS_LB max


async def test_routine_reply_strips_json_block(auth_client, exercise):
    """The routine JSON is removed from the chat text; only the prose note remains."""
    routine_json = json.dumps(
        {
            "name": "Test Routine",
            "source": "ai",
            "exercises": [
                {"exercise_id": exercise.name, "target_sets": 3, "target_reps": 10, "is_bodyweight": False, "order": 0}
            ],
        }
    )
    lm_response = f"Here you go:\n```json\n{routine_json}\n```\nAdd 5 lb each session."
    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a routine"}]},
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_routine"] is not None
    assert "```" not in data["reply"]
    assert "exercise_id" not in data["reply"]
    assert "Add 5 lb each session." in data["reply"]


async def test_routine_reply_still_includes_text(auth_client, exercise):
    """When a routine is extracted, the reply field still contains the text portion."""
    routine_json = json.dumps(
        {
            "name": "Test Routine",
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
    lm_response = f"```json\n{routine_json}\n```\nProgress by adding 5 lb each session."
    mock_resp = _mock_lm_response(lm_response)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a routine"}]},
        )
    assert resp.status_code == 200
    data = resp.json()
    assert "Progress" in data["reply"]
    assert data["suggested_routine"] is not None
