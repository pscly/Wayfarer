from __future__ import annotations

import datetime as dt
import uuid
from typing import Any

import sqlalchemy as sa
from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.errors import APIError
from app.db.base import utcnow
from app.db.session import get_db
from app.models.life_event import LifeEvent
from app.models.user import User


router = APIRouter(prefix="/v1/life-events", tags=["life-events"])


def _normalize_to_utc(value: dt.datetime) -> dt.datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=dt.timezone.utc)
    return value.astimezone(dt.timezone.utc)


def _isoformat_z(value: dt.datetime) -> str:
    s = value.isoformat()
    if s.endswith("+00:00"):
        return s.removesuffix("+00:00") + "Z"
    return s


class LifeEventItem(BaseModel):
    id: str
    event_type: str
    start_at: str
    end_at: str

    location_name: str | None = None
    manual_note: str | None = None

    latitude: float | None = None
    longitude: float | None = None
    gcj02_latitude: float | None = None
    gcj02_longitude: float | None = None
    coord_source: str | None = None
    coord_transform_status: str | None = None
    payload_json: dict[str, Any] | None = None

    created_at: str | None = None
    updated_at: str | None = None


class LifeEventListResponse(BaseModel):
    items: list[LifeEventItem]


class LifeEventCreateRequest(BaseModel):
    event_type: str
    start_at: dt.datetime
    end_at: dt.datetime

    location_name: str | None = None
    manual_note: str | None = None

    latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    longitude: float | None = Field(default=None, ge=-180.0, le=180.0)
    gcj02_latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    gcj02_longitude: float | None = Field(default=None, ge=-180.0, le=180.0)
    coord_source: str | None = None
    coord_transform_status: str | None = None
    payload_json: dict[str, Any] | None = None


class LifeEventUpdateRequest(BaseModel):
    # Partial update allowed; use model_fields_set to distinguish omitted vs null.
    event_type: str | None = None
    start_at: dt.datetime | None = None
    end_at: dt.datetime | None = None

    location_name: str | None = None
    manual_note: str | None = None

    latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    longitude: float | None = Field(default=None, ge=-180.0, le=180.0)
    gcj02_latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    gcj02_longitude: float | None = Field(default=None, ge=-180.0, le=180.0)
    coord_source: str | None = None
    coord_transform_status: str | None = None
    payload_json: dict[str, Any] | None = None


class LifeEventDeleteResponse(BaseModel):
    id: str
    status: str


def _to_item(e: LifeEvent) -> LifeEventItem:
    return LifeEventItem(
        id=str(e.id),
        event_type=str(e.event_type),
        start_at=_isoformat_z(_normalize_to_utc(e.start_at)),
        end_at=_isoformat_z(_normalize_to_utc(e.end_at)),
        location_name=e.location_name,
        manual_note=e.manual_note,
        latitude=(float(e.latitude) if e.latitude is not None else None),
        longitude=(float(e.longitude) if e.longitude is not None else None),
        gcj02_latitude=(
            float(e.gcj02_latitude) if e.gcj02_latitude is not None else None
        ),
        gcj02_longitude=(
            float(e.gcj02_longitude) if e.gcj02_longitude is not None else None
        ),
        coord_source=e.coord_source,
        coord_transform_status=e.coord_transform_status,
        payload_json=(dict(e.payload_json) if e.payload_json is not None else None),
        created_at=_isoformat_z(_normalize_to_utc(e.created_at))
        if e.created_at
        else None,
        updated_at=_isoformat_z(_normalize_to_utc(e.updated_at))
        if e.updated_at
        else None,
    )


@router.get("", response_model=LifeEventListResponse)
async def list_life_events(
    *,
    start: dt.datetime | None = Query(
        default=None, description="UTC ISO8601 start time"
    ),
    end: dt.datetime | None = Query(default=None, description="UTC ISO8601 end time"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LifeEventListResponse:
    start_utc = _normalize_to_utc(start) if start is not None else None
    end_utc = _normalize_to_utc(end) if end is not None else None
    if start_utc is not None and end_utc is not None and start_utc >= end_utc:
        raise APIError(
            code="LIFE_EVENT_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )

    stmt = sa.select(LifeEvent).where(LifeEvent.user_id == user.id)

    # Time window filter: return events overlapping [start, end].
    if start_utc is not None:
        stmt = stmt.where(LifeEvent.end_at >= start_utc)
    if end_utc is not None:
        stmt = stmt.where(LifeEvent.start_at <= end_utc)

    stmt = stmt.order_by(LifeEvent.start_at.asc())
    rows = (await db.execute(stmt)).scalars().all()
    return LifeEventListResponse(items=[_to_item(e) for e in rows])


@router.post("", response_model=LifeEventItem, status_code=201)
async def create_life_event(
    payload: LifeEventCreateRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LifeEventItem:
    start_utc = _normalize_to_utc(payload.start_at)
    end_utc = _normalize_to_utc(payload.end_at)
    if start_utc >= end_utc:
        raise APIError(
            code="LIFE_EVENT_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )

    now = utcnow()
    e = LifeEvent(
        user_id=user.id,
        event_type=str(payload.event_type),
        start_at=start_utc,
        end_at=end_utc,
        location_name=payload.location_name,
        manual_note=payload.manual_note,
        latitude=payload.latitude,
        longitude=payload.longitude,
        gcj02_latitude=payload.gcj02_latitude,
        gcj02_longitude=payload.gcj02_longitude,
        coord_source=payload.coord_source,
        coord_transform_status=payload.coord_transform_status,
        payload_json=payload.payload_json,
        created_at=now,
        updated_at=now,
    )
    db.add(e)
    await db.commit()
    await db.refresh(e)
    return _to_item(e)


@router.put("/{id}", response_model=LifeEventItem)
async def update_life_event(
    id: uuid.UUID,
    payload: LifeEventUpdateRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LifeEventItem:
    e = (
        await db.execute(
            sa.select(LifeEvent).where(LifeEvent.id == id, LifeEvent.user_id == user.id)
        )
    ).scalar_one_or_none()
    if e is None:
        raise APIError(
            code="LIFE_EVENT_NOT_FOUND",
            message="LifeEvent not found",
            status_code=404,
        )

    fields = payload.model_fields_set
    if "event_type" in fields:
        if payload.event_type is None:
            raise APIError(
                code="LIFE_EVENT_INVALID_TYPE",
                message="event_type cannot be null",
                status_code=400,
            )
        e.event_type = str(payload.event_type)
    if "location_name" in fields:
        e.location_name = payload.location_name
    if "manual_note" in fields:
        e.manual_note = payload.manual_note
    if "latitude" in fields:
        e.latitude = payload.latitude
    if "longitude" in fields:
        e.longitude = payload.longitude
    if "gcj02_latitude" in fields:
        e.gcj02_latitude = payload.gcj02_latitude
    if "gcj02_longitude" in fields:
        e.gcj02_longitude = payload.gcj02_longitude
    if "coord_source" in fields:
        e.coord_source = payload.coord_source
    if "coord_transform_status" in fields:
        e.coord_transform_status = payload.coord_transform_status
    if "payload_json" in fields:
        e.payload_json = payload.payload_json

    # Validate and apply time updates together.
    new_start = e.start_at
    new_end = e.end_at
    if "start_at" in fields:
        if payload.start_at is None:
            raise APIError(
                code="LIFE_EVENT_INVALID_RANGE",
                message="start_at cannot be null",
                status_code=400,
            )
        new_start = _normalize_to_utc(payload.start_at)
    if "end_at" in fields:
        if payload.end_at is None:
            raise APIError(
                code="LIFE_EVENT_INVALID_RANGE",
                message="end_at cannot be null",
                status_code=400,
            )
        new_end = _normalize_to_utc(payload.end_at)
    if new_start >= new_end:
        raise APIError(
            code="LIFE_EVENT_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )
    e.start_at = new_start
    e.end_at = new_end

    e.updated_at = utcnow()
    await db.commit()
    await db.refresh(e)
    return _to_item(e)


@router.delete("/{id}", response_model=LifeEventDeleteResponse)
async def delete_life_event(
    id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LifeEventDeleteResponse:
    e = (
        await db.execute(
            sa.select(LifeEvent).where(LifeEvent.id == id, LifeEvent.user_id == user.id)
        )
    ).scalar_one_or_none()
    if e is None:
        raise APIError(
            code="LIFE_EVENT_NOT_FOUND",
            message="LifeEvent not found",
            status_code=404,
        )

    await db.delete(e)
    await db.commit()
    return LifeEventDeleteResponse(id=str(id), status="DELETED")
