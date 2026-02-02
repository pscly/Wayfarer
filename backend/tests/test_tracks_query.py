from __future__ import annotations

import asyncio
import datetime as dt
import uuid

from fastapi.testclient import TestClient

from app.db.base import utcnow
from app.db.session import get_sessionmaker
from app.models.track_edit import TrackEdit


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


def _me_user_id(client: TestClient, *, access_token: str) -> str:
    r = client.get("/v1/users/me", headers=_auth_header(access_token))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("user_id"), body
    return str(body["user_id"])


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


def _insert_delete_range_edit(
    *, user_id: str, start_at: dt.datetime, end_at: dt.datetime
) -> None:
    async def _run() -> None:
        sessionmaker = get_sessionmaker()
        async with sessionmaker() as session:
            session.add(
                TrackEdit(
                    user_id=uuid.UUID(user_id),
                    type="DELETE_RANGE",
                    start_at=start_at,
                    end_at=end_at,
                    created_at=utcnow(),
                    canceled_at=None,
                    note=None,
                )
            )
            await session.commit()

    asyncio.run(_run())


def test_tracks_query_happy_path_orders_and_z_format(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)
    p1, p2 = _upload_two_points(client, access_token=access)

    r = client.get(
        "/v1/tracks/query?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z",
        headers=_auth_header(access),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    items = body["items"]
    assert len(items) == 2

    # Ordered by recorded_at ASC.
    assert items[0]["client_point_id"] == p1
    assert items[1]["client_point_id"] == p2
    assert items[0]["recorded_at"] < items[1]["recorded_at"]

    # Ensure Z formatting.
    assert items[0]["recorded_at"].endswith("Z")
    assert items[1]["recorded_at"].endswith("Z")


def test_tracks_query_pagination_limit_offset(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)
    _p1, p2 = _upload_two_points(client, access_token=access)

    r = client.get(
        "/v1/tracks/query?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z&limit=1&offset=1",
        headers=_auth_header(access),
    )
    assert r.status_code == 200, r.text
    items = r.json()["items"]
    assert len(items) == 1
    assert items[0]["client_point_id"] == p2
    assert items[0]["recorded_at"].endswith("Z")


def test_tracks_query_invalid_range_returns_400(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)

    r = client.get(
        "/v1/tracks/query?start=2026-01-30T12:00:00Z&end=2026-01-30T12:00:00Z",
        headers=_auth_header(access),
    )
    assert r.status_code == 400, r.text
    assert r.json()["code"] == "TRACK_QUERY_INVALID_RANGE"


def test_tracks_query_filters_active_delete_range_edit(client: TestClient) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, username=username, password=password)
    p1, p2 = _upload_two_points(client, access_token=access)

    user_id = _me_user_id(client, access_token=access)
    t0 = dt.datetime(2026, 1, 30, 12, 0, 0, tzinfo=dt.timezone.utc)
    _insert_delete_range_edit(user_id=user_id, start_at=t0, end_at=t0)

    r = client.get(
        "/v1/tracks/query?start=2026-01-30T11:59:00Z&end=2026-01-30T12:01:00Z",
        headers=_auth_header(access),
    )
    assert r.status_code == 200, r.text
    items = r.json()["items"]
    assert [it["client_point_id"] for it in items] == [p2]
    assert items[0]["recorded_at"].endswith("Z")
