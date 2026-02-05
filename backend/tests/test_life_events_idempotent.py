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


def test_life_event_create_idempotent_by_client_id(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    event_id = str(uuid.uuid4())
    payload = {
        "id": event_id,
        "event_type": "MARK_POINT",
        "start_at": "2026-01-30T12:00:00Z",
        "end_at": "2026-01-30T12:00:01Z",
        "location_name": "出门买东西",
        "manual_note": "测试幂等",
        "payload_json": {"source": "test"},
    }

    r1 = client.post("/v1/life-events", json=payload, headers=_auth_header(access))
    assert r1.status_code == 201, r1.text
    assert r1.json()["id"] == event_id

    # Same id again should return the existing row (idempotent).
    r2 = client.post("/v1/life-events", json=payload, headers=_auth_header(access))
    assert r2.status_code == 201, r2.text
    assert r2.json()["id"] == event_id

    lr = client.get(
        "/v1/life-events?start=2026-01-30T11:59:00Z&end=2026-01-30T12:02:00Z",
        headers=_auth_header(access),
    )
    assert lr.status_code == 200, lr.text
    items = [it for it in lr.json()["items"] if it.get("id") == event_id]
    assert len(items) == 1

