from __future__ import annotations

import asyncio
import datetime as dt
import uuid
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Coroutine, TypeVar

from app.db.session import get_sessionmaker
from app.services.life_event import recompute_auto_life_events_for_window
from app.tasks.celery_app import celery_app


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


async def _recompute_life_events(
    *,
    user_id: uuid.UUID,
    start_at: dt.datetime,
    end_at: dt.datetime,
) -> dict[str, int]:
    sessionmaker = get_sessionmaker()
    async with sessionmaker() as session:
        return await recompute_auto_life_events_for_window(
            session=session,
            user_id=user_id,
            start_at=start_at,
            end_at=end_at,
        )


@celery_app.task(name="app.tasks.life_event.recompute_life_events_task")
def recompute_life_events_task(
    user_id: str | uuid.UUID,
    start_at: dt.datetime | str,
    end_at: dt.datetime | str,
) -> dict[str, int]:
    """Recompute LifeEvent derived data for a time window.

    Notes:
    - In dev/test, Celery runs in eager mode, so `.delay(...)` executes inline.
    - Task args must be JSON-serializable (safe for eager and non-eager).
    """

    uid = user_id if isinstance(user_id, uuid.UUID) else uuid.UUID(str(user_id))
    start_dt = _coerce_datetime(start_at)
    end_dt = _coerce_datetime(end_at)
    if start_dt > end_dt:
        raise ValueError("start_at must be <= end_at")

    return _run_coro_sync(
        lambda: _recompute_life_events(user_id=uid, start_at=start_dt, end_at=end_dt)
    )
