from __future__ import annotations

from fastapi import APIRouter

from app.api.auth import router as auth_router
from app.api.export import router as export_router
from app.api.health import router as health_router
from app.api.users import router as users_router
from app.api.tracks import router as tracks_router
from app.api.life_events import router as life_events_router


api_router = APIRouter()
api_router.include_router(health_router)
api_router.include_router(auth_router)
api_router.include_router(users_router)
api_router.include_router(tracks_router)
api_router.include_router(life_events_router)
api_router.include_router(export_router)
