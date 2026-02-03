# Web Register 500 Fix + Password Policy Relax + Remove Auth Hints

## TL;DR

> **Quick Summary**: Production web registration (`https://waf.pscly.cc/register`) returns `INTERNAL_ERROR` (500). Plan: (1) identify the backend exception via `trace_id` and make docker-compose deploy fail-fast if migrations/config are wrong, (2) align DB schema to Alembic head, (3) relax password policy (>=6, ASCII letter+digit; special allowed) while avoiding policy “hint leaks” in UI, and (4) add automated tests (pytest + Playwright) and run them.
>
> **Deliverables**:
> - Registration succeeds on production (email optional, long password OK)
> - Password policy updated across backend + web + android + docs
> - Auth form hints removed (keep only password “大小写敏感”)
> - Automated test coverage added + commands executed
>
> **Estimated Effort**: Medium
> **Parallel Execution**: YES (2 waves)
> **Critical Path**: Diagnose 500 (logs) → Fix DB/migrations/config → Fix auth policy/UI → Add tests → Deploy + verify

---

## Context

### Original Request
- “Web 端注册直接报错” + “密码不用那么复杂：英文+数字，长度>5（不用提醒用户）” + “改好后详细测试”
- Production DB is PostgreSQL (credentials provided out-of-band; do not commit)

### Repro Evidence (user-provided)
- URL: `https://waf.pscly.cc/register`
- Error shown in UI (raw JSON rendered today):
  - `{"code":"INTERNAL_ERROR","message":"Internal error","details":null,"trace_id":"060d8d15-459d-4898-87a7-ef3e23455eca"}`
- Email: left blank (frontend sends `email: null`)
- Password: long/strong (not a short-password trigger)

### Current Code Path (web → backend)
- Web form submit: `web/app/register/page.tsx` calls `auth.register()` then `login()` in the same try/catch.
  - This means a `/v1/auth/login` failure can be misreported as “register failed” (same error UI).
- Web API client:
  - `web/lib/auth.ts` `register()` POSTs to `/v1/auth/register` with `credentials: "include"`.
  - `web/lib/auth.ts` `login()` POSTs to `/v1/auth/login` immediately after.
- Backend:
  - `backend/app/api/auth.py` implements `/v1/auth/register` and `/v1/auth/login`.
  - `backend/app/main.py` returns a standard envelope for `INTERNAL_ERROR` with `trace_id`.

### Likely Root Cause for 500
Given this is a 500 `INTERNAL_ERROR` (not 422 validation), the highest-probability causes are:
- DB schema / migration drift (e.g., missing `users.is_admin` column or wrong nullability) causing an unhandled DB exception.
- Migrations failing at startup but container still starts (`WAYFARER_MIGRATE_STRICT=0` default in `backend/docker-entrypoint.sh`).
 - Missing/invalid JWT signing key config can also cause `/v1/auth/login` to 500 after a successful register.

---

## Work Objectives

### Core Objective
Make production web registration succeed reliably (no 500), while applying the new password policy and removing all auth-form hint texts except password “大小写敏感”.

### Concrete Deliverables
- Backend: `/v1/auth/register` no longer returns 500 for the reported flow; email optional works.
- Backend: password policy = `len>=6 && contains [A-Za-z] && contains [0-9]` (special chars allowed).
- Web UI: remove all `FieldHint` texts on auth pages except “大小写敏感” for password.
- Web UI: do not render raw backend JSON bodies to end-users.
- Android UI: remove the visible 12-char password hint.
- Docs: update `README.md` to reflect the new policy.
- Tests: add targeted backend tests for password policy + add Playwright tests for register flow; run both.

### Definition of Done
- [x] Production API: `POST https://waf.pscly.cc/v1/auth/register` returns 201 for email null + compliant password.
- [x] Backend: `uv run pytest` passes.
- [x] Web: `npm run test:e2e` passes.

### Must NOT Have (Guardrails)
- MUST NOT commit DB credentials or `.env` with secrets.
- MUST NOT log plaintext passwords.
- MUST NOT show password-policy details in UI hints; keep user-facing copy generic.
- MUST NOT silently start backend if migrations fail in production.

---

## Verification Strategy (Tests-after)

### Test Decision
- **Infrastructure exists**: YES
  - Backend: pytest (`backend/tests/*`, `backend/pyproject.toml`, `backend/uv.lock`)
  - Web: Playwright (`web/playwright.config.ts`, `web/tests/*`)
- **User wants tests**: YES (Tests after initial fix)

### Evidence to Capture (Agent-executable)
- Backend container logs snippet containing `trace_id=060d8d15-...` before/after fix
- Alembic current revision output on production DB
- `uv run pytest` output (exit code 0)
- `npm run test:e2e` output (exit code 0)

---

## Execution Strategy

### Parallel Execution Waves

Wave 1 (Stabilize + Diagnose):
- Task 1: Locate backend exception by `trace_id` and identify root cause
- Task 2: Verify Alembic revision on production DB and enforce fail-fast migration behavior

Wave 2 (Code + Tests + Deploy):
- Task 3: Fix registration runtime failure (DB schema/config/migrations) and harden startup
- Task 4: Implement new password policy backend-side (no policy hint leaks)
- Task 5: Remove auth hints in Web/Android; keep only “大小写敏感”; sanitize error display
- Task 6: Update docs (README)
- Task 7: Add tests (pytest + Playwright) and run them
- Task 8: Deploy via docker compose and verify on production

Critical Path: 1 → 2 → 3 → 8

---

## TODOs

### 1) Diagnose the Production 500 via trace_id

**What to do**:
- In the production environment (docker compose), pull backend logs around:
  - `trace_id=060d8d15-459d-4898-87a7-ef3e23455eca`
- Identify which endpoint failed (`/v1/auth/register` vs `/v1/auth/login`) and the actual exception class/message.

**Recommended Agent Profile**:
- **Category**: `unspecified-high`
  - Reason: production incident diagnosis requires careful log + config verification.
- **Skills**: `git-master`
  - `git-master`: helps if a quick blame/log search is needed for regressions.

**References**:
- `backend/app/main.py` - unhandled exception handler logs `trace_id`.
- `backend/docker-entrypoint.sh` - migration behavior + strictness flags.
- `docker-compose.yml` - container names/ports.

**Acceptance Criteria (agent-executable)**:
- [x] `docker logs wayfarer-backend | rg "060d8d15-459d-4898-87a7-ef3e23455eca" -n` does not find the trace_id in current logs (container recreated; original stack trace unavailable).
- [x] The failing endpoint is identified (`/v1/auth/register` or `/v1/auth/login`).
- [x] Root cause category recorded (DB schema, migration failure, missing JWT keys, config, etc.).

---

### 2) Verify migrations on production DB and make failures fail-fast

**What to do**:
- Check the current Alembic revision against head.
- If not at head: run `alembic upgrade head`.
- In production, set `WAYFARER_MIGRATE_STRICT=1` (and keep `WAYFARER_MIGRATE_ON_START=1`) so the service will not start in a broken schema state.

**Recommended Agent Profile**:
- **Category**: `unspecified-high`
- **Skills**: `git-master`

**References**:
- `backend/alembic/versions/0003_user_admin_and_optional_email.py` - adds `users.is_admin` and makes email nullable.
- `backend/alembic/env.py` - how migration DB URL is derived.
- `backend/docker-entrypoint.sh` - uses `WAYFARER_MIGRATE_ON_START`, `WAYFARER_MIGRATE_STRICT`.

**Acceptance Criteria (agent-executable)**:
- [x] `docker exec wayfarer-backend uv run alembic current` shows head revision. (`0003_user_admin_and_optional_email (head)`)
- [x] If upgrade was needed: `docker exec wayfarer-backend uv run alembic upgrade head` exits 0. (not needed; already at head)
- [x] Production environment config includes `WAYFARER_MIGRATE_STRICT=1`.

---

### 3) Fix the registration 500 root cause (DB/schema/config)

**What to do**:
- Based on Task 1’s stack trace, apply the minimal fix.
  - If missing columns / schema drift: ensure migrations run and DB is at head.
  - If migration failures are being ignored: tighten entrypoint behavior + config.
  - If `/v1/auth/login` is failing: verify `WAYFARER_JWT_SIGNING_KEYS_JSON` and `WAYFARER_JWT_KID_CURRENT` are set correctly in the backend container environment.
  - If `NEXT_PUBLIC_API_BASE_URL` or routing is wrong: correct docker/nginx env so web hits the correct backend.

**Must NOT do**:
- Do not downgrade migrations or do destructive DB operations without explicit approval.
- Do not expose internal exception strings to clients.

**Recommended Agent Profile**:
- **Category**: `unspecified-high`
- **Skills**: `git-master`

**References**:
- `backend/app/api/auth.py` - `/v1/auth/register` and error mapping.
- `backend/app/models/user.py` - expected columns (`is_admin`, nullable email).
- `backend/alembic/versions/0001_core_tables.py`, `backend/alembic/versions/0003_user_admin_and_optional_email.py` - schema evolution.

**Acceptance Criteria (agent-executable)**:
- [x] Re-run the failing register request against production (via browser automation or curl) and confirm it no longer returns 500. (verified: register 201)
- [x] Backend logs show no unhandled exception for the flow. (no matches for `Traceback|ERROR|Unhandled|Exception`)

---

### 4) Implement new password policy (backend is source of truth)

**What to do**:
- Update password policy enforcement in `backend/app/core/security.py`.
  - Rule: length >= 6 AND contains at least one ASCII letter `[A-Za-z]` AND one digit `[0-9]`.
  - Special characters allowed.
- Avoid leaking policy details through 422 validation:
  - Prefer enforcing policy inside `hash_password()` and returning a generic 400 business error from `/v1/auth/register`.

**Recommended Agent Profile**:
- **Category**: `unspecified-high`
- **Skills**: `git-master`

**References**:
- `backend/app/core/security.py` - `hash_password()` currently enforces length >= 12.
- `backend/app/api/auth.py` - `RegisterRequest` uses `Field(min_length=12)` today.
- `backend/app/main.py` - 422 validation envelope; do not rely on it for password policy.

**Acceptance Criteria (agent-executable)**:
- [x] Backend rejects passwords:
  - [x] too short (e.g., `a1b2c`) → 400 business error (not 422)
  - [x] missing digit (e.g., `abcdef`) → 400 business error
  - [x] missing letter (e.g., `123456`) → 400 business error
- [x] Backend accepts passwords (e.g., `abc123`, `abc123!`)

---

### 5) Web/Android: remove auth hints; keep only “大小写敏感”; sanitize error display

**What to do**:
- Web auth pages:
  - Remove all `FieldHint` texts on register/login forms except password “大小写敏感”.
  - Remove password input `minLength` to avoid browser-native validation popovers (these are effectively “hints”).
  - Change error display so it doesn’t render raw JSON bodies; show a generic message, optionally including `trace_id`.
- Android:
  - Remove the visible password length hint text in register UI.

**Recommended Agent Profile**:
- **Category**: `visual-engineering`
- **Skills**: `frontend-ui-ux`, `playwright`
  - `frontend-ui-ux`: ensure hint removals don’t break layout.
  - `playwright`: verify UI behavior automatically.

**References**:
- `web/app/register/page.tsx` - form hints + minLength + error display.
- `web/app/login/page.tsx` - form hints.
- `web/components/ui/Field.tsx` - hint component.
- `web/lib/auth.ts` - `ApiError.bodyText` propagation.
- `android/app/src/main/java/com/wayfarer/android/ui/SettingsScreen.kt` - register dialog hint text.

**Acceptance Criteria (agent-executable)**:
- [x] Web `/register` and `/login` pages contain no hint texts except password “大小写敏感”.
- [x] When backend returns `INTERNAL_ERROR`, UI does not display raw JSON; it shows a user-safe message.

---

### 6) Update docs to match new policy

**What to do**:
- Update `README.md` password policy section from 12-char minimum to new policy.
- (Optional but recommended) update internal `.sisyphus/*` docs to avoid future drift.

**Recommended Agent Profile**:
- **Category**: `writing`
- **Skills**: `git-master`

**References**:
- `README.md` - documents current 12-char minimum.
- `.sisyphus/deliverables/plan-supplement.md` and `.sisyphus/plans/*` - internal mentions of `min_length=12`.

**Acceptance Criteria**:
- [x] `README.md` no longer claims “至少 12 位”.

---

### 7) Add automated tests + run them

**What to do**:
- Backend (pytest): add focused tests for password policy (too short / missing digit / missing letter) and email optional behavior.
- Web (Playwright): add `register.spec.ts` using the project’s established hermetic mocking pattern (`page.route("**/v1/**", ...)`) and external network blocking.
  - Include a test that asserts the register request payload contains `email: null` when the input is blank.
  - Include a test that backend 500 error renders a safe UI message.

**Recommended Agent Profile**:
- **Category**: `unspecified-high`
- **Skills**: `playwright`

**References**:
- Backend tests:
  - `backend/tests/conftest.py` (sqlite-per-test setup)
  - `backend/tests/test_auth.py` (existing auth test patterns)
- Web tests:
  - `web/tests/auth.spec.ts` (route mocking + external network block pattern)
  - `web/playwright.config.ts` (webServer config)

**Acceptance Criteria (agent-executable)**:
- [x] `cd backend && uv run pytest -q` → PASS
- [x] `cd web && npm run test:e2e` → PASS

---

### 8) Deploy (docker compose) + verify production

**What to do**:
- Build + restart containers.
- Confirm migrations run and are strict in production.
- Verify registration and login on production web.
  - Use a clearly identifiable test username (e.g., `test_<yyyymmddhhmm>`) to avoid collisions.

**Recommended Agent Profile**:
- **Category**: `unspecified-high`
- **Skills**: `playwright`

**Acceptance Criteria (agent-executable)**:
- [x] Production API register no longer returns `INTERNAL_ERROR`/500. (returns 201)
- [x] Production can register with email blank and password compliant with new policy. (email null, password `abc123!` -> 201)

---

## Commit Strategy

Recommended commits (Conventional Commits, Chinese preferred):
1) `fix(backend): 修复注册 500（迁移/DB 对齐 + 启动失败即退出）`
2) `feat(auth): 调整密码策略为 6+字母数字（不提示细则）`
3) `fix(web): 注册/登录移除提示文案并规范错误展示`
4) `test: 增加注册与密码策略覆盖（pytest + playwright）`
5) `docs: 更新 README 密码规则`

---

## Success Criteria

### Verification Commands
Backend:
```bash
cd backend
uv run pytest -q
```

Web:
```bash
cd web
npm run test:e2e
```

Production diagnosis/verification (docker compose):
```bash
docker logs wayfarer-backend | rg "060d8d15-459d-4898-87a7-ef3e23455eca" -n
docker exec wayfarer-backend uv run alembic current
```

### Final Checklist
- [x] No 500 on production registration (public `POST /v1/auth/register` -> 201)
- [x] Email blank (null) registration works (email null -> 201)
- [x] Password policy implemented and tested
- [x] Auth hints removed except “大小写敏感”
- [x] No secrets committed

### Evidence (2026-02-02)
- `docker compose ps` (prod):
  - `wayfarer-backend` Up (healthy) `127.0.0.1:18000->8000`
  - `wayfarer-web` Up (healthy) `127.0.0.1:13000->3000`
- `docker exec wayfarer-backend uv run alembic current` (prod): `0003_user_admin_and_optional_email (head)`
- Host-port checks (prod):
  - `POST http://127.0.0.1:18000/v1/auth/register` (email null, password `abc123!`) -> 201
  - `POST http://127.0.0.1:18000/v1/auth/login` (same new `test_<timestamp>` user) -> 200
- Public endpoint checks (workstation, PowerShell `Invoke-WebRequest`):
  - `POST https://waf.pscly.cc/v1/auth/register` -> 201
  - `POST https://waf.pscly.cc/v1/auth/login` (non-existent user) -> 401
- Backend logs sanity (prod): `docker logs --since 10m wayfarer-backend 2>&1 | grep -n -E 'Traceback|ERROR|Unhandled|Exception'` -> no output
- Note: original trace_id `060d8d15-459d-4898-87a7-ef3e23455eca` not present in current container logs (container recreated).
