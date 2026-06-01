async def test_list_exercises_requires_auth(client):
    resp = await client.get("/exercises")
    assert resp.status_code == 401


async def test_list_exercises_returns_list(auth_client, exercise):
    resp = await auth_client.get("/exercises")
    assert resp.status_code == 200
    data = resp.json()
    assert isinstance(data, list)
    ids = [e["id"] for e in data]
    assert str(exercise.id) in ids


async def test_exercise_has_expected_fields(auth_client, exercise):
    resp = await auth_client.get("/exercises")
    assert resp.status_code == 200
    match = next((e for e in resp.json() if e["id"] == str(exercise.id)), None)
    assert match is not None
    assert match["name"] == exercise.name
    assert match["muscle_group"] == exercise.muscle_group
    assert match["equipment"] == exercise.equipment


async def test_search_exercises_by_name(auth_client, exercise):
    resp = await auth_client.get(f"/exercises?search={exercise.name[:5]}")
    assert resp.status_code == 200
    ids = [e["id"] for e in resp.json()]
    assert str(exercise.id) in ids


async def test_search_exercises_case_insensitive(auth_client, exercise):
    resp = await auth_client.get(f"/exercises?search={exercise.name[:5].upper()}")
    assert resp.status_code == 200
    ids = [e["id"] for e in resp.json()]
    assert str(exercise.id) in ids


async def test_search_exercises_no_match(auth_client):
    resp = await auth_client.get("/exercises?search=ZZZNonexistentXXX999")
    assert resp.status_code == 200
    assert resp.json() == []
