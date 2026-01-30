# Learnings

## 2026-01-30 Task: init
- Notepad initialized for wayfarer-plan-supplement.

## 2026-01-30 Task: 1
- Deliverable ` .sisyphus/deliverables/plan-supplement.md` is a fixed-structure spec: every chapter must contain Parameters + Pseudocode/Diagram + Boundary Tests.
- Automated verification relies on fixed tokens and countable markers (`GAP-`, `DELTA-`, `ERR-`, `TEST-*`).

## 2026-01-30 Task: 2
- FSM acceptance is grep-driven: ensure same-line matches for `minDistance.*50m/5m/3m/20m` via a single `minDistance defaults:` line.
- Keep baseline defaults verbatim (Design Default，可调): states, intervals (STATIONARY=120s, WALKING=5s, RUNNING=3s, CYCLING=3s, DRIVING=5s), thresholds (speed <0.5 m/s, speed >2.5 m/s, speed >8.3 m/s), and `debounce: 3–5s`.
- Explicitly state priority: Activity Recognition > GPS speed inference; permission denied forces `GPS-only`.

## 2026-01-30 Task: 3
- Room/Sync is acceptance-token driven: include at least 3 literal `CREATE TABLE` blocks and the exact strings `track_points_local`, `sync_queue`, `payload_sha256`, `attempt_count`, `next_retry_at`, `rejected[]`, `client_point_id`, `UNIQUE(user_id, client_point_id)`, `ALTER TABLE track_points`, `round(lat, 5)`, `latitude`, `longitude`.
- Idempotency must be per-item with `client_point_id` (UUID, client-generated) and server uniqueness `(user_id, client_point_id)`; duplicates must be treated as accepted to avoid retry storms.
- Conflict handling is split: strict idempotency via (user_id, client_point_id), plus a weak dedupe hint `recorded_at+geom` (same-line token) for multi-device repeats.
- Geom/coord mapping must be finalized: Room stores WGS84 + GCJ-02 plus `coord_source` and `coord_transform_status` (OK/OUTSIDE_CN/BYPASS/FAILED); API requires WGS84 `latitude`/`longitude` and allows optional GCJ-02; Server stores WGS84 `geom(4326)` and does NOT do GCJ-02→WGS84 inverse.

## 2026-01-30 Task: 4
- API spec acceptance is grep-driven: ensure required fixed tokens exist verbatim (e.g. `15m`, `30d`, `HS256`, `kid`, `argon2id`, `CSRF`, `X-CSRF-Token`, `double-submit`, `refresh_tokens`, `family_id`, `replaced_by`, `AUTH_REFRESH_REUSED`, `timezone`, `Asia/Shanghai`, `CSV/GPX/GeoJSON/KML`, `trace_id`).
- Tracks batch must be explicitly idempotent: duplicates are accepted (go into accepted_ids[]), and failures are per-item in `rejected[]` with `reason_code`.
- Export retention acceptance requires exact Chinese tokens: `retention_metadata: 7 天` and `retention_artifact: 24 小时` (even if internally documented as 7d/24h).
- Auth identifier delta is token-checked: include `email TEXT UNIQUE NOT NULL` in the spec to align DB migration with API contracts.

## 2026-01-30 Task: 5
- Anti-Cheat should be written as a ruleset (hard rules first, then scoring), with all numeric baselines copied verbatim from `.sisyphus/plans/wayfarer-research-notes.md`.
- Provide a compact threshold matrix table (speed + 步幅) plus a separate signal threshold table (steps_no_distance / distance_no_steps / impossible_step_rate / gps_accuracy_suspicious / teleportation) to keep the section implementable and explainable.
- Boundary tests must use literal, countable markers (`- TEST-ANTICHEAT-###:`) and should include both “hard dirty” and “soft scoring” cases.

## 2026-01-30 Task: 6
- LifeEvent spec should be pipeline-shaped (stay points -> place clusters -> home/work scoring -> commute detection) so later backend implementation can be staged and tested per phase.
- Keep all baseline thresholds verbatim for acceptance: stay_point `200m` + `5 min`; home window `21:00–07:00` with min 2h and 50% nights; work window weekdays 09:00–18:00 with min 2h and >500m from home.
- Include baseline cluster/commute parameters explicitly (home cluster radius 100m; work cluster radius 150m; commute proximity 300m; morning 07:00–10:00; evening 17:00–20:00; min occurrences 3) to avoid later guessing.
- Home/Work selection must be deterministic: define explicit tie-breakers to prevent home/work center drifting across recomputes.
- Boundary tests must cover inclusive thresholds (exactly 5 min / 200m), midnight-spanning night window, tie cases, and commute min-occurrence cutoff.

## 2026-01-30 Task: 8
- Web Mapbox 性能分档要“可选策略 + 可验收预算”两件套：分档表显式覆盖 `50K`/`100K`/`1M`，同时保持研究基线（<50K GeoJSON；100K–500K simplify+cluster；1M+ tile-based）。
- OPT/SLA 标记必须是 literal 且可计数（grep-driven）；SLA 行内同时包含 `P95`/`ms`/`fps`/`MB` 以便后续自动验收。
- 边界测试要覆盖大规模退化路径：1M+ 禁止单 GeoJSON 全量注入；500K–1M 必须 viewport culling + 分片加载；频繁切换过滤条件不能导致 source/layer 泄漏。

## 2026-01-30 Task: 10
- Backend skeleton: keep `backend/main.py` as stable `uvicorn main:app` target; implement real app in `backend/app/main.py` with router split under `backend/app/api/`.
- Settings: use `pydantic-settings` with `env_prefix="WAYFARER_"`; default `db_url = sqlite+aiosqlite:///./data/dev.db`.
- DB baseline: SQLAlchemy 2.0 async engine + sessionmaker in `backend/app/db/session.py`; create engine lazily to avoid import-time side effects.
- Verification (Windows): if you probe `http://localhost:8000/...` (often resolves to ::1), start uvicorn with `--host ::` to ensure the check succeeds; use `Invoke-WebRequest -TimeoutSec 2` with a small retry loop to avoid hangs.

## 2026-01-30 Task: Ruff F821 forward refs
- Ruff/Pyflakes can flag F821 for unresolved names inside string forward references (e.g. `Mapped["User"]`) unless the referenced name exists in the module scope.
- Minimal fix in SQLAlchemy models: add `from typing import TYPE_CHECKING` + `if TYPE_CHECKING: from .user import User` (and peers) so the names exist for type-checking/lint only, avoiding runtime import cycles.

## 2026-01-30 Task: 13
- Tracks batch endpoint: accept `items: list[Any]` and validate each element with Pydantic inside the handler to avoid a single bad item turning the whole request into a 422.
- Idempotent insert pattern: PostgreSQL uses `insert(...).on_conflict_do_nothing(index_elements=["user_id","client_point_id"])`; SQLite uses `INSERT OR IGNORE` via `prefix_with("OR IGNORE")`.
- Tracks query + edits filtering: exclude deleted points with a correlated `NOT EXISTS` against `track_edits` where `type == "DELETE_RANGE"` and `canceled_at IS NULL`.
- Time serialization: normalize to tz-aware UTC and format with trailing `Z` for API responses.

## 2026-01-30 Task: 15 (Export create endpoint)
- Implemented `POST /v1/export` in `backend/app/api/export.py` and wired it via `backend/app/api/router.py`.
- Request schema uses `start`/`end` as `datetime` (ISO8601 input; naive timestamps are treated as UTC) + `format` validated/normalized to `CSV/GPX/GeoJSON/KML`.
- Defaulting reads from `user.settings` when present (`export_include_weather_default`, `timezone`) with fallbacks `false` and `UTC`.

## 2026-01-30 Task: FastAPI/Starlette downloads (FileResponse vs StreamingResponse)
- Prefer `FileResponse` when you have a real on-disk file (export artifacts, cached exports). It streams in chunks, sets `Content-Length`/`Last-Modified`/`ETag`, adds `Accept-Ranges: bytes`, and supports HTTP `Range` requests.
- Prefer `StreamingResponse` when the payload is generated on-the-fly (DB cursor -> CSV rows, incremental zip writing, etc). Ensure the iterator yields incremental chunks and does not build the whole payload (avoid `StringIO().getvalue()`/`BytesIO()` for large exports).
- `FileResponse(filename=...)` sets `Content-Disposition` for you, including RFC 5987-style `filename*=` for non-ASCII names; for `StreamingResponse`, set `Content-Disposition` manually.
- Suggested media types:
  - CSV: `text/csv` (Starlette will append `charset=utf-8` for `text/*`)
  - GPX: `application/gpx+xml`
  - GeoJSON: `application/geo+json`
  - KML: `application/vnd.google-earth.kml+xml`
- Cleanup temp artifacts: use `BackgroundTasks` (FastAPI) or `BackgroundTask` (Starlette) to delete/close temp files after the response is fully sent.
- Testing (pytest + httpx): assert `Content-Type`/`Content-Disposition`; Range tests (`Range: bytes=0-9` -> `206` + `Content-Range`; invalid ranges -> `416`); streaming tests should use `client.stream(...)` and iterate chunks.

## 2026-01-30 Task: 15 (Export job lifecycle + worker)
- Worker lives in `backend/app/tasks/export.py` as Celery task `app.tasks.export.run_export_job_task` (eager-safe via a `_run_coro_sync` thread fallback) and is triggered by `.delay(job_id)` from the export API.
- `artifact_path` is stored relative in DB using `{user_id}/{job_id}.{ext}`; API resolves the absolute path as `Path(Settings.export_dir) / artifact_path`.
- Compat `GET /v1/export` streams (200) only when `include_weather=false` AND points_count <= `Settings.sync_threshold_points` (default 50_000); otherwise it creates an async ExportJob and returns 202 `{job_id}`.

## 2026-01-30 Task: 16 (Weather enrichment + cache)
- Weather cache key is `(geohash_5, hour_time)` where `hour_time` is tz-aware UTC floored to the hour.
- Use stdlib-only geohash; query Open-Meteo at the geohash cell center to keep caching stable within a cell.
- Provider 429 is handled with exponential backoff; tests inject/patch sleep so there is no real sleeping.
- Export integrates enrichment when `include_weather=true` and writes `weather_snapshot_json` into CSV (empty when degraded).
- On provider failures/timeouts, export completes with job state `PARTIAL` and `EXPORT_WEATHER_DEGRADED` instead of crashing.
