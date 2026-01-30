from __future__ import annotations

from typing import TYPE_CHECKING

import datetime as dt
import uuid

import sqlalchemy as sa
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, GUID, utcnow

if TYPE_CHECKING:
    from .user import User


class TrackEdit(Base):
    __tablename__ = "track_edits"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        sa.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )

    type: Mapped[str] = mapped_column(sa.Text, nullable=False)
    start_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )
    end_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )

    created_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow
    )
    canceled_at: Mapped[dt.datetime | None] = mapped_column(
        sa.DateTime(timezone=True), nullable=True
    )
    note: Mapped[str | None] = mapped_column(sa.Text, nullable=True)

    user: Mapped["User"] = relationship(back_populates="track_edits")

    __table_args__ = (
        sa.Index("ix_track_edits_user_time", "user_id", "start_at", "end_at"),
        sa.Index("ix_track_edits_user_created", "user_id", "created_at"),
    )
