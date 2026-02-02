from __future__ import annotations

import asyncio
import uuid

import sqlalchemy as sa
from fastapi.testclient import TestClient

from app.db.session import get_sessionmaker
from app.models.track_point import TrackPoint


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


def _count_track_points() -> int:
    async def _run() -> int:
        sessionmaker = get_sessionmaker()
        async with sessionmaker() as session:
            result = await session.execute(
                sa.select(sa.func.count()).select_from(TrackPoint)
            )
            return int(result.scalar_one())

    return asyncio.run(_run())


def test_tracks_batch_upload_two_points_idempotent(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

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

    r1 = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access))
    assert r1.status_code == 200, r1.text
    body1 = r1.json()
    assert set(body1["accepted_ids"]) == {p1, p2}
    assert body1["rejected"] == []
    assert _count_track_points() == 2

    # Same batch again => still accepted, and no duplicate rows created.
    r2 = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access))
    assert r2.status_code == 200, r2.text
    body2 = r2.json()
    assert set(body2["accepted_ids"]) == {p1, p2}
    assert body2["rejected"] == []
    assert _count_track_points() == 2


def test_tracks_batch_mixed_valid_and_invalid_items(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    valid_id = str(uuid.uuid4())
    invalid_id = str(uuid.uuid4())
    batch = {
        "items": [
            {
                "client_point_id": valid_id,
                "recorded_at": "2026-01-30T12:00:00Z",
                "latitude": 31.2304,
                "longitude": 121.4737,
                "accuracy": 8.0,
            },
            {
                # Missing required fields like recorded_at/longitude/accuracy.
                "client_point_id": invalid_id,
                "latitude": 0.0,
            },
        ]
    }

    r = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access))
    assert r.status_code == 200, r.text
    body = r.json()

    assert set(body["accepted_ids"]) == {valid_id}
    assert len(body["rejected"]) == 1
    rej = body["rejected"][0]
    assert rej.get("client_point_id") == invalid_id
    assert rej.get("reason_code") == "TRACK_BATCH_ITEM_INVALID"
    assert _count_track_points() == 1
