import logging
import secrets
import smtplib
from datetime import datetime, timedelta, timezone
from email.mime.text import MIMEText

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.user import User
from app.schemas.auth import (
    ForgotPasswordRequest,
    LoginRequest,
    RegisterRequest,
    ResetPasswordRequest,
    TokenResponse,
)
from app.security import create_access_token, create_refresh_token, hash_password, verify_password

log = logging.getLogger(__name__)

RESET_TOKEN_EXPIRY_MINUTES = 60


async def register_user(db: AsyncSession, req: RegisterRequest) -> TokenResponse:
    result = await db.execute(select(User).where(User.email == req.email))
    if result.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Email already registered"
        )
    user = User(name=req.name, email=req.email, hashed_password=hash_password(req.password))
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return TokenResponse(
        access_token=create_access_token(str(user.id)),
        refresh_token=create_refresh_token(str(user.id)),
    )


async def login_user(db: AsyncSession, req: LoginRequest) -> TokenResponse:
    result = await db.execute(select(User).where(User.email == req.email))
    user = result.scalar_one_or_none()
    if not user or not verify_password(req.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials"
        )
    return TokenResponse(
        access_token=create_access_token(str(user.id)),
        refresh_token=create_refresh_token(str(user.id)),
    )


async def forgot_password(db: AsyncSession, req: ForgotPasswordRequest) -> None:
    result = await db.execute(select(User).where(User.email == req.email))
    user = result.scalar_one_or_none()
    # Always return success to avoid leaking whether an email is registered
    if user is None:
        return

    token = str(secrets.randbelow(900000) + 100000)  # 6-digit code: 100000–999999
    user.reset_token = token
    user.reset_token_expires_at = datetime.now(timezone.utc) + timedelta(
        minutes=RESET_TOKEN_EXPIRY_MINUTES
    )
    await db.commit()

    _deliver_reset_token(req.email, token)


async def reset_password(db: AsyncSession, req: ResetPasswordRequest) -> None:
    result = await db.execute(select(User).where(User.reset_token == req.token))
    user = result.scalar_one_or_none()
    if user is None or user.reset_token_expires_at is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid or expired reset code")

    expires = user.reset_token_expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    if datetime.now(timezone.utc) > expires:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid or expired reset code")

    user.hashed_password = hash_password(req.new_password)
    user.reset_token = None
    user.reset_token_expires_at = None
    await db.commit()


def _deliver_reset_token(email: str, token: str) -> None:
    if settings.smtp_host and settings.smtp_user and settings.smtp_password:
        _send_email(email, token)
    else:
        log.warning(
            "SMTP not configured — password reset code for %s: %s (valid %d min)",
            email,
            token,
            RESET_TOKEN_EXPIRY_MINUTES,
        )


def _send_email(to: str, token: str) -> None:
    body = (
        f"Your Spotter password reset code is: {token}\n\n"
        f"Enter this code in the app to reset your password. "
        f"It expires in {RESET_TOKEN_EXPIRY_MINUTES} minutes.\n\n"
        "If you didn't request this, you can ignore this email."
    )
    msg = MIMEText(body)
    msg["Subject"] = "Spotter — password reset code"
    msg["From"] = settings.smtp_from
    msg["To"] = to
    try:
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port) as smtp:
            smtp.starttls()
            smtp.login(settings.smtp_user, settings.smtp_password)
            smtp.sendmail(settings.smtp_from, [to], msg.as_string())
    except Exception as exc:
        log.error("Failed to send reset email to %s: %s", to, exc)
