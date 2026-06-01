from typing import Annotated

from fastapi import APIRouter, Depends

from app.models.user import User
from app.schemas.user import UserOut
from app.security import get_current_user

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/me", response_model=UserOut)
async def get_me(
    current_user: Annotated[User, Depends(get_current_user)],
):
    return current_user
