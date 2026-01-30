from __future__ import annotations

import asyncio
import datetime as dt

import httpx
import pytest

from app.db.session import get_sessionmaker
from app.models.weather_cache import WeatherCache
from app.services.weather import floor_to_hour_utc, get_weather_snapshot
from app.utils.geohash import encode


def _open_meteo_ok_payload(*, hour: dt.datetime) -> dict[str, object]:
    # Keep this intentionally minimal; the client only needs hourly.time and
    # the requested hourly fields.
    ts = hour.replace(tzinfo=None).isoformat(timespec="minutes")
    return {
        "hourly": {
            "time": [ts],
            "temperature_2m": [10.0],
            "relativehumidity_2m": [50],
            "precipitation": [0.0],
            "weathercode": [3],
            "windspeed_10m": [2.5],
        }
    }


def test_weather_provider_429_uses_exponential_backoff(
    client,  # noqa: ARG001
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[int] = []
    sleep_delays: list[float] = []

    async def fake_sleep(delay: float) -> None:
        sleep_delays.append(delay)

    async def fake_get(self, url: str, params=None, timeout=None):  # noqa: ANN001
        calls.append(1)
        req = httpx.Request("GET", url)
        if len(calls) <= 2:
            return httpx.Response(429, request=req)

        payload = _open_meteo_ok_payload(
            hour=dt.datetime(2026, 1, 30, 12, tzinfo=dt.timezone.utc)
        )
        return httpx.Response(200, json=payload, request=req)

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)

    async def _run() -> None:
        sessionmaker = get_sessionmaker()
        async with sessionmaker() as session:
            snap, degraded = await get_weather_snapshot(
                session=session,
                latitude=31.2304,
                longitude=121.4737,
                recorded_at=dt.datetime(2026, 1, 30, 12, 34, tzinfo=dt.timezone.utc),
                sleep=fake_sleep,
            )
            assert degraded is False
            assert isinstance(snap, dict)

    asyncio.run(_run())

    # 2x 429 + 1x success.
    assert len(calls) == 3
    assert sleep_delays == [0.5, 1.0]


def test_weather_cache_hit_avoids_provider_call(
    client,  # noqa: ARG001
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    hour = dt.datetime(2026, 1, 30, 12, tzinfo=dt.timezone.utc)
    geohash_5 = encode(31.2304, 121.4737, precision=5)

    async def fake_get(self, url: str, params=None, timeout=None):  # noqa: ANN001
        raise AssertionError("provider should not be called on cache hit")

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)

    async def _run() -> None:
        sessionmaker = get_sessionmaker()
        async with sessionmaker() as session:
            payload = {"provider": "cached", "hour_time": hour.isoformat()}
            session.add(
                WeatherCache(
                    geohash_5=geohash_5,
                    hour_time=floor_to_hour_utc(hour),
                    payload=payload,
                )
            )
            await session.commit()

        async with sessionmaker() as session:
            snap, degraded = await get_weather_snapshot(
                session=session,
                latitude=31.2304,
                longitude=121.4737,
                recorded_at=hour,
            )
            assert degraded is False
            assert snap == payload

    asyncio.run(_run())
