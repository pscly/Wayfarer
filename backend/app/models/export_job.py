from __future__ import annotations

from typing import TYPE_CHECKING

import datetime as dt
import uuid

import sqlalchemy as sa
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, GUID, utcnow

if TYPE_CHECKING:
    from .user import User


class ExportJob(Base):
    __tablename__ = "export_jobs"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        sa.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )

    state: Mapped[str] = mapped_column(sa.Text, nullable=False)
    format: Mapped[str] = mapped_column(sa.Text, nullable=False)
    include_weather: Mapped[bool] = mapped_column(sa.Boolean, nullable=False)

    start_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )
    end_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )
    timezone: Mapped[str] = mapped_column(sa.Text, nullable=False)

    artifact_path: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    error_code: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    error_message: Mapped[str | None] = mapped_column(sa.Text, nullable=True)

    created_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow
    )
    updated_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )
    finished_at: Mapped[dt.datetime | None] = mapped_column(
        sa.DateTime(timezone=True), nullable=True
    )

    user: Mapped["User"] = relationship(back_populates="export_jobs")

    __table_args__ = (
        sa.Index("ix_export_jobs_user_created", "user_id", "created_at"),
        sa.Index("ix_export_jobs_user_state", "user_id", "state"),
    )
