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


@lru_cache
def get_settings() -> Settings:
    return Settings()
