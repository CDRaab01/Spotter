"""Sanity-bounds enforcement on write paths (CLAUDE.md AI-guardrail requirement)."""

import datetime

import pytest


async def test_plan_rejects_out_of_range_reps(auth_client, exercise):
    resp = await auth_client.post(
        "/plans",
        json={
            "name": "Bad Plan",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 3,
                    "target_reps": 9999,  # > REPS_BOUNDS max
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        },
    )
    assert resp.status_code == 422


async def test_plan_rejects_out_of_range_sets_and_weight(auth_client, exercise):
    resp = await auth_client.post(
        "/plans",
        json={
            "name": "Bad Plan",
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 50,
                    "target_reps": 8,
                    "target_weight": 100000.0,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        },
    )
    assert resp.status_code == 422


@pytest.mark.parametrize(
    "payload",
    [
        {"set_number": 1, "reps": 9999},
        {"set_number": 1, "reps": 8, "weight": 99999.0},
        {"set_number": 0, "reps": 8},
        {"set_number": 1, "reps": 0},
    ],
)
async def test_log_set_rejects_out_of_range(auth_client, exercise, payload):
    create = await auth_client.post(
        "/sessions", json={"date": str(datetime.date.today())}
    )
    session_id = create.json()["id"]

    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), **payload},
    )
    assert resp.status_code == 422


async def test_valid_set_within_bounds_still_accepted(auth_client, exercise):
    create = await auth_client.post(
        "/sessions", json={"date": str(datetime.date.today())}
    )
    session_id = create.json()["id"]

    resp = await auth_client.post(
        f"/sessions/{session_id}/sets",
        json={"exercise_id": str(exercise.id), "set_number": 1, "reps": 8, "weight": 135.0},
    )
    assert resp.status_code == 201


@pytest.mark.parametrize(
    "payload",
    [
        {"weight": -10.0},        # negative bodyweight
        {"weight": 0.0},          # zero
        {"weight": 99999.0},      # absurdly large
        {"weight": 180.0, "bodyfat": -5.0},   # negative bodyfat
        {"weight": 180.0, "bodyfat": 200.0},  # impossible bodyfat
    ],
)
async def test_body_metric_rejects_out_of_range(auth_client, payload):
    resp = await auth_client.post(
        "/metrics/weight",
        json={"date": str(datetime.date.today()), **payload},
    )
    assert resp.status_code == 422


async def test_body_metric_within_bounds_accepted(auth_client):
    resp = await auth_client.post(
        "/metrics/weight",
        json={"date": str(datetime.date.today()), "weight": 180.0, "bodyfat": 15.0},
    )
    assert resp.status_code == 201
