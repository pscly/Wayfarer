"""Add PostGIS geom column and GIST index when available.

Revision ID: 0002_optional_postgis_geom
Revises: 0001_core_tables
Create Date: 2026-01-30

"""

from __future__ import annotations

# pyright: ignore

import sqlalchemy as sa
from alembic import context, op  # type: ignore[attr-defined]


# revision identifiers, used by Alembic.
revision = "0002_optional_postgis_geom"
down_revision = "0001_core_tables"
branch_labels = None
depends_on = None


def _is_postgresql() -> bool:
    ctx = context.get_context()
    dialect = getattr(ctx, "dialect", None)
    return bool(dialect) and dialect.name == "postgresql"


def _has_postgis(conn: sa.engine.Connection) -> bool:
    try:
        return bool(
            conn.execute(
                sa.text(
                    "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname='postgis')"
                )
            ).scalar()
        )
    except Exception:
        return False


def upgrade() -> None:
    if not _is_postgresql():
        return

    conn = op.get_bind()
    if not _has_postgis(conn):
        return

    # Add geometry column (WGS84) and index.
    op.execute("ALTER TABLE track_points ADD COLUMN geom geometry(POINT,4326)")
    op.execute(
        "CREATE INDEX ix_track_points_geom_gist ON track_points USING GIST (geom)"
    )

    # Best-effort backfill for existing data.
    op.execute(
        "UPDATE track_points "
        "SET geom = ST_SetSRID(ST_MakePoint(longitude, latitude), 4326) "
        "WHERE geom IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL"
    )


def downgrade() -> None:
    if not _is_postgresql():
        return

    conn = op.get_bind()
    if not _has_postgis(conn):
        return

    op.execute("DROP INDEX IF EXISTS ix_track_points_geom_gist")
    op.execute("ALTER TABLE track_points DROP COLUMN IF EXISTS geom")
