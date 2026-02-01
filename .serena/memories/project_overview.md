# Wayfarer（Monorepo）

用途：用于位置/轨迹类产品的单仓库骨架。

关键目录：

- `backend/`: FastAPI（uv 管理），默认端口 8000
- `web/`: Next.js 14（App Router）+ React 18 + TypeScript + Tailwind，默认端口 3000
- `android/`: 原生 Android（Kotlin + Jetpack Compose）

说明：

- 根目录 `run.bat` 启动 backend + web，并等待后端 `GET /healthz` 返回 200。
- 根目录 `stop.bat` 停止记录的 PID，并验证端口 8000/3000 已释放。
