"""The coach's return-after-a-layoff posture.

Like test_ai_postpartum.py, these pin *prompt content*, not model behaviour: the layoff
guidance must reach the model on every request, the guardrails must keep the topic in scope
(it's ordinary coaching, not medical), and the returner session-sizing paragraph must stay
distinct from the postpartum one — the two use deliberately different pinned phrasings
("relaxes" vs "overrides" the session-size rule) so an edit can't silently merge them.
"""

from unittest.mock import AsyncMock, MagicMock, patch

from app.services.ai.prompts import (
    SYSTEM_PROMPT,
    build_messages,
    validate_request,
    validate_response,
)

# Phrases a returning lifter actually types. None of them may be treated as an attack.
RETURNING_USER_MESSAGES = [
    "I haven't lifted in two years, where do I start?",
    "my bench used to be 225 but I've been off for a year",
    "getting back into the gym after a long break",
    "I used to squat 315 in college, how much should I start with now?",
]


def test_system_prompt_carries_layoff_guidance():
    lowered = SYSTEM_PROMPT.lower()
    assert "layoff" in lowered
    # The physiological core of the section: strength returns before load tolerance does.
    assert "tendons" in lowered
    assert "muscle memory" in lowered
    # The #1 reason returners quit must be warned about in advance.
    assert "soreness" in lowered
    # The restart fraction (dash-tolerant, like the postpartum 20-30-minute assert).
    assert "50–60%" in SYSTEM_PROMPT or "50-60%" in SYSTEM_PROMPT


def test_layoff_guidance_reaches_the_model_on_every_request():
    messages = build_messages([], RETURNING_USER_MESSAGES[0])
    system = messages[0]["content"].lower()
    assert messages[0]["role"] == "system"
    assert "layoff" in system
    assert "tendons" in system


def test_layoff_session_sizing_relaxes_not_overrides():
    """The returner paragraph softens the 5-6 exercise / 30-60 min rule; the postpartum one
    replaces it. Their pinned phrasings must stay distinct or a future edit could alias the
    two prescriptions into one."""
    lowered = SYSTEM_PROMPT.lower()
    assert "relaxes the session-size rule" in lowered
    assert lowered.count("overrides the session-size rule") == 1


def test_returning_messages_are_not_blocked_as_injection_or_out_of_scope():
    """Regression guard: a layoff is ordinary coaching input and must reach the coach."""
    for message in RETURNING_USER_MESSAGES:
        assert validate_request(message) is None, f"wrongly blocked: {message}"


def test_a_reply_prescribing_a_restart_load_survives_sanitising():
    reply = (
        "Your bench used to be 225 — start around 115 to 135 for now and let your tendons "
        "catch up. It'll climb back faster than you expect."
    )
    assert validate_response(reply) == reply.strip()


async def test_returning_chat_reaches_the_llm_with_the_guidance_attached(auth_client):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {
        "choices": [{"message": {"content": "Welcome back — we'll restart light."}}]
    }
    mock_resp.raise_for_status = MagicMock()

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_post = AsyncMock(return_value=mock_resp)
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": RETURNING_USER_MESSAGES[0]}]},
        )

    assert resp.status_code == 200
    sent_system = mock_post.call_args.kwargs["json"]["messages"][0]["content"].lower()
    assert "layoff" in sent_system
    assert "tendons" in sent_system
