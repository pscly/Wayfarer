import os

from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine


async def main() -> None:
    """Print current type/length of alembic_version.version_num."""

    db_url = os.environ.get("WAYFARER_DB_URL")
    if not db_url:
        raise RuntimeError("WAYFARER_DB_URL is not set")

    engine = create_async_engine(db_url, pool_pre_ping=True)
    async with engine.begin() as conn:
        result = await conn.execute(
            text(
                """
                SELECT data_type, character_maximum_length
                FROM information_schema.columns
                WHERE table_name = 'alembic_version'
                  AND column_name = 'version_num'
                """
            )
        )
        row = result.first()
        print(row)
    await engine.dispose()


if __name__ == "__main__":
    import asyncio

    asyncio.run(main())
