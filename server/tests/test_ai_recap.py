"""GET /ai/recap/weekly — server-computed numbers + best-effort LM narrative."""

import datetime
from unittest.mock import AsyncMock, MagicMock, patch

import httpx

TODAY = datetime.date.today()
WEEK_START = TODAY - datetime.timedelta(days=TODAY.weekday())


def _mock_lm_response(content: str):
    mock_resp = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    mock_resp.raise_for_status = MagicMock()
    return mock_resp


async def _seed_week(auth_client, exercise):
    """One completed strength session this week: 2 working sets 5x100 (=1000 lb
    volume), one completed warm-up that must NOT count, 30 min duration. Plus a
    prior-week 95 lb best so this week's 100 reads as a PR."""
    prior = TODAY - datetime.timedelta(days=TODAY.weekday() + 3)
    sess_prior = await auth_client.post("/sessions", json={"date": str(prior)})
    await auth_client.post(
        f"/sessions/{sess_prior.json()['id']}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 1,
            "reps": 5,
            "weight": 95.0,
            "completed": True,
        },
    )
    await auth_client.patch(
        f"/sessions/{sess_prior.json()['id']}", json={"status": "completed"}
    )

    sess = await auth_client.post("/sessions", json={"date": str(TODAY)})
    session_id = sess.json()["id"]
    for n in (1, 2):
        await auth_client.post(
            f"/sessions/{session_id}/sets",
            json={
                "exercise_id": str(exercise.id),
                "set_number": n,
                "reps": 5,
                "weight": 100.0,
                "completed": True,
            },
        )
    await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={
            "exercise_id": str(exercise.id),
            "set_number": 3,
            "reps": 5,
            "weight": 100.0,
            "completed": True,
            "set_type": "warmup",
        },
    )
    await auth_client.patch(
        f"/sessions/{session_id}", json={"status": "completed", "duration_seconds": 1800}
    )


def _assert_stats(stats: dict):
    # The prior-week session also counts toward nothing here: volume/minutes are
    # windowed Mon→today; only this week's session contributes.
    assert stats["strength_sessions"] == 1
    assert stats["cardio_sessions"] == 0
    assert stats["total_volume_lb"] == 1000.0  # 2 working sets x 5 x 100; warm-up excluded
    assert stats["active_minutes"] == 30
    assert stats["prs"] == 1  # 100 beats the prior-week 95
    assert stats["bodyweight_delta_lb"] is None  # no metrics logged


async def test_recap_numbers_with_lm_down_narrative_null(auth_client, exercise):
    await _seed_week(auth_client, exercise)
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(
            side_effect=httpx.ConnectError("connection refused")
        )
        resp = await auth_client.get("/ai/recap/weekly")

    # The endpoint still 200s: numbers are server-side, the narrative degrades.
    assert resp.status_code == 200
    data = resp.json()
    assert data["week_start"] == str(WEEK_START)
    assert data["narrative"] is None
    _assert_stats(data["stats"])


async def test_recap_numbers_with_lm_up_narrative_present(auth_client, exercise):
    await _seed_week(auth_client, exercise)
    mock_resp = _mock_lm_response("Strong week — one session, a PR, and 1000 lb moved.")
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_post = AsyncMock(return_value=mock_resp)
        mock_cls.return_value.__aenter__.return_value.post = mock_post
        resp = await auth_client.get("/ai/recap/weekly")

    assert resp.status_code == 200
    data = resp.json()
    assert data["narrative"] == "Strong week — one session, a PR, and 1000 lb moved."
    _assert_stats(data["stats"])
    # The model narrates numbers it was handed, it never computes them.
    sent = mock_post.call_args.kwargs["json"]["messages"]
    stats_msg = next(m["content"] for m in sent if m["role"] == "user")
    assert "1000 lb" in stats_msg
    assert "PRs this week: 1" in stats_msg


async def test_recap_bodyweight_delta_needs_two_points(auth_client, exercise):
    await auth_client.post(
        "/metrics/weight", json={"date": str(TODAY), "weight": 180.0}
    )
    with patch("app.services.ai.client.httpx.AsyncClient") as mock_cls:
        mock_cls.return_value.__aenter__.return_value.post = AsyncMock(
            side_effect=httpx.ConnectError("nope")
        )
        resp = await auth_client.get("/ai/recap/weekly")
    assert resp.json()["stats"]["bodyweight_delta_lb"] is None


async def test_recap_requires_auth(client):
    assert (await client.get("/ai/recap/weekly")).status_code == 401
