from __future__ import annotations

from fastapi import FastAPI

from app.api.router import api_router
from app.core.settings import get_settings


def create_app() -> FastAPI:
    settings = get_settings()

    app = FastAPI(title="Wayfarer API")
    app.include_router(api_router)

    # Ensure settings are loaded early so misconfig fails fast.
    _ = settings

    return app


app = create_app()
