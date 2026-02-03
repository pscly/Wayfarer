import os

from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine


async def main() -> None:
    """Widen alembic_version.version_num to support long revision ids.

    Alembic's default version table column uses VARCHAR(32). This repo uses
    human-readable revision ids like "0003_user_admin_and_optional_email" which
    exceed 32 characters, causing migrations to fail in PostgreSQL.
    """

    db_url = os.environ.get("WAYFARER_DB_URL")
    if not db_url:
        raise RuntimeError("WAYFARER_DB_URL is not set")

    engine = create_async_engine(db_url, pool_pre_ping=True)
    async with engine.begin() as conn:
        await conn.execute(
            text(
                "ALTER TABLE alembic_version ALTER COLUMN version_num TYPE VARCHAR(64)"
            )
        )
    await engine.dispose()


if __name__ == "__main__":
    import asyncio

    asyncio.run(main())
