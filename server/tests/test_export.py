"""GET /export — full per-user data export (ROADMAP T3 #6)."""

import datetime


async def test_export_empty_user(auth_client):
    r = await auth_client.get("/export")
    assert r.status_code == 200, r.text
    assert "attachment" in r.headers.get("content-disposition", "")
    assert r.headers["content-disposition"].endswith('.json"')

    data = r.json()
    assert data["app"] == "spotter"
    assert data["schema_version"] >= 1
    assert data["exported_at"]
    assert data["user"]["email"]
    assert "hashed_password" not in data["user"]
    assert "reset_token" not in data["user"]
    for key in (
        "workout_routines",
        "routine_exercises",
        "workout_programs",
        "program_days",
        "workout_sessions",
        "set_logs",
        "body_metrics",
        "cardio_sessions",
    ):
        assert data[key] == [], key


async def test_export_includes_body_metric(auth_client):
    post = await auth_client.post(
        "/metrics/weight",
        json={"date": str(datetime.date.today()), "weight": 180.0},
    )
    assert post.status_code in (200, 201), post.text

    data = (await auth_client.get("/export")).json()
    assert len(data["body_metrics"]) == 1
    assert isinstance(data["body_metrics"][0]["user_id"], str)  # UUID → string
    assert isinstance(data["body_metrics"][0]["date"], str)  # date → ISO string


async def test_export_requires_auth(client):
    assert (await client.get("/export")).status_code == 401
