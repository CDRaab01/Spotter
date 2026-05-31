from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from jose import JWTError, jwt
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.schemas.auth import LoginRequest, RefreshRequest, RegisterRequest, TokenResponse
from app.security import create_access_token, create_refresh_token
from app.services.auth_service import login_user, register_user

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/register", response_model=TokenResponse, status_code=201)
async def register(
    req: RegisterRequest, db: Annotated[AsyncSession, Depends(get_db)]
):
    return await register_user(db, req)


@router.post("/login", response_model=TokenResponse)
async def login(req: LoginRequest, db: Annotated[AsyncSession, Depends(get_db)]):
    return await login_user(db, req)


@router.post("/refresh", response_model=TokenResponse)
async def refresh(req: RefreshRequest):
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid refresh token"
    )
    try:
        payload = jwt.decode(
            req.refresh_token, settings.secret_key, algorithms=[settings.algorithm]
        )
        if payload.get("type") != "refresh":
            raise credentials_exception
        user_id: str | None = payload.get("sub")
        if not user_id:
            raise credentials_exception
    except JWTError:
        raise credentials_exception
    return TokenResponse(
        access_token=create_access_token(user_id),
        refresh_token=create_refresh_token(user_id),
    )
