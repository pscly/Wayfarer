# 常用命令（Windows）

仓库根目录：

- `run.bat`（启动 backend+web）
- `stop.bat`（停止 backend+web）

Web（`web/`）：

- `npm run dev`（启动 Next.js 开发服务）
- `npm run build`（Next.js 生产构建）
- `npm run start`（启动 Next.js 生产服务）
- `npm run lint`（Next.js lint）
- `npm run test:e2e`（Playwright 测试）

Backend（`backend/`）：

- `uv sync`（安装 Python 依赖）
- 常规 FastAPI 启动：`uv run ...`（以 `backend/` 的入口/文档为准）
