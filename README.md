# Wayfarer

Monorepo skeleton:

- `backend/`: FastAPI (uv-managed) on port 8000
- `web/`: Next.js 14 on port 3000
- `android/`: placeholder

## Prerequisites

- Windows
- `uv` installed
- Node.js + npm installed

## Quick start

1) Copy env template (optional for now)

   - `.env.example` -> `.env`

2) Start everything

   - Run `run.bat`

   Notes:
   - `run.bat` runs `uv sync` in `backend/` and only runs `npm install` in `web/` when `web/node_modules/` is missing.
   - PIDs are recorded in `.sisyphus/run/backend.pid` and `.sisyphus/run/web.pid`.
   - `run.bat` exits 0 only after `http://localhost:8000/healthz` returns 200.

3) Stop everything

   - Run `stop.bat`

   Notes:
   - `stop.bat` only kills the recorded PIDs (process tree) and then verifies ports 8000/3000 are no longer listening.

## Endpoints

- Backend health: `http://localhost:8000/healthz`
- Backend ready: `http://localhost:8000/readyz`

## Environment variables

See `.env.example` for the full list.
