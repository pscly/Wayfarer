from __future__ import annotations

import datetime as dt
import uuid

import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import DeclarativeBase
from sqlalchemy.types import TypeDecorator


def utcnow() -> dt.datetime:
    # Use tz-aware UTC timestamps everywhere.
    return dt.datetime.now(dt.timezone.utc)


class GUID(TypeDecorator[uuid.UUID]):
    """Platform-independent UUID type.

    - PostgreSQL: UUID(as_uuid=True)
    - Others (e.g. SQLite): stored as VARCHAR(36)
    """

    cache_ok = True
    impl = sa.String(36)

    def load_dialect_impl(self, dialect: sa.engine.Dialect) -> sa.types.TypeEngine:
        if dialect.name == "postgresql":
            return dialect.type_descriptor(PG_UUID(as_uuid=True))
        return dialect.type_descriptor(sa.String(36))

    def process_bind_param(self, value: object, dialect: sa.engine.Dialect) -> object:
        if value is None:
            return None
        if dialect.name == "postgresql":
            return value if isinstance(value, uuid.UUID) else uuid.UUID(str(value))
        # SQLite stores UUIDs as strings.
        return (
            str(value) if isinstance(value, uuid.UUID) else str(uuid.UUID(str(value)))
        )

    def process_result_value(
        self, value: object, dialect: sa.engine.Dialect
    ) -> uuid.UUID | None:
        if value is None:
            return None
        return value if isinstance(value, uuid.UUID) else uuid.UUID(str(value))


class Base(DeclarativeBase):
    """Declarative base for all ORM models."""

    # Ensure deterministic constraint/index names (useful for Alembic + DB portability).
    metadata = sa.MetaData(
        naming_convention={
            "ix": "ix_%(table_name)s_%(column_0_N_name)s",
            "uq": "uq_%(table_name)s_%(column_0_N_name)s",
            "ck": "ck_%(table_name)s_%(constraint_name)s",
            "fk": "fk_%(table_name)s_%(column_0_N_name)s_%(referred_table_name)s",
            "pk": "pk_%(table_name)s",
        }
    )
