from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from typing import Any

import sqlalchemy as sa
from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.errors import APIError
from app.db.session import get_db
from app.models.track_edit import TrackEdit
from app.models.track_point import TrackPoint
from app.models.user import User


router = APIRouter(prefix="/v1/stats", tags=["stats"])


def _normalize_to_utc(value: dt.datetime) -> dt.datetime:
    # Store tz-aware UTC timestamps everywhere.
    if value.tzinfo is None:
        return value.replace(tzinfo=dt.timezone.utc)
    return value.astimezone(dt.timezone.utc)


def _isoformat_z(value: dt.datetime) -> str:
    s = value.isoformat()
    if s.endswith("+00:00"):
        return s.removesuffix("+00:00") + "Z"
    return s


def _days_inclusive(start_day: dt.date, end_day: dt.date) -> list[dt.date]:
    if end_day < start_day:
        return []
    out: list[dt.date] = []
    cur = start_day
    while cur <= end_day:
        out.append(cur)
        cur = cur + dt.timedelta(days=1)
    return out


def _hours_inclusive(start_hour: dt.datetime, end_hour: dt.datetime) -> list[dt.datetime]:
    if end_hour < start_hour:
        return []
    out: list[dt.datetime] = []
    cur = start_hour
    while cur <= end_hour:
        out.append(cur)
        cur = cur + dt.timedelta(hours=1)
    return out


@dataclass(frozen=True)
class _AggRow:
    recorded_at: dt.datetime
    step_delta: int | None


class StepsDailyItem(BaseModel):
    day: str
    steps: int


class StepsDailyResponse(BaseModel):
    items: list[StepsDailyItem]


class StepsHourlyItem(BaseModel):
    hour_start: str
    steps: int


class StepsHourlyResponse(BaseModel):
    items: list[StepsHourlyItem]


def _tracks_excluding_deleted_edits_stmt(
    *,
    user_id: Any,
    start_utc: dt.datetime,
    end_utc: dt.datetime,
) -> sa.sql.Select:
    # Exclude points covered by active DELETE_RANGE edits (correlated NOT EXISTS).
    edit_match = (
        sa.select(1)
        .select_from(TrackEdit)
        .where(
            TrackEdit.user_id == user_id,
            TrackEdit.type == "DELETE_RANGE",
            TrackEdit.canceled_at.is_(None),
            TrackPoint.recorded_at >= TrackEdit.start_at,
            TrackPoint.recorded_at <= TrackEdit.end_at,
        )
        .correlate(TrackPoint)
    )

    return (
        sa.select(TrackPoint.recorded_at, TrackPoint.step_delta)
        .where(
            TrackPoint.user_id == user_id,
            TrackPoint.recorded_at >= start_utc,
            TrackPoint.recorded_at <= end_utc,
            ~sa.exists(edit_match),
        )
        .order_by(TrackPoint.recorded_at.asc())
    )


@router.get("/steps/daily", response_model=StepsDailyResponse)
async def steps_daily(
    *,
    start: dt.datetime = Query(..., description="UTC ISO8601 start time"),
    end: dt.datetime = Query(..., description="UTC ISO8601 end time"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> StepsDailyResponse:
    start_utc = _normalize_to_utc(start)
    end_utc = _normalize_to_utc(end)
    if start_utc >= end_utc:
        raise APIError(
            code="STATS_STEPS_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )

    stmt = _tracks_excluding_deleted_edits_stmt(
        user_id=user.id, start_utc=start_utc, end_utc=end_utc
    )
    rows_raw = (await db.execute(stmt)).all()
    rows = [_AggRow(recorded_at=r[0], step_delta=r[1]) for r in rows_raw]

    steps_by_day: dict[str, int] = {}
    for r in rows:
        t = _normalize_to_utc(r.recorded_at)
        key = t.date().isoformat()
        steps_by_day[key] = steps_by_day.get(key, 0) + int(r.step_delta or 0)

    items: list[StepsDailyItem] = []
    for d in _days_inclusive(start_utc.date(), end_utc.date()):
        key = d.isoformat()
        items.append(StepsDailyItem(day=key, steps=int(steps_by_day.get(key, 0))))

    return StepsDailyResponse(items=items)


@router.get("/steps/hourly", response_model=StepsHourlyResponse)
async def steps_hourly(
    *,
    start: dt.datetime = Query(..., description="UTC ISO8601 start time"),
    end: dt.datetime = Query(..., description="UTC ISO8601 end time"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> StepsHourlyResponse:
    start_utc = _normalize_to_utc(start)
    end_utc = _normalize_to_utc(end)
    if start_utc >= end_utc:
        raise APIError(
            code="STATS_STEPS_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )

    stmt = _tracks_excluding_deleted_edits_stmt(
        user_id=user.id, start_utc=start_utc, end_utc=end_utc
    )
    rows_raw = (await db.execute(stmt)).all()
    rows = [_AggRow(recorded_at=r[0], step_delta=r[1]) for r in rows_raw]

    steps_by_hour: dict[str, int] = {}
    for r in rows:
        t = _normalize_to_utc(r.recorded_at).replace(minute=0, second=0, microsecond=0)
        key = _isoformat_z(t)
        steps_by_hour[key] = steps_by_hour.get(key, 0) + int(r.step_delta or 0)

    start_hour = start_utc.replace(minute=0, second=0, microsecond=0)
    end_hour = end_utc.replace(minute=0, second=0, microsecond=0)

    items: list[StepsHourlyItem] = []
    for h in _hours_inclusive(start_hour, end_hour):
        key = _isoformat_z(h)
        items.append(StepsHourlyItem(hour_start=key, steps=int(steps_by_hour.get(key, 0))))

    return StepsHourlyResponse(items=items)
