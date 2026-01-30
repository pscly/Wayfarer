from __future__ import annotations

import datetime as dt
import math
import uuid
from dataclasses import dataclass

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import utcnow
from app.models.life_event import LifeEvent
from app.models.track_edit import TrackEdit
from app.models.track_point import TrackPoint


DEFAULT_DISTANCE_THRESHOLD_M = 200.0
DEFAULT_TIME_THRESHOLD_S = 5.0 * 60.0


def _normalize_to_utc(value: dt.datetime) -> dt.datetime:
    # Store tz-aware UTC timestamps everywhere.
    if value.tzinfo is None:
        return value.replace(tzinfo=dt.timezone.utc)
    return value.astimezone(dt.timezone.utc)


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    # Simple spherical distance; deterministic and sufficient for stay detection.
    r = 6_371_000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = (
        math.sin(dphi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    )
    return 2.0 * r * math.asin(math.sqrt(a))


@dataclass(frozen=True)
class StayCandidate:
    start_at: dt.datetime
    end_at: dt.datetime
    latitude: float
    longitude: float
    gcj02_latitude: float | None
    gcj02_longitude: float | None
    point_count: int


def _make_auto_stay_id(
    *, user_id: uuid.UUID, start_at: dt.datetime, end_at: dt.datetime
) -> uuid.UUID:
    # Deterministic id so recompute does not spam duplicates.
    # Note: if user manually edits start/end via CRUD, future recomputes may insert
    # a new auto record (MVP trade-off; see notepad).
    start_at = _normalize_to_utc(start_at)
    end_at = _normalize_to_utc(end_at)
    name = f"{user_id}|STAY|{start_at.isoformat()}|{end_at.isoformat()}"
    return uuid.uuid5(uuid.NAMESPACE_URL, name)


def detect_stay_candidates(
    points: list[TrackPoint],
    *,
    distance_threshold_m: float = DEFAULT_DISTANCE_THRESHOLD_M,
    time_threshold_s: float = DEFAULT_TIME_THRESHOLD_S,
) -> list[StayCandidate]:
    """Detect stay points from ordered TrackPoints.

    Deterministic windowing:
    - anchor is the first point in the window
    - window extends until a point exceeds distance_threshold
    - if duration >= time_threshold => emit one STAY event
    """

    if not points:
        return []

    points_sorted = sorted(points, key=lambda p: p.recorded_at)

    stays: list[StayCandidate] = []
    i = 0
    n = len(points_sorted)
    while i < n:
        anchor = points_sorted[i]
        j = i + 1
        # Greedily extend while points remain near the anchor.
        while j < n:
            p = points_sorted[j]
            d = _haversine_m(anchor.latitude, anchor.longitude, p.latitude, p.longitude)
            if d > distance_threshold_m:
                break
            j += 1

        # Candidate window is points[i:j]. If j == i+1 (single point), duration is 0.
        last = points_sorted[j - 1]
        duration_s = (last.recorded_at - anchor.recorded_at).total_seconds()
        if duration_s >= time_threshold_s and j - i >= 2:
            lat_sum = 0.0
            lon_sum = 0.0
            gcj_lat_sum = 0.0
            gcj_lon_sum = 0.0
            gcj_count = 0
            for k in range(i, j):
                pk = points_sorted[k]
                lat_sum += float(pk.latitude)
                lon_sum += float(pk.longitude)
                if pk.gcj02_latitude is not None and pk.gcj02_longitude is not None:
                    gcj_lat_sum += float(pk.gcj02_latitude)
                    gcj_lon_sum += float(pk.gcj02_longitude)
                    gcj_count += 1

            center_lat = lat_sum / float(j - i)
            center_lon = lon_sum / float(j - i)
            center_gcj_lat = (gcj_lat_sum / float(gcj_count)) if gcj_count else None
            center_gcj_lon = (gcj_lon_sum / float(gcj_count)) if gcj_count else None

            stays.append(
                StayCandidate(
                    start_at=anchor.recorded_at,
                    end_at=last.recorded_at,
                    latitude=center_lat,
                    longitude=center_lon,
                    gcj02_latitude=center_gcj_lat,
                    gcj02_longitude=center_gcj_lon,
                    point_count=(j - i),
                )
            )
            i = j
            continue

        # No valid stay: advance by one point (stable).
        i += 1

    return stays


async def load_track_points_for_window(
    *,
    session: AsyncSession,
    user_id: uuid.UUID,
    start_at: dt.datetime,
    end_at: dt.datetime,
) -> list[TrackPoint]:
    start_utc = _normalize_to_utc(start_at)
    end_utc = _normalize_to_utc(end_at)

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

    stmt = (
        sa.select(TrackPoint)
        .where(
            TrackPoint.user_id == user_id,
            TrackPoint.recorded_at >= start_utc,
            TrackPoint.recorded_at <= end_utc,
            ~sa.exists(edit_match),
        )
        .order_by(TrackPoint.recorded_at.asc())
    )

    return list((await session.execute(stmt)).scalars().all())


async def recompute_auto_life_events_for_window(
    *,
    session: AsyncSession,
    user_id: uuid.UUID,
    start_at: dt.datetime,
    end_at: dt.datetime,
    distance_threshold_m: float = DEFAULT_DISTANCE_THRESHOLD_M,
    time_threshold_s: float = DEFAULT_TIME_THRESHOLD_S,
) -> dict[str, int]:
    """Best-effort recompute for a time window.

    MVP behavior: only INSERT new deterministic-id events; do not delete or
    overwrite existing rows (so user edits survive).
    """

    points = await load_track_points_for_window(
        session=session,
        user_id=user_id,
        start_at=start_at,
        end_at=end_at,
    )
    stays = detect_stay_candidates(
        points,
        distance_threshold_m=float(distance_threshold_m),
        time_threshold_s=float(time_threshold_s),
    )
    if not stays:
        return {"computed": 0, "inserted": 0}

    # Deterministic primary key -> can use ON CONFLICT DO NOTHING.
    rows: list[dict[str, object]] = []
    now = utcnow()
    for s in stays:
        start_utc = _normalize_to_utc(s.start_at)
        end_utc = _normalize_to_utc(s.end_at)
        rows.append(
            {
                "id": _make_auto_stay_id(
                    user_id=user_id, start_at=start_utc, end_at=end_utc
                ),
                "user_id": user_id,
                "event_type": "STAY",
                "start_at": start_utc,
                "end_at": end_utc,
                "latitude": float(s.latitude),
                "longitude": float(s.longitude),
                "gcj02_latitude": (
                    float(s.gcj02_latitude) if s.gcj02_latitude is not None else None
                ),
                "gcj02_longitude": (
                    float(s.gcj02_longitude) if s.gcj02_longitude is not None else None
                ),
                "payload_json": {
                    "source": "AUTO_STAY",
                    "distance_threshold_m": float(distance_threshold_m),
                    "time_threshold_s": float(time_threshold_s),
                    "point_count": int(s.point_count),
                },
                "created_at": now,
                "updated_at": now,
            }
        )

    dialect = session.bind.dialect.name if session.bind is not None else ""
    inserted = 0
    if dialect in {"sqlite", "postgresql"}:
        if dialect == "sqlite":
            from sqlalchemy.dialects.sqlite import insert as _insert

            stmt = _insert(LifeEvent.__table__).values(rows).prefix_with("OR IGNORE")
        else:
            from sqlalchemy.dialects.postgresql import insert as _insert

            stmt = (
                _insert(LifeEvent.__table__)
                .values(rows)
                .on_conflict_do_nothing(index_elements=["id"])
            )
        result = await session.execute(stmt)
        # SQLAlchemy does not reliably report per-row ignored inserts across dialects.
        inserted = int(getattr(result, "rowcount", 0) or 0)
    else:
        # Fallback: insert one-by-one and ignore conflicts.
        for row in rows:
            try:
                async with session.begin_nested():
                    session.add(LifeEvent(**row))
                    await session.flush()
                inserted += 1
            except Exception:
                pass

    await session.commit()
    return {"computed": len(stays), "inserted": inserted}
