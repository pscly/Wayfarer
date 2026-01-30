from __future__ import annotations

import datetime as dt
import io
import uuid
from pathlib import Path
from typing import Any, cast

import sqlalchemy as sa
from fastapi import APIRouter, Depends, Query, Response
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.core.errors import APIError
from app.core.settings import get_settings
from app.db.base import utcnow
from app.db.session import get_db
from app.models.export_job import ExportJob
from app.models.track_edit import TrackEdit
from app.models.track_point import TrackPoint
from app.models.user import User
from app.tasks.export import run_export_job_task


router = APIRouter(prefix="/v1/export", tags=["export"])


_FORMAT_CANONICAL: dict[str, str] = {
    "csv": "CSV",
    "gpx": "GPX",
    "geojson": "GeoJSON",
    "kml": "KML",
}


def _normalize_to_utc(value: dt.datetime) -> dt.datetime:
    # Store tz-aware UTC timestamps everywhere.
    if value.tzinfo is None:
        return value.replace(tzinfo=dt.timezone.utc)
    return value.astimezone(dt.timezone.utc)


def _normalize_format(value: str) -> str:
    key = value.strip().lower()
    canonical = _FORMAT_CANONICAL.get(key)
    if canonical is None:
        raise APIError(
            code="EXPORT_FORMAT_UNSUPPORTED",
            message="Unsupported export format",
            status_code=400,
            details={"format": value},
        )
    return canonical


def _user_settings(user: User) -> dict[str, object]:
    raw = getattr(user, "settings", None)
    return raw if isinstance(raw, dict) else {}


class ExportCreateRequest(BaseModel):
    start: dt.datetime
    end: dt.datetime
    format: str

    include_weather: bool | None = None
    timezone: str | None = Field(default=None, min_length=1, max_length=64)


class ExportCreateResponse(BaseModel):
    job_id: str


class ExportJobError(BaseModel):
    code: str
    message: str


class ExportJobStatusResponse(BaseModel):
    job_id: str
    state: str
    created_at: dt.datetime
    finished_at: dt.datetime | None
    format: str
    timezone: str
    artifact_path: str | None
    error: ExportJobError | None


def _artifact_abs_path(*, artifact_path: str) -> Path:
    settings = get_settings()
    return Path(settings.export_dir) / artifact_path


async def _enforce_concurrent_exports_limit(*, user: User, db: AsyncSession) -> None:
    settings = get_settings()
    stmt = (
        select(sa.func.count())
        .select_from(ExportJob)
        .where(
            ExportJob.user_id == user.id,
            ExportJob.state.in_(["CREATED", "RUNNING"]),
        )
    )
    active = int((await db.execute(stmt)).scalar_one() or 0)
    if active >= settings.max_concurrent_exports:
        raise APIError(
            code="RATE_LIMITED",
            message="Too many concurrent export jobs",
            status_code=429,
            details={"max_concurrent_exports": settings.max_concurrent_exports},
        )


async def _count_export_points(
    *, user: User, start_utc: dt.datetime, end_utc: dt.datetime, db: AsyncSession
) -> int:
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
        select(sa.func.count())
        .select_from(TrackPoint)
        .where(
            TrackPoint.user_id == user.id,
            TrackPoint.recorded_at >= start_utc,
            TrackPoint.recorded_at <= end_utc,
            ~sa.exists(edit_match),
        )
    )
    return int((await db.execute(stmt)).scalar_one() or 0)


async def _load_export_points(
    *, user: User, start_utc: dt.datetime, end_utc: dt.datetime, db: AsyncSession
) -> list[TrackPoint]:
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
        select(TrackPoint)
        .where(
            TrackPoint.user_id == user.id,
            TrackPoint.recorded_at >= start_utc,
            TrackPoint.recorded_at <= end_utc,
            ~sa.exists(edit_match),
        )
        .order_by(TrackPoint.recorded_at.asc())
    )
    return list((await db.execute(stmt)).scalars().all())


@router.post("", status_code=202, response_model=ExportCreateResponse)
async def create_export_job(
    payload: ExportCreateRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ExportCreateResponse:
    await _enforce_concurrent_exports_limit(user=user, db=db)

    start_utc = _normalize_to_utc(payload.start)
    end_utc = _normalize_to_utc(payload.end)
    if start_utc >= end_utc:
        raise APIError(
            code="EXPORT_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )

    fmt = _normalize_format(payload.format)

    settings = _user_settings(user)
    include_weather_default = bool(
        settings.get("export_include_weather_default", False)
    )
    include_weather = (
        bool(payload.include_weather)
        if payload.include_weather is not None
        else include_weather_default
    )

    tz_default = str(settings.get("timezone", "UTC") or "UTC")
    tz = payload.timezone or tz_default

    job = ExportJob(
        user_id=user.id,
        state="CREATED",
        format=fmt,
        include_weather=include_weather,
        start_at=start_utc,
        end_at=end_utc,
        timezone=tz,
        artifact_path=None,
        error_code=None,
        error_message=None,
        finished_at=None,
    )
    db.add(job)
    await db.commit()

    # Enqueue export task. In dev/test (celery eager), this executes inline.
    cast(Any, run_export_job_task).delay(str(job.id))

    return ExportCreateResponse(job_id=str(job.id))


@router.get("", response_class=StreamingResponse, response_model=None)
async def export_compat_get(
    *,
    start: dt.datetime,
    end: dt.datetime,
    format: str,
    include_weather: bool = Query(default=False),
    timezone: str | None = Query(default=None, min_length=1, max_length=64),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Compatibility endpoint.

    - Small exports (<= sync_threshold_points) with include_weather=false stream directly.
    - Otherwise return 202 + job_id (same as POST /v1/export).
    """

    start_utc = _normalize_to_utc(start)
    end_utc = _normalize_to_utc(end)
    if start_utc >= end_utc:
        raise APIError(
            code="EXPORT_INVALID_RANGE",
            message="start must be before end",
            status_code=400,
        )

    fmt = _normalize_format(format)
    settings = get_settings()

    user_settings = _user_settings(user)
    tz_default = str(user_settings.get("timezone", "UTC") or "UTC")
    tz = timezone or tz_default

    if include_weather:
        # Weather export is intentionally async; provider failures degrade to PARTIAL.
        await _enforce_concurrent_exports_limit(user=user, db=db)
        job = ExportJob(
            user_id=user.id,
            state="CREATED",
            format=fmt,
            include_weather=True,
            start_at=start_utc,
            end_at=end_utc,
            timezone=tz,
            artifact_path=None,
            error_code=None,
            error_message=None,
            finished_at=None,
        )
        db.add(job)
        await db.commit()
        cast(Any, run_export_job_task).delay(str(job.id))
        return JSONResponse(status_code=202, content={"job_id": str(job.id)})

    points_count = await _count_export_points(
        user=user, start_utc=start_utc, end_utc=end_utc, db=db
    )
    if points_count <= settings.sync_threshold_points:
        # Sync/streaming response.
        points = await _load_export_points(
            user=user, start_utc=start_utc, end_utc=end_utc, db=db
        )

        # Reuse the worker's stdlib-only format implementation.
        from app.tasks.export import _format_bytes, _tzinfo_from_name

        payload = _format_bytes(fmt, points, _tzinfo_from_name(tz))
        media_type = "application/octet-stream"
        filename = f"wayfarer-export.{fmt.lower()}"
        return StreamingResponse(
            io.BytesIO(payload),
            media_type=media_type,
            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
        )

    # Async fallback.
    await _enforce_concurrent_exports_limit(user=user, db=db)
    job = ExportJob(
        user_id=user.id,
        state="CREATED",
        format=fmt,
        include_weather=False,
        start_at=start_utc,
        end_at=end_utc,
        timezone=tz,
        artifact_path=None,
        error_code=None,
        error_message=None,
        finished_at=None,
    )
    db.add(job)
    await db.commit()
    cast(Any, run_export_job_task).delay(str(job.id))
    return JSONResponse(status_code=202, content={"job_id": str(job.id)})


@router.get("/{job_id}", response_model=ExportJobStatusResponse)
async def get_export_job_status(
    job_id: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ExportJobStatusResponse:
    try:
        jid = uuid.UUID(job_id)
    except Exception:
        # Avoid leaking a UUID parsing error as 500.
        raise APIError(
            code="EXPORT_JOB_NOT_FOUND",
            message="Export job not found",
            status_code=404,
        )

    job = (
        await db.execute(
            select(ExportJob).where(ExportJob.id == jid, ExportJob.user_id == user.id)
        )
    ).scalar_one_or_none()
    if job is None:
        raise APIError(
            code="EXPORT_JOB_NOT_FOUND",
            message="Export job not found",
            status_code=404,
        )

    err = None
    if job.error_code or job.error_message:
        err = ExportJobError(code=job.error_code or "", message=job.error_message or "")

    return ExportJobStatusResponse(
        job_id=str(job.id),
        state=job.state,
        created_at=job.created_at,
        finished_at=job.finished_at,
        format=job.format,
        timezone=job.timezone,
        artifact_path=job.artifact_path,
        error=err,
    )


@router.get("/{job_id}/download")
async def download_export_job(
    job_id: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> FileResponse:
    try:
        jid = uuid.UUID(job_id)
    except Exception:
        raise APIError(
            code="EXPORT_JOB_NOT_FOUND",
            message="Export job not found",
            status_code=404,
        )

    job = (
        await db.execute(
            select(ExportJob).where(ExportJob.id == jid, ExportJob.user_id == user.id)
        )
    ).scalar_one_or_none()
    if job is None:
        raise APIError(
            code="EXPORT_JOB_NOT_FOUND",
            message="Export job not found",
            status_code=404,
        )

    if job.state not in {"SUCCEEDED", "PARTIAL"}:
        raise APIError(
            code="EXPORT_JOB_NOT_READY",
            message="Export job is not ready",
            status_code=409,
            details={"state": job.state},
        )

    if not job.artifact_path:
        raise APIError(
            code="EXPORT_JOB_NOT_FOUND",
            message="Export artifact not found",
            status_code=404,
        )

    path = _artifact_abs_path(artifact_path=job.artifact_path)
    if not path.exists():
        raise APIError(
            code="EXPORT_JOB_NOT_FOUND",
            message="Export artifact not found",
            status_code=404,
        )

    filename = path.name
    return FileResponse(
        path,
        media_type="application/octet-stream",
        filename=filename,
    )


@router.post("/{job_id}/cancel")
async def cancel_export_job(
    job_id: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict[str, str]:
    try:
        jid = uuid.UUID(job_id)
    except Exception:
        raise APIError(
            code="EXPORT_JOB_NOT_FOUND",
            message="Export job not found",
            status_code=404,
        )

    job = (
        await db.execute(
            select(ExportJob).where(ExportJob.id == jid, ExportJob.user_id == user.id)
        )
    ).scalar_one_or_none()
    if job is None:
        raise APIError(
            code="EXPORT_JOB_NOT_FOUND",
            message="Export job not found",
            status_code=404,
        )

    if job.state not in {"CREATED", "RUNNING"}:
        raise APIError(
            code="EXPORT_JOB_NOT_READY",
            message="Export job is not cancelable",
            status_code=409,
            details={"state": job.state},
        )

    # Best-effort artifact cleanup.
    if job.artifact_path:
        try:
            _artifact_abs_path(artifact_path=job.artifact_path).unlink(missing_ok=True)
        except Exception:
            pass

    job.state = "CANCELED"
    job.finished_at = utcnow()
    await db.commit()

    return {"job_id": str(job.id), "state": job.state}
