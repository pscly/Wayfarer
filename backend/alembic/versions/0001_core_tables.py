"""Create core tables (SQLite-friendly).

Revision ID: 0001_core_tables
Revises:
Create Date: 2026-01-30

"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql


# revision identifiers, used by Alembic.
revision = "0001_core_tables"
down_revision = None
branch_labels = None
depends_on = None


def _uuid_type() -> sa.types.TypeEngine:
    """Portable UUID column type.

    PostgreSQL gets a real UUID column; SQLite stores UUIDs as strings.
    """

    return sa.String(36).with_variant(postgresql.UUID(as_uuid=True), "postgresql")


def upgrade() -> None:
    uuid_t = _uuid_type()

    op.create_table(
        "users",
        sa.Column("id", uuid_t, primary_key=True, nullable=False),
        sa.Column("email", sa.Text(), nullable=False),
        sa.Column("username", sa.Text(), nullable=False),
        sa.Column("hashed_password", sa.Text(), nullable=False),
        sa.Column("settings", sa.JSON(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("email", name="uq_users_email"),
        sa.UniqueConstraint("username", name="uq_users_username"),
    )

    op.create_table(
        "track_points",
        sa.Column(
            "id",
            sa.BigInteger().with_variant(sa.Integer(), "sqlite"),
            primary_key=True,
            autoincrement=True,
            nullable=False,
        ),
        sa.Column("user_id", uuid_t, nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("latitude", sa.Float(), nullable=False),
        sa.Column("longitude", sa.Float(), nullable=False),
        sa.Column("gcj02_latitude", sa.Float(), nullable=True),
        sa.Column("gcj02_longitude", sa.Float(), nullable=True),
        sa.Column("altitude", sa.Float(), nullable=True),
        sa.Column("accuracy", sa.Float(), nullable=True),
        sa.Column("speed", sa.Float(), nullable=True),
        sa.Column("step_count", sa.Integer(), nullable=True),
        sa.Column("step_delta", sa.Integer(), nullable=True),
        sa.Column("activity_type", sa.SmallInteger(), nullable=True),
        sa.Column(
            "is_dirty", sa.Boolean(), nullable=False, server_default=sa.text("FALSE")
        ),
        sa.Column("weather_snapshot", sa.JSON(), nullable=True),
        sa.Column("client_point_id", uuid_t, nullable=False),
        sa.Column("geom_hash", sa.Text(), nullable=True),
        sa.Column("coord_source", sa.Text(), nullable=True),
        sa.Column("coord_transform_status", sa.Text(), nullable=True),
        sa.Column("geom_wkt", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.UniqueConstraint(
            "user_id",
            "client_point_id",
            name="uq_track_points_user_client_point_id",
        ),
    )
    op.create_index("ix_track_points_user_id", "track_points", ["user_id"])
    op.create_index("ix_track_points_recorded_at", "track_points", ["recorded_at"])
    op.create_index(
        "ix_track_points_user_recorded_at",
        "track_points",
        ["user_id", "recorded_at"],
    )

    op.create_table(
        "life_events",
        sa.Column("id", uuid_t, primary_key=True, nullable=False),
        sa.Column("user_id", uuid_t, nullable=False),
        sa.Column("event_type", sa.Text(), nullable=False),
        sa.Column("start_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("end_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("location_name", sa.Text(), nullable=True),
        sa.Column("manual_note", sa.Text(), nullable=True),
        sa.Column("latitude", sa.Float(), nullable=True),
        sa.Column("longitude", sa.Float(), nullable=True),
        sa.Column("gcj02_latitude", sa.Float(), nullable=True),
        sa.Column("gcj02_longitude", sa.Float(), nullable=True),
        sa.Column("coord_source", sa.Text(), nullable=True),
        sa.Column("coord_transform_status", sa.Text(), nullable=True),
        sa.Column("payload_json", sa.JSON(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
    )
    op.create_index("ix_life_events_user_id", "life_events", ["user_id"])
    op.create_index(
        "ix_life_events_user_time",
        "life_events",
        ["user_id", "start_at", "end_at"],
    )

    op.create_table(
        "weather_cache",
        sa.Column(
            "id",
            sa.Integer(),
            primary_key=True,
            autoincrement=True,
            nullable=False,
        ),
        sa.Column("geohash_5", sa.String(length=5), nullable=False),
        sa.Column("hour_time", sa.DateTime(timezone=True), nullable=False),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint(
            "geohash_5",
            "hour_time",
            name="uq_weather_cache_geohash_5_hour_time",
        ),
    )
    op.create_index(
        "ix_weather_cache_geohash_5_hour_time",
        "weather_cache",
        ["geohash_5", "hour_time"],
    )

    op.create_table(
        "refresh_tokens",
        sa.Column("id", uuid_t, primary_key=True, nullable=False),
        sa.Column("user_id", uuid_t, nullable=False),
        sa.Column("family_id", uuid_t, nullable=False),
        sa.Column("token_hash", sa.Text(), nullable=False),
        sa.Column("issued_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("replaced_by", uuid_t, nullable=True),
        sa.Column("user_agent", sa.Text(), nullable=True),
        sa.Column("ip", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
    )
    op.create_index("ix_refresh_tokens_user_id", "refresh_tokens", ["user_id"])
    op.create_index("ix_refresh_tokens_family_id", "refresh_tokens", ["family_id"])
    op.create_index("ix_refresh_tokens_token_hash", "refresh_tokens", ["token_hash"])
    op.create_index(
        "ix_refresh_tokens_user_created_at",
        "refresh_tokens",
        ["user_id", "created_at"],
    )

    op.create_table(
        "export_jobs",
        sa.Column("id", uuid_t, primary_key=True, nullable=False),
        sa.Column("user_id", uuid_t, nullable=False),
        sa.Column("state", sa.Text(), nullable=False),
        sa.Column("format", sa.Text(), nullable=False),
        sa.Column("include_weather", sa.Boolean(), nullable=False),
        sa.Column("start_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("end_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("timezone", sa.Text(), nullable=False),
        sa.Column("artifact_path", sa.Text(), nullable=True),
        sa.Column("error_code", sa.Text(), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
    )
    op.create_index("ix_export_jobs_user_id", "export_jobs", ["user_id"])
    op.create_index(
        "ix_export_jobs_user_created",
        "export_jobs",
        ["user_id", "created_at"],
    )
    op.create_index(
        "ix_export_jobs_user_state",
        "export_jobs",
        ["user_id", "state"],
    )

    op.create_table(
        "track_edits",
        sa.Column("id", uuid_t, primary_key=True, nullable=False),
        sa.Column("user_id", uuid_t, nullable=False),
        sa.Column("type", sa.Text(), nullable=False),
        sa.Column("start_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("end_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("canceled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("note", sa.Text(), nullable=True),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
    )
    op.create_index("ix_track_edits_user_id", "track_edits", ["user_id"])
    op.create_index(
        "ix_track_edits_user_time",
        "track_edits",
        ["user_id", "start_at", "end_at"],
    )
    op.create_index(
        "ix_track_edits_user_created",
        "track_edits",
        ["user_id", "created_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_track_edits_user_created", table_name="track_edits")
    op.drop_index("ix_track_edits_user_time", table_name="track_edits")
    op.drop_index("ix_track_edits_user_id", table_name="track_edits")
    op.drop_table("track_edits")

    op.drop_index("ix_export_jobs_user_state", table_name="export_jobs")
    op.drop_index("ix_export_jobs_user_created", table_name="export_jobs")
    op.drop_index("ix_export_jobs_user_id", table_name="export_jobs")
    op.drop_table("export_jobs")

    op.drop_index("ix_refresh_tokens_user_created_at", table_name="refresh_tokens")
    op.drop_index("ix_refresh_tokens_token_hash", table_name="refresh_tokens")
    op.drop_index("ix_refresh_tokens_family_id", table_name="refresh_tokens")
    op.drop_index("ix_refresh_tokens_user_id", table_name="refresh_tokens")
    op.drop_table("refresh_tokens")

    op.drop_index("ix_weather_cache_geohash_5_hour_time", table_name="weather_cache")
    op.drop_table("weather_cache")

    op.drop_index("ix_life_events_user_time", table_name="life_events")
    op.drop_index("ix_life_events_user_id", table_name="life_events")
    op.drop_table("life_events")

    op.drop_index("ix_track_points_user_recorded_at", table_name="track_points")
    op.drop_index("ix_track_points_recorded_at", table_name="track_points")
    op.drop_index("ix_track_points_user_id", table_name="track_points")
    op.drop_table("track_points")

    op.drop_table("users")
