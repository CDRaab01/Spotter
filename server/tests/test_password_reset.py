import uuid

from sqlalchemy import select

from app.models.user import User
from app.database import AsyncSessionLocal


async def _register(client, email: str, password: str = "Testpass123!"):
    resp = await client.post(
        "/auth/register",
        json={"name": "Reset User", "email": email, "password": password},
    )
    assert resp.status_code == 201, resp.text


async def _read_reset_token(email: str) -> str | None:
    async with AsyncSessionLocal() as session:
        result = await session.execute(select(User).where(User.email == email))
        user = result.scalar_one_or_none()
        return user.reset_token if user else None


async def test_full_password_reset_flow(client):
    """forgot-password issues a long token that reset-password consumes.

    Regression test for the reset_token column being too small (VARCHAR(6)) to
    hold the ~43-char secrets.token_urlsafe(32) value — which previously made the
    entire reset flow 500 on Postgres.
    """
    email = f"reset_{uuid.uuid4().hex[:8]}@spotter.com"
    await _register(client, email, password="OldPass123!")

    # Request a reset code — must succeed (and persist a full-length token).
    forgot = await client.post("/auth/forgot-password", json={"email": email})
    assert forgot.status_code == 200, forgot.text

    token = await _read_reset_token(email)
    assert token is not None
    assert len(token) > 6  # the original VARCHAR(6) column would have truncated this

    # Consume the token to set a new password.
    reset = await client.post(
        "/auth/reset-password",
        json={"token": token, "new_password": "NewPass456!"},
    )
    assert reset.status_code == 200, reset.text

    # Old password no longer works; new one does.
    bad = await client.post("/auth/login", json={"email": email, "password": "OldPass123!"})
    assert bad.status_code == 401
    good = await client.post("/auth/login", json={"email": email, "password": "NewPass456!"})
    assert good.status_code == 200, good.text

    # Token is single-use — it was cleared after a successful reset.
    assert await _read_reset_token(email) is None


async def test_reset_password_rejects_unknown_token(client):
    resp = await client.post(
        "/auth/reset-password",
        json={"token": "does-not-exist", "new_password": "Whatever123!"},
    )
    assert resp.status_code == 400


async def test_forgot_password_unknown_email_still_200(client):
    """Avoid leaking which emails are registered — always returns success."""
    resp = await client.post(
        "/auth/forgot-password", json={"email": "nobody@spotter.com"}
    )
    assert resp.status_code == 200
