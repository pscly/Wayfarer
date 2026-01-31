# Suggested Commands (Windows)

Root:
- `run.bat` (start backend+web)
- `stop.bat` (stop backend+web)

Web (`web/`):
- `npm run dev` (start Next.js dev server)
- `npm run build` (Next.js production build)
- `npm run lint` (Next.js lint)
- `npm run test:e2e` (Playwright tests)

Backend (`backend/`):
- `uv sync` (install Python deps)
- Typical FastAPI run is via `uv run ...` (see backend docs/entrypoints if needed)
