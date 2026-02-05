# Wayfarer（行止）

Wayfarer 是一个单仓库（monorepo）项目，目标是把「行」与「止」变成可回溯的数据：

- 行：连续轨迹点（位置/速度/步数/活动类型等）
- 止：可编辑的轨迹片段、可标注的生活事件（停留/事件）
- 导出：CSV/GPX/GeoJSON/KML（可选天气回填）

线上地址：`https://waf.pscly.cc`

---

## 架构总览

核心组件：

- Android：原生 Kotlin + Jetpack Compose；本地 Room 存储；通过 HTTP 与后端同步（可配置 `WAYFARER_API_BASE_URL`）。
- Backend：FastAPI + SQLAlchemy（异步）+ Alembic；默认本地 SQLite（异步）；生产可切 PostgreSQL；内置导出与天气回填逻辑；可选 Celery 任务。
- Web：Next.js 14（App Router）控制台；通过同源反代（推荐）或直连 API；浏览器认证使用 Cookie + CSRF。

请求与数据流（简化）：

```text
Android (OkHttp)  --/v1/tracks/batch-->  Backend (FastAPI)
                                        |  - DB: TrackPoint / TrackEdit / LifeEvent / WeatherCache / ExportJob
Web (Next.js)  <--------/v1/*----------> |
  - Cookie+CSRF refresh (wf_refresh, wf_csrf)
  - Bearer access token

Export: POST /v1/export (job)  -> 后台生成文件 -> GET /v1/export/{job_id}/download
Health: /healthz (进程活着) /readyz (DB schema + JWT 配置可用)
```

---

## 仓库结构

```text
./
  backend/               # FastAPI 服务端（Python + uv + Alembic）
  web/                   # Next.js 控制台（TypeScript + Tailwind + Playwright）
  android/               # 原生 Android（Kotlin + Jetpack Compose）
  scripts/               # 线上排障脚本（例如 scripts/prod_diagnose.sh）
  docs/                  # 预留文档目录

  docker-compose.yml     # 生产推荐：Docker Compose（web+backend）
  .env.example           # 环境变量模板（根目录 .env）
  run.bat / stop.bat     # Windows 本地一键启动/停止（backend+web）
  部署.md                # 部署速查（完整版本以 README 为准）

  .sisyphus/             # 运行期/AI 辅助产物（PID、notepads、known_hosts 等）
```

---

## 快速开始（Windows 本地开发）

前置条件：

- Windows（建议 PowerShell 7）
- 已安装 `uv`
- 已安装 Node.js 20+（npm）

步骤：

1) （可选）创建 `.env`

   将仓库根目录的 `.env.example` 复制为 `.env`，按需填写。

2) 启动后端 + Web

   运行：

   ```bat
   run.bat
   ```

   说明（`run.bat` 的实际行为）：

   - 在 `backend/` 执行 `uv sync`，确保依赖可用。
   - 仅在 `web/node_modules/` 不存在时才会在 `web/` 执行 `npm install`。
   - 进程 PID 会记录在 `.sisyphus/run/backend.pid` 与 `.sisyphus/run/web.pid`。
   - 只有当 `http://localhost:8000/healthz` 返回 200 时，`run.bat` 才以 0 退出。
   - 若缺少 `WAYFARER_JWT_SIGNING_KEYS_JSON`，`run.bat` 会生成仅用于本机开发的 key map JSON，并仅注入到本次后端进程（不会写入仓库文件）。

3) 停止后端 + Web

   运行：

   ```bat
   stop.bat
   ```

   说明（`stop.bat` 的实际行为）：

   - 只会杀掉记录过的 PID（含子进程），并验证端口 `8000/3000` 不再监听。

本地地址：

- Web：`http://localhost:3000`
- Backend：`http://localhost:8000`
- Backend 健康检查：`http://localhost:8000/healthz`
- Backend 就绪检查：`http://localhost:8000/readyz`
- Backend OpenAPI：`http://localhost:8000/docs`

---

## 单独启动（可选）

适用于你只想启动某一个子项目，或希望在 IDE 中调试。

### Backend

在 `backend/` 下：

```bat
uv sync
uv run uvicorn main:app --host :: --port 8000
```

### Web

在 `web/` 下：

```bat
npm ci
npm run dev -- -p 3000
```

### Android

- 使用 Android Studio 打开 `android/`。
- Debug 构建默认 API Base URL：`http://10.0.2.2:8000`（Android Emulator 访问宿主机）。
- 可用以下优先级覆盖：
  - Gradle 属性：`-PWAYFARER_API_BASE_URL=...`
  - 环境变量：`WAYFARER_API_BASE_URL=...`

高德地图 Key（可选但推荐）：

- 环境变量：`WAYFARER_AMAP_API_KEY`
- 或 `android/local.properties`：`WAYFARER_AMAP_API_KEY=...`

---

## 配置（环境变量）

本仓库使用环境变量进行配置：

- Backend 配置项统一使用前缀 `WAYFARER_`（见 `backend/app/core/settings.py`）。
- Web（Next.js）读取公开环境变量（build/runtime），例如 `NEXT_PUBLIC_API_BASE_URL`。

环境变量模板在根目录 `.env.example`。

### Web 环境变量

- `NEXT_PUBLIC_API_BASE_URL`
  - 本地默认：`http://localhost:8000`
  - 线上（同源部署）：设置为 `https://waf.pscly.cc`（Web 会请求 `https://waf.pscly.cc/v1/...`）

- `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN`（可选）
  - 不配置时页面会显示“地图已禁用”的占位卡片，并尽量避免任何第三方网络请求。

### Backend 环境变量

核心：

- `WAYFARER_DB_URL`
  - 默认：`sqlite+aiosqlite:///./data/dev.db`（适合本地开发）
  - PostgreSQL 示例：`postgresql+psycopg://wayfarer:CHANGE_ME@127.0.0.1:5432/wayfarer`

- `WAYFARER_JWT_SIGNING_KEYS_JSON`（生产必填）
  - 格式：`kid -> secret` 的 JSON map，例如：`{"prod-1":"REPLACE_WITH_32B_BASE64URL"}`
  - 生产必须稳定（不要依赖 `run.bat` 的自动生成）

- `WAYFARER_JWT_KID_CURRENT`
  - 当前使用的 key id（必须存在于 `WAYFARER_JWT_SIGNING_KEYS_JSON`）
  - 默认：`dev-1`

Web Cookie + CORS（浏览器认证非常重要）：

- `WAYFARER_CORS_ALLOW_ORIGIN`
  - 默认：`http://localhost:3000`
  - 线上：设置为 `https://waf.pscly.cc`

- `WAYFARER_CORS_ALLOW_CREDENTIALS`
  - 默认：`1/true`

- `WAYFARER_DEV_COOKIE_SECURE`
  - 控制 `wf_refresh` 与 `wf_csrf` Cookie 的 `Secure` 标志
  - 本地 HTTP 默认：`0/false`
  - 线上 HTTPS：设置为 `1/true`

异步任务（本地友好默认值）：

- `WAYFARER_CELERY_EAGER`
  - 本地默认：`1`（任务在进程内同步执行）
  - 线上推荐：`0`（仅当你实际部署 worker + broker）

第三方（可选）：

- `WAYFARER_AMAP_API_KEY`

---

## API 速查（当前实现）

FastAPI 会在 `/docs` 提供交互式 OpenAPI 文档。这里列出仓库当前实现的主要接口（以代码为准）：

- Health
  - `GET /healthz`：进程存活
  - `GET /readyz`：DB schema 可用 + JWT 配置可用

- Auth（`/v1/auth`）
  - `POST /v1/auth/register`
  - `POST /v1/auth/login`
  - `POST /v1/auth/refresh`

- Users（`/v1/users`）
  - `GET /v1/users/me`

- Admin（`/v1/admin`，仅管理员）
  - `GET /v1/admin/users`
  - `PUT /v1/admin/users/{user_id}/admin`

- Tracks（`/v1/tracks`）
  - `POST /v1/tracks/batch`：批量写入轨迹点
  - `GET /v1/tracks/query`：查询轨迹点
  - `POST /v1/tracks/edits`：创建编辑（例如删除区间）
  - `GET /v1/tracks/edits`：列出编辑
  - `DELETE /v1/tracks/edits/{id}`：删除编辑

- Life Events（`/v1/life-events`）
  - `GET /v1/life-events`
  - `POST /v1/life-events`
  - `PUT /v1/life-events/{id}`
  - `DELETE /v1/life-events/{id}`

- Export（`/v1/export`）
  - `POST /v1/export`：创建导出任务（返回 `job_id`）
  - `GET /v1/export`：同步流式导出（适合小数据量）
  - `GET /v1/export/{job_id}`：查询任务状态
  - `GET /v1/export/{job_id}/download`：下载导出产物
  - `POST /v1/export/{job_id}/cancel`：取消任务

---

## 账号注册与登录（username）

后端认证标识为 `username`（用户名）。

规则：

- 登录：使用 `username + password`
- 注册：`email` 为可选（可为 `null` 或不传）
- 密码规则：长度至少 6 位，且至少包含 1 个 ASCII 字母与 1 个数字（允许特殊字符；后端强制）
- 管理员：第一个成功注册的用户会自动成为管理员（bootstrap）

### 注册

`POST /v1/auth/register`

示例：

```bash
curl -sS -X POST http://localhost:8000/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":null,"password":"password123!"}'
```

### 登录

`POST /v1/auth/login`

示例：

```bash
curl -sS -X POST http://localhost:8000/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123!"}'
```

说明：

- 对于 Web（带 `Origin` 且与 `WAYFARER_CORS_ALLOW_ORIGIN` 匹配），后端会把 refresh token 写入 Cookie（`wf_refresh`），并额外下发 CSRF cookie（`wf_csrf`）。响应体只返回 `access_token`。
- 对于 Android/脚本（通常无 `Origin`），后端会在 JSON 响应体中额外返回 `refresh_token`。

### 管理员接口

- `GET /v1/admin/users`：列出用户
- `PUT /v1/admin/users/{user_id}/admin`：授予/取消管理员

Web 控制台：

- `/register`：注册
- `/login`：登录
- `/admin/users`：用户管理（仅管理员会在顶部导航中看到入口）

---

## 认证机制（Web vs 非 Web 客户端）

后端 refresh token 有两种工作模式：

1) Web 客户端（浏览器）

- 后端通过判断 `Origin == WAYFARER_CORS_ALLOW_ORIGIN` 来识别“Web 请求”。
- `login/refresh` 时后端会设置 Cookie：
  - `wf_refresh`：httpOnly refresh token
  - `wf_csrf`：可被 JS 读取（double-submit CSRF）
- Web 客户端必须携带 Cookie，因此 `fetch(..., { credentials: "include" })` 是必须的（已在 `web/lib/api.ts` 固化）。

2) Android / 脚本（无 Origin）

- 这类客户端通常不会发送 `Origin`。
- 后端会在 JSON 响应体内返回 `refresh_token`，并要求 refresh 时由客户端在请求体中回传。

生产部署注意事项：

- 如果 Web 站点部署在 `https://waf.pscly.cc`，需要设置 `WAYFARER_CORS_ALLOW_ORIGIN=https://waf.pscly.cc`。
- 如果使用 HTTPS（TLS 终止），需要设置 `WAYFARER_DEV_COOKIE_SECURE=1` 以启用 Cookie 的 `Secure`。

---

## 错误与可观测性（Trace ID）

- 每个请求都会被注入/回显 `X-Trace-Id`：
  - 如果客户端请求头已提供 `X-Trace-Id`，服务端会沿用。
  - 否则服务端自动生成 UUID。

- 业务错误与校验错误统一包装为 JSON：

```json
{
  "code": "...",
  "message": "...",
  "details": null,
  "trace_id": "..."
}
```

排障建议：一旦线上报错，优先携带 `X-Trace-Id`（或响应体 `trace_id`）去搜日志。

---

## 数据库与迁移

本地开发默认使用 SQLite（异步），位于 `backend/data/dev.db`。

生产推荐使用 PostgreSQL。

Alembic 配置在 `backend/alembic.ini`，实际 URL 通过 `backend/alembic/env.py` 从 `WAYFARER_DB_URL` 动态注入。

常用迁移命令（在 `backend/` 下运行）：

```bat
uv run alembic upgrade head
uv run alembic current
uv run alembic history
```

---

## 生产部署（推荐：Docker Compose + Nginx 同源反代）

仓库根目录提供 `docker-compose.yml`：

- backend 监听宿主机 `127.0.0.1:18000`（容器内 `8000`）
- web 监听宿主机 `127.0.0.1:13000`（容器内 `3000`）
- 两者均只绑定本机回环，供 Nginx 反代使用
- backend 容器启动时可按配置自动跑迁移（见下方）

### 1) 准备 `.env`

在仓库根目录创建 `.env`（参考 `.env.example`），至少需要：

- `WAYFARER_JWT_SIGNING_KEYS_JSON`
- `WAYFARER_JWT_KID_CURRENT`
- `NEXT_PUBLIC_API_BASE_URL`（建议为你的同源域名，如 `https://waf.pscly.cc`）

### 2) 启动

```bash
docker compose up -d --build
```

健康检查（compose 端口是 18000/13000）：

```bash
curl -fsS http://127.0.0.1:18000/readyz
curl -fsS http://127.0.0.1:13000/
```

### 3) 迁移策略（生产建议）

为避免“代码已更新但数据库 schema 未同步”导致 `INTERNAL_ERROR`，建议在生产环境启用启动时迁移，并开启严格模式：

- `WAYFARER_MIGRATE_ON_START=1`
- `WAYFARER_MIGRATE_STRICT=1`
- 如数据库连接不稳定或启动阶段偶发失败，可提高 `WAYFARER_MIGRATE_MAX_ATTEMPTS`（例如 10）

这些变量由 `backend/docker-entrypoint.sh` 读取。

### 4) Nginx 反向代理（同源）

推荐布局（单机示例）：

- Nginx 监听 `:443`（域名 `waf.pscly.cc`）
- 路由：
  - `/v1/` -> backend `http://127.0.0.1:18000`
  - `/` -> web `http://127.0.0.1:13000`

这样 Web + API 在同一 origin（`https://waf.pscly.cc`），与后端“Web 客户端”的认证逻辑一致。

最小 server block 示例（示例内容保持 ASCII）：

```nginx
server {
  listen 443 ssl;
  server_name waf.pscly.cc;

  # ssl_certificate     /etc/letsencrypt/live/waf.pscly.cc/fullchain.pem;
  # ssl_certificate_key /etc/letsencrypt/live/waf.pscly.cc/privkey.pem;

  location /v1/ {
    proxy_pass http://127.0.0.1:18000;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  location / {
    proxy_pass http://127.0.0.1:13000;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
}
```

---

## 生产环境变量示例（.env，占位符）

Backend（示例值）：

```ini
WAYFARER_DB_URL=postgresql+psycopg://wayfarer:CHANGE_ME@127.0.0.1:5432/wayfarer
WAYFARER_CORS_ALLOW_ORIGIN=https://waf.pscly.cc
WAYFARER_CORS_ALLOW_CREDENTIALS=1
WAYFARER_DEV_COOKIE_SECURE=1

# IMPORTANT: stable signing keys (do NOT rely on run.bat auto-generation)
WAYFARER_JWT_SIGNING_KEYS_JSON='{"prod-1":"REPLACE_WITH_32B_BASE64URL"}'
WAYFARER_JWT_KID_CURRENT=prod-1

# Migrations (recommended for production)
WAYFARER_MIGRATE_ON_START=1
WAYFARER_MIGRATE_STRICT=1
WAYFARER_MIGRATE_MAX_ATTEMPTS=10

# Optional
WAYFARER_CELERY_EAGER=0
```

Web（示例值）：

```ini
NEXT_PUBLIC_API_BASE_URL=https://waf.pscly.cc
NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN=CHANGE_ME_IF_USED
```

说明：

- `docker-compose.yml` 默认会读取根目录 `.env`（`env_file: .env`）。
- `web/Dockerfile` 在构建期通过 `ARG NEXT_PUBLIC_API_BASE_URL` 注入，因此在生产建议固定同源域名（避免构建后再改导致前端请求指向错误）。

---

## 可选：不使用 Docker 的 systemd 单元（示例）

适用于你希望用 systemd 直接管理 `uvicorn` 与 `next start`（不走容器）。建议把环境变量文件放在 `/etc/wayfarer/`，不要写进 unit 文件里。

假设：

- 仓库部署在 `/opt/wayfarer`
- 后端工作目录 `/opt/wayfarer/backend`
- Web 工作目录 `/opt/wayfarer/web`
- 环境变量文件：`/etc/wayfarer/wayfarer-backend.env` 与 `/etc/wayfarer/wayfarer-web.env`

后端单元：`/etc/systemd/system/wayfarer-backend.service`

```ini
[Unit]
Description=Wayfarer Backend (FastAPI)
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/wayfarer/backend
EnvironmentFile=/etc/wayfarer/wayfarer-backend.env
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
ExecStart=/usr/bin/env npm run start -- -H 127.0.0.1 -p 3000
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

---

## 部署检查清单（建议）

1) Backend

- 设置稳定的 JWT 签名 key（`WAYFARER_JWT_SIGNING_KEYS_JSON`）和 `WAYFARER_JWT_KID_CURRENT`。
- 将 DB URL（`WAYFARER_DB_URL`）指向 PostgreSQL（或你实际的生产数据库）。
- 运行迁移：

  ```bash
  cd /opt/wayfarer/backend
  # 先加载与 service 相同的环境变量，然后执行：
  uv run alembic upgrade head
  ```

2) Web

- 设置 `NEXT_PUBLIC_API_BASE_URL=https://waf.pscly.cc`（推荐同源）。
- 构建一次：

  ```bash
  cd /opt/wayfarer/web
  npm ci
  npm run build
  ```

3) Nginx

- 启用站点配置并 reload：`nginx -t && systemctl reload nginx`

---

## CI/CD（GitHub Actions）

仓库内置 `.github/workflows/cicd.yml`：

- PR：只跑 CI（backend/web/android）
- push 到 `main`：跑 CI + deploy（SSH 到服务器执行 `docker compose up -d --build`）
- deploy 采用双重锁：Actions concurrency + 服务器侧 lock，并带健康门禁：
  - backend：`/readyz`
  - web：`/`

deploy 需要的 Secrets（名称以 workflow 为准）：

- `CI`（或 `SSH_PRIVATE_KEY`）：SSH 私钥（GitHub Actions runner -> 目标机）
- `HOST`、`PORT`、`USER`：目标机信息
  - `HOST` 必须是**公网可达**的域名或公网 IP（因为 GitHub-hosted runner 在外网）。
  - 如果误填 `10.*` / `172.16-31.*` / `192.168.*` / `127.*` 等内网/回环地址，workflow 会直接报错并中止 deploy（防止白跑 CI）。
- `KNOWN_HOSTS`：pinned known_hosts（推荐固定，保持 `StrictHostKeyChecking=yes`；更换 `HOST` 后记得同步更新）

### 常见报错与排障（deploy）

- 日志里出现 `/home/runner/...`：这是 GitHub-hosted runner 的工作目录，属于正常现象（workflow 就是在 runner 上执行 `ssh`）。
- `Pinned known_hosts missing entry ...`：
  - 含义：pinned `known_hosts` 里缺少当前 `HOST/PORT` 的条目（StrictHostKeyChecking 下会直接拒绝）。
  - 处理：更新 `secrets.KNOWN_HOSTS` 或提交 `.github/known_hosts`，确保包含 `HOST` 或 `[HOST]:PORT` 对应的 host key。
- `Permission denied (publickey,password)`（最常见）：
  - 含义：服务器**没有接受** workflow 使用的 SSH 私钥（公钥未在 `authorized_keys`、用户不对、或 sshd/防火墙策略限制）。
  - 建议按以下顺序排查：
    1) 在 Actions 日志中找到 `Deploy SSH key fingerprint` / `offered_public_key_fingerprint`。
    2) 在服务器上运行 `ssh-keygen -lf ~/.ssh/authorized_keys`，确认该 fingerprint 存在于目标用户的 `authorized_keys`。
    3) 检查权限：`chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys`（权限过宽会导致 sshd 忽略该文件）。
    4) 查看服务器日志定位根因：`journalctl -u ssh -S "30 min ago" | tail -n 200`（重点看是否连到了别的机器/端口转发错误、是否被 fail2ban/Match Address 限制等）。
- 不想把 SSH 暴露到公网：推荐改用 **self-hosted runner**（部署机/内网机器跑 runner），deploy job 直接本机执行 `docker compose up -d --build`，不再需要从外网 SSH 进入内网。

说明：CI/CD 属于“可选能力”，不影响本地开发。

---

## 开发与测试

### Backend

在 `backend/` 下：

```bat
uv run pytest -q
```

### Web

在 `web/` 下：

```bash
npm run lint
npm run build
npm run test:e2e
```

### Android

在 `android/` 下：

```bash
./gradlew test
```

备注：仓库路径包含中文/非 ASCII 字符时，Android 的单元测试在 Windows 上可能出现 classpath 乱码问题；本项目在 `android/app/build.gradle.kts` 中提供了针对该场景的可移植测试任务替代。

---

## 运维与排障

### 1) 健康检查

```bash
curl -fsS http://127.0.0.1:18000/healthz
curl -fsS http://127.0.0.1:18000/readyz
```

### 2) 生产排障脚本

仓库提供 `scripts/prod_diagnose.sh`，用于采集：容器状态、健康检查、（可选）alembic current/upgrade、以及按 `TRACE_ID` grep 日志。

示例：

```bash
TRACE_ID=REPLACE_WITH_TRACE_ID DO_UPGRADE=1 sh scripts/prod_diagnose.sh
```

### 3) 常见问题

- `readyz = 503`：优先检查 `WAYFARER_JWT_SIGNING_KEYS_JSON`/`WAYFARER_JWT_KID_CURRENT` 是否配置正确，以及数据库迁移是否已执行。
- 浏览器登录后“刷新失败/401”：确认 Web 与 API 是否同源（推荐 Nginx 同源反代），并检查 `WAYFARER_CORS_ALLOW_ORIGIN`、`WAYFARER_DEV_COOKIE_SECURE`。
- 需要更精准定位线上问题：把响应头 `X-Trace-Id`（或响应体 `trace_id`）带到日志系统中检索。

---

## 贡献与规范

- 本仓库要求文件统一使用 UTF-8（无 BOM）。
- 不要把 `.env` 或任何 secret 提交到仓库。
- 版本号遵循 SemVer，当前阶段保持主版本号为 `0`。

---

## License

本仓库暂未提供独立 LICENSE 文件；如需开源协议，请先补齐 LICENSE 并在此处声明。
