"""Smarter-AI coaching: trusted history context + progression suggestions."""

import datetime
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

from app.schemas.session import SetLogOut
from app.services.ai.context_service import _athlete_status_line
from app.services.session_service import suggest_next_weight


# ── _athlete_status_line (pure) ───────────────────────────────────────────────


def test_status_new_when_no_program_or_workouts():
    line = _athlete_status_line(0, has_program=False, recent_active=False)
    assert "Athlete status: new" in line


def test_status_early_with_program_no_workouts():
    line = _athlete_status_line(0, has_program=True, recent_active=False)
    assert "Athlete status: early" in line


def test_status_established_but_returning_when_inactive():
    line = _athlete_status_line(10, has_program=True, recent_active=False)
    assert "established but returning" in line
    assert "Welcome them back" in line


def test_status_established_when_recently_active():
    line = _athlete_status_line(10, has_program=True, recent_active=True)
    assert "Athlete status: established —" in line
    assert "returning" not in line


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


async def _make_active_program(auth_client, exercise):
    """Create + activate a program (one training day + a rest day) via the AI accept
    endpoint, which auto-activates it."""
    resp = await auth_client.post(
        "/ai/programs/accept",
        json={
            "name": "Test PPL",
            "days": [
                {
                    "label": "Push",
                    "order": 0,
                    "exercises": [
                        {
                            "exercise_id": str(exercise.id),
                            "target_sets": 3,
                            "target_reps": 8,
                            "target_weight": 135.0,
                            "is_bodyweight": False,
                            "order": 0,
                        }
                    ],
                },
                {"label": "Rest", "order": 1, "exercises": []},
            ],
        },
    )
    assert resp.status_code == 201


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
    # No logged-history block, but the model is now told this is a brand-new athlete.
    assert "recent logged training data" not in system_msg
    assert "Athlete status: new" in system_msg


async def test_greeting_with_active_program_is_context_aware_and_makes_no_plan(
    auth_client, exercise
):
    """A bare greeting from a user who already has a program must not produce a plan;
    the system prompt should carry their active-program + status context."""
    await _make_active_program(auth_client, exercise)
    captured = {}

    async def fake_post(url, json=None, **kwargs):
        captured["payload"] = json
        return _mock_lm_response("Hey! Nice work staying on it — how did the last session feel?")

    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(side_effect=fake_post)
        resp = await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "hey"}]},
        )

    assert resp.status_code == 200
    system_msg = captured["payload"]["messages"][0]["content"]
    assert 'Active program: "Test PPL"' in system_msg
    assert "do not offer to create a new one" in system_msg
    # Active program but nothing completed yet → early stage.
    assert "Athlete status: early" in system_msg
    body = resp.json()
    assert body["suggested_routine"] is None
    assert body["suggested_program"] is None


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
