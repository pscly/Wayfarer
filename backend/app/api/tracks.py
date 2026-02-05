from __future__ import annotations

import datetime as dt
import hashlib
import logging
import uuid
from typing import Any, cast

import sqlalchemy as sa
from fastapi import APIRouter, Depends, Query, Response
from pydantic import BaseModel, Field, ValidationError
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.errors import APIError
from app.db.base import utcnow
from app.db.session import get_db
from app.models.track_edit import TrackEdit
from app.models.track_point import TrackPoint
from app.models.user import User
from app.tasks.anti_cheat import audit_track_segment_task
from app.tasks.life_event import recompute_life_events_task


router = APIRouter(prefix="/v1/tracks", tags=["tracks"])


logger = logging.getLogger(__name__)


class TrackPointItemIn(BaseModel):
    # client-generated UUID for idempotency (UNIQUE per user)
    client_point_id: uuid.UUID
    recorded_at: dt.datetime

    # WGS84 required.
    latitude: float = Field(ge=-90.0, le=90.0)
    longitude: float = Field(ge=-180.0, le=180.0)
    accuracy: float = Field(gt=0)

    # Optional GCJ-02.
    gcj02_latitude: float | None = Field(default=None, ge=-90.0, le=90.0)
    gcj02_longitude: float | None = Field(default=None, ge=-180.0, le=180.0)

    altitude: float | None = None
    speed: float | None = Field(default=None, ge=0)

    step_count: int | None = Field(default=None, ge=0)
    step_delta: int | None = Field(default=None, ge=0)
    activity_type: int | None = None

    coord_source: str | None = None
    coord_transform_status: str | None = None
    geom_wkt: str | None = None


class TrackBatchRequest(BaseModel):
    # Keep items untyped so one bad item doesn't 422 the whole batch.
    items: list[Any] = Field(default_factory=list)


class TrackBatchRejectedItem(BaseModel):
    client_point_id: str | None
    reason_code: str
    message: str


class TrackBatchResponse(BaseModel):
    accepted_ids: list[str]
    rejected: list[TrackBatchRejectedItem]


class TrackQueryItem(BaseModel):
    client_point_id: str
    recorded_at: str
    latitude: float
    longitude: float
    accuracy: float | None
    is_dirty: bool

    gcj02_latitude: float | None = None
    gcj02_longitude: float | None = None
    step_count: int | None = None
    step_delta: int | None = None


class TrackQueryResponse(BaseModel):
    items: list[TrackQueryItem]


class TrackEditCreateRequest(BaseModel):
    # Use manual validation so unsupported types return APIError (not 422).
    type: str
    start: dt.datetime
    end: dt.datetime


class TrackEditCreateResponse(BaseModel):
    edit_id: str
    applied_count: int


class TrackEditListItem(BaseModel):
    edit_id: str
    type: str
    start: str
    end: str
    created_at: str
    canceled_at: str | None


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


def _compute_geom_hash(
    *, recorded_at: dt.datetime, latitude: float, longitude: float
) -> str:
    # Weak dedupe helper (no new deps): stable hash from rounded coords only.
    # Keep signature stable (recorded_at is intentionally ignored here).
    lat_r = round(latitude, 5)
    lon_r = round(longitude, 5)
    base = f"{lat_r:.5f}|{lon_r:.5f}"
    return hashlib.sha256(base.encode("utf-8")).hexdigest()


def _dialect_name(db: AsyncSession) -> str:
    bind = db.bind or db.get_bind()
    if bind is None or getattr(bind, "dialect", None) is None:
        return ""
    return str(bind.dialect.name or "")


def _idempotent_insert_stmt(
    *, dialect: str, rows: list[dict[str, Any]]
) -> sa.sql.Insert:
    if dialect == "postgresql":
        stmt = pg_insert(TrackPoint).values(rows)
        return stmt.on_conflict_do_nothing(
            index_elements=["user_id", "client_point_id"]
        )
    if dialect == "sqlite":
        return sqlite_insert(TrackPoint).values(rows).prefix_with("OR IGNORE")
    raise APIError(
        code="TRACKS_UNSUPPORTED_DIALECT",
        message=f"Unsupported database dialect: {dialect!r}",
        status_code=500,
    )


@router.post("/batch", response_model=TrackBatchResponse)
async def batch_upload(
    payload: TrackBatchRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TrackBatchResponse:
    rejected: list[TrackBatchRejectedItem] = []
    accepted_ids: list[str] = []
    valid_rows: list[dict[str, Any]] = []
    valid_client_ids: list[str] = []
    batch_start_at: dt.datetime | None = None
    batch_end_at: dt.datetime | None = None

    for raw in payload.items:
        try:
            item = TrackPointItemIn.model_validate(raw)
        except ValidationError as exc:
            client_point_id = None
            if isinstance(raw, dict):
                raw_cpid = raw.get("client_point_id")
                if raw_cpid is not None:
                    client_point_id = str(raw_cpid)
            msg = "; ".join(err.get("msg", "invalid") for err in exc.errors())
            rejected.append(
                TrackBatchRejectedItem(
                    client_point_id=client_point_id,
                    reason_code="TRACK_BATCH_ITEM_INVALID",
                    message=msg,
                )
            )
            continue

        recorded_at = _normalize_to_utc(item.recorded_at)
        if batch_start_at is None or recorded_at < batch_start_at:
            batch_start_at = recorded_at
        if batch_end_at is None or recorded_at > batch_end_at:
            batch_end_at = recorded_at
        valid_rows.append(
            {
                "user_id": user.id,
                "client_point_id": item.client_point_id,
                "recorded_at": recorded_at,
                "latitude": item.latitude,
                "longitude": item.longitude,
                "gcj02_latitude": item.gcj02_latitude,
                "gcj02_longitude": item.gcj02_longitude,
                "altitude": item.altitude,
                "accuracy": item.accuracy,
                "speed": item.speed,
                "step_count": item.step_count,
                "step_delta": item.step_delta,
                "activity_type": item.activity_type,
                "geom_hash": _compute_geom_hash(
                    recorded_at=recorded_at,
                    latitude=item.latitude,
                    longitude=item.longitude,
                ),
                "coord_source": item.coord_source,
                "coord_transform_status": item.coord_transform_status,
                "geom_wkt": item.geom_wkt,
            }
        )
        valid_client_ids.append(str(item.client_point_id))

    if not valid_rows:
        return TrackBatchResponse(accepted_ids=[], rejected=rejected)

    # The task args must be JSON-serializable (safe for eager and non-eager).
    audit_start_at = _isoformat_z(
        _normalize_to_utc(batch_start_at or valid_rows[0]["recorded_at"])
    )
    audit_end_at = _isoformat_z(
        _normalize_to_utc(batch_end_at or valid_rows[0]["recorded_at"])
    )

    dialect = _dialect_name(db)
    stmt = _idempotent_insert_stmt(dialect=dialect, rows=valid_rows)

    # Fast path: one bulk insert for the whole batch.
    try:
        await db.execute(stmt)
        await db.commit()
        try:
            audit_track_segment_task.delay(str(user.id), audit_start_at, audit_end_at)
        except Exception:
            # Best-effort: never fail the upload response on audit enqueue.
            logger.warning("Failed to enqueue audit_track_segment_task", exc_info=True)
        try:
            cast(Any, recompute_life_events_task).delay(
                str(user.id),
                audit_start_at,
                audit_end_at,
            )
        except Exception:
            # Best-effort: never fail the upload response on life-event recompute.
            logger.warning(
                "Failed to enqueue recompute_life_events_task", exc_info=True
            )
        accepted_ids = valid_client_ids
        return TrackBatchResponse(accepted_ids=accepted_ids, rejected=rejected)
    except IntegrityError:
        # Fallback: isolate DB failures per item (duplicates should still be accepted).
        await db.rollback()

    accepted_ids = []
    for row, client_id in zip(valid_rows, valid_client_ids, strict=True):
        try:
            async with db.begin_nested():
                per_stmt = _idempotent_insert_stmt(dialect=dialect, rows=[row])
                await db.execute(per_stmt)
        except IntegrityError as exc:
            rejected.append(
                TrackBatchRejectedItem(
                    client_point_id=client_id,
                    reason_code="TRACK_BATCH_DB_ERROR",
                    message=str(exc.orig) if getattr(exc, "orig", None) else "DB error",
                )
            )
            continue
        accepted_ids.append(client_id)

    await db.commit()
    try:
        audit_track_segment_task.delay(str(user.id), audit_start_at, audit_end_at)
    except Exception:
        # Best-effort: never fail the upload response on audit enqueue.
        logger.warning("Failed to enqueue audit_track_segment_task", exc_info=True)
    try:
        cast(Any, recompute_life_events_task).delay(
            str(user.id),
            audit_start_at,
            audit_end_at,
        )
    except Exception:
        # Best-effort: never fail the upload response on life-event recompute.
        logger.warning("Failed to enqueue recompute_life_events_task", exc_info=True)
    return TrackBatchResponse(accepted_ids=accepted_ids, rejected=rejected)


@router.get("/query", response_model=TrackQueryResponse)
async def query_tracks(
    *,
    start: dt.datetime = Query(..., description="UTC ISO8601 start time"),
    end: dt.datetime = Query(..., description="UTC ISO8601 end time"),
    limit: int = Query(1000, ge=1, le=5000),
    offset: int = Query(0, ge=0),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TrackQueryResponse:
    start_utc = _normalize_to_utc(start)
    end_utc = _normalize_to_utc(end)
    if start_utc >= end_utc:
        raise APIError(
            code="TRACK_QUERY_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )

    # Exclude points covered by active DELETE_RANGE edits (correlated NOT EXISTS).
    edit_match = (
        sa.select(1)
        .select_from(TrackEdit)
        .where(
            TrackEdit.user_id == user.id,
            TrackEdit.type == "DELETE_RANGE",
            TrackEdit.canceled_at.is_(None),
            TrackPoint.recorded_at >= TrackEdit.start_at,
            TrackPoint.recorded_at <= TrackEdit.end_at,
        )
        .correlate(TrackPoint)
    )

    stmt = (
        sa.select(TrackPoint)
        .where(
            TrackPoint.user_id == user.id,
            TrackPoint.recorded_at >= start_utc,
            TrackPoint.recorded_at <= end_utc,
            ~sa.exists(edit_match),
        )
        .order_by(TrackPoint.recorded_at.asc())
        .limit(limit)
        .offset(offset)
    )

    rows = (await db.execute(stmt)).scalars().all()
    items = [
        TrackQueryItem(
            client_point_id=str(p.client_point_id),
            recorded_at=_isoformat_z(_normalize_to_utc(p.recorded_at)),
            latitude=float(p.latitude),
            longitude=float(p.longitude),
            accuracy=(float(p.accuracy) if p.accuracy is not None else None),
            is_dirty=bool(p.is_dirty),
            gcj02_latitude=(
                float(p.gcj02_latitude) if p.gcj02_latitude is not None else None
            ),
            gcj02_longitude=(
                float(p.gcj02_longitude) if p.gcj02_longitude is not None else None
            ),
            step_count=(int(p.step_count) if p.step_count is not None else None),
            step_delta=(int(p.step_delta) if p.step_delta is not None else None),
        )
        for p in rows
    ]
    return TrackQueryResponse(items=items)


@router.post("/edits", response_model=TrackEditCreateResponse)
async def create_edit(
    payload: TrackEditCreateRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TrackEditCreateResponse:
    if payload.type != "DELETE_RANGE":
        raise APIError(
            code="EDIT_UNSUPPORTED_TYPE",
            message="Unsupported edit type",
            status_code=400,
        )

    start_utc = _normalize_to_utc(payload.start)
    end_utc = _normalize_to_utc(payload.end)
    if start_utc >= end_utc:
        raise APIError(
            code="EDIT_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )

    # Count points that will be hidden by this edit (inclusive range).
    applied_count = int(
        (
            await db.execute(
                sa.select(sa.func.count())
                .select_from(TrackPoint)
                .where(
                    TrackPoint.user_id == user.id,
                    TrackPoint.recorded_at >= start_utc,
                    TrackPoint.recorded_at <= end_utc,
                )
            )
        ).scalar_one()
    )

    edit = TrackEdit(
        user_id=user.id,
        type="DELETE_RANGE",
        start_at=start_utc,
        end_at=end_utc,
        created_at=utcnow(),
        canceled_at=None,
        note=None,
    )
    db.add(edit)
    await db.commit()

    return TrackEditCreateResponse(edit_id=str(edit.id), applied_count=applied_count)


@router.get("/edits", response_model=list[TrackEditListItem])
async def list_edits(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[TrackEditListItem]:
    stmt = (
        sa.select(TrackEdit)
        .where(TrackEdit.user_id == user.id)
        .order_by(TrackEdit.created_at.desc())
    )
    edits = (await db.execute(stmt)).scalars().all()

    items: list[TrackEditListItem] = []
    for e in edits:
        canceled = None
        if e.canceled_at is not None:
            canceled = _isoformat_z(_normalize_to_utc(e.canceled_at))
        items.append(
            TrackEditListItem(
                edit_id=str(e.id),
                type=str(e.type),
                start=_isoformat_z(_normalize_to_utc(e.start_at)),
                end=_isoformat_z(_normalize_to_utc(e.end_at)),
                created_at=_isoformat_z(_normalize_to_utc(e.created_at)),
                canceled_at=canceled,
            )
        )
    return items


@router.delete("/edits/{id}", status_code=204)
async def cancel_edit(
    id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    edit = (
        await db.execute(
            sa.select(TrackEdit).where(TrackEdit.id == id, TrackEdit.user_id == user.id)
        )
    ).scalar_one_or_none()
    if edit is None:
        raise APIError(code="EDIT_NOT_FOUND", message="Edit not found", status_code=404)

    if edit.canceled_at is None:
        edit.canceled_at = utcnow()
        await db.commit()
    return Response(status_code=204)
