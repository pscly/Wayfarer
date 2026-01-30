from __future__ import annotations

import uuid

import jwt
from fastapi import Depends, Header, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import APIError
from app.core.security import decode_jwt
from app.core.settings import get_settings
from app.db.session import get_db
from app.models.user import User


async def get_current_user(
    authorization: str | None = Header(default=None, alias="Authorization"),
    db: AsyncSession = Depends(get_db),
) -> User:
    if not authorization or not authorization.startswith("Bearer "):
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="Missing bearer token",
            status_code=401,
        )

    token = authorization.removeprefix("Bearer ").strip()
    settings = get_settings()
    try:
        payload = decode_jwt(token=token, settings=settings, expected_type="access")
    except jwt.ExpiredSignatureError:
        raise APIError(
            code="AUTH_TOKEN_EXPIRED",
            message="Access token expired",
            status_code=401,
        )
    except jwt.InvalidTokenError:
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="Invalid access token",
            status_code=401,
        )

    try:
        user_id = uuid.UUID(str(payload.get("sub")))
    except Exception:
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="Invalid access token subject",
            status_code=401,
        )

    user = (
        await db.execute(select(User).where(User.id == user_id))
    ).scalar_one_or_none()
    if user is None:
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="User not found",
            status_code=401,
        )
    return user


def require_web_csrf(request: Request) -> None:
    """Double-submit CSRF protection for web refresh.

    Contract:
    - Cookie: wf_csrf
    - Header: X-CSRF-Token
    - Must match exactly.
    """

    csrf_cookie = request.cookies.get("wf_csrf")
    csrf_header = request.headers.get("X-CSRF-Token")

    if not csrf_cookie or not csrf_header:
        raise APIError(
            code="AUTH_FORBIDDEN",
            message="Missing CSRF token",
            status_code=403,
        )
    if csrf_cookie != csrf_header:
        raise APIError(
            code="AUTH_FORBIDDEN",
            message="CSRF token mismatch",
            status_code=403,
        )
