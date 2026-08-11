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


async def test_accept_program_creates_routines_and_active_program(auth_client, exercise):
    resp = await auth_client.post("/ai/programs/accept", json=_accept_body(str(exercise.id)))
    assert resp.status_code == 201, resp.text
    program = resp.json()
    assert program["is_active"] is True
    assert len(program["days"]) == 2
    # Push day links a routine; rest day does not.
    push = next(d for d in program["days"] if d["label"] == "Push")
    rest = next(d for d in program["days"] if d["label"] == "Rest")
    assert push["routine_id"] is not None
    assert rest["routine_id"] is None

    # The per-day routine was created as an AI routine.
    routines = (await auth_client.get("/routines")).json()
    ai_routines = [r for r in routines if r["source"] == "ai"]
    assert any(r["id"] == push["routine_id"] for r in ai_routines)


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


async def test_accept_failure_leaves_no_orphan_routines_or_program(auth_client, exercise):
    """Accept is one transaction: a failure on a later day must roll back the
    routines already created for earlier days (previously each step committed
    independently, stranding orphan source="ai" routines)."""
    import uuid as _uuid

    body = _accept_body(str(exercise.id), name="Broken")
    # Second training day references an exercise that doesn't exist → the
    # routine creation for that day 404s after day one's routine was flushed.
    body["days"].append(
        {
            "label": "Pull",
            "order": 2,
            "exercises": [
                {
                    "exercise_id": str(_uuid.uuid4()),
                    "target_sets": 3,
                    "target_reps": 8,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        }
    )
    resp = await auth_client.post("/ai/programs/accept", json=body)
    assert resp.status_code == 404

    routines = (await auth_client.get("/routines")).json()
    assert not any(r["name"].startswith("Broken") for r in routines)
    programs = (await auth_client.get("/programs")).json()
    assert not any(p["name"] == "Broken" for p in programs)


async def test_accept_with_max_length_name_and_label_truncates_routine_name(auth_client, exercise):
    """255-char program name + 100-char label compose past the routine-name
    column; accept must truncate instead of 500ing."""
    body = _accept_body(str(exercise.id), name="N" * 255)
    body["days"][0]["label"] = "L" * 100
    resp = await auth_client.post("/ai/programs/accept", json=body)
    assert resp.status_code == 201, resp.text

    program = resp.json()
    day = next(d for d in program["days"] if d["routine_id"] is not None)
    routines = (await auth_client.get("/routines")).json()
    routine = next(r for r in routines if r["id"] == day["routine_id"])
    assert len(routine["name"]) <= 255
    assert routine["name"].startswith("N" * 100)


async def test_accept_program_name_over_255_returns_422(auth_client, exercise):
    resp = await auth_client.post(
        "/ai/programs/accept", json=_accept_body(str(exercise.id), name="x" * 256)
    )
    assert resp.status_code == 422


async def test_accept_more_than_14_days_returns_422(auth_client, exercise):
    body = _accept_body(str(exercise.id))
    body["days"] = [{"label": f"Day {i}", "order": i, "exercises": []} for i in range(15)]
    resp = await auth_client.post("/ai/programs/accept", json=body)
    assert resp.status_code == 422
