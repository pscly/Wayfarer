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
    return str(body["access_token"])


def _auth_header(access_token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {access_token}"}


def test_anti_cheat_impossible_step_rate_marks_later_point_dirty(
    client: TestClient,
) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, email=email, password=password)

    # ~10m north in latitude (1 deg lat ~= 111,320m).
    lat1, lon1 = 31.2304, 121.4737
    lat2, lon2 = lat1 + 0.0000899, lon1

    p1 = str(uuid.uuid4())
    p2 = str(uuid.uuid4())
    batch = {
        "items": [
            {
                "client_point_id": p1,
                "recorded_at": "2026-01-30T12:00:00Z",
                "latitude": lat1,
                "longitude": lon1,
                "accuracy": 5.0,
                # step_delta omitted/None => treated as 0.
            },
            {
                "client_point_id": p2,
                "recorded_at": "2026-01-30T12:00:01Z",
                "latitude": lat2,
                "longitude": lon2,
                "accuracy": 5.0,
                "step_delta": 10,  # 10 steps/sec => impossible_step_rate (>4)
            },
        ]
    }

    r1 = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access))
    assert r1.status_code == 200, r1.text
    assert set(r1.json()["accepted_ids"]) == {p1, p2}
    assert r1.json()["rejected"] == []

    r2 = client.get(
        "/v1/tracks/query?start=2026-01-30T11:59:00Z&end=2026-01-30T12:00:02Z",
        headers=_auth_header(access),
    )
    assert r2.status_code == 200, r2.text
    items = r2.json()["items"]
    by_id = {it["client_point_id"]: it for it in items}
    assert set(by_id) == {p1, p2}
    assert by_id[p1]["is_dirty"] is False
    assert by_id[p2]["is_dirty"] is True


def test_anti_cheat_normal_walking_does_not_mark_points_dirty(
    client: TestClient,
) -> None:
    email = f"u-{uuid.uuid4().hex}@test.com"
    username = f"u-{uuid.uuid4().hex}"
    password = "password123!"
    _register(client, email=email, username=username, password=password)
    access = _login_access_token(client, email=email, password=password)

    # ~10m segment over 10s => ~1 m/s; 12 steps => ~0.83 m/step; 1.2 steps/s.
    lat1, lon1 = 31.2304, 121.4737
    lat2, lon2 = lat1 + 0.0000899, lon1

    p1 = str(uuid.uuid4())
    p2 = str(uuid.uuid4())
    batch = {
        "items": [
            {
                "client_point_id": p1,
                "recorded_at": "2026-01-30T12:00:00Z",
                "latitude": lat1,
                "longitude": lon1,
                "accuracy": 5.0,
            },
            {
                "client_point_id": p2,
                "recorded_at": "2026-01-30T12:00:10Z",
                "latitude": lat2,
                "longitude": lon2,
                "accuracy": 5.0,
                "step_delta": 12,
            },
        ]
    }

    r1 = client.post("/v1/tracks/batch", json=batch, headers=_auth_header(access))
    assert r1.status_code == 200, r1.text
    assert set(r1.json()["accepted_ids"]) == {p1, p2}
    assert r1.json()["rejected"] == []

    r2 = client.get(
        "/v1/tracks/query?start=2026-01-30T11:59:00Z&end=2026-01-30T12:00:20Z",
        headers=_auth_header(access),
    )
    assert r2.status_code == 200, r2.text
    items = r2.json()["items"]
    by_id = {it["client_point_id"]: it for it in items}
    assert set(by_id) == {p1, p2}
    assert by_id[p1]["is_dirty"] is False
    assert by_id[p2]["is_dirty"] is False
