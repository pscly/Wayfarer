from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.errors import APIError
from app.db.session import get_db
from app.models.user import User


router = APIRouter(prefix="/v1/admin", tags=["admin"])


async def require_admin(user: User = Depends(get_current_user)) -> User:
    if not bool(getattr(user, "is_admin", False)):
        raise APIError(
            code="AUTH_FORBIDDEN",
            message="Admin permission required",
            status_code=403,
        )
    return user


class AdminUserRow(BaseModel):
    user_id: str
    username: str
    email: str | None
    is_admin: bool
    created_at: str


class SetAdminRequest(BaseModel):
    is_admin: bool


@router.get("/users", response_model=list[AdminUserRow])
async def list_users(
    _admin: User = Depends(require_admin),
    db: AsyncSession = Depends(get_db),
) -> list[AdminUserRow]:
    users = (
        (await db.execute(select(User).order_by(User.created_at.asc()))).scalars().all()
    )
    out: list[AdminUserRow] = []
    for u in users:
        created_at = u.created_at.isoformat()
        if created_at.endswith("+00:00"):
            created_at = created_at.removesuffix("+00:00") + "Z"
        out.append(
            AdminUserRow(
                user_id=str(u.id),
                username=u.username,
                email=u.email,
                is_admin=bool(getattr(u, "is_admin", False)),
                created_at=created_at,
            )
        )
    return out


@router.put("/users/{user_id}/admin", response_model=AdminUserRow)
async def set_admin(
    user_id: str,
    payload: SetAdminRequest,
    _admin: User = Depends(require_admin),
    db: AsyncSession = Depends(get_db),
) -> AdminUserRow:
    try:
        uid = uuid.UUID(user_id)
    except Exception:
        raise APIError(
            code="VALIDATION_ERROR",
            message="Invalid user_id",
            status_code=400,
        )

    user = (await db.execute(select(User).where(User.id == uid))).scalar_one_or_none()
    if user is None:
        raise APIError(
            code="NOT_FOUND",
            message="User not found",
            status_code=404,
        )

    user.is_admin = bool(payload.is_admin)
    await db.commit()

    created_at = user.created_at.isoformat()
    if created_at.endswith("+00:00"):
        created_at = created_at.removesuffix("+00:00") + "Z"
    return AdminUserRow(
        user_id=str(user.id),
        username=user.username,
        email=user.email,
        is_admin=bool(user.is_admin),
        created_at=created_at,
    )
