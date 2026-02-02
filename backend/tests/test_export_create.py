from __future__ import annotations

import uuid

from fastapi.testclient import TestClient


def _register(client: TestClient, *, email: str, username: str, password: str) -> None:
    r = client.post(
        "/v1/auth/register",
        json={"email": email, "username": username, "password": password},
    )
    assert r.status_code == 201, r.text


def _android_login(client: TestClient, *, username: str, password: str) -> str:
    # No Origin header -> treated as Android/scripting client.
    r = client.post("/v1/auth/login", json={"username": username, "password": password})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("access_token")
    return str(body["access_token"])


def test_post_export_creates_job_and_returns_202(client: TestClient) -> None:
    _register(
        client,
        email="export@test.com",
        username="export",
        password="password123!",
    )
    access = _android_login(client, username="export", password="password123!")

    r = client.post(
        "/v1/export",
        headers={"Authorization": f"Bearer {access}"},
        json={
            "start": "2026-01-01T00:00:00Z",
            "end": "2026-02-01T00:00:00Z",
            "format": "CSV",
            "include_weather": False,
            "timezone": "UTC",
        },
    )
    assert r.status_code == 202, r.text
    body = r.json()
    assert body.get("job_id")
    uuid.UUID(str(body["job_id"]))
