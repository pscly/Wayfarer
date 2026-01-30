from __future__ import annotations

# pyright: ignore

import os
import sys
from pathlib import Path
from logging.config import fileConfig

import sqlalchemy as sa
from alembic import context  # type: ignore[attr-defined]
from sqlalchemy import engine_from_config, pool

PROJECT_ROOT = Path(__file__).resolve().parents[1]
# Ensure `import app.*` works regardless of alembic invocation cwd.
sys.path.insert(0, str(PROJECT_ROOT))

from app.core.settings import get_settings  # noqa: E402
from app.db.base import Base  # noqa: E402

# Import all models so they are registered on Base.metadata.
import app.models  # noqa: F401,E402  # pylint: disable=unused-import


config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

target_metadata = Base.metadata


def _make_sync_url(url: str) -> str:
    """Convert runtime async DB URLs into sync URLs for Alembic."""

    u = sa.engine.make_url(url)
    if u.drivername == "sqlite+aiosqlite":
        u = u.set(drivername="sqlite")
    elif u.drivername == "postgresql+asyncpg":
        u = u.set(drivername="postgresql+psycopg")
    return str(u)


def get_db_url() -> str:
    env_url = os.getenv("WAYFARER_DB_URL")
    runtime_url = env_url or get_settings().db_url
    return _make_sync_url(runtime_url)


def run_migrations_offline() -> None:
    url = get_db_url()
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    configuration = config.get_section(config.config_ini_section) or {}
    configuration["sqlalchemy.url"] = get_db_url()

    connectable = engine_from_config(
        configuration,
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            compare_type=True,
        )

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
