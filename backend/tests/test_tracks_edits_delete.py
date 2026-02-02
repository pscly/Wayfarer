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


def test_tracks_edits_delete_restores_points_and_sets_canceled_at(
    client: TestClient,
) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    p1, p2 = _upload_two_points(client, access_token=access)

    # Create DELETE_RANGE edit covering only p1.
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

    # Query should hide p1.
    q1 = client.get(
        "/v1/tracks/query?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z",
        headers=_auth_header(access),
    )
    assert q1.status_code == 200, q1.text
    assert [it["client_point_id"] for it in q1.json()["items"]] == [p2]

    # Cancel the edit.
    dr = client.delete(f"/v1/tracks/edits/{edit_id}", headers=_auth_header(access))
    assert dr.status_code == 204, dr.text

    # Query should show both points again.
    q2 = client.get(
        "/v1/tracks/query?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z",
        headers=_auth_header(access),
    )
    assert q2.status_code == 200, q2.text
    assert [it["client_point_id"] for it in q2.json()["items"]] == [p1, p2]

    # List edits should show canceled_at populated (Z format).
    lr = client.get("/v1/tracks/edits", headers=_auth_header(access))
    assert lr.status_code == 200, lr.text
    body = lr.json()
    assert isinstance(body, list)
    assert len(body) >= 1
    match = next(e for e in body if e.get("edit_id") == edit_id)
    assert match["canceled_at"] is not None
    assert isinstance(match["canceled_at"], str)
    assert match["canceled_at"].endswith("Z")


def test_tracks_edits_delete_nonexistent_returns_404(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    missing = str(uuid.uuid4())
    r = client.delete(f"/v1/tracks/edits/{missing}", headers=_auth_header(access))
    assert r.status_code == 404, r.text
    assert r.json()["code"] == "EDIT_NOT_FOUND"
