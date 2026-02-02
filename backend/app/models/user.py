from __future__ import annotations

from typing import TYPE_CHECKING

import datetime as dt
import uuid

import sqlalchemy as sa
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, GUID, utcnow

if TYPE_CHECKING:
    from .export_job import ExportJob
    from .life_event import LifeEvent
    from .refresh_token import RefreshToken
    from .track_edit import TrackEdit
    from .track_point import TrackPoint


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid.uuid4)

    # Email is optional; login uses username by default.
    # Keep UNIQUE so if provided it cannot collide.
    email: Mapped[str | None] = mapped_column(
        sa.Text,
        nullable=True,
        unique=True,
        default=None,
    )

    # Keep plan.md compatibility: username stays UNIQUE.
    username: Mapped[str] = mapped_column(sa.Text, nullable=False, unique=True)
    hashed_password: Mapped[str] = mapped_column(sa.Text, nullable=False)

    # First registered user becomes admin; subsequent users default to non-admin.
    is_admin: Mapped[bool] = mapped_column(
        sa.Boolean,
        nullable=False,
        default=False,
        server_default=sa.text("FALSE"),
    )

    settings: Mapped[dict[str, object] | None] = mapped_column(sa.JSON, nullable=True)

    created_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow
    )
    updated_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )

    track_points: Mapped[list["TrackPoint"]] = relationship(back_populates="user")
    life_events: Mapped[list["LifeEvent"]] = relationship(back_populates="user")
    refresh_tokens: Mapped[list["RefreshToken"]] = relationship(back_populates="user")
    export_jobs: Mapped[list["ExportJob"]] = relationship(back_populates="user")
    track_edits: Mapped[list["TrackEdit"]] = relationship(back_populates="user")
