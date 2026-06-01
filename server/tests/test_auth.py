import uuid


async def test_register_returns_tokens(client):
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Alice", "email": f"alice_{uid}@test.com", "password": "secret123"},
    )
    assert resp.status_code == 201
    data = resp.json()
    assert "access_token" in data
    assert "refresh_token" in data
    assert data["token_type"] == "bearer"


async def test_register_duplicate_email_returns_409(client):
    uid = uuid.uuid4().hex[:8]
    email = f"dup_{uid}@test.com"
    payload = {"name": "Alice", "email": email, "password": "secret123"}
    await client.post("/auth/register", json=payload)
    resp = await client.post("/auth/register", json=payload)
    assert resp.status_code == 409


async def test_login_success(client):
    uid = uuid.uuid4().hex[:8]
    email = f"login_{uid}@test.com"
    await client.post("/auth/register", json={"name": "Alice", "email": email, "password": "mypass"})
    resp = await client.post("/auth/login", json={"email": email, "password": "mypass"})
    assert resp.status_code == 200
    assert "access_token" in resp.json()


async def test_login_wrong_password_returns_401(client):
    uid = uuid.uuid4().hex[:8]
    email = f"fail_{uid}@test.com"
    await client.post("/auth/register", json={"name": "Alice", "email": email, "password": "correct"})
    resp = await client.post("/auth/login", json={"email": email, "password": "wrong"})
    assert resp.status_code == 401


async def test_refresh_issues_new_access_token(client):
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={"name": "Bob", "email": f"bob_{uid}@test.com", "password": "pw123456"},
    )
    refresh_token = resp.json()["refresh_token"]
    resp2 = await client.post("/auth/refresh", json={"refresh_token": refresh_token})
    assert resp2.status_code == 200
    assert "access_token" in resp2.json()


async def test_plans_require_auth(client):
    resp = await client.get("/plans")
    assert resp.status_code == 401


async def test_health_check(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}
