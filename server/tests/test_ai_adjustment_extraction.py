"""Extraction guardrails for AI live-workout adjustments.

The model's adjustment JSON is untrusted: it must only be honored with a live
session in context, targets must resolve to catalog rows actually in the
session, values are clamped, and the action count is capped.
"""

import datetime
import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest_asyncio

from app.database import AsyncSessionLocal
from app.models.exercise import Exercise


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


@pytest_asyncio.fixture
async def second_exercise():
    async with AsyncSessionLocal() as session:
        ex = Exercise(
            name=f"Test DB Press {uuid.uuid4().hex[:6]}",
            muscle_group="chest",
            equipment="dumbbell",
        )
        session.add(ex)
        await session.commit()
        await session.refresh(ex)
        return ex


async def _start_session_with_sets(auth_client, exercise) -> str:
    create = await auth_client.post(
        "/sessions", json={"date": str(datetime.date.today())}
    )
    session_id = create.json()["id"]
    for n in (1, 2):
        await auth_client.post(
            f"/sessions/{session_id}/sets",
            json={
                "exercise_id": str(exercise.id),
                "set_number": n,
                "reps": 8,
                "weight": 135.0,
            },
        )
    return session_id


async def _chat(auth_client, reply: str, session_id: str | None):
    body = {"messages": [{"role": "user", "content": "I can't do these"}]}
    if session_id is not None:
        body["current_session_id"] = session_id
    mock_post = AsyncMock(return_value=_mock_lm_response(reply))
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        return await auth_client.post("/ai/chat", json=body)


def _adjustment_reply(actions: list[dict]) -> str:
    return (
        "Let's change that up.\n```json\n"
        + json.dumps({"actions": actions})
        + "\n```"
    )


async def test_adjustment_extracted_with_live_session(
    auth_client, exercise, second_exercise
):
    session_id = await _start_session_with_sets(auth_client, exercise)
    reply = _adjustment_reply(
        [
            {
                "type": "swap",
                "exercise": exercise.name,
                "new_exercise": second_exercise.name,
                "weight": 40.0,
                "summary": "Swap to dumbbells",
            }
        ]
    )
    resp = await _chat(auth_client, reply, session_id)
    assert resp.status_code == 200
    data = resp.json()
    adj = data["suggested_adjustment"]
    assert adj is not None
    assert len(adj["actions"]) == 1
    action = adj["actions"][0]
    assert action["type"] == "swap"
    assert action["exercise_id"] == str(exercise.id)
    assert action["new_exercise_id"] == str(second_exercise.id)
    assert action["weight"] == 40.0
    # Only one suggestion type, and the JSON is stripped from the prose.
    assert data["suggested_routine"] is None
    assert data["suggested_program"] is None
    assert "```" not in data["reply"]
    assert "Let's change that up." in data["reply"]


async def test_adjustment_ignored_without_session_context(
    auth_client, exercise, second_exercise
):
    """The same JSON outside a live workout must not produce a suggestion."""
    reply = _adjustment_reply(
        [
            {
                "type": "swap",
                "exercise": exercise.name,
                "new_exercise": second_exercise.name,
                "weight": 40.0,
            }
        ]
    )
    resp = await _chat(auth_client, reply, session_id=None)
    assert resp.status_code == 200
    assert resp.json()["suggested_adjustment"] is None


async def test_malformed_adjustment_json_is_dropped(auth_client, exercise):
    session_id = await _start_session_with_sets(auth_client, exercise)
    resp = await _chat(
        auth_client, "Try this.\n```json\n{\"actions\": not json}\n```", session_id
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_adjustment"] is None
    assert "Try this." in data["reply"]


async def test_action_targeting_exercise_not_in_session_is_dropped(
    auth_client, exercise, second_exercise
):
    """swap/adjust/remove must target an exercise in the live workout."""
    session_id = await _start_session_with_sets(auth_client, exercise)
    reply = _adjustment_reply(
        [
            # second_exercise is NOT in the session — must be dropped.
            {"type": "remove", "exercise": second_exercise.name},
        ]
    )
    resp = await _chat(auth_client, reply, session_id)
    assert resp.json()["suggested_adjustment"] is None


async def test_add_action_allows_exercise_not_in_session(
    auth_client, exercise, second_exercise
):
    session_id = await _start_session_with_sets(auth_client, exercise)
    reply = _adjustment_reply(
        [
            {
                "type": "add",
                "exercise": second_exercise.name,
                "sets": 3,
                "reps": 10,
                "weight": 30.0,
            }
        ]
    )
    adj = (await _chat(auth_client, reply, session_id)).json()["suggested_adjustment"]
    assert adj is not None
    assert adj["actions"][0]["type"] == "add"
    assert adj["actions"][0]["exercise_id"] == str(second_exercise.id)


async def test_unresolvable_swap_target_is_dropped(auth_client, exercise):
    session_id = await _start_session_with_sets(auth_client, exercise)
    reply = _adjustment_reply(
        [
            {
                "type": "swap",
                "exercise": exercise.name,
                "new_exercise": "Quantum Flux Press",
                "weight": 40.0,
            }
        ]
    )
    assert (await _chat(auth_client, reply, session_id)).json()[
        "suggested_adjustment"
    ] is None


async def test_out_of_bounds_values_are_clamped(auth_client, exercise):
    session_id = await _start_session_with_sets(auth_client, exercise)
    reply = _adjustment_reply(
        [
            {"type": "adjust_weight", "exercise": exercise.name, "weight": 5000.0},
            {
                "type": "add",
                "exercise": exercise.name,
                "sets": 99,
                "reps": 500,
                "weight": 0.1,
            },
        ]
    )
    adj = (await _chat(auth_client, reply, session_id)).json()["suggested_adjustment"]
    assert adj is not None
    assert adj["actions"][0]["weight"] == 600.0
    assert adj["actions"][1]["sets"] == 10
    assert adj["actions"][1]["reps"] == 50
    assert adj["actions"][1]["weight"] == 0.5


async def test_actions_capped_at_limit(auth_client, exercise):
    session_id = await _start_session_with_sets(auth_client, exercise)
    reply = _adjustment_reply(
        [
            {"type": "add", "exercise": exercise.name, "sets": 1, "reps": 5, "weight": 45.0}
            for _ in range(10)
        ]
    )
    adj = (await _chat(auth_client, reply, session_id)).json()["suggested_adjustment"]
    assert adj is not None
    assert len(adj["actions"]) == 6


async def test_adjust_weight_without_weight_is_dropped(auth_client, exercise):
    session_id = await _start_session_with_sets(auth_client, exercise)
    reply = _adjustment_reply([{"type": "adjust_weight", "exercise": exercise.name}])
    assert (await _chat(auth_client, reply, session_id)).json()[
        "suggested_adjustment"
    ] is None


async def test_blank_summary_gets_server_fallback(auth_client, exercise):
    session_id = await _start_session_with_sets(auth_client, exercise)
    reply = _adjustment_reply(
        [{"type": "remove", "exercise": exercise.name, "summary": ""}]
    )
    adj = (await _chat(auth_client, reply, session_id)).json()["suggested_adjustment"]
    assert adj is not None
    assert exercise.name in adj["actions"][0]["summary"]
