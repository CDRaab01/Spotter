import uuid

import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.database import AsyncSessionLocal, Base, engine
from app.main import app
from app.models.exercise import Exercise


@pytest_asyncio.fixture(scope="session", autouse=True)
async def setup_tables():
    """Ensure all tables exist before any test runs (safe to call after alembic)."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


@pytest_asyncio.fixture
async def auth_client(client):
    """HTTP client pre-authenticated as a fresh unique test user."""
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={
            "name": "Test User",
            "email": f"test_{uid}@spotter.test",
            "password": "Testpass123!",
        },
    )
    assert resp.status_code == 201, resp.text
    token = resp.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {token}"
    return client


@pytest_asyncio.fixture
async def exercise():
    """A real exercise row inserted directly into the test DB."""
    async with AsyncSessionLocal() as session:
        ex = Exercise(
            name=f"Test Squat {uuid.uuid4().hex[:6]}",
            muscle_group="legs",
            equipment="barbell",
        )
        session.add(ex)
        await session.commit()
        await session.refresh(ex)
        return ex
