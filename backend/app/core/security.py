from __future__ import annotations

import base64
import datetime as dt
import hashlib
import json
import secrets
import uuid
from typing import Any, Literal

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError

from app.core.settings import Settings


ACCESS_TTL_SECONDS = 15 * 60
REFRESH_TTL_SECONDS = 30 * 24 * 60 * 60


_pwd_hasher = PasswordHasher(time_cost=2, memory_cost=102400, parallelism=8)


def hash_password(password: str) -> str:
    # Enforce policy in code even if request validation is bypassed.
    if len(password) < 12:
        raise ValueError("password_too_short")
    return _pwd_hasher.hash(password)


def verify_password(password: str, hashed_password: str) -> bool:
    try:
        return _pwd_hasher.verify(hashed_password, password)
    except VerifyMismatchError:
        return False


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def generate_csrf_token() -> str:
    # Double-submit token; must be random and unguessable.
    return _b64url(secrets.token_bytes(32))


def hash_refresh_token(refresh_token: str) -> str:
    # Refresh token is high-entropy; plain SHA256 is sufficient for at-rest protection.
    return hashlib.sha256(refresh_token.encode("utf-8")).hexdigest()


def _load_signing_keys(settings: Settings) -> dict[str, str]:
    if not settings.jwt_signing_keys_json:
        raise RuntimeError("WAYFARER_JWT_SIGNING_KEYS_JSON is required")
    raw = json.loads(settings.jwt_signing_keys_json)
    if not isinstance(raw, dict):
        raise ValueError("WAYFARER_JWT_SIGNING_KEYS_JSON must be a JSON object")
    keys: dict[str, str] = {}
    for k, v in raw.items():
        if isinstance(k, str) and isinstance(v, str):
            keys[k] = v
    if not keys:
        raise ValueError("WAYFARER_JWT_SIGNING_KEYS_JSON must contain at least one kid")
    return keys


def _now_utc() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def issue_access_token(*, user_id: uuid.UUID, settings: Settings) -> tuple[str, int]:
    keys = _load_signing_keys(settings)
    kid = settings.jwt_kid_current
    key = keys.get(kid)
    if not key:
        raise RuntimeError("WAYFARER_JWT_KID_CURRENT not found in signing key map")

    now = _now_utc()
    payload: dict[str, Any] = {
        "sub": str(user_id),
        "typ": "access",
        "iat": int(now.timestamp()),
        "exp": int((now + dt.timedelta(seconds=ACCESS_TTL_SECONDS)).timestamp()),
    }
    token = jwt.encode(payload, key, algorithm="HS256", headers={"kid": kid})
    return token, ACCESS_TTL_SECONDS


def issue_refresh_token(
    *,
    user_id: uuid.UUID,
    family_id: uuid.UUID,
    token_id: uuid.UUID,
    settings: Settings,
) -> tuple[str, int]:
    keys = _load_signing_keys(settings)
    kid = settings.jwt_kid_current
    key = keys.get(kid)
    if not key:
        raise RuntimeError("WAYFARER_JWT_KID_CURRENT not found in signing key map")

    now = _now_utc()
    payload: dict[str, Any] = {
        "sub": str(user_id),
        "typ": "refresh",
        "jti": str(token_id),
        "fid": str(family_id),
        "iat": int(now.timestamp()),
        "exp": int((now + dt.timedelta(seconds=REFRESH_TTL_SECONDS)).timestamp()),
    }
    token = jwt.encode(payload, key, algorithm="HS256", headers={"kid": kid})
    return token, REFRESH_TTL_SECONDS


def decode_jwt(
    *, token: str, settings: Settings, expected_type: Literal["access", "refresh"]
) -> dict[str, Any]:
    keys = _load_signing_keys(settings)

    # Select key by header.kid to support key rotation.
    try:
        header = jwt.get_unverified_header(token)
    except jwt.InvalidTokenError as e:
        raise e

    kid = header.get("kid")
    if not isinstance(kid, str) or kid not in keys:
        raise jwt.InvalidTokenError("unknown kid")

    payload = jwt.decode(
        token,
        keys[kid],
        algorithms=["HS256"],
        options={"require": ["exp", "iat", "sub"]},
    )
    if payload.get("typ") != expected_type:
        raise jwt.InvalidTokenError("wrong token type")
    return payload
