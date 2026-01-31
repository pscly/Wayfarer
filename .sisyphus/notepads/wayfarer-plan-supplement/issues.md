# Issues

## 2026-01-30 Task: init
- None yet.

## 2026-01-30 Task: 3
- Server migration safety: `ALTER TABLE track_points ADD COLUMN client_point_id UUID NOT NULL;` is required as a token, but a production-safe path likely needs nullable + backfill + set NOT NULL.
- payload_sha256 definition: confirm whether sha256 is computed over canonical JSON text, gzip bytes, or a stable canonical form to keep debugging/dedupe consistent across clients.
- OUTSIDE_CN handling: confirm whether Android should store GCJ-02 columns as NULL (recommended) or equal-to-WGS84 when coord_transform_status=OUTSIDE_CN.

## 2026-01-30 Task: 5
- Step signals availability: step_count/step_rate may not exist on all clients/sessions; confirm whether anti-cheat should (a) skip step-based rules, (b) infer from distance only, or (c) mark as unknown/insufficient.
- teleportation_detected definition: current baseline names the signal but does not specify a numeric cutoff beyond “impossible speed jump”; confirm whether >8.3 m/s alone is sufficient, or whether a separate (higher) hard cutoff should be introduced later via field calibration.
- Tooling: `lsp_diagnostics` cannot run on `.md` in this repo because no Markdown LSP server is configured (attempted; tool reports extension not supported).

## 2026-01-30 Task: 6
- Tooling: `lsp_diagnostics` still cannot run on `.md` (LifeEvent spec + notepad are Markdown); verification is blocked unless a Markdown LSP is configured in oh-my-opencode.

## 2026-01-30 Task: 10
- Windows networking: `Invoke-WebRequest http://localhost:8000/...` may resolve to IPv6 (::1). If uvicorn binds to IPv4-only (`--host 127.0.0.1`), localhost checks can time out; use `http://127.0.0.1:8000/...` for smoke tests (run.bat currently polls `localhost`).
- Fix chosen: start uvicorn with `--host ::` (IPv6 / dual-stack when supported) so `http://localhost:8000/healthz` works reliably even when `localhost` resolves to `::1`.
- Tooling: `lsp_diagnostics` appears to return stale Ruff `F401` for `backend/main.py` even after code changes; CLI `ruff check backend/main.py` is clean.
- Tooling gotcha: the CLI "bash" runner here behaves like cmd.exe; use PowerShell (`Start-Process`, `Start-Sleep`, `Stop-Process`) for background server + health-check verification instead of `&/sleep/kill`.

## 2026-01-30 Task: 11/12/13
- Repo hygiene gotcha: critical backend files for Tasks 11/12 (alembic/models/auth/tests) existed locally but were not yet committed; this breaks clean checkouts if not fixed.

## 2026-01-30 Task: 15 worker/settings
- Retention not implemented in backend export: spec mentions metadata 7d and artifact 24h, but ExportJob has no expires fields and there is no periodic cleanup task; only best-effort unlink on cancel.
- Celery eager is default (run.bat sets WAYFARER_CELERY_EAGER=1 when unset): export jobs run inline in dev/test even though the API returns 202.
