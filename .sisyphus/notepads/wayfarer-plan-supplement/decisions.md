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
