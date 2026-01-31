# Decisions

## 2026-01-30 Task: init
- None yet.

## 2026-01-30 Task: 1
- Decide spec precedence: supplement overrides `plan.md` when conflicting, without modifying `plan.md`.
- Decide batch idempotency model baseline to document early: introduce `client_point_id` as the idempotency key and standardize `accepted_ids[]`/`rejected[]` ack semantics.

## 2026-01-30 Task: 4
- Decide auth identifier: login uses `email` as primary identifier; `username` remains a display name (still UNIQUE for compatibility).
- Decide Web refresh mechanism: refresh token only in httpOnly cookie + CSRF `double-submit` using `wf_csrf` + `X-CSRF-Token`.
- Decide Settings update semantics: `PUT /v1/settings` is merge update (PATCH-like); omitted fields keep existing.
- Decide export compatibility strategy: keep `GET /v1/export?...` for small streaming exports, otherwise return 202 + job_id and use async job lifecycle.

## 2026-01-30 15:11:30 Task: 9
- Align JWT dev signing keys JSON format to plan: map JSON `{ "dev-1": "<random>" }` for `WAYFARER_JWT_SIGNING_KEYS_JSON`.
- Task 9 (repo skeleton + dev toolchain scripts) committed.

## 2026-01-30 Task: 14b
- Anti-cheat teleportation threshold: mark dirty when `(max(0, dist_m - (acc_prev + acc_cur + 5m)) / dt_s) > 120 m/s` (~432 km/h); chosen to be conservative and avoid flagging ordinary vehicle travel or noisy GPS.

## 2026-01-31 07:08:09 Task: 15 export formats research
- CSV: use `text/csv` (RFC 4180) with UTF-8; include header row by default; write with `newline=""` to avoid extra blank lines on Windows.
- GeoJSON: prefer `application/geo+json` (RFC 7946) and extension `.geojson`; coordinates are `[lon, lat]` (and optional `alt`) in that order.
- GPX: GPX 1.1 (`http://www.topografix.com/GPX/1/1`) uses `trk/trkseg/trkpt` with `lat`/`lon` attrs; `<time>` is `xsd:dateTime` and must be UTC (ISO 8601; allow fractional seconds).
- KML: use `application/vnd.google-earth.kml+xml` (IANA) and `.kml`; for per-point time, use `gx:Track` with `<when>` + `<gx:coord>` (lon/lat/alt); for huge exports consider 2-pass generation (whens pass + coords pass) or fall back to LineString + TimeSpan.
- Streaming: for large exports, avoid building full strings/lists in memory; prefer generator that yields bytes chunks (CSV rows; GeoJSON FeatureCollection with point-features; GPX/KML line-by-line). For LineString+times (GeoJSON) and gx:Track (KML), either buffer, or do multi-pass over the DB query.
