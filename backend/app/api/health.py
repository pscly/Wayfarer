from __future__ import annotations

import json

from fastapi import APIRouter
from fastapi import Depends
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.settings import Settings
from app.core.settings import get_settings
from app.db.session import get_db
from app.models import User


router = APIRouter(tags=["health"])


@router.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/readyz")
async def readyz(
    db: AsyncSession = Depends(get_db),
    settings: Settings = Depends(get_settings),
) -> JSONResponse:
    # Readiness: both DB schema and JWT config must be usable.

    # JWT config is required in production; key map must contain current kid.
    if not settings.jwt_signing_keys_json:
        return JSONResponse(status_code=503, content={"status": "not_ready"})
    try:
        key_map = json.loads(settings.jwt_signing_keys_json)
    except Exception:
        return JSONResponse(status_code=503, content={"status": "not_ready"})
    if not isinstance(key_map, dict):
        return JSONResponse(status_code=503, content={"status": "not_ready"})

    secret = key_map.get(settings.jwt_kid_current)
    if not isinstance(secret, str) or not secret:
        return JSONResponse(status_code=503, content={"status": "not_ready"})

    # DB schema check: query a known column; fails if migrations not applied.
    try:
        await db.execute(select(User.is_admin).limit(1))
    except Exception:
        return JSONResponse(status_code=503, content={"status": "not_ready"})

    return JSONResponse(status_code=200, content={"status": "ready"})
