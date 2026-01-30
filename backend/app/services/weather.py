from __future__ import annotations

import asyncio
import datetime as dt
from dataclasses import dataclass
from typing import Any, Callable

import httpx
import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.settings import get_settings
from app.models.weather_cache import WeatherCache
from app.utils.geohash import decode_center, encode


class WeatherProviderError(Exception):
    pass


class WeatherRateLimitedError(WeatherProviderError):
    pass


class WeatherTimeoutError(WeatherProviderError):
    pass


def floor_to_hour_utc(ts: dt.datetime) -> dt.datetime:
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=dt.timezone.utc)
    ts = ts.astimezone(dt.timezone.utc)
    return ts.replace(minute=0, second=0, microsecond=0)


@dataclass(frozen=True)
class WeatherKey:
    geohash_5: str
    hour_time: dt.datetime


class OpenMeteoArchiveClient:
    """Minimal Open-Meteo archive client with 429 exponential backoff.

    Baseline rate limit: 600/min (free tier); we back off on 429 to avoid a
    request storm.
    """

    def __init__(
        self,
        *,
        base_url: str,
        timeout_s: float,
        max_retries: int,
        backoff_base_s: float,
        http_client: httpx.AsyncClient | None = None,
        sleep: Callable[[float], Any] | None = None,
    ) -> None:
        self._base_url = base_url
        self._timeout = timeout_s
        self._max_retries = max_retries
        self._backoff_base = backoff_base_s
        self._client = http_client
        self._sleep = sleep or asyncio.sleep

    async def _get_json(self, *, params: dict[str, Any]) -> dict[str, Any]:
        close_client = False
        client = self._client
        if client is None:
            close_client = True
            client = httpx.AsyncClient()

        try:
            for attempt in range(self._max_retries):
                try:
                    resp = await client.get(
                        self._base_url,
                        params=params,
                        timeout=self._timeout,
                    )
                except httpx.TimeoutException as e:
                    raise WeatherTimeoutError("Open-Meteo request timed out") from e
                except httpx.HTTPError as e:
                    raise WeatherProviderError("Open-Meteo request failed") from e

                if resp.status_code == 429:
                    if attempt >= self._max_retries - 1:
                        raise WeatherRateLimitedError("Open-Meteo rate limited")
                    delay = self._backoff_base * (2**attempt)
                    await self._sleep(delay)
                    continue

                if 500 <= resp.status_code < 600:
                    if attempt >= self._max_retries - 1:
                        raise WeatherProviderError(
                            f"Open-Meteo server error: {resp.status_code}"
                        )
                    delay = self._backoff_base * (2**attempt)
                    await self._sleep(delay)
                    continue

                resp.raise_for_status()
                return resp.json()

            raise WeatherProviderError("Open-Meteo request retries exhausted")
        finally:
            if close_client:
                await client.aclose()

    async def get_hour_snapshot(
        self,
        *,
        latitude: float,
        longitude: float,
        hour_time_utc: dt.datetime,
    ) -> dict[str, object]:
        hour_time_utc = floor_to_hour_utc(hour_time_utc)
        day = hour_time_utc.date()
        # Keep hourly fields minimal; they can be extended later without schema changes.
        hourly_fields = [
            "temperature_2m",
            "relativehumidity_2m",
            "precipitation",
            "weathercode",
            "windspeed_10m",
        ]
        params = {
            "latitude": latitude,
            "longitude": longitude,
            "start_date": day.isoformat(),
            "end_date": day.isoformat(),
            "hourly": ",".join(hourly_fields),
            "timezone": "UTC",
        }
        data = await self._get_json(params=params)

        hourly = data.get("hourly")
        if not isinstance(hourly, dict):
            raise WeatherProviderError("Open-Meteo response missing hourly payload")

        times = hourly.get("time")
        if not isinstance(times, list):
            raise WeatherProviderError("Open-Meteo response missing hourly.time")

        target = hour_time_utc.replace(tzinfo=None).isoformat(timespec="minutes")
        try:
            idx = times.index(target)
        except ValueError as e:
            raise WeatherProviderError("Open-Meteo response missing target hour") from e

        snapshot: dict[str, object] = {
            "provider": "open-meteo",
            "hour_time": hour_time_utc.isoformat().replace("+00:00", "Z"),
            "latitude": latitude,
            "longitude": longitude,
        }
        for k in hourly_fields:
            values = hourly.get(k)
            if isinstance(values, list) and idx < len(values):
                snapshot[k] = values[idx]
        return snapshot


async def get_weather_snapshot(
    *,
    session: AsyncSession,
    latitude: float,
    longitude: float,
    recorded_at: dt.datetime,
    http_client: httpx.AsyncClient | None = None,
    sleep: Callable[[float], Any] | None = None,
) -> tuple[dict[str, object] | None, bool]:
    """Return (snapshot, degraded).

    - snapshot is loaded from `weather_cache` or fetched from Open-Meteo and cached.
    - degraded=True means provider failed; caller may mark job as PARTIAL.
    """

    settings = get_settings()
    precision = int(settings.weather_geohash_precision)

    geohash_5 = encode(latitude, longitude, precision=precision)
    hour_time = floor_to_hour_utc(recorded_at)

    stmt = sa.select(WeatherCache).where(
        WeatherCache.geohash_5 == geohash_5,
        WeatherCache.hour_time == hour_time,
    )
    cached = (await session.execute(stmt)).scalar_one_or_none()
    if cached is not None:
        return cached.payload, False

    # Query provider at the geohash cell center for stable caching.
    lat_c, lon_c = decode_center(geohash_5)
    client = OpenMeteoArchiveClient(
        base_url=settings.open_meteo_archive_base_url,
        timeout_s=float(settings.open_meteo_timeout_s),
        max_retries=int(settings.open_meteo_max_retries),
        backoff_base_s=float(settings.open_meteo_backoff_base_s),
        http_client=http_client,
        sleep=sleep,
    )
    try:
        payload = await client.get_hour_snapshot(
            latitude=lat_c,
            longitude=lon_c,
            hour_time_utc=hour_time,
        )
    except WeatherProviderError:
        return None, True

    # Cache write: unique (geohash_5, hour_time).
    values = {
        "geohash_5": geohash_5,
        "hour_time": hour_time,
        "payload": payload,
    }

    dialect = session.bind.dialect.name if session.bind is not None else ""
    if dialect == "sqlite":
        from sqlalchemy.dialects.sqlite import insert as _insert

        stmt_ins = (
            _insert(WeatherCache.__table__)
            .values(**values)
            .on_conflict_do_nothing(index_elements=["geohash_5", "hour_time"])
        )
        await session.execute(stmt_ins)
    elif dialect == "postgresql":
        from sqlalchemy.dialects.postgresql import insert as _insert

        stmt_ins = (
            _insert(WeatherCache.__table__)
            .values(**values)
            .on_conflict_do_nothing(index_elements=["geohash_5", "hour_time"])
        )
        await session.execute(stmt_ins)
    else:
        # Fallback: best-effort insert; if it races, ignore the error.
        try:
            async with session.begin_nested():
                session.add(WeatherCache(**values))
                await session.flush()
        except Exception:
            pass

    return payload, False
