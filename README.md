# Wayfarer

Wayfarer 是一个单仓库（monorepo），包含：

- `backend/`: FastAPI + SQLAlchemy（异步），默认端口 `8000`
- `web/`: Next.js 14（App Router），默认端口 `3000`
- `android/`: 原生 Android（Kotlin + Jetpack Compose）

主要线上部署地址：`https://waf.pscly.cc`

本 README 作为项目使用手册：本地开发、配置，以及端到端的生产部署。

## 快速开始（Windows 本地开发）

前置条件：

- Windows
- 已安装 `uv`
- 已安装 Node.js + npm

步骤：

1) （可选）创建 `.env`

   将仓库根目录的 `.env.example` 复制为 `.env`。

2) 启动后端 + Web

   运行：

   ```bat
   run.bat
   ```

   说明（实际行为）：

   - `run.bat` 会在 `backend/` 里执行 `uv sync`。
   - `run.bat` 只在 `web/node_modules/` 缺失时才会在 `web/` 里执行 `npm install`。
   - 进程 PID 会记录在 `.sisyphus/run/backend.pid` 和 `.sisyphus/run/web.pid`。
   - `run.bat` 只有在 `http://localhost:8000/healthz` 返回 200 后才会以 0 退出。
   - 如果缺少 `WAYFARER_JWT_SIGNING_KEYS_JSON`，`run.bat` 会生成仅用于本地开发的 key map JSON，并仅注入到本次后端进程（不会写入仓库文件）。

3) 停止后端 + Web

   运行：

   ```bat
   stop.bat
   ```

   说明（实际行为）：

   - `stop.bat` 只会杀掉记录过的 PID（含子进程），然后验证端口 `8000/3000` 不再监听。

本地地址：

- Web：`http://localhost:3000`
- Backend 健康检查：`http://localhost:8000/healthz`
- Backend 就绪检查：`http://localhost:8000/readyz`

## 配置

本仓库使用环境变量进行配置。

- 后端配置项统一使用前缀 `WAYFARER_`。
- Web（Next.js）读取公开环境变量（build/runtime），例如 `NEXT_PUBLIC_API_BASE_URL`。

### Web 环境变量

在 `web/lib/api.ts` 中使用：

- `NEXT_PUBLIC_API_BASE_URL`
  - 默认：`http://localhost:8000`
  - `waf.pscly.cc` 线上：设置为 `https://waf.pscly.cc`（这样 Web 会请求 `https://waf.pscly.cc/v1/...`）

其他 Web 变量：

- `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN`（可选，取决于功能开关）

### Backend 环境变量

核心：

- `WAYFARER_DB_URL`
  - 默认：`sqlite+aiosqlite:///./data/dev.db`（适合本地开发）
- `WAYFARER_JWT_SIGNING_KEYS_JSON`
  - 真实部署必填
  - 格式：`kid -> secret` 的 JSON map，例如：`{"prod-1":"REPLACE_WITH_32B_BASE64URL"}`
  - 生产必须稳定（不要依赖 `run.bat` 的自动生成）
- `WAYFARER_JWT_KID_CURRENT`
  - 当前使用的 key id（来自 `WAYFARER_JWT_SIGNING_KEYS_JSON`）
  - 默认：`dev-1`

Web Cookie + CORS（浏览器认证非常重要）：

- `WAYFARER_CORS_ALLOW_ORIGIN`
  - 默认：`http://localhost:3000`
  - `waf.pscly.cc` 线上：设置为 `https://waf.pscly.cc`
- `WAYFARER_CORS_ALLOW_CREDENTIALS`
  - 默认：`1`（true）
- `WAYFARER_DEV_COOKIE_SECURE`
  - 控制 `wf_refresh` 和 `wf_csrf` Cookie 的 `Secure` 标志
  - 本地默认：`0`
  - 线上 HTTPS：设置为 `1`

异步任务（本地友好默认值）：

- `WAYFARER_CELERY_EAGER`
  - 本地默认：`1`（任务在进程内同步执行）
  - 线上推荐：`0`（仅当你实际部署 worker + broker）

第三方（可选）：

- `WAYFARER_AMAP_API_KEY`

## 认证机制（Web vs 非 Web 客户端）

后端的 refresh token 有两种模式：

1) Web 客户端（浏览器）

- 后端通过判断 `Origin == WAYFARER_CORS_ALLOW_ORIGIN` 来识别“Web 请求”。
- login/refresh 时后端会设置 Cookie：
  - `wf_refresh`：httpOnly refresh token
  - `wf_csrf`：可被 JS 读取（double-submit CSRF）
- Web 客户端必须携带 Cookie，因此 `fetch(..., { credentials: "include" })` 是必须的（已在 `web/lib/api.ts` 固化）。

2) Android / 脚本（无 Origin）

- 这类客户端通常不会发送 `Origin`。
- 后端会在 JSON 响应体内返回 `refresh_token`，并要求 refresh 时由客户端在请求体中回传。

生产部署注意事项：

- 如果 Web 站点部署在 `https://waf.pscly.cc`，需要设置 `WAYFARER_CORS_ALLOW_ORIGIN=https://waf.pscly.cc`。
- 如果使用 HTTPS（TLS 终止），需要设置 `WAYFARER_DEV_COOKIE_SECURE=1` 以启用 Cookie 的 `Secure`。

## 数据库与迁移

本地开发默认使用 SQLite（异步），位于 `./data/dev.db`。

生产推荐使用 PostgreSQL。

示例 DB URL：

- SQLite（dev）：`sqlite+aiosqlite:///./data/dev.db`
- PostgreSQL（prod 示例）：`postgresql+psycopg://wayfarer:CHANGE_ME@127.0.0.1:5432/wayfarer`

Alembic 配置在 `backend/alembic.ini`，实际 URL 通过 `backend/alembic/env.py` 从 `WAYFARER_DB_URL` 动态注入。

常用迁移命令（在 `backend/` 下运行）：

```bat
uv run alembic upgrade head
uv run alembic current
uv run alembic history
```

## 生产部署： https://waf.pscly.cc

推荐布局（单机示例）：

- Nginx 监听 `:443`（域名 `waf.pscly.cc`）
- Nginx 路由：
  - `/v1/` -> backend `http://127.0.0.1:8000`
  - `/` -> Next.js `http://127.0.0.1:3000`

这样 Web + API 在同一 origin（`https://waf.pscly.cc`），与后端“Web 客户端”的行为一致。

### 生产环境变量（示例，占位符）

Backend（示例值）：

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

Web（示例值）：

```ini
NEXT_PUBLIC_API_BASE_URL=https://waf.pscly.cc
NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN=CHANGE_ME_IF_USED
```

### 最小 Nginx 配置（反向代理）

以下是最小 server block 示例（示例内容保持 ASCII）。根据你的环境调整 SSL 证书路径。

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

### systemd 单元（示例）

假设：

- 仓库部署在 `/opt/wayfarer`
- 后端使用 `uv`，工作目录 `/opt/wayfarer/backend`
- Web 使用 `npm`，工作目录 `/opt/wayfarer/web`
- 环境变量文件统一放在 `/etc/wayfarer/`（推荐）

后端单元：`/etc/systemd/system/wayfarer-backend.service`

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

Web 单元：`/etc/systemd/system/wayfarer-web.service`

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

### 部署检查清单（建议）

1) Backend

- 设置稳定的 JWT 签名 key（`WAYFARER_JWT_SIGNING_KEYS_JSON`）和 `WAYFARER_JWT_KID_CURRENT`。
- 将 DB URL（`WAYFARER_DB_URL`）指向 PostgreSQL。
- 运行迁移：

  ```bash
  cd /opt/wayfarer/backend
  # 先加载与 service 相同的环境变量，然后执行：
  uv run alembic upgrade head
  ```

2) Web

- 设置 `NEXT_PUBLIC_API_BASE_URL=https://waf.pscly.cc`。
- 构建一次：

  ```bash
  cd /opt/wayfarer/web
  npm ci
  npm run build
  ```

3) Nginx

- 启用站点配置并 reload：`nginx -t && systemctl reload nginx`

## 运维

健康检查：

```bash
curl -fsS http://127.0.0.1:8000/healthz
curl -fsS http://127.0.0.1:8000/readyz
```

服务状态与日志：

```bash
systemctl status wayfarer-backend
systemctl status wayfarer-web

journalctl -u wayfarer-backend -f
journalctl -u wayfarer-web -f
```

重启：

```bash
systemctl restart wayfarer-backend
systemctl restart wayfarer-web
```

数据库迁移（使用与后端相同的环境变量）：

```bash
cd /opt/wayfarer/backend
uv run alembic upgrade head
```
