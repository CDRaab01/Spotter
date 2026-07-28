import time
import uuid

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from jose import jwk
from jose import jwt as jose_jwt
from sqlalchemy import func, select

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.user import User

ISSUER = "http://id.test"
KID = "test-kid"

_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PRIVATE_PEM = _key.private_bytes(
    serialization.Encoding.PEM,
    serialization.PrivateFormat.PKCS8,
    serialization.NoEncryption(),
).decode()
_PUBLIC_PEM = (
    _key.public_key()
    .public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    .decode()
)


def _jwks() -> dict:
    d = jwk.construct(_PUBLIC_PEM, "RS256").to_dict()
    d.update({"kid": KID, "use": "sig", "alg": "RS256"})
    return {"keys": [d]}


def _suite_token(email: str, *, iss: str = ISSUER, aud: str = "suite", name: str = "SSO User") -> str:
    now = int(time.time())
    claims = {
        "iss": iss,
        "sub": "suite-user-1",
        "aud": aud,
        "email": email,
        "name": name,
        "iat": now,
        "exp": now + 300,
    }
    return jose_jwt.encode(claims, _PRIVATE_PEM, algorithm="RS256", headers={"kid": KID})


@pytest.fixture
def suite_enabled(monkeypatch):
    monkeypatch.setattr(settings, "suite_jwks_url", "http://id.test/jwks")
    monkeypatch.setattr(settings, "suite_issuer", ISSUER)
    monkeypatch.setattr(settings, "suite_audience", "suite")

    async def _fake_fetch(*, force: bool = False):
        return _jwks()

    monkeypatch.setattr("app.services.suite_auth._fetch_jwks", _fake_fetch)


async def _count_users(email: str) -> int:
    async with AsyncSessionLocal() as s:
        return (
            await s.execute(select(func.count()).select_from(User).where(User.email == email))
        ).scalar()


async def test_disabled_by_default_returns_404(client):
    r = await client.post("/auth/suite", json={"suite_token": "anything"})
    assert r.status_code == 404


async def test_new_email_creates_and_links(client, suite_enabled):
    # Unique per run: these assert on "this email does not exist yet", so a fixed address
    # only passes against a virgin database and reports a false failure on any re-run.
    email = f"brandnew-{uuid.uuid4().hex[:12]}@example.com"
    assert await _count_users(email) == 0
    r = await client.post("/auth/suite", json={"suite_token": _suite_token(email)})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["access_token"] and body["refresh_token"]
    assert await _count_users(email) == 1
    r = await client.post("/auth/suite", json={"suite_token": _suite_token(email)})
    assert r.status_code == 200
    assert await _count_users(email) == 1


async def test_links_to_existing_password_account(client, suite_enabled):
    email = f"existing-{uuid.uuid4().hex[:12]}@example.com"
    reg = await client.post(
        "/auth/register",
        json={"name": "Existing", "email": email, "password": "Password123!"},
    )
    assert reg.status_code == 201, reg.text
    assert await _count_users(email) == 1
    r = await client.post("/auth/suite", json={"suite_token": _suite_token(email)})
    assert r.status_code == 200
    assert await _count_users(email) == 1


async def test_wrong_issuer_rejected(client, suite_enabled):
    r = await client.post(
        "/auth/suite", json={"suite_token": _suite_token("x@example.com", iss="http://evil")}
    )
    assert r.status_code == 401


async def test_wrong_audience_rejected(client, suite_enabled):
    r = await client.post(
        "/auth/suite", json={"suite_token": _suite_token("y@example.com", aud="not-suite")}
    )
    assert r.status_code == 401


async def test_garbage_token_rejected(client, suite_enabled):
    r = await client.post("/auth/suite", json={"suite_token": "not-a-jwt"})
    assert r.status_code == 401


# --- Cross-app service tokens (ROADMAP T2 #5) — the RS256 dual-accept path on GET /workouts ---


async def _make_user(email: str) -> None:
    async with AsyncSessionLocal() as s:
        s.add(User(name="X", email=email, hashed_password="x"))
        await s.commit()


async def test_cross_app_rs256_token_accepted(client, suite_enabled):
    """A dragonfly-id RS256 token (aud="cross-app") authenticates the cross-app /workouts surface,
    validated against the same JWKS as SSO — no cross_app_secret needed on this side."""
    email = f"xapp-rs256-{uuid.uuid4().hex[:8]}@example.com"
    await _make_user(email)
    token = _suite_token(email, aud="cross-app")
    r = await client.get(
        "/workouts", params={"date": "2026-07-04"}, headers={"Authorization": f"Bearer {token}"}
    )
    assert r.status_code == 200, r.text
    assert r.json()["trained"] is False


async def test_suite_sso_token_rejected_on_cross_app(client, suite_enabled):
    """Audience scoping: a suite SSO user token (aud="suite") must NOT work on the cross-app
    surface — the whole point of the distinct aud="cross-app". (401 fires before any user lookup.)"""
    token = _suite_token("sso-user@example.com", aud="suite")
    r = await client.get(
        "/workouts", params={"date": "2026-07-04"}, headers={"Authorization": f"Bearer {token}"}
    )
    assert r.status_code == 401
