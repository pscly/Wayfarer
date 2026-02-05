from __future__ import annotations

import uuid

from fastapi.testclient import TestClient


def _register(client: TestClient, *, email: str, username: str, password: str) -> None:
    r = client.post(
        "/v1/auth/register",
        json={"email": email, "username": username, "password": password},
    )
    assert r.status_code == 201, r.text


def _login_access_token(client: TestClient, *, username: str, password: str) -> str:
    # No Origin header => treated as Android/scripting, but still returns access token.
    r = client.post(
        "/v1/auth/login",
        json={"username": username, "password": password},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("access_token"), body
    return body["access_token"]


def _auth_header(access_token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {access_token}"}


def test_stats_steps_daily_and_hourly(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    batch = {
        "items": [
            {
                "client_point_id": str(uuid.uuid4()),
                "recorded_at": "2026-01-30T12:00:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
                "step_count": 100,
                "step_delta": 10,
            },
            {
                "client_point_id": str(uuid.uuid4()),
                "recorded_at": "2026-01-30T12:30:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
                "step_count": 105,
                "step_delta": 5,
            },
            {
                "client_point_id": str(uuid.uuid4()),
                "recorded_at": "2026-01-30T13:05:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
                "step_count": 112,
                "step_delta": 7,
            },
            {
                "client_point_id": str(uuid.uuid4()),
                "recorded_at": "2026-01-31T00:10:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
                "step_count": 115,
                "step_delta": 3,
            },
        ]
    }
    r = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access))
    assert r.status_code == 200, r.text

    dr = client.get(
        "/v1/stats/steps/daily?start=2026-01-30T00:00:00Z&end=2026-01-31T23:59:59Z",
        headers=_auth_header(access),
    )
    assert dr.status_code == 200, dr.text
    daily = dr.json()["items"]
    assert daily == [
        {"day": "2026-01-30", "steps": 22},
        {"day": "2026-01-31", "steps": 3},
    ]

    hr = client.get(
        "/v1/stats/steps/hourly?start=2026-01-30T12:00:00Z&end=2026-01-30T13:59:59Z",
        headers=_auth_header(access),
    )
    assert hr.status_code == 200, hr.text
    hourly = hr.json()["items"]
    assert hourly == [
        {"hour_start": "2026-01-30T12:00:00Z", "steps": 15},
        {"hour_start": "2026-01-30T13:00:00Z", "steps": 7},
    ]


def test_stats_steps_invalid_range_returns_400(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    r = client.get(
        "/v1/stats/steps/daily?start=2026-01-30T00:00:00Z&end=2026-01-30T00:00:00Z",
        headers=_auth_header(access),
    )
    assert r.status_code == 400, r.text
    assert r.json()["code"] == "STATS_STEPS_INVALID_RANGE"

