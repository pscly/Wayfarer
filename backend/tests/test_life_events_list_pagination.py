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


def test_life_events_list_supports_limit_offset(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    # Create 3 events in a small time window.
    created_ids: list[str] = []
    for i, ts in enumerate(
        [
            "2026-01-30T12:00:00Z",
            "2026-01-30T12:01:00Z",
            "2026-01-30T12:02:00Z",
        ]
    ):
        event_id = str(uuid.uuid4())
        created_ids.append(event_id)
        payload = {
            "id": event_id,
            "event_type": "MARK_POINT",
            "start_at": ts,
            "end_at": "2026-01-30T12:02:01Z",
            "location_name": f"测试-{i}",
            "manual_note": "pagination",
        }
        r = client.post("/v1/life-events", json=payload, headers=_auth_header(access))
        assert r.status_code == 201, r.text
        assert r.json()["id"] == event_id

    # Page 1: 2 items.
    r1 = client.get(
        "/v1/life-events?start=2026-01-30T11:59:00Z&end=2026-01-30T12:03:00Z&limit=2&offset=0",
        headers=_auth_header(access),
    )
    assert r1.status_code == 200, r1.text
    items1 = r1.json()["items"]
    assert len(items1) == 2
    assert [it["id"] for it in items1] == created_ids[:2]

    # Page 2: 1 item.
    r2 = client.get(
        "/v1/life-events?start=2026-01-30T11:59:00Z&end=2026-01-30T12:03:00Z&limit=2&offset=2",
        headers=_auth_header(access),
    )
    assert r2.status_code == 200, r2.text
    items2 = r2.json()["items"]
    assert len(items2) == 1
    assert [it["id"] for it in items2] == created_ids[2:]
