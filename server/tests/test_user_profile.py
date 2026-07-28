"""Persistent server-side training profile (GET/PATCH /users/me/profile).

The bug this pins: the profile used to live only in the Android client's
DataStore, written only by the onboarding questionnaire that most users never
see, and reached the coach only as the untrusted client-supplied `user_context`
string — so "the AI keeps forgetting what equipment I have". It is now stored on
the user and injected into the trusted, DB-derived AI context on every call.
"""

import uuid
from unittest.mock import AsyncMock, MagicMock, patch

from app.database import AsyncSessionLocal
from app.models.user import User
from app.services.ai.context_service import build_user_context

EMPTY_PROFILE = {
    "equipment": None,
    "experience": None,
    "goal": None,
    "age_group": None,
    "limitations": None,
    "profile_updated_at": None,
}


async def _register(client) -> str:
    """Register a fresh user and return their access token."""
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={
            "name": "Profile User",
            "email": f"profile_{uid}@spotter.com",
            "password": "Testpass123!",
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


async def _user_id(auth_client) -> uuid.UUID:
    return uuid.UUID((await auth_client.get("/users/me")).json()["id"])


# ── Read/write basics ─────────────────────────────────────────────────────────


async def test_fresh_user_profile_is_empty(auth_client):
    resp = await auth_client.get("/users/me/profile")
    assert resp.status_code == 200, resp.text
    assert resp.json() == EMPTY_PROFILE


async def test_patch_sets_fields_and_get_reflects_them(auth_client):
    payload = {
        "equipment": "dumbbells up to 50lb, pull-up bar, bands",
        "experience": "intermediate",
        "goal": "build muscle",
        "age_group": "30-39",
        "limitations": "left shoulder impingement",
    }
    resp = await auth_client.patch("/users/me/profile", json=payload)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    for key, value in payload.items():
        assert body[key] == value
    assert body["profile_updated_at"] is not None

    again = await auth_client.get("/users/me/profile")
    assert again.status_code == 200
    assert again.json() == body


async def test_values_are_stripped(auth_client):
    resp = await auth_client.patch(
        "/users/me/profile", json={"equipment": "  full gym  "}
    )
    assert resp.status_code == 200
    assert resp.json()["equipment"] == "full gym"


# ── Partial-update semantics: omitted ≠ cleared ───────────────────────────────


async def test_partial_update_leaves_omitted_fields_untouched(auth_client):
    await auth_client.patch(
        "/users/me/profile",
        json={"equipment": "full gym", "goal": "strength", "experience": "beginner"},
    )
    resp = await auth_client.patch("/users/me/profile", json={"goal": "fat loss"})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["goal"] == "fat loss"
    assert body["equipment"] == "full gym"
    assert body["experience"] == "beginner"


async def test_empty_string_clears_a_field(auth_client):
    await auth_client.patch(
        "/users/me/profile", json={"equipment": "full gym", "goal": "strength"}
    )
    resp = await auth_client.patch("/users/me/profile", json={"equipment": ""})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["equipment"] is None
    # An explicit clear must not take its neighbours down with it.
    assert body["goal"] == "strength"


async def test_explicit_null_clears_a_field(auth_client):
    await auth_client.patch("/users/me/profile", json={"limitations": "bad knee"})
    resp = await auth_client.patch("/users/me/profile", json={"limitations": None})
    assert resp.status_code == 200, resp.text
    assert resp.json()["limitations"] is None


async def test_empty_patch_is_a_noop(auth_client):
    await auth_client.patch("/users/me/profile", json={"equipment": "full gym"})
    resp = await auth_client.patch("/users/me/profile", json={})
    assert resp.status_code == 200, resp.text
    assert resp.json()["equipment"] == "full gym"


# ── Bounds + auth + ownership ─────────────────────────────────────────────────


async def test_over_long_equipment_rejected(auth_client):
    resp = await auth_client.patch("/users/me/profile", json={"equipment": "x" * 256})
    assert resp.status_code == 422


async def test_over_long_experience_rejected(auth_client):
    resp = await auth_client.patch("/users/me/profile", json={"experience": "x" * 33})
    assert resp.status_code == 422


async def test_over_long_limitations_rejected(auth_client):
    resp = await auth_client.patch("/users/me/profile", json={"limitations": "x" * 2001})
    assert resp.status_code == 422


async def test_profile_requires_auth(client):
    assert (await client.get("/users/me/profile")).status_code == 401
    assert (
        await client.patch("/users/me/profile", json={"equipment": "full gym"})
    ).status_code == 401


async def test_profile_is_per_user(client):
    token_a = await _register(client)
    client.headers["Authorization"] = f"Bearer {token_a}"
    await client.patch("/users/me/profile", json={"equipment": "A's home gym"})

    token_b = await _register(client)
    client.headers["Authorization"] = f"Bearer {token_b}"
    # B cannot read A's profile...
    assert (await client.get("/users/me/profile")).json()["equipment"] is None
    # ...and B's write cannot reach A's row.
    await client.patch("/users/me/profile", json={"equipment": "B's bands"})

    client.headers["Authorization"] = f"Bearer {token_a}"
    assert (await client.get("/users/me/profile")).json()["equipment"] == "A's home gym"


# ── The trusted-context injection (the actual fix) ────────────────────────────


async def test_context_includes_profile_for_user_with_no_sessions(auth_client):
    """The regression: build_user_context used to return None whenever there was
    no logged training, so a profile on a brand-new user never reached the model."""
    await auth_client.patch(
        "/users/me/profile",
        json={"equipment": "dumbbells up to 50lb, pull-up bar", "goal": "build muscle"},
    )
    user_id = await _user_id(auth_client)

    async with AsyncSessionLocal() as session:
        context = await build_user_context(session, user_id)

    assert context is not None
    assert "Training profile" in context
    assert "Equipment available: dumbbells up to 50lb, pull-up bar" in context
    assert "Goal: build muscle" in context
    # No history for this user, so nothing beyond the profile block.
    assert "recent logged training data" not in context


async def test_context_is_none_without_profile_or_history(auth_client):
    user_id = await _user_id(auth_client)
    async with AsyncSessionLocal() as session:
        assert await build_user_context(session, user_id) is None


async def test_context_keeps_history_alongside_profile(auth_client, exercise):
    """Adding the profile block must not displace the training-history block."""
    await auth_client.patch("/users/me/profile", json={"equipment": "full gym"})
    routine = await auth_client.post(
        "/routines",
        json={
            "name": "Profile Ctx Routine",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 1,
                    "target_reps": 5,
                    "target_weight": 135.0,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        },
    )
    assert routine.status_code == 201, routine.text
    user_id = await _user_id(auth_client)

    async with AsyncSessionLocal() as session:
        context = await build_user_context(session, user_id)

    assert context is not None
    assert "Equipment available: full gym" in context
    assert "recent logged training data" in context
    # Profile leads, history follows.
    assert context.index("Training profile") < context.index("recent logged training data")


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


async def test_saved_equipment_reaches_the_llm_system_prompt(auth_client):
    """End-to-end pin for the reported bug: after saving equipment once, every
    /ai/chat call carries it in the system prompt — with no client-supplied
    user_context at all."""
    await auth_client.patch(
        "/users/me/profile",
        json={
            "equipment": "dumbbells up to 50lb, pull-up bar, bands",
            "experience": "intermediate",
            "limitations": "left shoulder impingement",
        },
    )

    captured = {}

    async def fake_post(url, json=None, **kwargs):
        captured["payload"] = json
        return _mock_lm_response("Here's a dumbbell program.")

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(
            side_effect=fake_post
        )
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "build me a program"}]},
        )

    assert resp.status_code == 200, resp.text
    system_msg = captured["payload"]["messages"][0]["content"]
    assert captured["payload"]["messages"][0]["role"] == "system"
    assert "dumbbells up to 50lb, pull-up bar, bands" in system_msg
    assert "Experience: intermediate" in system_msg
    assert "Limitations: left shoulder impingement" in system_msg
    assert "trusted, do not re-ask" in system_msg


async def test_client_supplied_context_stays_stated_preferences(auth_client):
    """The client string is still appended as *stated* preferences — the trusted
    block is the new one, and the client's is not promoted alongside it."""
    await auth_client.patch("/users/me/profile", json={"equipment": "full gym"})

    captured = {}

    async def fake_post(url, json=None, **kwargs):
        captured["payload"] = json
        return _mock_lm_response("Sure.")

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(
            side_effect=fake_post
        )
        resp = await auth_client.post(
            "/ai/chat",
            json={
                "messages": [{"role": "user", "content": "hi"}],
                "user_context": "I like Bulgarian split squats",
            },
        )

    assert resp.status_code == 200, resp.text
    system_msg = captured["payload"]["messages"][0]["content"]
    assert "Athlete-stated profile/preferences:" in system_msg
    assert "I like Bulgarian split squats" in system_msg
    assert "Equipment available: full gym" in system_msg


# ── Prompt guardrail (isolated to prompts.py) ─────────────────────────────────


def test_prompt_marks_training_profile_authoritative():
    from app.services.ai.prompts import SYSTEM_PROMPT

    assert "Training profile" in SYSTEM_PROMPT
    assert "authoritative and persisted" in SYSTEM_PROMPT
    assert "within the listed equipment" in SYSTEM_PROMPT


# ── Reset ─────────────────────────────────────────────────────────────────────


async def test_reset_clears_the_profile(auth_client):
    await auth_client.patch(
        "/users/me/profile",
        json={"equipment": "full gym", "goal": "strength", "limitations": "bad knee"},
    )
    assert (await auth_client.post("/users/reset")).status_code == 204
    assert (await auth_client.get("/users/me/profile")).json() == EMPTY_PROFILE


async def test_dead_settings_column_is_gone():
    """Migration 0016 drops `users.settings` — it was never read anywhere."""
    assert not hasattr(User, "settings")
