from __future__ import annotations

# pyright: ignore

import os
import sys
from pathlib import Path
from logging.config import fileConfig

import sqlalchemy as sa
from alembic import context  # type: ignore[attr-defined]
from alembic.ddl.postgresql import PostgresqlImpl  # type: ignore[attr-defined]
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


ALEMBIC_VERSION_NUM_MAXLEN = 255


class WayfarerPostgresqlImpl(PostgresqlImpl):
    """自定义 Alembic 在 PostgreSQL 下创建的版本表结构。

    Alembic 默认把 `alembic_version.version_num` 定义为 `VARCHAR(32)`；
    但本项目的 revision id 采用可读字符串（如 `0003_user_admin_and_optional_email`），
    长度可能超过 32，导致迁移在更新版本号时直接失败。
    """

    __dialect__ = "postgresql"

    def version_table_impl(
        self,
        *,
        version_table: str,
        version_table_schema: str | None,
        version_table_pk: bool,
        **kw: object,
    ) -> sa.Table:
        vt = sa.Table(
            version_table,
            sa.MetaData(),
            sa.Column(
                "version_num",
                sa.String(ALEMBIC_VERSION_NUM_MAXLEN),
                nullable=False,
            ),
            schema=version_table_schema,
        )
        if version_table_pk:
            vt.append_constraint(
                sa.PrimaryKeyConstraint(
                    "version_num", name=f"{version_table}_pkc"
                )
            )
        return vt


def _ensure_alembic_version_num_len(connection: sa.Connection) -> None:
    """兼容已存在的 PostgreSQL 数据库：自动扩容版本表字段长度。

    - 新库：通过 `WayfarerPostgresqlImpl.version_table_impl()` 创建即为 255，无需处理
    - 老库：若 `alembic_version.version_num` 仍是 VARCHAR(32)，则迁移到长 revision id 时会失败
    """

    if connection.dialect.name != "postgresql":
        return

    # 约定：本项目版本表默认在 public schema（未显式指定 version_table_schema）。
    # 注意：SQLAlchemy 2.x 默认会在首次 execute 时 autobegin 一个事务；
    # 若这里不显式提交，后续 Alembic 的事务边界可能被“外层事务”包住，
    # 导致迁移看似执行成功但在连接关闭时被回滚。
    with connection.begin():
        connection.execute(
            sa.text(
                f"""
DO $$
DECLARE
  cur_len integer;
BEGIN
  SELECT character_maximum_length
  INTO cur_len
  FROM information_schema.columns
  WHERE table_schema = 'public'
    AND table_name = 'alembic_version'
    AND column_name = 'version_num';

  IF cur_len IS NOT NULL AND cur_len < {ALEMBIC_VERSION_NUM_MAXLEN} THEN
    EXECUTE 'ALTER TABLE public.alembic_version ALTER COLUMN version_num TYPE VARCHAR({ALEMBIC_VERSION_NUM_MAXLEN})';
  END IF;
END $$;
"""
            )
        )


def _make_sync_url(url: str) -> str:
    """将运行时的异步 DB URL 转换为 Alembic 可用的同步 URL。"""

    u = sa.engine.make_url(url)
    if u.drivername == "sqlite+aiosqlite":
        u = u.set(drivername="sqlite")
    elif u.drivername == "postgresql+asyncpg":
        u = u.set(drivername="postgresql+psycopg")
    # 重要：
    # SQLAlchemy 的 URL 在 str() 时会默认把密码掩码成 "***"，
    # 但 Alembic 需要真实连接串才能连上数据库，因此这里必须关闭掩码。
    return u.render_as_string(hide_password=False)


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
        _ensure_alembic_version_num_len(connection)
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
