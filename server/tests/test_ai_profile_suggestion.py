"""AI-proposed training-profile updates ("I bought a squat rack").

The coach may PROPOSE persisting a durable fact about the athlete's setup; it
still has no write path. These tests pin the trust model (nothing is persisted
by /ai/chat), the extraction rules (clamp, drop no-ops, drop malformed), the
invariant change (a profile update is independent of the single workout
suggestion), and the guardrail prompt content.

Applying an accepted proposal deliberately re-uses PATCH /users/me/profile —
there is no apply endpoint — so the last test drives that end to end.
"""

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.schemas.user import EQUIPMENT_MAX_LEN


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


def _profile_block(**fields) -> str:
    return f"```json\n{json.dumps({'profile_update': fields})}\n```"


async def _chat(auth_client, content: str, message: str = "I bought a squat rack"):
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(
            return_value=_mock_lm_response(content)
        )
        return await auth_client.post(
            "/ai/chat", json={"messages": [{"role": "user", "content": message}]}
        )


# ── Happy path: proposed, never persisted ─────────────────────────────────────


async def test_new_equipment_is_proposed_and_nothing_is_persisted(auth_client):
    """The reported ask: "I bought a squat rack" surfaces a confirm card carrying
    the FULL new equipment list — and the stored profile is untouched until the
    user applies it."""
    await auth_client.patch(
        "/users/me/profile", json={"equipment": "dumbbells up to 50lb, pull-up bar"}
    )

    reply = "Nice — want me to add that to your saved equipment?\n" + _profile_block(
        equipment="dumbbells up to 50lb, pull-up bar, squat rack",
        summary="Add a squat rack to your equipment",
    )
    resp = await _chat(auth_client, reply)

    assert resp.status_code == 200, resp.text
    update = resp.json()["suggested_profile_update"]
    assert update is not None
    assert update["equipment"] == "dumbbells up to 50lb, pull-up bar, squat rack"
    assert update["summary"] == "Add a squat rack to your equipment"
    # Unchanged fields ride along as null ("leave as is"), not as guesses.
    assert update["experience"] is None
    assert update["goal"] is None
    assert update["age_group"] is None
    assert update["limitations"] is None

    # The trust model: chatting proposes, it does not write.
    stored = await auth_client.get("/users/me/profile")
    assert stored.json()["equipment"] == "dumbbells up to 50lb, pull-up bar"


async def test_json_block_is_stripped_from_the_chat_bubble(auth_client):
    prose = "Nice, that opens up barbell squats."
    resp = await _chat(
        auth_client,
        f"{prose}\n" + _profile_block(equipment="squat rack", summary="Add a squat rack"),
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_profile_update"] is not None
    assert "```" not in data["reply"]
    assert "profile_update" not in data["reply"]
    assert prose in data["reply"]


async def test_profile_only_reply_gets_a_fallback_line(auth_client):
    """A reply that is nothing but the block still needs something in the bubble."""
    resp = await _chat(
        auth_client, _profile_block(goal="strength", summary="Change your goal to strength")
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_profile_update"] is not None
    assert data["reply"].strip() != ""
    assert "```" not in data["reply"]


async def test_multiple_fields_can_be_proposed_at_once(auth_client):
    resp = await _chat(
        auth_client,
        _profile_block(
            goal="strength",
            limitations="left shoulder impingement",
            summary="Update your goal and note your shoulder",
        ),
    )
    assert resp.status_code == 200
    update = resp.json()["suggested_profile_update"]
    assert update["goal"] == "strength"
    assert update["limitations"] == "left shoulder impingement"
    assert update["equipment"] is None


# ── No-op suppression: only propose a genuine change ──────────────────────────


async def test_value_already_stored_produces_no_card(auth_client):
    """A proposal identical to what is saved is not a proposal — showing a card
    that changes nothing trains the user to dismiss them."""
    await auth_client.patch("/users/me/profile", json={"equipment": "full gym"})
    resp = await _chat(
        auth_client, _profile_block(equipment="full gym", summary="Set equipment to full gym")
    )
    assert resp.status_code == 200
    assert resp.json()["suggested_profile_update"] is None


async def test_no_op_comparison_ignores_case_and_whitespace(auth_client):
    await auth_client.patch("/users/me/profile", json={"equipment": "Full Gym"})
    resp = await _chat(
        auth_client, _profile_block(equipment="  full gym  ", summary="Keep full gym")
    )
    assert resp.status_code == 200
    assert resp.json()["suggested_profile_update"] is None


async def test_no_op_field_is_dropped_but_a_real_change_survives(auth_client):
    """Per-field diffing, not all-or-nothing: the unchanged field drops out and
    the genuinely new one still reaches the card."""
    await auth_client.patch(
        "/users/me/profile", json={"equipment": "full gym", "goal": "build muscle"}
    )
    resp = await _chat(
        auth_client,
        _profile_block(
            equipment="full gym",
            goal="strength",
            summary="Switch your goal to strength",
        ),
    )
    assert resp.status_code == 200
    update = resp.json()["suggested_profile_update"]
    assert update is not None
    assert update["goal"] == "strength"
    assert update["equipment"] is None  # already stored — not re-proposed


# ── Untrusted input: clamp, don't drop ────────────────────────────────────────


async def test_over_long_value_is_clamped_not_dropped(auth_client):
    """Same posture as the plan/adjustment extractors: cap the absurd value
    rather than losing the whole suggestion. The clamp also keeps the eventual
    PATCH from being rejected with a 422 for length."""
    resp = await _chat(
        auth_client,
        _profile_block(equipment="x" * (EQUIPMENT_MAX_LEN + 500), summary="Update equipment"),
    )
    assert resp.status_code == 200
    update = resp.json()["suggested_profile_update"]
    assert update is not None
    assert len(update["equipment"]) == EQUIPMENT_MAX_LEN

    # ...and the clamped value is short enough for the real apply path.
    applied = await auth_client.patch(
        "/users/me/profile", json={"equipment": update["equipment"]}
    )
    assert applied.status_code == 200, applied.text


async def test_values_are_stripped(auth_client):
    resp = await _chat(
        auth_client, _profile_block(goal="  strength  ", summary="  Set goal  ")
    )
    assert resp.status_code == 200
    update = resp.json()["suggested_profile_update"]
    assert update["goal"] == "strength"
    assert update["summary"] == "Set goal"


async def test_unknown_keys_are_ignored(auth_client):
    resp = await _chat(
        auth_client,
        _profile_block(
            goal="strength",
            is_admin=True,
            password="hunter2",
            summary="Set goal to strength",
        ),
    )
    assert resp.status_code == 200
    update = resp.json()["suggested_profile_update"]
    assert update["goal"] == "strength"
    assert "is_admin" not in update
    assert "password" not in update


@pytest.mark.parametrize(
    "reply",
    [
        "Nice, that opens up barbell squats.",  # no block at all
        "```json\n{profile_update: {bad json}\n```",  # malformed JSON
        '```json\n{"profile_update": {"summary": "Add a rack"}}\n```',  # no fields
        '```json\n{"profile_update": {"equipment": "", "summary": "Add a rack"}}\n```',
        '```json\n{"profile_update": {"equipment": "   ", "summary": "Add a rack"}}\n```',
        '```json\n{"profile_update": {"equipment": "squat rack"}}\n```',  # no summary
        '```json\n{"profile_update": {"equipment": "squat rack", "summary": "  "}}\n```',
        '```json\n{"profile_update": {"equipment": "squat rack", "summary": 42}}\n```',
        '```json\n{"profile_update": "squat rack"}\n```',  # not an object
        '```json\n{"equipment": "squat rack", "summary": "x"}\n```',  # unkeyed
    ],
)
async def test_unusable_blocks_produce_no_card(auth_client, reply):
    resp = await _chat(auth_client, reply)
    assert resp.status_code == 200, resp.text
    assert resp.json()["suggested_profile_update"] is None


# ── The invariant change: independent of the workout suggestion ───────────────


async def test_profile_update_coexists_with_a_program_suggestion(auth_client, exercise):
    """The documented invariant used to be "exactly one suggestion per reply". It
    is now "exactly one WORKOUT suggestion, plus an optional profile update":
    a durable fact and the program built from it can land in the same turn,
    instead of making the user re-ask for the program after applying the change.
    """
    program = json.dumps(
        {
            "name": "Barbell Strength",
            "source": "ai",
            "days": [
                {
                    "label": "Full Body",
                    "exercises": [
                        {
                            "exercise_id": exercise.name,
                            "target_sets": 3,
                            "target_reps": 5,
                            "target_weight": 135.0,
                            "is_bodyweight": False,
                            "order": 0,
                        }
                    ],
                },
                {"label": "Rest", "exercises": []},
            ],
        }
    )
    reply = (
        "With a rack you can squat properly — here's the split.\n"
        f"```json\n{program}\n```\n"
        + _profile_block(
            equipment="dumbbells, squat rack", summary="Add a squat rack to your equipment"
        )
    )
    resp = await _chat(auth_client, reply)

    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["suggested_program"] is not None
    assert data["suggested_program"]["name"] == "Barbell Strength"
    assert data["suggested_profile_update"] is not None
    assert data["suggested_profile_update"]["equipment"] == "dumbbells, squat rack"
    # Still exactly one workout suggestion.
    assert data["suggested_routine"] is None
    assert data["suggested_adjustment"] is None


async def test_block_order_does_not_matter(auth_client, exercise):
    """Regression: block selection is by SHAPE, not position.

    The workout extractors used to validate only the FIRST JSON block, so a reply
    that put the profile-update block ahead of the program silently lost the
    program — the user's suggestion just vanished. The prompt asks the model to
    emit the workout block first, but prompt ordering is guidance, not a
    guarantee, and local models reorder freely. This pins the reversed order.
    """
    program = json.dumps(
        {
            "name": "Rack Strength",
            "source": "ai",
            "days": [
                {
                    "label": "Full Body",
                    "exercises": [
                        {
                            "exercise_id": exercise.name,
                            "target_sets": 3,
                            "target_reps": 5,
                            "target_weight": 135.0,
                            "is_bodyweight": False,
                            "order": 0,
                        }
                    ],
                },
                {"label": "Rest", "exercises": []},
            ],
        }
    )
    # Profile block FIRST — the order the prompt does not ask for.
    reply = (
        _profile_block(
            equipment="dumbbells, squat rack", summary="Add a squat rack to your equipment"
        )
        + f"\n```json\n{program}\n```\n"
    )
    resp = await _chat(auth_client, reply)

    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert data["suggested_program"] is not None, "program dropped when it wasn't the first block"
    assert data["suggested_program"]["name"] == "Rack Strength"
    assert data["suggested_profile_update"]["equipment"] == "dumbbells, squat rack"


async def test_a_plain_program_reply_carries_no_profile_update(auth_client, exercise):
    """The new field must not fire on ordinary program replies."""
    program = json.dumps(
        {
            "name": "Split",
            "source": "ai",
            "days": [
                {
                    "label": "Push",
                    "exercises": [
                        {
                            "exercise_id": exercise.name,
                            "target_sets": 3,
                            "target_reps": 8,
                            "is_bodyweight": False,
                            "order": 0,
                        }
                    ],
                }
            ],
        }
    )
    resp = await _chat(auth_client, f"```json\n{program}\n```", message="give me a program")
    assert resp.status_code == 200
    data = resp.json()
    assert data["suggested_program"] is not None
    assert data["suggested_profile_update"] is None


# ── Prompt guardrail (isolated to prompts.py) ─────────────────────────────────


def test_prompt_teaches_the_profile_update_block():
    from app.services.ai.prompts import SYSTEM_PROMPT

    assert "Proposing a Training Profile Update" in SYSTEM_PROMPT
    assert '"profile_update"' in SYSTEM_PROMPT
    # Equipment overwrites, so a partial list would erase the rest.
    assert "COMPLETE new list" in SYSTEM_PROMPT
    # It is a proposal, never a save.
    assert "NOTHING is saved unless the athlete confirms" in SYSTEM_PROMPT


def test_prompt_forbids_proposing_updates_for_temporary_situations():
    """The main failure mode: a one-week circumstance written into the permanent
    profile is wrong for every future conversation."""
    from app.services.ai.prompts import SYSTEM_PROMPT

    assert "NEVER propose an update for a temporary situation" in SYSTEM_PROMPT
    for example in ("hotel gym this week", "travelling", "gym is closed today", "borrowing"):
        assert example in SYSTEM_PROMPT, f"missing temporary-situation example: {example}"
    # Durable facts are the only trigger, and never inferred.
    assert "durable, athlete-stated facts" in SYSTEM_PROMPT
    assert "NEVER invent or infer a profile change" in SYSTEM_PROMPT


def test_profile_update_guidance_reaches_the_model_on_every_request():
    from app.services.ai.prompts import build_messages

    system = build_messages([], "I bought a squat rack")[0]["content"]
    assert "Proposing a Training Profile Update" in system
    assert "NEVER propose an update for a temporary situation" in system


# ── Applying it: the existing PATCH, no new endpoint ──────────────────────────


async def test_applying_the_proposal_via_patch_produces_the_expected_state(auth_client):
    """End-to-end of the intended flow. The card's payload is fed straight to the
    existing PATCH /users/me/profile: its partial semantics (omitted = unchanged)
    are exactly the proposal's shape, which is why no apply endpoint exists."""
    await auth_client.patch(
        "/users/me/profile",
        json={"equipment": "dumbbells up to 50lb, pull-up bar", "goal": "build muscle"},
    )

    resp = await _chat(
        auth_client,
        _profile_block(
            equipment="dumbbells up to 50lb, pull-up bar, squat rack",
            summary="Add a squat rack to your equipment",
        ),
    )
    update = resp.json()["suggested_profile_update"]

    # The client applies only the non-null fields — nulls mean "unchanged".
    payload = {k: v for k, v in update.items() if k != "summary" and v is not None}
    applied = await auth_client.patch("/users/me/profile", json=payload)
    assert applied.status_code == 200, applied.text

    stored = (await auth_client.get("/users/me/profile")).json()
    assert stored["equipment"] == "dumbbells up to 50lb, pull-up bar, squat rack"
    assert stored["goal"] == "build muscle"  # untouched field survives
    assert stored["profile_updated_at"] is not None

    # ...and once applied, re-proposing the same value is a no-op (no second card).
    again = await _chat(
        auth_client,
        _profile_block(
            equipment="dumbbells up to 50lb, pull-up bar, squat rack",
            summary="Add a squat rack to your equipment",
        ),
    )
    assert again.json()["suggested_profile_update"] is None
