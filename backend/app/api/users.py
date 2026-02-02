from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.api.deps import get_current_user
from app.models.user import User


router = APIRouter(prefix="/v1/users", tags=["users"])


class MeResponse(BaseModel):
    user_id: str
    username: str
    email: str | None
    is_admin: bool
    created_at: str


@router.get("/me", response_model=MeResponse)
async def me(user: User = Depends(get_current_user)) -> MeResponse:
    created_at = user.created_at.isoformat()
    if created_at.endswith("+00:00"):
        created_at = created_at.removesuffix("+00:00") + "Z"
    return MeResponse(
        user_id=str(user.id),
        username=user.username,
        email=user.email,
        is_admin=bool(getattr(user, "is_admin", False)),
        created_at=created_at,
    )
