import json
from unittest.mock import AsyncMock, MagicMock, patch


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


def _program_json(exercise_name: str, **overrides) -> str:
    push = {
        "exercise_id": exercise_name,
        "target_sets": overrides.get("sets", 4),
        "target_reps": overrides.get("reps", 6),
        "target_weight": overrides.get("weight", 135.0),
        "is_bodyweight": False,
        "order": 0,
    }
    return json.dumps(
        {
            "name": "Push/Pull/Legs",
            "source": "ai",
            "days": [
                {"label": "Push", "exercises": [push]},
                {"label": "Rest", "exercises": []},
            ],
        }
    )


async def _chat(auth_client, content: str):
    mock_resp = _mock_lm_response(content)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        return await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a ppl program"}]},
        )


async def test_valid_program_json_populates_suggested_program(auth_client, exercise):
    resp = await _chat(auth_client, f"Here's your split:\n```json\n{_program_json(exercise.name)}\n```\nProgress weekly.")
    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_plan"] is None
    prog = data["suggested_program"]
    assert prog is not None
    assert prog["name"] == "Push/Pull/Legs"
    assert len(prog["days"]) == 2
    push, rest = prog["days"]
    assert push["label"] == "Push"
    assert push["exercises"][0]["exercise_id"] == str(exercise.id)
    assert rest["label"] == "Rest"
    assert rest["exercises"] == []  # rest day preserved


async def test_program_with_unresolved_exercises_skips_them(auth_client, exercise):
    program = json.dumps(
        {
            "name": "Split",
            "source": "ai",
            "days": [
                {
                    "label": "Push",
                    "exercises": [
                        {"exercise_id": "ZZZ Not Real XYZ999", "target_sets": 3, "target_reps": 8, "is_bodyweight": False, "order": 0},
                        {"exercise_id": exercise.name, "target_sets": 3, "target_reps": 8, "is_bodyweight": False, "order": 1},
                    ],
                },
            ],
        }
    )
    resp = await _chat(auth_client, f"```json\n{program}\n```")
    assert resp.status_code == 200
    prog = resp.json()["suggested_program"]
    assert prog is not None
    # The unresolved exercise is dropped; the real one remains.
    assert len(prog["days"][0]["exercises"]) == 1
    assert prog["days"][0]["exercises"][0]["exercise_id"] == str(exercise.id)


async def test_program_clamps_absurd_values(auth_client, exercise):
    resp = await _chat(
        auth_client,
        f"```json\n{_program_json(exercise.name, sets=999, reps=9999, weight=100000.0)}\n```",
    )
    assert resp.status_code == 200
    ex = resp.json()["suggested_program"]["days"][0]["exercises"][0]
    assert ex["target_sets"] == 10
    assert ex["target_reps"] == 50
    assert ex["target_weight"] == 600.0


async def test_malformed_program_json_returns_none(auth_client):
    resp = await _chat(auth_client, "Sure:\n```json\n{days: [bad json}\n```")
    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_program"] is None
    assert data["suggested_plan"] is None


async def test_single_plan_still_returns_plan_not_program(auth_client, exercise):
    """Backward compat: a single-plan JSON (top-level exercises) yields suggested_plan."""
    plan_json = json.dumps(
        {
            "name": "Single Day",
            "source": "ai",
            "exercises": [
                {"exercise_id": exercise.name, "target_sets": 3, "target_reps": 8, "is_bodyweight": False, "order": 0}
            ],
        }
    )
    resp = await _chat(auth_client, f"```json\n{plan_json}\n```")
    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_program"] is None
    assert data["suggested_plan"] is not None
    assert data["suggested_plan"]["name"] == "Single Day"


async def test_program_with_only_rest_days_returns_none(auth_client):
    program = json.dumps(
        {"name": "Empty", "source": "ai", "days": [{"label": "Rest", "exercises": []}]}
    )
    resp = await _chat(auth_client, f"```json\n{program}\n```")
    assert resp.status_code == 200
    assert resp.json()["suggested_program"] is None
