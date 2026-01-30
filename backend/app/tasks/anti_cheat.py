from __future__ import annotations

import asyncio
import datetime as dt
import math
import uuid
from concurrent.futures import ThreadPoolExecutor
from collections.abc import Callable
from typing import Any, Coroutine, TypeVar, cast

import sqlalchemy as sa
from sqlalchemy import Table, select

from app.db.session import get_sessionmaker
from app.models.track_point import TrackPoint
from app.tasks.celery_app import celery_app

# Hard-rule thresholds (see plan-supplement Anti-Cheat section).
_MAX_STEP_RATE_STEPS_PER_SEC = 4.0
_MIN_STEP_LENGTH_M = 0.3
_MAX_STEP_LENGTH_M = 2.5

# Conservative "teleportation" detection: only flag clearly impossible jumps.
# We offset distance by reported GPS accuracy so we don't punish noisy points.
_TELEPORT_SPEED_MPS = 120.0  # ~432 km/h
_ACCURACY_FUDGE_M = 5.0


_T = TypeVar("_T")


def _coerce_datetime(value: dt.datetime | str) -> dt.datetime:
    if isinstance(value, dt.datetime):
        out = value
    elif isinstance(value, str):
        # Support ISO strings from clients/workers (including trailing 'Z').
        s = value.strip()
        if s.endswith("Z"):
            s = s[:-1] + "+00:00"
        out = dt.datetime.fromisoformat(s)
    else:
        raise TypeError(f"Unsupported datetime value: {type(value)!r}")

    # DB column is timezone-aware; treat naive values as UTC to avoid comparison errors.
    if out.tzinfo is None:
        out = out.replace(tzinfo=dt.timezone.utc)
    return out


def _haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    # WGS84-ish spherical distance; sufficient for anti-cheat heuristics.
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


def _is_point_dirty(prev: TrackPoint | None, cur: TrackPoint) -> bool:
    if prev is None:
        # No segment to evaluate yet.
        return False

    dt_s = (cur.recorded_at - prev.recorded_at).total_seconds()
    step_delta = int(cur.step_delta or 0)

    dist_m = _haversine_m(prev.latitude, prev.longitude, cur.latitude, cur.longitude)
    acc_prev = float(prev.accuracy or 0.0)
    acc_cur = float(cur.accuracy or 0.0)
    effective_dist_m = max(0.0, dist_m - (acc_prev + acc_cur + _ACCURACY_FUDGE_M))

    if dt_s <= 0:
        # Same timestamp (or out-of-order). Any positive steps or real movement is impossible.
        return step_delta > 0 or effective_dist_m > 0.0

    # Hard rule: impossible step rate.
    if step_delta > 0 and (step_delta / dt_s) > _MAX_STEP_RATE_STEPS_PER_SEC:
        return True

    # Hard rule: implausible step length.
    if step_delta > 0:
        step_len = dist_m / step_delta
        if step_len < _MIN_STEP_LENGTH_M or step_len > _MAX_STEP_LENGTH_M:
            return True

    # Hard rule: teleportation / impossible speed jump.
    if (effective_dist_m / dt_s) > _TELEPORT_SPEED_MPS:
        return True

    return False


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


async def _audit_track_segment(
    *,
    user_id: uuid.UUID,
    start_at: dt.datetime,
    end_at: dt.datetime,
) -> dict[str, int]:
    sessionmaker = get_sessionmaker()
    async with sessionmaker() as session:
        prev_stmt = (
            select(TrackPoint)
            .where(TrackPoint.user_id == user_id)
            .where(TrackPoint.recorded_at < start_at)
            .order_by(TrackPoint.recorded_at.desc())
            .limit(1)
        )
        prev = (await session.execute(prev_stmt)).scalars().first()

        points_stmt = (
            select(TrackPoint)
            .where(TrackPoint.user_id == user_id)
            .where(TrackPoint.recorded_at >= start_at)
            .where(TrackPoint.recorded_at <= end_at)
            .order_by(TrackPoint.recorded_at.asc())
        )
        points = list((await session.execute(points_stmt)).scalars().all())

        if not points:
            return {"updated": 0, "dirty": 0, "clean": 0}

        updates: list[dict[str, object]] = []
        dirty_count = 0
        cur_prev = prev
        for p in points:
            dirty = _is_point_dirty(cur_prev, p)
            if dirty:
                dirty_count += 1
            updates.append({"b_id": p.id, "is_dirty": dirty})
            cur_prev = p

        table = cast(Table, TrackPoint.__table__)
        update_stmt = (
            # Use a Core table update to avoid ORM session synchronization errors
            # when doing executemany-style bulk updates.
            sa.update(table)
            .where(table.c.id == sa.bindparam("b_id"))
            .values(is_dirty=sa.bindparam("is_dirty"))
        )
        await session.execute(update_stmt, updates)
        await session.commit()

        return {
            "updated": len(updates),
            "dirty": dirty_count,
            "clean": len(updates) - dirty_count,
        }


@celery_app.task(name="app.tasks.anti_cheat.audit_track_segment_task")
def audit_track_segment_task(
    user_id: str | uuid.UUID,
    start_at: dt.datetime | str,
    end_at: dt.datetime | str,
) -> dict[str, int]:
    """Audit a user track segment and deterministically set `TrackPoint.is_dirty`.

    Notes:
    - In dev, Celery runs in eager mode, so `.delay(...)` executes inline.
    - We only update points whose recorded_at is within [start_at, end_at] inclusive.
    """

    uid = user_id if isinstance(user_id, uuid.UUID) else uuid.UUID(str(user_id))
    start_dt = _coerce_datetime(start_at)
    end_dt = _coerce_datetime(end_at)
    if start_dt > end_dt:
        raise ValueError("start_at must be <= end_at")

    return _run_coro_sync(
        lambda: _audit_track_segment(user_id=uid, start_at=start_dt, end_at=end_dt)
    )
