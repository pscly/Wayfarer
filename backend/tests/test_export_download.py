from __future__ import annotations

import uuid

import httpx
import pytest
from fastapi.testclient import TestClient


def _register(client: TestClient, *, email: str, username: str, password: str) -> None:
    r = client.post(
        "/v1/auth/register",
        json={"email": email, "username": username, "password": password},
    )
    assert r.status_code == 201, r.text


def _login_access_token(client: TestClient, *, username: str, password: str) -> str:
    r = client.post(
        "/v1/auth/login",
        json={"username": username, "password": password},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("access_token"), body
    return str(body["access_token"])


def _auth_header(access_token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {access_token}"}


def _upload_one_point(client: TestClient, *, access_token: str) -> str:
    pid = str(uuid.uuid4())
    batch = {
        "items": [
            {
                "client_point_id": pid,
                "recorded_at": "2026-01-30T12:00:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
            },
        ]
    }
    r = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access_token))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["accepted_ids"] == [pid]
    assert body["rejected"] == []
    return pid


def test_export_job_eager_finishes_and_downloads(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)
    _upload_one_point(client, access_token=access)

    r = client.post(
        "/v1/export",
        headers=_auth_header(access),
        json={
            "start": "2026-01-30T11:59:00Z",
            "end": "2026-01-30T12:01:00Z",
            "format": "CSV",
            "include_weather": False,
            "timezone": "UTC",
        },
    )
    assert r.status_code == 202, r.text
    job_id = r.json()["job_id"]

    s = client.get(f"/v1/export/{job_id}", headers=_auth_header(access))
    assert s.status_code == 200, s.text
    assert s.json()["state"] == "SUCCEEDED"

    d = client.get(f"/v1/export/{job_id}/download", headers=_auth_header(access))
    assert d.status_code == 200, d.text
    assert len(d.content) > 0


def test_export_compat_get_streams_small_no_weather(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)
    _upload_one_point(client, access_token=access)

    r = client.get(
        "/v1/export?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z&format=CSV&include_weather=false&timezone=UTC",
        headers=_auth_header(access),
    )
    assert r.status_code == 200, r.text
    assert len(r.content) > 0
    assert "Content-Disposition" in r.headers


def test_export_compat_get_include_weather_returns_202_and_downloads(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)
    _upload_one_point(client, access_token=access)

    async def fake_get(self, url: str, params=None, timeout=None):  # noqa: ANN001
        # Simulate provider timeout; export must still complete as PARTIAL.
        raise httpx.ReadTimeout("timeout", request=httpx.Request("GET", url))

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)

    r = client.get(
        "/v1/export?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z&format=CSV&include_weather=true&timezone=UTC",
        headers=_auth_header(access),
    )
    assert r.status_code == 202, r.text
    body = r.json()
    assert body.get("job_id"), body
    uuid.UUID(str(body["job_id"]))

    s = client.get(f"/v1/export/{body['job_id']}", headers=_auth_header(access))
    assert s.status_code == 200, s.text
    assert s.json()["state"] == "PARTIAL"
    assert s.json()["error"], s.json()

    d = client.get(
        f"/v1/export/{body['job_id']}/download", headers=_auth_header(access)
    )
    assert d.status_code == 200, d.text
    assert len(d.content) > 0

    # CSV includes weather_snapshot_json column, but it's empty on timeout degradation.
    text = d.content.decode("utf-8")
    lines = [ln for ln in text.splitlines() if ln.strip()]
    assert lines, text
    header = lines[0].split(",")
    assert "weather_snapshot_json" in header
    if len(lines) >= 2:
        row = lines[1].split(",")
        assert row[header.index("weather_snapshot_json")] == ""


def test_export_compat_get_threshold_fallback_returns_202(
    client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Force the "too many points" branch for even 1 point.
    monkeypatch.setenv("WAYFARER_SYNC_THRESHOLD_POINTS", "0")
    from app.core.settings import get_settings

    get_settings.cache_clear()

    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)
    _upload_one_point(client, access_token=access)

    r = client.get(
        "/v1/export?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z&format=CSV&include_weather=false&timezone=UTC",
        headers=_auth_header(access),
    )
    assert r.status_code == 202, r.text
    body = r.json()
    assert body.get("job_id"), body

    s = client.get(f"/v1/export/{body['job_id']}", headers=_auth_header(access))
    assert s.status_code == 200, s.text
    assert s.json()["state"] == "SUCCEEDED"

    d = client.get(
        f"/v1/export/{body['job_id']}/download", headers=_auth_header(access)
    )
    assert d.status_code == 200, d.text
    assert len(d.content) > 0

    # Restore defaults for other tests in this process.
    monkeypatch.delenv("WAYFARER_SYNC_THRESHOLD_POINTS", raising=False)
    get_settings.cache_clear()
