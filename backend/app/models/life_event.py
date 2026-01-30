from __future__ import annotations

from typing import TYPE_CHECKING

import datetime as dt
import uuid

import sqlalchemy as sa
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, GUID, utcnow

if TYPE_CHECKING:
    from .user import User


class LifeEvent(Base):
    __tablename__ = "life_events"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        sa.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )

    event_type: Mapped[str] = mapped_column(sa.Text, nullable=False)
    start_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )
    end_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )

    location_name: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    manual_note: Mapped[str | None] = mapped_column(sa.Text, nullable=True)

    # Recommended by API spec: store center point + optional payload_json.
    latitude: Mapped[float | None] = mapped_column(sa.Float, nullable=True)
    longitude: Mapped[float | None] = mapped_column(sa.Float, nullable=True)
    gcj02_latitude: Mapped[float | None] = mapped_column(sa.Float, nullable=True)
    gcj02_longitude: Mapped[float | None] = mapped_column(sa.Float, nullable=True)
    coord_source: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    coord_transform_status: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    payload_json: Mapped[dict[str, object] | None] = mapped_column(
        sa.JSON, nullable=True
    )

    created_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow
    )
    updated_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )

    user: Mapped["User"] = relationship(back_populates="life_events")

    __table_args__ = (
        sa.Index("ix_life_events_user_time", "user_id", "start_at", "end_at"),
    )
