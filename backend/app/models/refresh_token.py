from __future__ import annotations

from typing import TYPE_CHECKING

import datetime as dt
import uuid

import sqlalchemy as sa
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, GUID

if TYPE_CHECKING:
    from .user import User


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id: Mapped[uuid.UUID] = mapped_column(GUID(), primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        GUID(),
        sa.ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )

    family_id: Mapped[uuid.UUID] = mapped_column(GUID(), nullable=False, index=True)
    token_hash: Mapped[str] = mapped_column(sa.Text, nullable=False, index=True)

    issued_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )
    expires_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )
    revoked_at: Mapped[dt.datetime | None] = mapped_column(
        sa.DateTime(timezone=True), nullable=True
    )
    replaced_by: Mapped[uuid.UUID | None] = mapped_column(GUID(), nullable=True)

    user_agent: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    ip: Mapped[str | None] = mapped_column(sa.Text, nullable=True)
    created_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )

    user: Mapped["User"] = relationship(back_populates="refresh_tokens")

    __table_args__ = (
        sa.Index("ix_refresh_tokens_user_created_at", "user_id", "created_at"),
    )
