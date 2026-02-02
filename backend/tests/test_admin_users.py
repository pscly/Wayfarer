from __future__ import annotations

from fastapi.testclient import TestClient


def _register(
    client: TestClient, *, username: str, password: str, email: str | None = None
) -> dict:
    payload: dict = {"username": username, "password": password}
    if email is not None:
        payload["email"] = email
    r = client.post("/v1/auth/register", json=payload)
    assert r.status_code == 201, r.text
    return r.json()


def _login_access_token(client: TestClient, *, username: str, password: str) -> str:
    r = client.post("/v1/auth/login", json={"username": username, "password": password})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("access_token")
    return body["access_token"]


def test_first_user_is_admin_and_can_manage_users(client: TestClient) -> None:
    admin = _register(client, username="admin", password="password123!")
    assert admin.get("is_admin") is True

    admin_access = _login_access_token(
        client, username="admin", password="password123!"
    )
    r = client.get(
        "/v1/admin/users", headers={"Authorization": f"Bearer {admin_access}"}
    )
    assert r.status_code == 200, r.text
    items = r.json()
    assert isinstance(items, list)
    assert len(items) == 1
    assert items[0]["username"] == "admin"
    assert items[0]["is_admin"] is True


def test_non_admin_forbidden_and_admin_can_promote(client: TestClient) -> None:
    admin = _register(client, username="admin", password="password123!")
    user = _register(client, username="u2", password="password123!")
    assert admin.get("is_admin") is True
    assert user.get("is_admin") is False

    user_access = _login_access_token(client, username="u2", password="password123!")
    r_forbid = client.get(
        "/v1/admin/users",
        headers={"Authorization": f"Bearer {user_access}"},
    )
    assert r_forbid.status_code == 403
    assert r_forbid.json()["code"] == "AUTH_FORBIDDEN"

    admin_access = _login_access_token(
        client, username="admin", password="password123!"
    )
    r_promote = client.put(
        f"/v1/admin/users/{user['user_id']}/admin",
        json={"is_admin": True},
        headers={"Authorization": f"Bearer {admin_access}"},
    )
    assert r_promote.status_code == 200, r_promote.text
    assert r_promote.json()["is_admin"] is True

    # Now user can access admin endpoints.
    r_ok = client.get(
        "/v1/admin/users",
        headers={"Authorization": f"Bearer {user_access}"},
    )
    assert r_ok.status_code == 200, r_ok.text
