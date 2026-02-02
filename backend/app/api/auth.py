from __future__ import annotations

import datetime as dt
import uuid

import jwt
from fastapi import APIRouter, Body, Depends, Request, Response
from pydantic import BaseModel, Field
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import require_web_csrf
from app.core.errors import APIError
from app.core.security import (
    REFRESH_TTL_SECONDS,
    generate_csrf_token,
    hash_password,
    hash_refresh_token,
    issue_access_token,
    issue_refresh_token,
    verify_password,
)
from app.core.settings import get_settings
from app.db.base import utcnow
from app.db.session import get_db
from app.models.refresh_token import RefreshToken
from app.models.user import User


router = APIRouter(prefix="/v1/auth", tags=["auth"])


class RegisterRequest(BaseModel):
    username: str
    email: str | None = None
    password: str = Field(min_length=12)


class RegisterResponse(BaseModel):
    user_id: str
    username: str
    email: str | None
    is_admin: bool


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "Bearer"
    expires_in: int
    refresh_token: str | None = None


class RefreshRequest(BaseModel):
    refresh_token: str | None = None


def _set_web_cookies(
    *, response: Response, refresh_token: str, csrf_token: str
) -> None:
    settings = get_settings()
    secure = bool(settings.dev_cookie_secure)

    # Web refresh token: httpOnly cookie.
    response.set_cookie(
        key="wf_refresh",
        value=refresh_token,
        httponly=True,
        secure=secure,
        samesite="lax",
        path="/",
        max_age=REFRESH_TTL_SECONDS,
    )
    # CSRF double-submit token cookie: readable by JS.
    response.set_cookie(
        key="wf_csrf",
        value=csrf_token,
        httponly=False,
        secure=secure,
        samesite="lax",
        path="/",
        max_age=REFRESH_TTL_SECONDS,
    )


async def _mint_refresh_row(
    *,
    db: AsyncSession,
    user_id: uuid.UUID,
    family_id: uuid.UUID | None,
    user_agent: str | None,
    ip: str | None,
) -> tuple[RefreshToken, str]:
    settings = get_settings()
    now = utcnow()
    token_id = uuid.uuid4()
    fam = family_id or uuid.uuid4()
    refresh_jwt, _ = issue_refresh_token(
        user_id=user_id,
        family_id=fam,
        token_id=token_id,
        settings=settings,
    )
    token_row = RefreshToken(
        id=token_id,
        user_id=user_id,
        family_id=fam,
        token_hash=hash_refresh_token(refresh_jwt),
        issued_at=now,
        expires_at=now + dt.timedelta(seconds=REFRESH_TTL_SECONDS),
        revoked_at=None,
        replaced_by=None,
        user_agent=user_agent,
        ip=ip,
        created_at=now,
    )
    db.add(token_row)
    return token_row, refresh_jwt


@router.post("/register", status_code=201, response_model=RegisterResponse)
async def register(
    payload: RegisterRequest, db: AsyncSession = Depends(get_db)
) -> RegisterResponse:
    try:
        hashed = hash_password(payload.password)
    except ValueError:
        raise APIError(
            code="AUTH_INVALID_CREDENTIALS",
            message="Password does not meet policy",
            status_code=400,
        )

    # First user becomes admin (bootstrap).
    is_first = (await db.execute(select(User.id).limit(1))).first() is None

    user = User(
        email=payload.email,
        username=payload.username,
        hashed_password=hashed,
        is_admin=is_first,
    )
    db.add(user)
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise APIError(
            code="AUTH_INVALID_CREDENTIALS",
            message="User already exists",
            status_code=400,
        )

    return RegisterResponse(
        user_id=str(user.id),
        email=user.email,
        username=user.username,
        is_admin=bool(user.is_admin),
    )


@router.post("/login", response_model=TokenResponse)
async def login(
    payload: LoginRequest,
    request: Request,
    response: Response,
    db: AsyncSession = Depends(get_db),
) -> TokenResponse:
    user = (
        await db.execute(select(User).where(User.username == payload.username))
    ).scalar_one_or_none()
    if user is None or not verify_password(payload.password, user.hashed_password):
        raise APIError(
            code="AUTH_INVALID_CREDENTIALS",
            message="Invalid username or password",
            status_code=401,
        )

    settings = get_settings()
    access_token, expires_in = issue_access_token(user_id=user.id, settings=settings)

    user_agent = request.headers.get("User-Agent")
    ip = request.client.host if request.client else None
    _token_row, refresh_jwt = await _mint_refresh_row(
        db=db,
        user_id=user.id,
        family_id=None,
        user_agent=user_agent,
        ip=ip,
    )
    await db.commit()

    # Web vs Android behavior:
    # - Web calls (from localhost:3000) are expected to include an Origin header.
    # - Android/scripting clients typically won't send Origin.
    is_web = request.headers.get("Origin") == settings.cors_allow_origin

    if is_web:
        csrf = generate_csrf_token()
        _set_web_cookies(response=response, refresh_token=refresh_jwt, csrf_token=csrf)
        return TokenResponse(access_token=access_token, expires_in=expires_in)
    return TokenResponse(
        access_token=access_token,
        expires_in=expires_in,
        refresh_token=refresh_jwt,
    )


@router.post("/refresh", response_model=TokenResponse)
async def refresh(
    request: Request,
    response: Response,
    payload: RefreshRequest | None = Body(default=None),
    db: AsyncSession = Depends(get_db),
) -> TokenResponse:
    settings = get_settings()

    body_token = payload.refresh_token if payload else None
    cookie_token = request.cookies.get("wf_refresh")

    is_android = bool(body_token)
    if not is_android and cookie_token:
        # Web: cookie-based + CSRF.
        require_web_csrf(request)
    refresh_token = body_token or cookie_token
    if not refresh_token:
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="Missing refresh token",
            status_code=401,
        )

    # Verify JWT signature/exp/type.
    try:
        from app.core.security import decode_jwt

        decoded = decode_jwt(
            token=refresh_token, settings=settings, expected_type="refresh"
        )
    except jwt.ExpiredSignatureError:
        raise APIError(
            code="AUTH_TOKEN_EXPIRED",
            message="Refresh token expired",
            status_code=401,
        )
    except jwt.InvalidTokenError:
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="Invalid refresh token",
            status_code=401,
        )

    # DB lookup by token hash.
    token_hash = hash_refresh_token(refresh_token)
    row = (
        await db.execute(
            select(RefreshToken).where(RefreshToken.token_hash == token_hash)
        )
    ).scalar_one_or_none()
    if row is None:
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="Unknown refresh token",
            status_code=401,
        )

    now = utcnow()
    expires_at = row.expires_at
    # SQLite returns naive datetimes even when timezone=True; treat as UTC.
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=dt.timezone.utc)
    if expires_at <= now:
        raise APIError(
            code="AUTH_TOKEN_EXPIRED",
            message="Refresh token expired",
            status_code=401,
        )

    # Basic claim binding checks.
    if str(row.user_id) != str(decoded.get("sub")):
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="Refresh token subject mismatch",
            status_code=401,
        )
    if str(row.family_id) != str(decoded.get("fid")):
        raise APIError(
            code="AUTH_TOKEN_INVALID",
            message="Refresh token family mismatch",
            status_code=401,
        )

    if row.revoked_at is not None:
        # Reuse detection: revoke the whole family.
        await db.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == row.family_id)
            .where(RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now)
        )
        await db.commit()
        raise APIError(
            code="AUTH_REFRESH_REUSED",
            message="Refresh token reuse detected",
            status_code=401,
        )

    user_agent = request.headers.get("User-Agent")
    ip = request.client.host if request.client else None
    new_row, new_refresh_jwt = await _mint_refresh_row(
        db=db,
        user_id=row.user_id,
        family_id=row.family_id,
        user_agent=user_agent,
        ip=ip,
    )
    row.revoked_at = now
    row.replaced_by = new_row.id
    await db.commit()

    access_token, expires_in = issue_access_token(
        user_id=row.user_id, settings=settings
    )
    if is_android:
        return TokenResponse(
            access_token=access_token,
            expires_in=expires_in,
            refresh_token=new_refresh_jwt,
        )

    csrf = generate_csrf_token()
    _set_web_cookies(response=response, refresh_token=new_refresh_jwt, csrf_token=csrf)
    return TokenResponse(access_token=access_token, expires_in=expires_in)
