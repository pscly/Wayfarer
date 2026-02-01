# Wayfarer

Wayfarer is a monorepo with:

- `backend/`: FastAPI + SQLAlchemy (async) on port `8000`
- `web/`: Next.js 14 on port `3000`
- `android/`: placeholder

Primary production target: `https://waf.pscly.cc`

This README is intended to be the project manual: local dev, configuration, and end-to-end production deployment.

## Quick start (local dev on Windows)

Prereqs:

- Windows
- `uv` installed
- Node.js + npm installed

Steps:

1) (Optional) Create `.env`

   Copy `.env.example` to `.env` at repo root.

2) Start backend + web

   Run:

   ```bat
   run.bat
   ```

   Notes (actual behavior):

   - `run.bat` runs `uv sync` in `backend/`.
   - `run.bat` runs `npm install` in `web/` only if `web/node_modules/` is missing.
   - PIDs are recorded in `.sisyphus/run/backend.pid` and `.sisyphus/run/web.pid`.
   - `run.bat` exits 0 only after `http://localhost:8000/healthz` returns 200.
   - If `WAYFARER_JWT_SIGNING_KEYS_JSON` is missing, `run.bat` generates a dev-only key map JSON and injects it into the backend process only.

3) Stop backend + web

   Run:

   ```bat
   stop.bat
   ```

   Notes (actual behavior):

   - `stop.bat` kills only the recorded PIDs (process tree) and then verifies ports `8000/3000` are no longer listening.

Local URLs:

- Web: `http://localhost:3000`
- Backend health: `http://localhost:8000/healthz`
- Backend ready: `http://localhost:8000/readyz`

## Configuration

This repo uses environment variables.

- Backend settings are loaded with prefix `WAYFARER_`.
- Web reads public env vars at build/runtime (Next.js) like `NEXT_PUBLIC_API_BASE_URL`.

### Web env vars

Used in `web/lib/api.ts`:

- `NEXT_PUBLIC_API_BASE_URL`
  - Default: `http://localhost:8000`
  - Production for `waf.pscly.cc`: set to `https://waf.pscly.cc` (so web calls `https://waf.pscly.cc/v1/...`).

Other web vars:

- `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN` (optional, depends on features)

### Backend env vars

Core:

- `WAYFARER_DB_URL`
  - Default: `sqlite+aiosqlite:///./data/dev.db` (good for local dev)
- `WAYFARER_JWT_SIGNING_KEYS_JSON`
  - Required for real deployments
  - Format: JSON map of `kid -> secret`, example: `{"prod-1":"REPLACE_WITH_32B_BASE64URL"}`
  - MUST be stable in production (do NOT rely on `run.bat` auto-generation)
- `WAYFARER_JWT_KID_CURRENT`
  - The active key id in `WAYFARER_JWT_SIGNING_KEYS_JSON`
  - Default: `dev-1`

Web cookie + CORS (important for browser auth):

- `WAYFARER_CORS_ALLOW_ORIGIN`
  - Default: `http://localhost:3000`
  - Production for `waf.pscly.cc`: set to `https://waf.pscly.cc`
- `WAYFARER_CORS_ALLOW_CREDENTIALS`
  - Default: `1` (true)
- `WAYFARER_DEV_COOKIE_SECURE`
  - Controls the cookie `Secure` flag for `wf_refresh` and `wf_csrf`
  - Local dev default: `0`
  - Production behind HTTPS: set to `1`

Async tasks (dev-friendly defaults):

- `WAYFARER_CELERY_EAGER`
  - Local dev default: `1` (tasks run inline)
  - Production recommendation: `0` (only if you actually run a worker + broker)

Third-party (optional):

- `WAYFARER_AMAP_API_KEY`

## How auth works (web vs non-web clients)

The backend has two modes for refresh tokens:

1) Web client (browser)

- The backend detects "web" by checking `Origin == WAYFARER_CORS_ALLOW_ORIGIN`.
- On login/refresh, the backend sets cookies:
  - `wf_refresh`: httpOnly refresh token
  - `wf_csrf`: readable by JS (double-submit CSRF)
- The web client MUST send cookies, so `fetch(..., { credentials: "include" })` is required (already enforced in `web/lib/api.ts`).

2) Android / scripts (no Origin)

- Clients typically do not send `Origin`.
- The backend returns `refresh_token` in the JSON response body, and expects it back in the request body for refresh.

Production implications:

- If you serve web at `https://waf.pscly.cc`, set `WAYFARER_CORS_ALLOW_ORIGIN=https://waf.pscly.cc`.
- If you terminate TLS (HTTPS), set `WAYFARER_DEV_COOKIE_SECURE=1` so cookies have `Secure`.

## Database and migrations

Local dev default is SQLite (async) at `./data/dev.db`.

For production, use PostgreSQL.

Example DB URLs:

- SQLite (dev): `sqlite+aiosqlite:///./data/dev.db`
- PostgreSQL (prod example): `postgresql+psycopg://wayfarer:CHANGE_ME@127.0.0.1:5432/wayfarer`

Alembic is configured in `backend/alembic.ini`, but the URL is set dynamically in `backend/alembic/env.py` from `WAYFARER_DB_URL`.

Common migration commands (run from `backend/`):

```bat
uv run alembic upgrade head
uv run alembic current
uv run alembic history
```

## Production deployment: https://waf.pscly.cc

Recommended layout (single host):

- Nginx listens on `:443` for `waf.pscly.cc`
- Nginx routes:
  - `/v1/` -> backend `http://127.0.0.1:8000`
  - `/` -> Next.js `http://127.0.0.1:3000`

This keeps web + API on the same origin (`https://waf.pscly.cc`), which matches the backend "web client" behavior.

### Required production env vars (safe placeholders)

Backend (example values):

```ini
WAYFARER_DB_URL=postgresql+psycopg://wayfarer:CHANGE_ME@127.0.0.1:5432/wayfarer
WAYFARER_CORS_ALLOW_ORIGIN=https://waf.pscly.cc
WAYFARER_CORS_ALLOW_CREDENTIALS=1
WAYFARER_DEV_COOKIE_SECURE=1

# IMPORTANT: stable signing keys (do NOT rely on run.bat auto-generation)
WAYFARER_JWT_SIGNING_KEYS_JSON='{"prod-1":"REPLACE_WITH_32B_BASE64URL"}'
WAYFARER_JWT_KID_CURRENT=prod-1

# Optional
WAYFARER_CELERY_EAGER=0
```

Web (example values):

```ini
NEXT_PUBLIC_API_BASE_URL=https://waf.pscly.cc
NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN=CHANGE_ME_IF_USED
```

### Minimal Nginx config (reverse proxy)

This is a minimal server block example (ASCII only). Adjust SSL cert paths for your environment.

```nginx
server {
  listen 443 ssl;
  server_name waf.pscly.cc;

  # ssl_certificate     /etc/letsencrypt/live/waf.pscly.cc/fullchain.pem;
  # ssl_certificate_key /etc/letsencrypt/live/waf.pscly.cc/privkey.pem;

  # Backend API
  location /v1/ {
    proxy_pass http://127.0.0.1:8000;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  # Next.js web
  location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
}
```

### systemd units (example)

Assumptions:

- Repo checked out to `/opt/wayfarer`
- Backend runs with `uv` in `/opt/wayfarer/backend`
- Web runs with `npm` in `/opt/wayfarer/web`
- Env files are managed in `/etc/wayfarer/` (recommended)

Backend unit: `/etc/systemd/system/wayfarer-backend.service`

```ini
[Unit]
Description=Wayfarer Backend (FastAPI)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/wayfarer/backend

# Keep env vars out of unit files when possible.
EnvironmentFile=/etc/wayfarer/wayfarer-backend.env

# Ensure deps exist (run once manually is usually better).
ExecStart=/usr/bin/env uv run uvicorn main:app --host 127.0.0.1 --port 8000

Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

Web unit: `/etc/systemd/system/wayfarer-web.service`

```ini
[Unit]
Description=Wayfarer Web (Next.js)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/wayfarer/web
EnvironmentFile=/etc/wayfarer/wayfarer-web.env

# Production run: build once during deploy, then start.
ExecStart=/usr/bin/env npm run start -- -p 3000

Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

### Deployment checklist (suggested)

1) Backend

- Set stable JWT signing keys (`WAYFARER_JWT_SIGNING_KEYS_JSON`) and `WAYFARER_JWT_KID_CURRENT`.
- Set DB URL (`WAYFARER_DB_URL`) to PostgreSQL.
- Run migrations:

  ```bash
  cd /opt/wayfarer/backend
  # load the same env vars as the service, then:
  uv run alembic upgrade head
  ```

2) Web

- Set `NEXT_PUBLIC_API_BASE_URL=https://waf.pscly.cc`.
- Build once:

  ```bash
  cd /opt/wayfarer/web
  npm ci
  npm run build
  ```

3) Nginx

- Enable the site config and reload: `nginx -t && systemctl reload nginx`

## Operations

Health checks:

```bash
curl -fsS http://127.0.0.1:8000/healthz
curl -fsS http://127.0.0.1:8000/readyz
```

Service status and logs:

```bash
systemctl status wayfarer-backend
systemctl status wayfarer-web

journalctl -u wayfarer-backend -f
journalctl -u wayfarer-web -f
```

Restart:

```bash
systemctl restart wayfarer-backend
systemctl restart wayfarer-web
```

Database migrations (same env as backend):

```bash
cd /opt/wayfarer/backend
uv run alembic upgrade head
```
