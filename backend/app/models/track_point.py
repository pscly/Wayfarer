from __future__ import annotations

from typing import TYPE_CHECKING

import datetime as dt
import uuid

import sqlalchemy as sa
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, GUID, utcnow

if TYPE_CHECKING:
    from .user import User


class TrackPoint(Base):
    __tablename__ = "track_points"

    id: Mapped[int] = mapped_column(
        sa.BigInteger().with_variant(sa.Integer, "sqlite"),
        primary_key=True,
        autoincrement=True,
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        sa.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    recorded_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, index=True
    )

    # WGS84 required.
    latitude: Mapped[float] = mapped_column(sa.Float, nullable=False)
    longitude: Mapped[float] = mapped_column(sa.Float, nullable=False)

    # Optional GCJ-02 (for AMap rendering consistency).
    gcj02_latitude: Mapped[float | None] = mapped_column(sa.Float, nullable=True)
    gcj02_longitude: Mapped[float | None] = mapped_column(sa.Float, nullable=True)

    altitude: Mapped[float | None] = mapped_column(sa.Float, nullable=True)
    accuracy: Mapped[float | None] = mapped_column(sa.Float, nullable=True)
    speed: Mapped[float | None] = mapped_column(sa.Float, nullable=True)

    step_count: Mapped[int | None] = mapped_column(sa.Integer, nullable=True)
    step_delta: Mapped[int | None] = mapped_column(sa.Integer, nullable=True)
    activity_type: Mapped[int | None] = mapped_column(sa.SmallInteger, nullable=True)

    is_dirty: Mapped[bool] = mapped_column(
        sa.Boolean, nullable=False, server_default=sa.false()
    )
    weather_snapshot: Mapped[dict[str, object] | None] = mapped_column(
        sa.JSON, nullable=True
    )

    # Idempotency key (client-generated UUID), MUST be UNIQUE per user.
    client_point_id: Mapped[uuid.UUID] = mapped_column(GUID(), nullable=False)

    # Optional: weak dedupe helper / coord metadata (defined in plan-supplement).
    geom_hash: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    coord_source: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    coord_transform_status: Mapped[str | None] = mapped_column(sa.Text, nullable=True)

    # Optional downgrade storage when PostGIS is unavailable.
    geom_wkt: Mapped[str | None] = mapped_column(sa.Text, nullable=True)

    created_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow
    )
    updated_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )

    user: Mapped["User"] = relationship(back_populates="track_points")

    __table_args__ = (
        sa.UniqueConstraint(
            "user_id",
            "client_point_id",
            name="uq_track_points_user_client_point_id",
        ),
        sa.Index("ix_track_points_user_recorded_at", "user_id", "recorded_at"),
    )
