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


def _upload_two_points(client: TestClient, *, access_token: str) -> tuple[str, str]:
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
                "recorded_at": "2026-01-30T12:00:10Z",
                "latitude": 31.2305,
                "longitude": 121.4738,
                "accuracy": 9.0,
            },
        ]
    }
    r = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access_token))
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body["accepted_ids"]) == {p1, p2}
    assert body["rejected"] == []
    return p1, p2


def test_tracks_edits_create_delete_range_excludes_point_in_query(
    client: TestClient,
) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, email=email, password=password)

    p1, p2 = _upload_two_points(client, access_token=access)

    # Cover only the first point (inclusive range), and keep start < end.
    r = client.post(
        "/v1/tracks/edits",
        json={
            "type": "DELETE_RANGE",
            "start": "2026-01-30T12:00:00Z",
            "end": "2026-01-30T12:00:01Z",
        },
        headers=_auth_header(access),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("edit_id")
    assert body.get("applied_count") == 1

    qr = client.get(
        "/v1/tracks/query?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z",
        headers=_auth_header(access),
    )
    assert qr.status_code == 200, qr.text
    items = qr.json()["items"]
    assert [it["client_point_id"] for it in items] == [p2]


def test_tracks_edits_create_invalid_type_returns_400(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, email=email, password=password)

    r = client.post(
        "/v1/tracks/edits",
        json={
            "type": "NOPE",
            "start": "2026-01-30T12:00:00Z",
            "end": "2026-01-30T12:00:01Z",
        },
        headers=_auth_header(access),
    )
    assert r.status_code == 400, r.text
    assert r.json()["code"] == "EDIT_UNSUPPORTED_TYPE"


def test_tracks_edits_create_invalid_range_returns_400(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, email=email, password=password)

    r = client.post(
        "/v1/tracks/edits",
        json={
            "type": "DELETE_RANGE",
            "start": "2026-01-30T12:00:00Z",
            "end": "2026-01-30T12:00:00Z",
        },
        headers=_auth_header(access),
    )
    assert r.status_code == 400, r.text
    assert r.json()["code"] == "EDIT_INVALID_RANGE"
