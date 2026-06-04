def _accept_body(exercise_id: str, name: str = "PPL"):
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
            {"label": "Rest", "order": 1, "exercises": []},
        ],
    }


async def test_accept_program_creates_plans_and_active_program(auth_client, exercise):
    resp = await auth_client.post("/ai/programs/accept", json=_accept_body(str(exercise.id)))
    assert resp.status_code == 201, resp.text
    program = resp.json()
    assert program["is_active"] is True
    assert len(program["days"]) == 2
    # Push day links a plan; rest day does not.
    push = next(d for d in program["days"] if d["label"] == "Push")
    rest = next(d for d in program["days"] if d["label"] == "Rest")
    assert push["plan_id"] is not None
    assert rest["plan_id"] is None

    # The per-day plan was created as an AI plan.
    plans = (await auth_client.get("/plans")).json()
    ai_plans = [p for p in plans if p["source"] == "ai"]
    assert any(p["id"] == push["plan_id"] for p in ai_plans)


async def test_accept_program_deactivates_previous_active(auth_client, exercise):
    first = await auth_client.post("/ai/programs/accept", json=_accept_body(str(exercise.id), name="First"))
    assert first.status_code == 201
    first_id = first.json()["id"]

    second = await auth_client.post("/ai/programs/accept", json=_accept_body(str(exercise.id), name="Second"))
    assert second.status_code == 201

    programs = (await auth_client.get("/programs")).json()
    active = [p for p in programs if p["is_active"]]
    assert len(active) == 1
    assert active[0]["id"] != first_id


async def test_accept_program_requires_auth(client, exercise):
    resp = await client.post("/ai/programs/accept", json=_accept_body(str(exercise.id)))
    assert resp.status_code == 401
