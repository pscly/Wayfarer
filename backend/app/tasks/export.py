from __future__ import annotations

import asyncio
import csv
import datetime as dt
import io
import json
import uuid
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from collections.abc import Callable
from pathlib import Path
from typing import Any, Coroutine, TypeVar
from zoneinfo import ZoneInfo

import sqlalchemy as sa
from sqlalchemy import select

from app.core.settings import get_settings
from app.db.base import utcnow
from app.db.session import get_sessionmaker
from app.models.export_job import ExportJob
from app.models.track_edit import TrackEdit
from app.models.track_point import TrackPoint
from app.tasks.celery_app import celery_app


_T = TypeVar("_T")


def _run_coro_sync(coro_factory: Callable[[], Coroutine[Any, Any, _T]]) -> _T:
    """Run an async coroutine from a sync context.

    In Celery eager mode, tasks may be invoked from within an already-running
    event loop (e.g. FastAPI). `asyncio.run()` would crash there, so we fall
    back to executing the coroutine on a one-off thread.
    """

    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro_factory())

    with ThreadPoolExecutor(max_workers=1) as ex:
        fut = ex.submit(lambda: asyncio.run(coro_factory()))
        return fut.result()


def _tzinfo_from_name(name: str) -> dt.tzinfo:
    if name.upper() == "UTC":
        return dt.timezone.utc
    try:
        return ZoneInfo(name)
    except Exception:
        # Timezone only affects formatting; fall back to UTC for invalid input.
        return dt.timezone.utc


def _format_dt(ts: dt.datetime, tz: dt.tzinfo) -> str:
    out = ts.astimezone(tz)
    if tz is dt.timezone.utc:
        # Keep a stable Z suffix for common clients.
        return out.isoformat().replace("+00:00", "Z")
    return out.isoformat()


def _export_ext(fmt: str) -> str:
    match fmt:
        case "CSV":
            return "csv"
        case "GPX":
            return "gpx"
        case "GeoJSON":
            return "geojson"
        case "KML":
            return "kml"
        case _:
            # Should be prevented at API boundary.
            return "bin"


def _csv_bytes(points: list[TrackPoint], tz: dt.tzinfo) -> bytes:
    buf = io.StringIO(newline="")
    w = csv.writer(buf)
    w.writerow(
        [
            "client_point_id",
            "recorded_at",
            "latitude",
            "longitude",
            "accuracy",
            "altitude",
            "speed",
            "is_dirty",
        ]
    )
    for p in points:
        w.writerow(
            [
                str(p.client_point_id),
                _format_dt(p.recorded_at, tz),
                p.latitude,
                p.longitude,
                p.accuracy,
                p.altitude,
                p.speed,
                bool(p.is_dirty),
            ]
        )
    return buf.getvalue().encode("utf-8")


def _geojson_bytes(points: list[TrackPoint], tz: dt.tzinfo) -> bytes:
    features: list[dict[str, Any]] = []
    for p in points:
        features.append(
            {
                "type": "Feature",
                "geometry": {
                    "type": "Point",
                    "coordinates": [p.longitude, p.latitude],
                },
                "properties": {
                    "client_point_id": str(p.client_point_id),
                    "recorded_at": _format_dt(p.recorded_at, tz),
                    "accuracy": p.accuracy,
                    "altitude": p.altitude,
                    "speed": p.speed,
                    "is_dirty": bool(p.is_dirty),
                },
            }
        )

    payload = {
        "type": "FeatureCollection",
        "features": features,
    }
    return json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _gpx_bytes(points: list[TrackPoint], tz: dt.tzinfo) -> bytes:
    gpx = ET.Element(
        "gpx",
        attrib={
            "version": "1.1",
            "creator": "wayfarer",
            "xmlns": "http://www.topografix.com/GPX/1/1",
        },
    )
    trk = ET.SubElement(gpx, "trk")
    trkseg = ET.SubElement(trk, "trkseg")
    for p in points:
        trkpt = ET.SubElement(
            trkseg,
            "trkpt",
            attrib={"lat": str(p.latitude), "lon": str(p.longitude)},
        )
        if p.altitude is not None:
            ET.SubElement(trkpt, "ele").text = str(p.altitude)
        ET.SubElement(trkpt, "time").text = _format_dt(p.recorded_at, tz)
    return ET.tostring(gpx, encoding="utf-8", xml_declaration=True)


def _kml_bytes(points: list[TrackPoint], tz: dt.tzinfo) -> bytes:
    kml = ET.Element(
        "kml",
        attrib={"xmlns": "http://www.opengis.net/kml/2.2"},
    )
    doc = ET.SubElement(kml, "Document")

    placemark = ET.SubElement(doc, "Placemark")
    ET.SubElement(placemark, "name").text = "Wayfarer Export"
    ET.SubElement(
        placemark, "description"
    ).text = f"points={len(points)}; timezone={tz.tzname(dt.datetime.now(tz)) or 'UTC'}"

    linestring = ET.SubElement(placemark, "LineString")
    ET.SubElement(linestring, "tessellate").text = "1"
    coords = []
    for p in points:
        alt = p.altitude or 0.0
        coords.append(f"{p.longitude},{p.latitude},{alt}")
    ET.SubElement(linestring, "coordinates").text = " ".join(coords)

    # Optional: attach timestamps as a simple ExtendedData block.
    ext = ET.SubElement(placemark, "ExtendedData")
    for p in points:
        d = ET.SubElement(ext, "Data", attrib={"name": str(p.client_point_id)})
        ET.SubElement(d, "value").text = _format_dt(p.recorded_at, tz)

    return ET.tostring(kml, encoding="utf-8", xml_declaration=True)


def _format_bytes(fmt: str, points: list[TrackPoint], tz: dt.tzinfo) -> bytes:
    match fmt:
        case "CSV":
            return _csv_bytes(points, tz)
        case "GeoJSON":
            return _geojson_bytes(points, tz)
        case "GPX":
            return _gpx_bytes(points, tz)
        case "KML":
            return _kml_bytes(points, tz)
        case _:
            raise ValueError(f"Unsupported export format: {fmt}")


async def _run_export_job(*, job_id: uuid.UUID) -> dict[str, Any]:
    settings = get_settings()
    sessionmaker = get_sessionmaker()

    async with sessionmaker() as session:
        job = (
            await session.execute(select(ExportJob).where(ExportJob.id == job_id))
        ).scalar_one_or_none()
        if job is None:
            return {"status": "not_found"}

        if job.state in {"SUCCEEDED", "PARTIAL", "FAILED", "CANCELED"}:
            return {"status": "skipped", "state": job.state}

        job.state = "RUNNING"
        job.error_code = None
        job.error_message = None
        job.finished_at = None
        await session.commit()

        # Exclude points covered by active DELETE_RANGE edits (correlated NOT EXISTS).
        edit_match = (
            sa.select(1)
            .select_from(TrackEdit)
            .where(
                TrackEdit.user_id == job.user_id,
                TrackEdit.type == "DELETE_RANGE",
                TrackEdit.canceled_at.is_(None),
                TrackPoint.recorded_at >= TrackEdit.start_at,
                TrackPoint.recorded_at <= TrackEdit.end_at,
            )
            .correlate(TrackPoint)
        )

        count_stmt = (
            sa.select(sa.func.count())
            .select_from(TrackPoint)
            .where(
                TrackPoint.user_id == job.user_id,
                TrackPoint.recorded_at >= job.start_at,
                TrackPoint.recorded_at <= job.end_at,
                ~sa.exists(edit_match),
            )
        )
        points_count = int((await session.execute(count_stmt)).scalar_one() or 0)
        if points_count > settings.max_export_points:
            job.state = "FAILED"
            job.error_code = "EXPORT_TOO_MANY_POINTS"
            job.error_message = f"Too many points to export ({points_count} > {settings.max_export_points})"
            job.finished_at = utcnow()
            await session.commit()
            return {"status": "failed", "error": job.error_code}

        points_stmt = (
            sa.select(TrackPoint)
            .where(
                TrackPoint.user_id == job.user_id,
                TrackPoint.recorded_at >= job.start_at,
                TrackPoint.recorded_at <= job.end_at,
                ~sa.exists(edit_match),
            )
            .order_by(TrackPoint.recorded_at.asc())
        )
        points = list((await session.execute(points_stmt)).scalars().all())

        tz = _tzinfo_from_name(job.timezone)
        ext = _export_ext(job.format)
        rel_path = f"{job.user_id}/{job.id}.{ext}"
        abs_path = Path(settings.export_dir) / rel_path
        abs_path.parent.mkdir(parents=True, exist_ok=True)

        payload = _format_bytes(job.format, points, tz)
        abs_path.write_bytes(payload)

        # Respect cancel requests that raced with artifact generation.
        await session.refresh(job)
        if job.state == "CANCELED":
            try:
                abs_path.unlink(missing_ok=True)
            except Exception:
                pass
            return {"status": "canceled"}

        job.artifact_path = rel_path
        if job.include_weather:
            job.state = "PARTIAL"
            job.error_code = "EXPORT_WEATHER_DEGRADED"
            job.error_message = (
                "Weather enrichment not implemented; exported without weather."
            )
        else:
            job.state = "SUCCEEDED"
            job.error_code = None
            job.error_message = None
        job.finished_at = utcnow()
        await session.commit()

        return {
            "status": "ok",
            "state": job.state,
            "artifact_path": job.artifact_path,
            "points_count": points_count,
        }


@celery_app.task(name="app.tasks.export.run_export_job_task")
def run_export_job_task(job_id: str | uuid.UUID) -> dict[str, Any]:
    jid = job_id if isinstance(job_id, uuid.UUID) else uuid.UUID(str(job_id))
    return _run_coro_sync(lambda: _run_export_job(job_id=jid))
