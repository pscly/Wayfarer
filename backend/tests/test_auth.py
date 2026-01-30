from __future__ import annotations

from fastapi.testclient import TestClient


WEB_ORIGIN = "http://localhost:3000"


def _register(client: TestClient, *, email: str, username: str, password: str) -> None:
    r = client.post(
        "/v1/auth/register",
        json={"email": email, "username": username, "password": password},
    )
    assert r.status_code == 201, r.text


def _web_login(client: TestClient, *, email: str, password: str) -> str:
    r = client.post(
        "/v1/auth/login",
        json={"email": email, "password": password},
        headers={"Origin": WEB_ORIGIN},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("access_token")
    assert body.get("refresh_token") in (None, "")
    return body["access_token"]


def test_web_login_sets_cookies_and_no_refresh_in_body(client: TestClient) -> None:
    _register(
        client,
        email="web@test.com",
        username="web",
        password="password123!",
    )

    r = client.post(
        "/v1/auth/login",
        json={"email": "web@test.com", "password": "password123!"},
        headers={"Origin": WEB_ORIGIN},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("access_token")
    assert "refresh_token" not in body or body.get("refresh_token") is None

    set_cookies = r.headers.get_list("set-cookie")
    assert any(c.lower().startswith("wf_refresh=") for c in set_cookies)
    assert any(c.lower().startswith("wf_csrf=") for c in set_cookies)

    refresh_sc = next(c for c in set_cookies if c.lower().startswith("wf_refresh="))
    csrf_sc = next(c for c in set_cookies if c.lower().startswith("wf_csrf="))

    assert "httponly" in refresh_sc.lower()
    assert "samesite=lax" in refresh_sc.lower()
    assert "path=/" in refresh_sc.lower()
    assert "secure" not in refresh_sc.lower()

    assert "httponly" not in csrf_sc.lower()
    assert "samesite=lax" in csrf_sc.lower()
    assert "path=/" in csrf_sc.lower()
    assert "secure" not in csrf_sc.lower()


def test_web_refresh_requires_csrf_header(client: TestClient) -> None:
    _register(
        client,
        email="csrf@test.com",
        username="csrf",
        password="password123!",
    )
    _web_login(client, email="csrf@test.com", password="password123!")

    r = client.post(
        "/v1/auth/refresh",
        headers={"Origin": WEB_ORIGIN},
    )
    assert r.status_code == 403
    assert r.json()["code"] == "AUTH_FORBIDDEN"


def test_web_refresh_rejects_csrf_mismatch(client: TestClient) -> None:
    _register(
        client,
        email="csrf2@test.com",
        username="csrf2",
        password="password123!",
    )
    _web_login(client, email="csrf2@test.com", password="password123!")

    r = client.post(
        "/v1/auth/refresh",
        headers={"Origin": WEB_ORIGIN, "X-CSRF-Token": "wrong"},
    )
    assert r.status_code == 403
    assert r.json()["code"] == "AUTH_FORBIDDEN"


def test_refresh_rotation_and_reuse_detection(client: TestClient) -> None:
    _register(
        client,
        email="rot@test.com",
        username="rot",
        password="password123!",
    )
    _web_login(client, email="rot@test.com", password="password123!")

    old_refresh = client.cookies.get("wf_refresh")
    old_csrf = client.cookies.get("wf_csrf")
    assert old_refresh
    assert old_csrf

    # Normal refresh rotates the refresh cookie.
    r1 = client.post(
        "/v1/auth/refresh",
        headers={"Origin": WEB_ORIGIN, "X-CSRF-Token": old_csrf},
    )
    assert r1.status_code == 200, r1.text
    assert r1.json().get("access_token")
    assert "refresh_token" not in r1.json() or r1.json().get("refresh_token") is None

    new_refresh = client.cookies.get("wf_refresh")
    new_csrf = client.cookies.get("wf_csrf")
    assert new_refresh
    assert new_csrf
    assert new_refresh != old_refresh

    # Using an already-revoked refresh token again triggers reuse detection.
    # We must override the cookie jar to force the old token to be used.
    client.cookies.set("wf_refresh", old_refresh)
    client.cookies.set("wf_csrf", old_csrf)
    r2 = client.post(
        "/v1/auth/refresh",
        headers={"Origin": WEB_ORIGIN, "X-CSRF-Token": old_csrf},
    )
    assert r2.status_code == 401
    assert r2.json()["code"] == "AUTH_REFRESH_REUSED"

    # Family revoke: even the newest token should no longer work.
    client.cookies.set("wf_refresh", new_refresh)
    client.cookies.set("wf_csrf", new_csrf)
    r3 = client.post(
        "/v1/auth/refresh",
        headers={"Origin": WEB_ORIGIN, "X-CSRF-Token": new_csrf},
    )
    assert r3.status_code == 401
    assert r3.json()["code"] == "AUTH_REFRESH_REUSED"


def test_android_login_and_refresh_return_refresh_token_in_body(
    client: TestClient,
) -> None:
    _register(
        client,
        email="android@test.com",
        username="android",
        password="password123!",
    )
    r = client.post(
        "/v1/auth/login",
        json={"email": "android@test.com", "password": "password123!"},
        # No Origin -> treated as Android/scripting.
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("access_token")
    assert body.get("refresh_token")
    assert not r.headers.get_list("set-cookie")

    r2 = client.post("/v1/auth/refresh", json={"refresh_token": body["refresh_token"]})
    assert r2.status_code == 200, r2.text
    body2 = r2.json()
    assert body2.get("access_token")
    assert body2.get("refresh_token")
