# Draft: Wayfarer Next Steps

## Current State (observed in repo)
- Plan exists: `.sisyphus/plans/wayfarer-plan-supplement.md`
- Local research baselines exist: `.sisyphus/plans/wayfarer-research-notes.md`
- Deliverable file exists but is placeholder: `.sisyphus/deliverables/plan-supplement.md` (needs Tasks 1-8 to fill)

## Requirements (confirmed)
- Fill ALL design gaps from `plan.md` without modifying `plan.md`.
- Then implement end-to-end: `backend/` + `web/` + `android/`.
- Android map SDK: AMap (高德地图 SDK).
- Export formats: CSV / GPX / GeoJSON / KML.
- Weather enrichment is best-effort: allow export without weather (PARTIAL).
- No secrets committed (keys via env/gradle injection).
- 沟通语言：用户要求使用中文。

## Requirements (newly confirmed in this turn)
- 坐标系策略：两套都存（WGS84 + GCJ-02）。
- 推进方式：先做高精度审查（Momus review loop）。

## Requirements (newly confirmed in latest user message)
- 本地开发环境：没有 Docker。
- 本地 `run.bat`：必须使用 `uv` 启动（而不是 docker compose）。

## Environment Notes (newly confirmed)
- 你有一个局域网内的 PostgreSQL（在另一台电脑上，不是本机）。
- 你在询问 PostGIS 是什么（说明当前 PG 是否装 PostGIS 不确定）。

## Critical Ambiguity / Decision Needed
- **Coordinate system canonicalization**:
  - AMap/China ecosystems often use GCJ-02, while DB/API currently assumes WGS84 (EPSG:4326).
  - Need explicit decision on what is stored/transmitted/exported and where conversions happen.

## Next Steps (proposed)
1. Decide coordinate system strategy (WGS84 vs GCJ-02 vs store both).
2. Optionally run a high-accuracy plan review (Momus) on `.sisyphus/plans/wayfarer-plan-supplement.md`.
3. Start execution from Task 1 (generate full `.sisyphus/deliverables/plan-supplement.md`), then proceed to implementation Tasks 9-25.

## Momus Review (result)
## Momus Review (latest)
- Verdict: OKAY (after multiple iterations)
- Key fixes applied:
  - 修复计划自引用的错误行号引用（如 `:42`），改为稳定的 Section 引用
  - 明确本计划是“两阶段同一计划”（Phase 1 文档交付 + Phase 2 全量实现），并补齐验收口径
  - 补齐 Web 时间轴/轨迹编辑的后端契约（tracks/query + tracks/edits），避免实现阻塞
  - 增加 Env Var Contract（键名/用途/空值降级），避免 Key 阻塞落地
  - 本地无 Docker：run.bat/stop.bat 使用 uv + npm；Celery dev eager；统一 Windows/PowerShell 命令口径
  - DB driver 对齐：PostgreSQL 使用 psycopg3；SQLite 使用 aiosqlite；DB URL 格式定稿
  - 双坐标策略落地：WGS84 来源 + WGS84→GCJ-02 转换方式 + 状态字段定稿
  - Playwright/Mapbox：测试不依赖外网/真实地图渲染；data-testid 断言；page.route mock 口径定稿
  - PostGIS geometry：不引入 geoalchemy2，使用 SQL 表达式写 geom；不使用触发器/生成列

## Scope Boundaries
- INCLUDE: design supplement + full implementation.
- EXCLUDE: committing real API keys or credentials; modifying `plan.md`.
