from __future__ import annotations

import datetime as dt

import sqlalchemy as sa
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, utcnow


class WeatherCache(Base):
    __tablename__ = "weather_cache"

    id: Mapped[int] = mapped_column(sa.Integer, primary_key=True, autoincrement=True)

    geohash_5: Mapped[str] = mapped_column(sa.String(5), nullable=False)
    hour_time: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False
    )

    payload: Mapped[dict[str, object]] = mapped_column(sa.JSON, nullable=False)

    created_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow
    )
    updated_at: Mapped[dt.datetime] = mapped_column(
        sa.DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )

    __table_args__ = (
        sa.UniqueConstraint(
            "geohash_5", "hour_time", name="uq_weather_cache_geohash_5_hour_time"
        ),
        sa.Index("ix_weather_cache_geohash_5_hour_time", "geohash_5", "hour_time"),
    )
