"""AI program periodization authoring: weeks/deload_week extraction + clamping,
and the AcceptProgramRequest metadata/activate flow."""

import json
from unittest.mock import AsyncMock, MagicMock, patch


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


def _program_json(exercise_name: str, **top_level) -> str:
    return json.dumps(
        {
            "name": "Block PPL",
            "source": "ai",
            "days": [
                {
                    "label": "Push",
                    "exercises": [
                        {
                            "exercise_id": exercise_name,
                            "target_sets": 4,
                            "target_reps": 6,
                            "target_weight": 135.0,
                            "is_bodyweight": False,
                            "order": 0,
                        }
                    ],
                },
            ],
            **top_level,
        }
    )


async def _chat(auth_client, content: str):
    mock_resp = _mock_lm_response(content)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(return_value=mock_resp)
        return await auth_client.post(
            "/ai/chat",
            json={"messages": [{"role": "user", "content": "give me a 6 week block"}]},
        )


# ── Extraction ─────────────────────────────────────────────────────────────


async def test_program_extraction_carries_weeks_and_deload(auth_client, exercise):
    resp = await _chat(
        auth_client, f"```json\n{_program_json(exercise.name, weeks=6, deload_week=6)}\n```"
    )
    assert resp.status_code == 200
    prog = resp.json()["suggested_program"]
    assert prog is not None
    assert prog["weeks"] == 6
    assert prog["deload_week"] == 6


async def test_program_extraction_defaults_to_no_periodization(auth_client, exercise):
    resp = await _chat(auth_client, f"```json\n{_program_json(exercise.name)}\n```")
    prog = resp.json()["suggested_program"]
    assert prog is not None
    assert prog["weeks"] is None
    assert prog["deload_week"] is None


async def test_program_extraction_clamps_absurd_weeks(auth_client, exercise):
    # weeks clamped into PROGRAM_WEEKS_BOUNDS; a deload_week beyond the clamped
    # length is dropped rather than kept out of range.
    resp = await _chat(
        auth_client,
        f"```json\n{_program_json(exercise.name, weeks=200, deload_week=60)}\n```",
    )
    prog = resp.json()["suggested_program"]
    assert prog["weeks"] == 52
    assert prog["deload_week"] is None


async def test_program_extraction_drops_deload_without_weeks(auth_client, exercise):
    resp = await _chat(
        auth_client, f"```json\n{_program_json(exercise.name, deload_week=4)}\n```"
    )
    prog = resp.json()["suggested_program"]
    assert prog["weeks"] is None
    assert prog["deload_week"] is None


async def test_program_extraction_drops_zero_deload_week(auth_client, exercise):
    resp = await _chat(
        auth_client,
        f"```json\n{_program_json(exercise.name, weeks=4, deload_week=0)}\n```",
    )
    prog = resp.json()["suggested_program"]
    assert prog["weeks"] == 4
    assert prog["deload_week"] is None


# ── Accept ─────────────────────────────────────────────────────────────────


def _accept_body(exercise_id: str, name: str = "Block PPL", **extra):
    return {
        "name": name,
        "days": [
            {
                "label": "Push",
                "order": 0,
                "exercises": [
                    {
                        "exercise_id": exercise_id,
                        "target_sets": 4,
                        "target_reps": 6,
                        "target_weight": 135.0,
                        "is_bodyweight": False,
                        "order": 0,
                    }
                ],
            },
        ],
        **extra,
    }


async def test_accept_program_persists_periodization_metadata(auth_client, exercise):
    resp = await auth_client.post(
        "/ai/programs/accept",
        json=_accept_body(
            str(exercise.id),
            weeks=6,
            deload_week=6,
            description="Six-week block with a final deload.",
        ),
    )
    assert resp.status_code == 201, resp.text
    data = resp.json()
    assert data["weeks"] == 6
    assert data["deload_week"] == 6
    assert data["description"] == "Six-week block with a final deload."
    assert data["source"] == "ai"  # the AcceptProgramRequest default
    assert data["is_active"] is True
    assert data["started_on"] is not None  # activation stamped the anchor
    assert data["current_week"] == 1


async def test_accept_program_rejects_deload_outside_weeks(auth_client, exercise):
    resp = await auth_client.post(
        "/ai/programs/accept",
        json=_accept_body(str(exercise.id), weeks=4, deload_week=5),
    )
    assert resp.status_code == 422


async def test_accept_with_activate_false_keeps_current_active(auth_client, exercise):
    first = await auth_client.post(
        "/ai/programs/accept", json=_accept_body(str(exercise.id), name="Active One")
    )
    assert first.status_code == 201
    active_id = first.json()["id"]

    second = await auth_client.post(
        "/ai/programs/accept",
        json=_accept_body(str(exercise.id), name="Saved For Later", activate=False),
    )
    assert second.status_code == 201
    saved = second.json()
    assert saved["is_active"] is False
    assert saved["started_on"] is None  # never activated → no anchor

    programs = (await auth_client.get("/programs")).json()
    active = [p for p in programs if p["is_active"]]
    assert [p["id"] for p in active] == [active_id]
