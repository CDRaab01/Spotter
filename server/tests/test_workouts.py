"""Cross-app workout-status endpoint tests (consumed by sister app "Plate").

Covers the shared-secret/email auth on ``GET /workouts`` and the trained/not-trained counting for
completed strength and cardio sessions. The cross-app secret is set for the suite so a Plate-style
token can be minted; a normal Spotter access token must NOT be accepted here.
"""
import datetime
import uuid

import pytest_asyncio
from jose import jwt

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.cardio_session import CardioSession
from app.models.user import User
from app.models.workout_session import WorkoutSession
from app.security import create_access_token, hash_password

# Enable the cross-app surface for the suite (disabled by default in production until configured).
settings.cross_app_secret = "test-cross-app-secret"

TODAY = datetime.date.today()


def _cross_app_token(email: str, *, secret: str | None = None, type_: str = "cross_app") -> str:
    expire = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=5)
    return jwt.encode(
        {"email": email, "type": type_, "exp": expire},
        secret or settings.cross_app_secret,
        algorithm=settings.algorithm,
    )


@pytest_asyncio.fixture
async def trained_user():
    """A user with one completed strength session today and one untouched email identity."""
    async with AsyncSessionLocal() as session:
        user = User(
            name="Plate Reader",
            email=f"xapp_{uuid.uuid4().hex[:8]}@spotter.com",
            hashed_password=hash_password("secret123"),
        )
        session.add(user)
        await session.commit()
        await session.refresh(user)
        return user


async def _add_strength(user_id, day, status="completed"):
    async with AsyncSessionLocal() as session:
        session.add(WorkoutSession(user_id=user_id, date=day, status=status))
        await session.commit()


async def _add_cardio(user_id, completed_at, status="completed"):
    async with AsyncSessionLocal() as session:
        session.add(
            CardioSession(
                user_id=user_id,
                program_id="c25k",
                status=status,
                completed_at=completed_at,
            )
        )
        await session.commit()


async def test_workouts_requires_token(client):
    resp = await client.get("/workouts", params={"date": TODAY.isoformat()})
    assert resp.status_code == 401


async def test_workouts_rejects_spotter_access_token(client, trained_user):
    # A normal Spotter access token is signed with secret_key + type "access" — not valid here.
    token = create_access_token(str(trained_user.id))
    resp = await client.get(
        "/workouts",
        params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401


async def test_workouts_rejects_wrong_secret(client, trained_user):
    token = _cross_app_token(trained_user.email, secret="not-the-shared-secret")
    resp = await client.get(
        "/workouts",
        params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401


async def test_workouts_rejects_wrong_type(client, trained_user):
    token = _cross_app_token(trained_user.email, type_="access")
    resp = await client.get(
        "/workouts",
        params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401


async def test_workouts_unknown_email_is_401(client):
    token = _cross_app_token("nobody@spotter.com")
    resp = await client.get(
        "/workouts",
        params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401


async def test_workouts_not_trained_when_no_sessions(client, trained_user):
    token = _cross_app_token(trained_user.email)
    resp = await client.get(
        "/workouts",
        params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["trained"] is False
    assert body["strength_sessions"] == 0
    assert body["cardio_sessions"] == 0


async def test_workouts_trained_with_completed_strength(client, trained_user):
    await _add_strength(trained_user.id, TODAY)
    token = _cross_app_token(trained_user.email)
    resp = await client.get(
        "/workouts",
        params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    body = resp.json()
    assert body["trained"] is True
    assert body["strength_sessions"] == 1


async def test_workouts_in_progress_strength_does_not_count(client, trained_user):
    await _add_strength(trained_user.id, TODAY, status="in_progress")
    token = _cross_app_token(trained_user.email)
    resp = await client.get(
        "/workouts",
        params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    body = resp.json()
    assert body["trained"] is False
    assert body["strength_sessions"] == 0


async def test_workouts_trained_with_completed_cardio(client, trained_user):
    completed_at = datetime.datetime(
        TODAY.year, TODAY.month, TODAY.day, 12, 0, tzinfo=datetime.timezone.utc
    )
    await _add_cardio(trained_user.id, completed_at)
    token = _cross_app_token(trained_user.email)
    resp = await client.get(
        "/workouts",
        params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    body = resp.json()
    assert body["trained"] is True
    assert body["cardio_sessions"] == 1


async def test_workouts_other_day_is_not_trained(client, trained_user):
    await _add_strength(trained_user.id, TODAY)
    token = _cross_app_token(trained_user.email)
    other = (TODAY - datetime.timedelta(days=3)).isoformat()
    resp = await client.get(
        "/workouts",
        params={"date": other},
        headers={"Authorization": f"Bearer {token}"},
    )
    body = resp.json()
    assert body["trained"] is False
