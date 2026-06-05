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


async def _model_used_for(auth_client, body: dict) -> str:
    """POST to /ai/chat with the LLM mocked and return the model id the server sent."""
    mock_post = AsyncMock(return_value=_mock_lm_response("Sure — here's some guidance."))
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        resp = await auth_client.post("/ai/chat", json=body)
    assert resp.status_code == 200
    return mock_post.call_args.kwargs["json"]["model"]


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
