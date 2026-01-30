from __future__ import annotations

import uuid

from fastapi.testclient import TestClient


def _register(client: TestClient, *, email: str, username: str, password: str) -> None:
    r = client.post(
        "/v1/auth/register",
        json={"email": email, "username": username, "password": password},
    )
    assert r.status_code == 201, r.text


def _login_access_token(client: TestClient, *, email: str, password: str) -> str:
    # No Origin header => treated as Android/scripting, but still returns access token.
    r = client.post(
        "/v1/auth/login",
        json={"email": email, "password": password},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("access_token"), body
    return body["access_token"]


def _auth_header(access_token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {access_token}"}


def test_life_event_stay_detects_exactly_5min_within_200m(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, email=email, password=password)

    # Two points at the same location, exactly 5 minutes apart => STAY.
    p1 = str(uuid.uuid4())
    p2 = str(uuid.uuid4())
    batch = {
        "items": [
            {
                "client_point_id": p1,
                "recorded_at": "2026-01-30T12:00:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
            },
            {
                "client_point_id": p2,
                "recorded_at": "2026-01-30T12:05:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
            },
        ]
    }
    r = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access))
    assert r.status_code == 200, r.text

    lr = client.get(
        "/v1/life-events?start=2026-01-30T11:59:00Z&end=2026-01-30T12:06:00Z",
        headers=_auth_header(access),
    )
    assert lr.status_code == 200, lr.text
    items = lr.json()["items"]
    stays = [it for it in items if it.get("event_type") == "STAY"]
    assert len(stays) == 1, items
    assert stays[0]["start_at"].endswith("Z")
    assert stays[0]["end_at"].endswith("Z")


def test_life_event_stay_does_not_detect_4m59s_within_200m(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, email=email, password=password)

    p1 = str(uuid.uuid4())
    p2 = str(uuid.uuid4())
    batch = {
        "items": [
            {
                "client_point_id": p1,
                "recorded_at": "2026-01-30T12:00:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
            },
            {
                "client_point_id": p2,
                "recorded_at": "2026-01-30T12:04:59Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
            },
        ]
    }
    r = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access))
    assert r.status_code == 200, r.text

    lr = client.get(
        "/v1/life-events?start=2026-01-30T11:59:00Z&end=2026-01-30T12:06:00Z",
        headers=_auth_header(access),
    )
    assert lr.status_code == 200, lr.text
    items = lr.json()["items"]
    stays = [it for it in items if it.get("event_type") == "STAY"]
    assert stays == [], items
