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
