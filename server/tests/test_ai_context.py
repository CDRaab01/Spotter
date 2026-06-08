"""Smarter-AI coaching: trusted history context + progression suggestions."""

import datetime
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

from app.schemas.session import SetLogOut
from app.services.session_service import suggest_next_weight


def _set(completed: bool, reps: int = 8, weight: float | None = 100.0) -> SetLogOut:
    return SetLogOut(
        id=uuid.uuid4(),
        session_id=uuid.uuid4(),
        exercise_id=uuid.uuid4(),
        set_number=1,
        reps=reps,
        weight=weight,
        completed=completed,
    )


# ── suggest_next_weight (pure) ────────────────────────────────────────────────

def test_suggest_bumps_upper_body_by_2_5_when_all_completed():
    weight, reason = suggest_next_weight(100.0, [_set(True), _set(True)], "chest")
    assert weight == 102.5
    assert "2.5" in reason


def test_suggest_bumps_lower_body_by_5_when_all_completed():
    weight, reason = suggest_next_weight(100.0, [_set(True), _set(True)], "legs")
    assert weight == 105.0
    assert "5" in reason


def test_suggest_holds_when_reps_missed():
    weight, reason = suggest_next_weight(
        100.0, [_set(True), _set(False)], "legs"
    )
    assert weight == 100.0
    assert "repeat" in reason.lower()


def test_suggest_none_for_bodyweight():
    weight, reason = suggest_next_weight(None, [_set(True, weight=None)], "back")
    assert weight is None
    assert "bodyweight" in reason.lower()


def test_suggest_clamped_to_upper_bound():
    weight, reason = suggest_next_weight(599.0, [_set(True)], "legs")
    assert weight == 600.0  # clamped to WEIGHT_BOUNDS_LB max, not 604


# ── build_user_context wired into /ai/chat ────────────────────────────────────

def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


async def _make_routine_session_completed(auth_client, exercise):
    routine = await auth_client.post(
        "/routines",
        json={
            "name": "Ctx Routine",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 2,
                    "target_reps": 8,
                    "target_weight": 135.0,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        },
    )
    routine_id = routine.json()["id"]
    sess = await auth_client.post(
        "/sessions", json={"routine_id": routine_id, "date": str(datetime.date.today())}
    )
    for sl in sess.json()["set_logs"]:
        await auth_client.patch(
            f"/sessions/{sess.json()['id']}/sets/{sl['id']}", json={"completed": True}
        )


async def test_logged_history_reaches_the_llm_system_prompt(auth_client, exercise):
    await _make_routine_session_completed(auth_client, exercise)

    captured = {}

    async def fake_post(url, json=None, **kwargs):
        captured["payload"] = json
        return _mock_lm_response("Looks good. Keep progressing.")

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(side_effect=fake_post)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "how am I doing?"}]},
        )

    assert resp.status_code == 200
    system_msg = captured["payload"]["messages"][0]["content"]
    assert captured["payload"]["messages"][0]["role"] == "system"
    # The trusted, server-derived history block is present in the system prompt.
    assert "recent logged training data" in system_msg
    assert exercise.name in system_msg


async def test_exercise_catalog_reaches_the_llm_system_prompt(auth_client, exercise):
    """The seeded exercise library is injected so the model only names exercises
    that resolve — preventing full workouts from collapsing to one or two lifts."""
    captured = {}

    async def fake_post(url, json=None, **kwargs):
        captured["payload"] = json
        return _mock_lm_response("Sure, here's a plan.")

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(side_effect=fake_post)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "build me a program"}]},
        )

    assert resp.status_code == 200
    system_msg = captured["payload"]["messages"][0]["content"]
    assert "Exercise Library — Allowed Exercises" in system_msg
    assert exercise.name in system_msg


async def test_new_user_chat_has_no_history_block(auth_client):
    captured = {}

    async def fake_post(url, json=None, **kwargs):
        captured["payload"] = json
        return _mock_lm_response("What equipment do you have?")

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(side_effect=fake_post)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "I want to get fit"}]},
        )

    assert resp.status_code == 200
    system_msg = captured["payload"]["messages"][0]["content"]
    assert "recent logged training data" not in system_msg


async def test_prior_bests_includes_suggested_weight(auth_client, exercise):
    # First session: complete all sets at 135 lb
    await _make_routine_session_completed(auth_client, exercise)

    # Second session from the same routine
    routine = (await auth_client.get("/routines")).json()[0]
    s2 = await auth_client.post(
        "/sessions", json={"routine_id": routine["id"], "date": str(datetime.date.today())}
    )
    resp = await auth_client.get(f"/sessions/{s2.json()['id']}/prior-bests")
    assert resp.status_code == 200
    best = resp.json()[0]
    assert best["suggested_weight"] is not None
    assert best["suggested_weight"] > best["weight"]
    assert best["suggested_reason"]
