"""The coach's pregnancy/postpartum posture.

These pin *prompt content*, not model behaviour — the LLM is mocked everywhere else in the
suite and we can't assert on what a local model actually says. What we can guarantee is that
the guidance reaches the model on every request and that the surrounding guardrails still
treat the topic as in-scope coaching (exercise selection + referral), never as medical advice.
"""

from unittest.mock import AsyncMock, MagicMock, patch

from app.services.ai.prompts import (
    SYSTEM_PROMPT,
    build_messages,
    validate_request,
    validate_response,
)

# Phrases a user in this situation actually types. None of them may be treated as an attack.
POSTPARTUM_USER_MESSAGES = [
    "I gave birth 8 weeks ago and want to start training again",
    "I had a c-section, what can I do?",
    "I'm postpartum and leaking a bit when I squat",
    "my doctor cleared me last week, where do I start?",
    "I feel heaviness in my pelvis after workouts",
]


def test_system_prompt_carries_pregnancy_and_postpartum_guidance():
    lowered = SYSTEM_PROMPT.lower()
    assert "postpartum" in lowered
    # The specific referral most users don't know to ask for.
    assert "pelvic floor physiotherapist" in lowered
    # Clearance is the clinician's call, never the model's.
    assert "clearance comes first" in lowered
    # The stop-and-refer symptom list.
    for symptom in ("leaking", "doming", "bulging", "bleeding"):
        assert symptom in lowered, f"missing stop-and-refer symptom: {symptom}"
    # A C-section is abdominal surgery and gets extra conservatism.
    assert "c-section" in lowered


def test_postpartum_guidance_reaches_the_model_on_every_request():
    messages = build_messages([], "I gave birth recently, can you build me a program?")
    system = messages[0]["content"].lower()
    assert messages[0]["role"] == "system"
    assert "postpartum" in system
    assert "pelvic floor physiotherapist" in system


def test_postpartum_session_sizing_overrides_the_default_session_rule():
    """The generic rule demands 5-6 exercises / 30-60 min; a newborn makes that the wrong
    prescription, so the postpartum section must explicitly override it."""
    lowered = SYSTEM_PROMPT.lower()
    assert "overrides the session-size rule" in lowered
    assert "20–30 minutes" in SYSTEM_PROMPT or "20-30 minutes" in SYSTEM_PROMPT


def test_live_adjustments_refer_postpartum_symptoms_instead_of_lightening_load():
    lowered = SYSTEM_PROMPT.lower()
    assert "never answer it with a lighter load" in lowered


def test_postpartum_messages_are_not_blocked_as_injection_or_out_of_scope():
    """Regression guard: these describe bodies and symptoms, and must reach the coach. A
    guardrail that rejected them would silently make the app useless for this user."""
    for message in POSTPARTUM_USER_MESSAGES:
        assert validate_request(message) is None, f"wrongly blocked: {message}"


def test_a_reply_naming_the_pelvic_floor_referral_survives_sanitising():
    reply = (
        "That heaviness is worth getting looked at — stop that movement for now and see your "
        "doctor or a pelvic floor physiotherapist before we add load."
    )
    assert validate_response(reply) == reply.strip()


async def test_postpartum_chat_reaches_the_llm_with_the_guidance_attached(auth_client):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {
        "choices": [{"message": {"content": "Let's start gently — glute bridges and step-ups."}}]
    }
    mock_resp.raise_for_status = MagicMock()

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_post = AsyncMock(return_value=mock_resp)
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": POSTPARTUM_USER_MESSAGES[0]}]},
        )

    assert resp.status_code == 200
    sent_system = mock_post.call_args.kwargs["json"]["messages"][0]["content"].lower()
    assert "postpartum" in sent_system
    assert "pelvic floor physiotherapist" in sent_system
