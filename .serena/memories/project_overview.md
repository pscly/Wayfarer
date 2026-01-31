# Wayfarer (Monorepo)

Purpose: Monorepo skeleton for a location/tracks product.

Key dirs:
- backend/: FastAPI (uv-managed), default port 8000
- web/: Next.js 14 (App Router) + React 18 + TypeScript + Tailwind, default port 3000
- android/: placeholder

Notes:
- Root `run.bat` starts backend + web and waits for backend `GET /healthz` (200).
- Root `stop.bat` stops recorded PIDs and verifies ports 8000/3000 are free.
