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


def test_tracks_edits_list_returns_created_edit(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    cr = client.post(
        "/v1/tracks/edits",
        json={
            "type": "DELETE_RANGE",
            "start": "2026-01-30T12:00:00Z",
            "end": "2026-01-30T12:00:01Z",
        },
        headers=_auth_header(access),
    )
    assert cr.status_code == 200, cr.text
    edit_id = cr.json().get("edit_id")
    assert edit_id

    r = client.get("/v1/tracks/edits", headers=_auth_header(access))
    assert r.status_code == 200, r.text
    body = r.json()
    assert isinstance(body, list)
    assert len(body) >= 1

    # Newest first; for this test, we only created one edit.
    item = body[0]
    assert item["edit_id"] == edit_id
    assert item["type"] == "DELETE_RANGE"
    assert item["canceled_at"] is None

    for key in ("start", "end", "created_at"):
        assert isinstance(item[key], str)
        assert item[key].endswith("Z")
