import uuid


async def _create_plan_with_exercise(auth_client, exercise_id: str, name: str = "Test Plan") -> dict:
    resp = await auth_client.post(
        "/plans",
        json={
            "name": name,
            "source": "manual",
            "exercises": [
                {
                    "exercise_id": exercise_id,
                    "target_sets": 3,
                    "target_reps": 8,
                    "target_weight": 135.0,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ],
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


async def test_update_plan_exercises_replaces_all(auth_client, exercise):
    """PUT /plans/{id}/exercises with 2 exercises replaces the original 1."""
    from app.database import AsyncSessionLocal
    from app.models.exercise import Exercise

    # Create a second exercise
    async with AsyncSessionLocal() as session:
        ex2 = Exercise(
            name=f"Test Deadlift {uuid.uuid4().hex[:6]}",
            muscle_group="back",
            equipment="barbell",
        )
        session.add(ex2)
        await session.commit()
        await session.refresh(ex2)

    plan = await _create_plan_with_exercise(auth_client, str(exercise.id))
    assert len(plan["exercises"]) == 1

    resp = await auth_client.put(
        f"/plans/{plan['id']}/exercises",
        json={
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 4,
                    "target_reps": 6,
                    "target_weight": 155.0,
                    "is_bodyweight": False,
                    "order": 0,
                },
                {
                    "exercise_id": str(ex2.id),
                    "target_sets": 3,
                    "target_reps": 10,
                    "target_weight": None,
                    "is_bodyweight": False,
                    "order": 1,
                },
            ]
        },
    )
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert len(data["exercises"]) == 2


async def test_update_plan_exercises_returns_exercise_names(auth_client, exercise):
    """exercise_name field is populated after updating exercises."""
    plan = await _create_plan_with_exercise(auth_client, str(exercise.id))

    resp = await auth_client.put(
        f"/plans/{plan['id']}/exercises",
        json={
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 3,
                    "target_reps": 8,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ]
        },
    )
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert len(data["exercises"]) == 1
    assert data["exercises"][0]["exercise_name"] == exercise.name


async def test_update_plan_exercises_404_for_wrong_user(auth_client, client, exercise):
    """A second user cannot update another user's plan exercises."""
    plan = await _create_plan_with_exercise(auth_client, str(exercise.id))

    # Register a second user
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={
            "name": "Other User",
            "email": f"other_{uid}@spotter.com",
            "password": "Testpass123!",
        },
    )
    assert resp.status_code == 201
    other_token = resp.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {other_token}"

    resp = await client.put(
        f"/plans/{plan['id']}/exercises",
        json={
            "exercises": [
                {
                    "exercise_id": str(exercise.id),
                    "target_sets": 3,
                    "target_reps": 8,
                    "is_bodyweight": False,
                    "order": 0,
                }
            ]
        },
    )
    assert resp.status_code == 404


async def test_get_plan_includes_exercise_name(auth_client, exercise):
    """GET /plans/{id} returns exercises with exercise_name populated."""
    plan = await _create_plan_with_exercise(auth_client, str(exercise.id))

    resp = await auth_client.get(f"/plans/{plan['id']}")
    assert resp.status_code == 200, resp.text
    data = resp.json()
    assert len(data["exercises"]) == 1
    assert data["exercises"][0]["exercise_name"] == exercise.name
