# 草稿：Wayfarer 下一步

## 当前状态（仓库观察）

- 已存在规划文件：`.sisyphus/plans/wayfarer-plan-supplement.md`
- 已存在本地研究基线：`.sisyphus/plans/wayfarer-research-notes.md`
- 已存在交付物文件但仍是占位：`.sisyphus/deliverables/plan-supplement.md`（需要 Tasks 1-8 逐步填充）

## 已确认的需求

- 补齐 `plan.md` 中的所有设计缺口，但不修改 `plan.md`。
- 然后端到端实现：`backend/` + `web/` + `android/`。
- Android 地图 SDK：AMap（高德地图 SDK）。
- 导出格式：CSV / GPX / GeoJSON / KML。
- 天气填充 best-effort：允许无天气导出（PARTIAL）。
- 禁止提交任何敏感信息（key 通过 env/gradle 注入）。
- 沟通语言：中文。

## 本轮新增确认

- 坐标系策略：两套都存（WGS84 + GCJ-02）。
- 推进方式：先做高精度审查（Momus review loop）。

## 最近用户消息新增确认

- 本地开发环境：没有 Docker。
- 本地 `run.bat`：必须使用 `uv` 启动（而不是 docker compose）。

## 环境说明（已确认）

- 你有一个局域网内的 PostgreSQL（在另一台电脑上，不是本机）。
- 你在询问 PostGIS 是什么（说明当前 PG 是否已安装 PostGIS 不确定）。

## 关键歧义 / 待决策

- **坐标系规范化（canonicalization）**：
  - AMap/国内生态通常使用 GCJ-02；而 DB/API 往往默认按 WGS84（EPSG:4326）。
  - 需要明确：哪些字段存/传/导出；以及在哪一层做转换（采集端/服务端/导出端）。

## 下一步（建议）

1. 决定坐标系策略（WGS84 vs GCJ-02 vs 同时存储）。
2. （可选）对 `.sisyphus/plans/wayfarer-plan-supplement.md` 进行高精度计划审查（Momus）。
3. 从 Task 1 开始执行（生成完整 `.sisyphus/deliverables/plan-supplement.md`），再继续实现 Tasks 9-25。

## Momus 审查（结果）

- 结论：OKAY（多轮迭代后）
- 关键修复：
  - 修复计划中自引用的错误行号引用（如 `:42`），改为稳定的 Section 引用
  - 明确本计划是“两阶段同一计划”（Phase 1 文档交付 + Phase 2 全量实现），并补齐验收口径
  - 补齐 Web 时间轴/轨迹编辑的后端契约（tracks/query + tracks/edits），避免实现阻塞
  - 增加 Env Var Contract（键名/用途/空值降级），避免 Key 阻塞落地
  - 本地无 Docker：run.bat/stop.bat 使用 uv + npm；Celery dev eager；统一 Windows/PowerShell 命令口径
  - DB driver 对齐：PostgreSQL 使用 psycopg3；SQLite 使用 aiosqlite；DB URL 格式定稿
  - 双坐标策略落地：WGS84 来源 + WGS84→GCJ-02 转换方式 + 状态字段定稿
  - Playwright/Mapbox：测试不依赖外网/真实地图渲染；data-testid 断言；page.route mock 口径定稿
  - PostGIS geometry：不引入 geoalchemy2，使用 SQL 表达式写 geom；不使用触发器/生成列

## 范围边界

- INCLUDE：设计补充文档 + 全量实现。
- EXCLUDE：提交真实 API key/凭据；修改 `plan.md`。
