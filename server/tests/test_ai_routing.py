"""Dual-model routing: plan/program generation goes to the larger plan model;
conversation, tweaks, and in-workout advice stay on the fast chat model.

The LLM is mocked — these assert *which model id* the server puts in the outgoing
/chat/completions payload, given the request shape.
"""

import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.config import settings


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


async def _capture_for(auth_client, body: dict) -> dict:
    """POST to /ai/chat with the LLM mocked; return the model, temperature, and the
    httpx client timeout the server used."""
    mock_post = AsyncMock(return_value=_mock_lm_response("Sure — here's some guidance."))
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        resp = await auth_client.post("/ai/chat", json=body)
    assert resp.status_code == 200
    payload = mock_post.call_args.kwargs["json"]
    return {
        "model": payload["model"],
        "temperature": payload["temperature"],
        "timeout": mock_cls.call_args.kwargs["timeout"],
    }


async def _model_used_for(auth_client, body: dict) -> str:
    """POST to /ai/chat with the LLM mocked and return the model id the server sent."""
    return (await _capture_for(auth_client, body))["model"]


@pytest.fixture
def plan_model(monkeypatch):
    """Configure a distinct plan model so routing is observable."""
    monkeypatch.setattr(settings, "lm_studio_plan_model", "test-26b-plan-model")
    return "test-26b-plan-model"


async def test_generation_request_uses_plan_model(auth_client, plan_model):
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "Generate a 4-day workout program for me."}]},
    )
    assert model == plan_model


async def test_known_split_name_uses_plan_model(auth_client, plan_model):
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "Set me up with a push pull legs split."}]},
    )
    assert model == plan_model


async def test_explicit_intent_uses_plan_model(auth_client, plan_model):
    model = await _model_used_for(
        auth_client,
        {
            "messages": [{"role": "user", "content": "Based on my profile, generate a program."}],
            "intent": "generate",
        },
    )
    assert model == plan_model


async def test_conversational_tweak_uses_chat_model(auth_client, plan_model):
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "Can you swap squats for leg press?"}]},
    )
    assert model == settings.lm_studio_model


async def test_general_question_uses_chat_model(auth_client, plan_model):
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "How do I improve my bench press?"}]},
    )
    assert model == settings.lm_studio_model


async def test_in_workout_generation_text_stays_on_chat_model(auth_client, plan_model):
    """In-workout chat is advice-only — even generation-sounding text stays on e4b."""
    model = await _model_used_for(
        auth_client,
        {
            "messages": [{"role": "user", "content": "Build me a new program right now."}],
            "current_session_id": str(uuid.uuid4()),
        },
    )
    assert model == settings.lm_studio_model


async def test_backcompat_no_plan_model_uses_chat_model(auth_client, monkeypatch):
    """With no plan model configured, generation requests fall back to the chat model
    (identical to prior single-model behaviour)."""
    monkeypatch.setattr(settings, "lm_studio_plan_model", None)
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "Generate a 4-day workout program for me."}]},
    )
    assert model == settings.lm_studio_model


# ── Heuristic tuning ──────────────────────────────────────────────────────────


async def test_comparison_question_stays_on_chat_model(auth_client, plan_model):
    """Naming splits in a comparison/question is discussion, not generation."""
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "What's the difference between PPL and upper/lower?"}]},
    )
    assert model == settings.lm_studio_model


async def test_want_a_routine_uses_plan_model(auth_client, plan_model):
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "I want a routine for building muscle."}]},
    )
    assert model == plan_model


async def test_need_a_split_uses_plan_model(auth_client, plan_model):
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "I need a 4-day split."}]},
    )
    assert model == plan_model


async def test_build_me_a_ppl_uses_plan_model(auth_client, plan_model):
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "Build me a ppl."}]},
    )
    assert model == plan_model


async def test_casual_days_reference_stays_on_chat_model(auth_client, plan_model):
    """Bare time references ('a couple days') must not trip the plan-noun heuristic."""
    model = await _model_used_for(
        auth_client,
        {"messages": [{"role": "user", "content": "I can only train a couple days a week, is that enough?"}]},
    )
    assert model == settings.lm_studio_model


# ── Per-model inference params ────────────────────────────────────────────────


async def test_plan_turn_uses_low_temp_and_long_timeout(auth_client, plan_model):
    cap = await _capture_for(
        auth_client,
        {"messages": [{"role": "user", "content": "Generate a 4-day workout program for me."}]},
    )
    assert cap["temperature"] == 0.4
    assert cap["timeout"] == settings.lm_studio_plan_timeout


async def test_chat_turn_uses_default_temp_and_timeout(auth_client, plan_model):
    cap = await _capture_for(
        auth_client,
        {"messages": [{"role": "user", "content": "How do I improve my bench press?"}]},
    )
    assert cap["temperature"] == 0.7
    assert cap["timeout"] == settings.lm_studio_timeout
