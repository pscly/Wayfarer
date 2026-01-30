from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration loaded from environment variables."""

    model_config = SettingsConfigDict(
        env_prefix="WAYFARER_",
        case_sensitive=False,
    )

    # Design default: local async sqlite database.
    db_url: str = "sqlite+aiosqlite:///./data/dev.db"

    # Celery
    # Default dev behavior: run tasks inline unless explicitly disabled.
    celery_eager: bool = True
    redis_url: str | None = None

    # Export
    export_dir: str = "./data/exports"
    max_concurrent_exports: int = 2
    max_export_points: int = 5_000_000
    sync_threshold_points: int = 50_000

    # Weather (Open-Meteo archive)
    # Fixed token for acceptance: geohash_precision: 5
    weather_geohash_precision: int = 5  # geohash_precision: 5
    open_meteo_archive_base_url: str = "https://archive-api.open-meteo.com/v1/archive"
    open_meteo_timeout_s: float = 10.0
    open_meteo_max_retries: int = 5
    open_meteo_backoff_base_s: float = 0.5

    # Auth (JWT)
    jwt_signing_keys_json: str | None = None
    jwt_kid_current: str = "dev-1"

    # Cookie/CORS (dev defaults from plan-supplement)
    dev_cookie_secure: bool = False
    cors_allow_origin: str = "http://localhost:3000"
    cors_allow_credentials: bool = True


@lru_cache
def get_settings() -> Settings:
    return Settings()
