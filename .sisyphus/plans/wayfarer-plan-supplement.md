# Wayfarer 设计补充方案（Plan Supplement）

## TL;DR

> **Quick Summary**: 先生成可执行的补充设计文档（Spec），再在同一执行周期内把后端 + Web + Android 全部落地到可运行、可联调、可导出的“完整可交付版本”。
>
> **Deliverables**:
> - `.sisyphus/deliverables/plan-supplement.md`（补充设计文档，唯一规范入口）
> - `backend/`（FastAPI + SQLAlchemy + Alembic + Celery）
> - `web/`（Next.js 14 + Mapbox GL JS）
> - `android/`（Kotlin + Compose + 高德地图 SDK + Room）
> - `docker-compose.yml`（可选：用于有 Docker 的环境；本地无 Docker 时不作为前置条件）
> - `run.bat` / `stop.bat`（本地一键启动/停止：使用 uv + npm，不依赖 Docker）
> - 自动化测试：Backend pytest、Web Playwright、Android 单元测试/基础集成校验
>
> **Estimated Effort**: XL
> **Parallel Execution**: NO - sequential (single deliverable file)
> **Critical Path**: Task 1 → Task 4 → Task 8

---

## Context

### Original Request
“按照 plan.md 具体看看哪些地方需要仔细详细设计，并全部补充。”

### Interview Summary
**Key Discussions**:
- 用户选择“全部都要”，补充所有设计缺口。
- 用户要求“一次性开发完毕并直接完善”，因此本计划扩展为：设计补充 + 全量实现 + 联调交付。
- Android 地图 SDK：高德地图 SDK。
- Root 增强：纳入（防睡眠白名单 + /dev/input 监控）。
- 交付物包含补充设计文档；不修改现有 plan.md。

**Research Findings**:
- Android FSM: 已研究 FusedLocationProvider + Activity Recognition API，得到状态集合、采样间隔、转换条件。
- 反作弊: 得到速度/步幅阈值、GPS 欺骗检测策略与伪代码。
- LifeEvent: 得到停留点检测、Home/Work 识别、通勤识别算法与参数。
- Open-Meteo: 得到 Rate Limit、Geohash 精度、批处理策略与缓存结构。
- Web Mapbox: 得到大规模轨迹渲染策略（简化、聚类、瓦片化、视口裁剪）。

### Metis Review
**Identified Gaps** (addressed in this plan):
- 认证机制未明确（JWT vs Session）
- Room Schema 与服务端映射策略未明确
- 导出任务生命周期（异步、取消、过期、存储位置）未明确
- Activity Recognition 权限拒绝与离线退化策略未明确

---

## Reference Sources & Parameter Baselines

> 执行者在补充文档中必须直接使用以下基线参数，除非业务明确要求调整。
> 若来源为定性建议，请将数值标注为“设计默认值”，并在文档中明确可调整。
> 每个基线给出来源链接，避免“凭空发明阈值”。

注：本计划以仓库内可读的 `.sisyphus/plans/wayfarer-research-notes.md` 作为“可验证来源”。
若团队希望补充外链，请在执行阶段将外链统一追加到该 notes 文件对应段落中（避免在补充文档里散落外链）。

### 本地研究依据（可验证）
- `.sisyphus/plans/wayfarer-research-notes.md` - 本次规划会话的研究摘录（FSM/反作弊/LifeEvent/天气/Mapbox/Auth/Sync/API/Export 等）

### Source Of Truth（防止资料互相打架）

- **最终规范入口**：`.sisyphus/deliverables/plan-supplement.md`（由 Tasks 1-8 生成）
- 若 `.sisyphus/plans/wayfarer-research-notes.md` 内部存在不一致表述：以“本计划的 Decisions Resolved + 最终 spec 文档”的定稿为准；research-notes 仅作为设计基线素材，不是最终契约。

### Android FSM (采样间隔与优先级)
- **Design Default** (需标注为“默认值，可调”):
  - State Set: IDLE / STATIONARY / WALKING / RUNNING / CYCLING / DRIVING / UNKNOWN
  - Intervals: STATIONARY=120s, WALKING=5s, RUNNING=3s, CYCLING=3s, DRIVING=5s
  - Priority Mapping: PASSIVE / LOW_POWER / BALANCED / HIGH_ACCURACY
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Android FSM（智能采样）`（状态/采样参数/minDistance）

### FSM Transition Defaults (阈值与条件)
- **Design Default** (需标注为“默认值，可调”):
  - STILL → STATIONARY: speed <0.5 m/s for 120s
  - WALKING → RUNNING: speed >2.5 m/s for 10s OR activity=RUNNING
  - RUNNING → WALKING: speed <2.0 m/s for 30s
  - ANY → DRIVING: speed >8.3 m/s for 30s OR activity=IN_VEHICLE
  - STOP DETECT: speed <0.5 m/s for 120s
  - debounce: 3–5s
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Android FSM（智能采样）`（默认转换阈值）

### 反作弊阈值 (速度/步幅)
- **Design Default** (需在补充文档中标注为“默认值，可调”):
  - Speed: walking 0.5–2.5 m/s, running 2.5–6.5 m/s, vehicle >8.3 m/s
  - Step Length: walking 0.4–1.0 m, running 0.8–2.0 m, absolute 0.3–2.5 m
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `反作弊阈值（速度/步幅/GPS 欺骗）`

### LifeEvent (停留点/家/办公)
- **Design Default** (需标注为“默认值，可调”):
  - Stay Point: distance 200m, time 5 min
  - Home Window: 21:00–07:00, min 2h, 50% nights
  - Work Window: weekdays 09:00–18:00, min 2h, >500m from home
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `LifeEvent（停留点/家/办公/通勤）`

### Open-Meteo (限流/缓存)
- **Rate Limit (free)**: 600/min, 5,000/hour, 10,000/day
- **Geohash Precision**: 5 (~5km)
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Open-Meteo（历史天气回溯）`

### Mapbox Web 性能 (规模阈值)
- **Design Default** (需标注为“默认值，可调”):
  - <50K points = GeoJSON; 100K–500K = simplify+cluster; 1M+ = vector tiles
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Mapbox（Web 轨迹渲染性能）`

### JWT 默认参数 (安全基线)
- **Design Default** (需标注为“默认值，可调”):
  - Access TTL: 15 minutes
  - Refresh TTL: 30 days
  - Refresh Rotation: enabled
  - signing_alg: HS256 (with kid)
  - password_hash: argon2id (min_length=12)
  - Storage: Android Keystore + Web httpOnly cookie
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Auth（JWT）`

### Room/Sync Baselines (本地存储与同步)
- **Design Default** (需标注为“默认值，可调”):
  - Local Tables: track_points_local, life_events_local, sync_queue
  - sync_status: 0=NEW, 1=QUEUED, 2=UPLOADING, 3=ACKED, 4=FAILED
  - retry_policy: exponential backoff, max_retries=5
  - conflict_dedupe: recorded_at + geom hash
  - coord_storage: WGS84 + GCJ-02 双坐标同时存储（用于 Web/导出 与 AMap 渲染一致性）

- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Room/Sync（本地存储与同步）`

### API Endpoint Baselines (清单)
- **Design Default** (需标注为“默认值，可调”):
  - POST /v1/auth/register
  - POST /v1/auth/login
  - POST /v1/auth/refresh
  - GET /v1/users/me
  - POST /v1/tracks/batch
  - GET /v1/tracks/query
  - GET /v1/life-events
  - POST /v1/life-events
  - PUT /v1/life-events/{id}
  - DELETE /v1/life-events/{id}
  - GET /v1/settings
  - PUT /v1/settings
  - POST /v1/export
  - GET /v1/export/{job_id}
  - GET /v1/export/{job_id}/download
  - POST /v1/export/{job_id}/cancel

- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `API 规范（端点清单与错误码）`

### Export Job Baselines (状态与保留)
- **Design Default** (需标注为“默认值，可调”):
  - job states: CREATED/RUNNING/PARTIAL/SUCCEEDED/FAILED/CANCELED
  - retention: metadata=7d, artifact=24h
  - artifact_dir: ./data/exports
  - file_naming: {user_id}/{job_id}.{ext}
  - max_concurrent_exports: 2 per user
  - max_export_points: 5_000_000
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Export Job（导出任务生命周期）`

### Export API Compatibility Baselines (与 plan.md 对齐)
- **Design Default** (需标注为“默认值，可调”):
  - 保留 `GET /v1/export?...` 的同步/流式体验
  - include_weather=true 或数据量 >50K points 时：返回 202 + JSON(body.job_id)
  - Web UI 推荐 `POST /v1/export` 创建任务
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Export API Compatibility（与 plan.md 同步导出描述对齐）`
  - `plan.md:144` - 现有 plan.md 对导出接口的描述

### Track Point Identity Baselines (幂等与回执匹配)
- **Design Default** (需标注为“默认值，可调”):
  - client_point_id: UUID（客户端生成，随上传 payload）
  - server unique: UNIQUE(user_id, client_point_id)
  - batch ack: accepted_ids[] / rejected[]（含 client_point_id + reason）
  - geom_hash: round(lat,5)+round(lon,5) → sha256
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Track Point Identity（幂等与回执匹配）`

### Geom Mapping Baselines (Server ↔ API ↔ Room)
- **Design Default** (需标注为“默认值，可调”):
  - Server: PostGIS `geom GEOMETRY(POINT, 4326)`（存 WGS84）；另存 GCJ-02 数值列以保证 AMap 渲染一致性（或运行时由 WGS84 计算）
  - API: 必填 `latitude`/`longitude`（WGS84）；可选 `gcj02_latitude`/`gcj02_longitude`（客户端可提供时上送）
  - Room: 同时存 WGS84 与 GCJ-02（例如 `lat_wgs84/lon_wgs84` + `lat_gcj02/lon_gcj02`），并记录：
    - `coord_source`（Design Default）：FusedLocationProvider(GPS/WGS84)
    - `coord_transform_status`（OK/OUTSIDE_CN/BYPASS/FAILED）

  - Android 坐标来源与转换（必须定稿，避免跨端对不齐返工）：
    - WGS84 来源：Android FusedLocationProvider（GPS/WGS84）
    - WGS84 → GCJ-02：使用高德 SDK `CoordinateConverter`（输入类型 GPS，输出 AMap/GCJ-02）
    - 中国境外：不做转换（`coord_transform_status=OUTSIDE_CN`，GCJ-02 可等于 WGS84 或置空，二选一在 spec 中定稿）
    - 不做 GCJ-02 → WGS84 逆转换（误差不可控）；服务端也不做逆转换
- **Sources**:
  - `plan.md:67` - 服务端 geom 类型
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Geom Mapping（Server ↔ API ↔ Room）`
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `坐标系策略（WGS84 / GCJ-02）`（双坐标存储/上传约定）

### Auth Server Model Baselines (refresh 落地)
- **Design Default** (需标注为“默认值，可调”):
  - refresh_tokens 表字段（family_id / token_hash / revoked_at / replaced_by 等）
  - rotation + reuse detection：复用被撤销 token 时撤销 family
  - cookie: httpOnly+Secure, SameSite=Lax 默认
  - refresh endpoint: CSRF 防护（double-submit + header）
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Auth Server Model（JWT Refresh 落地细节）`

### API Error Codes Baselines (稳定业务码)
- **Design Default** (需标注为“默认值，可调”):
  - 至少 10 个业务码（AUTH_*, TRACK_BATCH_*, EXPORT_*, RATE_LIMITED）
- **Sources**:
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `API Error Codes（稳定业务码）`

---

## Work Objectives

### Core Objective
生成 `.sisyphus/deliverables/plan-supplement.md`，以结构化方式补充 plan.md 的所有空白与细化部分，并给出可落地的参数、伪代码、边界测试与性能指标。

### Concrete Deliverables
- `.sisyphus/deliverables/plan-supplement.md`
  - 章节覆盖：Android FSM、Room Schema + Sync、API 规范 + 认证、反作弊、LifeEvent、天气回溯、Web 地图性能
  - 每章包含：参数表、伪代码/状态图、边界测试矩阵、SLA/性能指标

### Definition of Done
- [x] `.sisyphus/deliverables/plan-supplement.md` 包含完整章节与目录（FSM/API/反作弊/Room/LifeEvent/天气/性能）
- [x] 所有缺口均有明确阈值/算法/流程图
- [x] 包含可执行的验证标准（无需人工确认）

### Must Have
- FSM 状态机完整定义（含状态图、采样参数、转换条件）
- API 规范（包含认证、错误码、curl 示例）
- 反作弊与 LifeEvent 算法参数化
- Open-Meteo 缓存与限流策略
- 规范优先级声明：当补充文档与 `plan.md` 冲突时，以补充文档为准（但不修改 `plan.md`）

### Must NOT Have (Guardrails)
- 不修改 `plan.md`
- 不引入新的技术栈依赖
- 不引入 ML/AI 反作弊或微服务拆分设计
- 不提交任何密钥/Token/账号到仓库（禁止 `.env` / 私钥等进入 git）
- 版本号规则：在用户明确要求发布 v1.0.0 之前，保持主版本号为 0（0.x.y）
- `plan.md` 中的 “Version 1.0.0 (Initial Release)” 视为“文档版本/规划版本”，不等同于软件版本；软件版本继续遵循 0.x.y

---

## Verification Strategy (MANDATORY)

### Verification Phases (本计划是“同一份计划、两类交付物”) 

- **Phase 1（Tasks 1-8）**：生成补充设计交付物 `.sisyphus/deliverables/plan-supplement.md`
  - 验收：仅文档校验（PowerShell `Select-String` 计数/存在性检查）
- **Phase 2（Tasks 9-25）**：落地后端 + Web + Android 并完成联调交付
  - 验收：工程化自动化（Backend pytest、Web Playwright、Android Gradle tests、本地 run.bat/stop.bat 一键启动）

### Phase 1 Test Decision
- **Infrastructure exists**: N/A（文档任务）
- **User wants tests**: Automated-only（无人工确认）
- **Framework**: PowerShell (Select-String)

### Phase 2 Test Decision
- **Infrastructure exists**: 将由 Tasks 9-25 建立（backend/web/android 各自的测试框架）
- **User wants tests**: YES（自动化为主）
- **Framework**: pytest + Playwright + Gradle tests

### Automated Verification Only (NO User Intervention)

每个任务必须提供可自动执行的校验方法，示例：

> Windows PowerShell 注意：`curl` 可能是 `Invoke-WebRequest` 的别名；脚本/验收中如需使用 curl，必须写 `curl.exe`。
> 本计划的 E2E 示例默认使用 `Invoke-RestMethod`，避免该坑。

### Deliverable Conventions (MUST)

为满足“Automated-only”，补充文档必须使用可计数的固定标记（执行者不得自由发挥格式）：
- Gap Inventory 行：`| GAP-### | ... |`
- Spec Delta 行：`| DELTA-### | ... |`
- 边界测试用例：`- TEST-<DOMAIN>-###: ...`（例：`TEST-FSM-001`）
- 性能/预算指标：`- SLA-<DOMAIN>-###: ...`（例：`SLA-WEBMAP-001`）
- 优化策略条目：`- OPT-<DOMAIN>-###: ...`（例：`OPT-WEBMAP-001`）
- 错误码条目：`- ERR-###: CODE_NAME (HTTP xxx)`（例：`ERR-001: AUTH_INVALID_CREDENTIALS (HTTP 401)`）

### Fixed Tokens (MUST)

> 为避免“内容写对了但验收因措辞不同失败”，以下固定 token 必须在补充文档中按原样出现（区分大小写）。

- `recorded_at(UTC)`
- `GPS-only`
- `life_event_recompute_on_edit: false`
- `pagination: limit_offset`
- `cookie_refresh_name: wf_refresh`
- `cookie_csrf_name: wf_csrf`
- `dev_cookie_secure: false`
- `cors_allow_origin: http://localhost:3000`
- `cors_allow_credentials: true`
- `frontend_credentials: include`

只要存在“至少 N 条”要求，必须配套提供 `Select-String` 计数命令。

```powershell
# 验证关键章节存在（Windows 内置，无需 rg）
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Android FSM'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## API 规范'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## 反作弊'"

# 验证 FSM 状态图与参数表
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'stateDiagram-v2'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '采样间隔'"

# 验证 API 含 curl 示例
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'curl -X'"

# 计数示例（Gap 行数）
powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '| GAP-' -SimpleMatch).Count"
```

---

## Execution Strategy

### Sequential Execution (Recommended)

> Phase 1（Tasks 1-8）：由于所有任务都写入同一个交付文件 `.sisyphus/deliverables/plan-supplement.md`，为避免合并冲突，要求顺序执行。
> 
> Phase 2（Tasks 9-25）：属于工程实现，可并行但需要遵循依赖关系；如团队不想并行，按任务顺序顺推也可。

Execution Order:
1) Task 1: 建骨架 + Gap Inventory + Spec Delta 规则
2) Task 2: Android FSM
3) Task 3: Room/Sync + 幂等 + geom 映射 + DDL delta
4) Task 4: API/Auth/Export（含兼容策略、安全落地、错误码）
5) Task 5: Anti-Cheat
6) Task 6: LifeEvent
7) Task 7: Weather
8) Task 8: Web Map Performance

Critical Path: Task 1 → Task 4 → Task 8

### Dependency Matrix

| Task | Depends On | Blocks | Can Parallelize With |
|------|------------|--------|----------------------|
| 1 | None | 2 | Sequential |
| 2 | 1 | 3 | Sequential |
| 3 | 2 | 4 | Sequential |
| 4 | 3 | 5 | Sequential |
| 5 | 4 | 6 | Sequential |
| 6 | 5 | 7 | Sequential |
| 7 | 6 | 8 | Sequential |
| 8 | 7 | None | Sequential |

---

## TODOs

> Implementation + Verification = ONE Task. 不拆分。

- [x] 1. 生成补充设计文档骨架与全局规则

  **What to do**:
  - 确保目录存在：`.sisyphus/deliverables/`（如不存在则创建）
  - 创建/覆盖 `.sisyphus/deliverables/plan-supplement.md` 骨架与目录
    - **硬规则**：当前文件即使存在占位内容，也必须被 Task 1 的固定骨架“整文件重写覆盖”。
    - GAP/DELTA 编号规则：
      - Task 1 首次生成时从 `GAP-001` / `DELTA-001` 开始
      - 后续章节追加只能递增，不允许重用编号
    - 必须包含这些一级标题（固定名，便于验收与链接）：
      - `## Gap Inventory`
      - `## Spec Delta`
      - `## Android FSM`
      - `## Room Schema & Sync`
      - `## API 规范`
      - `## Anti-Cheat`
      - `## LifeEvent`
      - `## Weather Enrichment & Cache`
      - `## Web Map Performance`
      - `## Global Error Codes`
      - `## Export Job Lifecycle`
  - 写明范围、术语、关键假设与待决策项
  - 定义每章必含结构：参数表 + 伪代码/状态图 + 边界测试矩阵
  - 增加 `Gap Inventory` 表：将 `plan.md` 的缺口逐条编号，并映射到补充文档章节
    - 列: gap_id / plan_md_ref / gap_desc / target_section / verification
    - 最少覆盖: FSM 空白、Sync/冲突、Batch API 回执、Export 同步/异步冲突、反作弊阈值缺失、LifeEvent 算法缺失、天气限流与降级、Web 大数据渲染
    - 抽取规则（最小算法）：
      - 若 plan.md 出现“空白标题/伪代码未给出内容/只有一句话无参数/无阈值表/无错误码/无状态机/无幂等与回执语义”则判定为 gap
  - 增加 `Spec Delta（与 plan.md 的差异清单）` 固定章节（阻止执行阶段猜测“哪个为准”）
    - 列: delta_id / plan_md_ref / supplement_section / change_type(新增/修订/澄清) / impact(DB/API/Android/Web/Export) / notes
    - 必须覆盖: client_point_id 字段增量、Export 同步→异步兼容策略、Auth(JWT refresh_tokens) 增量、Sync 回执语义增量、版本号规则（AGENTS.md: 主版本保持 0）

  **Must NOT do**:
  - 不修改 `plan.md`
  - 不引入新技术栈

  **Recommended Agent Profile**:
  - **Category**: writing
    - Reason: 交付物为结构化设计文档
  - **Skills**: ["text-formatter"]
    - text-formatter: 保证文档结构清晰、格式统一
  - **Skills Evaluated but Omitted**:
    - project-analyze: 无需深度代码结构分析

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential (Wave 1)
  - **Blocks**: 2
  - **Blocked By**: None

  **References**:
  - `plan.md:49` - users 表字段（username/hashed_password/settings）作为注册/登录输入依据
  - `plan.md:1` - 现有总体计划格式与语气

  **Acceptance Criteria**:
  - [ ] `powershell -NoProfile -Command "Test-Path '.sisyphus/deliverables'"` 输出 True
  - [ ] `.sisyphus/deliverables/plan-supplement.md` 存在且包含目录
  - [ ] 交付文档骨架包含全部固定一级标题（逐条校验）:
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Gap Inventory'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Spec Delta'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Android FSM'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Room Schema & Sync'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## API 规范'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Anti-Cheat'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## LifeEvent'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Weather Enrichment & Cache'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Web Map Performance'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Global Error Codes'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Export Job Lifecycle'"`
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '| GAP-' -SimpleMatch).Count"` 输出 >= 8
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'DELTA-' -SimpleMatch).Count"` 输出 >= 3
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'ERR-' -SimpleMatch).Count"` 输出 >= 10
  - [ ] 占位文案必须被覆盖（避免“标题存在但内容仍是占位”）：
    - `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '占位' -SimpleMatch).Count"` 输出 == 0

- [x] 2. Android FSM 详细设计

  **What to do**:
  - 定义状态集合、采样参数表、转换矩阵
  - 用 Mermaid `stateDiagram-v2` 绘制状态图
  - 写明权限降级（GPS-only 推断）、离线退化策略与边界测试
    - 边界测试必须用 `TEST-FSM-###:` 标记（至少 5 条）
  - 明确每个状态的 `minDistance` 默认值，并给出“可调理由”（省电/精度）
  - 明确转换阈值默认值（speed + duration + debounce）并列出优先级（Activity > GPS 推断）

  **Must NOT do**:
  - 不写具体 Kotlin 实现代码

  **Recommended Agent Profile**:
  - **Category**: writing
    - Reason: 输出设计规格与参数表
  - **Skills**: ["text-formatter"]
    - text-formatter: 表格与图示格式一致
  - **Skills Evaluated but Omitted**:
    - project-analyze: 无需代码分析

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 3
  - **Blocked By**: 1

  **References**:
  - `plan.md:113` - FSM 章节空白位置
  - `plan.md:97` - Android 服务与 Root 相关上下文
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `Android FSM (采样间隔与优先级)` + `FSM Transition Defaults (阈值与条件)`（本计划内的 FSM 基线参数）
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Android FSM（智能采样）`（本地可验证）

  **Acceptance Criteria**:
  - [ ] FSM 章节包含状态表（含采样间隔、优先级、minDistance）
  - [ ] 含 `stateDiagram-v2` 图示
  - [ ] 含至少 5 条边界测试用例
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'TEST-FSM-' -SimpleMatch).Count"` 输出 >= 5
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'stateDiagram-v2'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'STATIONARY.*120s'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'WALKING.*5s'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'RUNNING.*3s'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'minDistance.*50m'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'minDistance.*5m'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'minDistance.*3m'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'minDistance.*20m'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'speed <0\.5 m/s'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'speed >2\.5 m/s'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '离线'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'GPS-only'"`

- [x] 3. Room Schema + Sync 详细设计

  **What to do**:
  - 定义 Room 表结构（track_points_local / life_events_local / sync_queue）
  - 明确与服务端字段映射与 sync_status 状态机（完全镜像，含 weather_snapshot）
  - 补充字段映射表（本地字段 ↔ 服务端字段）
  - geom/坐标映射必须定稿：
    - Room(WGS84+GCJ-02 双存) ↔ API(WGS84 必填 + GCJ-02 可选) ↔ PostGIS geom(POINT,4326=WGS84)
    - 转换策略（设计默认）：Android 本地计算两套坐标并随上传携带，服务端不做 GCJ→WGS 的近似逆转换
  - 补充网络中断、部分成功与重试策略
  - 定义多设备同步冲突处理规则（recorded_at+geom 去重）
  - 明确 sync_queue 字段清单（id/user_id/payload_sha256/attempt_count/next_retry_at/last_error 等）
  - 明确“部分成功”语义：服务端必须返回 per-item 失败原因，客户端按 client_point_id 标记 ACKED/FAILED
  - 明确幂等主键：client_point_id（客户端生成）与服务端唯一约束（user_id, client_point_id）
  - 明确服务端 BIGSERIAL `track_points.id` 与客户端 `client_point_id` 的角色区别（内部ID vs 幂等ID）
  - 冲突优先级（需在补充文档写清）：
    - 幂等/回执匹配：以 (user_id, client_point_id) 为准
    - 多设备“弱去重”：recorded_at + geom_hash 仅用于标注/合并提示，不作为严格唯一约束
  - geom_hash 的计算口径必须写入补充文档（round(lat,5)/round(lon,5) + sha256）
  - 在补充文档内提供“服务端 Schema Delta（DDL）”片段：
    - `ALTER TABLE track_points ADD COLUMN client_point_id UUID NOT NULL;`
    - `CREATE UNIQUE INDEX ... ON track_points(user_id, client_point_id);`

  **Must NOT do**:
  - 不写 Room DAO 实现代码

  **Recommended Agent Profile**:
  - **Category**: writing
    - Reason: 规范化 Schema 与同步规则
  - **Skills**: ["text-formatter"]
    - text-formatter: SQL DDL 与表格格式一致
  - **Skills Evaluated but Omitted**:
    - project-analyze: 不依赖现有代码

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 4
  - **Blocked By**: 2

  **References**:
  - `plan.md:58` - 服务端 track_points 字段
  - `plan.md:77` - life_events 表结构
  - `plan.md:119` - Sync Engine 描述
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `Room/Sync Baselines (本地存储与同步)`（同步状态/重试/双坐标口径）
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `Track Point Identity Baselines (幂等与回执匹配)`（client_point_id/回执/geom_hash）
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `Geom Mapping Baselines (Server ↔ API ↔ Room)`（WGS84+GCJ-02 双坐标与 PostGIS 映射）
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Track Point Identity` + `Room/Sync`（幂等/回执/队列/部分成功）

  **Acceptance Criteria**:
  - [ ] 包含 SQL DDL 段落（至少 3 张表）
  - [ ] 包含 sync_status 状态定义与转换条件
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'CREATE TABLE').Count"` 输出 >= 3
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'track_points_local'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'sync_queue'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'recorded_at\+geom'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '字段映射'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'payload_sha256'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'attempt_count'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'next_retry_at'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'rejected\[\]'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'client_point_id'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'UNIQUE\(user_id, client_point_id\)'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'ALTER TABLE track_points'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'round\(lat, 5\)'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'latitude'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'longitude'"`

- [x] 4. API 规范 + 认证 + 导出生命周期

  **What to do**:
  - 定义认证 API（JWT access/refresh，注册/登录/刷新）与错误码
  - 补全轨迹、LifeEvent、设置、导出相关 API
  - **补齐 Web 时间轴/轨迹编辑所需的后端契约（实现阻塞点，必须在补充文档里定稿）**：
    - 轨迹查询：`GET /v1/tracks/query`
      - 说明：按用户 + 时间窗查询点位；默认返回“已应用编辑规则”的视图
    - 轨迹编辑（最小可交付契约，Design Default，可扩展）：
      - `POST /v1/tracks/edits`（创建编辑操作）
      - `GET /v1/tracks/edits`（列出编辑操作）
      - `DELETE /v1/tracks/edits/{id}`（撤销编辑操作）
    - 编辑语义（Design Default，写清以避免实现阶段拍脑袋）：
      - **非破坏性编辑优先**：`track_points` 作为原始数据追加表；编辑作为“edit layer”影响 query/export 的返回视图
      - MVP 支持：按时间区间删除（DELETE_RANGE）；平滑（SMOOTH）作为可选扩展（可先做前端可视化平滑，不落库）
      - 影响链路默认：展示/导出使用编辑后视图；LifeEvent 是否重算在补充文档中明确（Design Default 可配）
  - 规定时间字段规范（recorded_at 统一 UTC）
    - 导出 timezone（参数名：timezone，默认 UTC，支持 IANA TZ 如 Asia/Shanghai）
    - timezone 仅影响导出时间格式化，不影响查询窗口（仍按 recorded_at(UTC)）
  - 定义导出异步任务生命周期（create/status/download/cancel/expiry）
  - 明确导出格式：CSV/GPX/GeoJSON/KML
  - 明确 JWT TTL（access=15m, refresh=30d）与刷新策略
  - 明确错误码格式（HTTP + code/message/details/trace_id）并列出最少 10 个稳定业务码
    - 错误码条目必须用 `ERR-###:` 标记（便于自动计数）
  - 明确导出 job 状态枚举（CREATED/RUNNING/PARTIAL/SUCCEEDED/FAILED/CANCELED）与迁移条件
  - 解决 plan.md 同步导出与异步 Job 的冲突：给出兼容策略（GET 直出 vs 202 job_id）
  - 认证落地细节必须写清：refresh_tokens 表、rotation、reuse detection、cookie 属性、CSRF 边界
    - JWT signing_alg（定稿 HS256 + kid）与 key rotation 口径
    - password hashing（定稿 argon2id）与最小密码策略（min_length=12）
  - CSRF 最小流程（设计默认）：double-submit
    - 服务端下发 csrf cookie（非 httpOnly）
    - 客户端在 refresh 请求时带 `X-CSRF-Token` header
    - 服务端校验 cookie 值 == header 值
    - cookie 命名（必须定稿，便于自动验收/跨端一致）：
      - `cookie_refresh_name: wf_refresh`
      - `cookie_csrf_name: wf_csrf`

    - 本地开发环境（http://localhost）cookie 合同必须定稿（阻塞级）：
      - `dev_cookie_secure: false`（否则 http 下浏览器不会发送 Secure cookie，refresh 全链路会卡死）
      - SameSite（Design Default）：Lax
      - Domain/Path（Design Default）：domain=localhost, path=/

    - 跨端 CORS/credentials 合同必须定稿（阻塞级，Web 3000 → Backend 8000）：
      - `cors_allow_origin: http://localhost:3000`
      - `cors_allow_credentials: true`
      - allow_headers 至少包含：`Content-Type`, `Authorization`, `X-CSRF-Token`
      - 前端 fetch 必须使用：`frontend_credentials: include`

    - refresh / cookie 行为必须定稿（阻塞级，避免跨端不一致）：
      - Web:
        - login：Set-Cookie 写入 refresh cookie（wf_refresh），JSON body 返回 access_token
        - refresh：校验 CSRF 后 rotation refresh cookie（wf_refresh），JSON body 返回 access_token
        - 约束：Web 永不在 JSON body 返回 refresh token
      - Android:
        - login：JSON body 返回 access_token + refresh_token
        - refresh：JSON body 返回新的 access_token + refresh_token（rotation）
    - 明确 Android 与 Web 差异：Android 不走 cookie/CSRF（Authorization header + body refresh）；Web 走 cookie+CSRF
  - 导出存储与边界必须写清：artifact_dir/file_naming/max_concurrent_exports/max_export_points/清理触发
    - 固定写法要求（便于验收）：
      - `retention_metadata: 7 天`
      - `retention_artifact: 24 小时`

  - API 最小 schema 必须写入补充文档（避免执行阶段拍脑袋）：
    - `/v1/tracks/batch` request item 必填字段：client_point_id, recorded_at, latitude, longitude, accuracy  # latitude/longitude = WGS84
    - `/v1/tracks/batch` request item 可选字段：gcj02_latitude, gcj02_longitude, coord_source
    - `/v1/tracks/batch` response 必须包含：accepted_ids[]、rejected[]
      - rejected item 至少包含：client_point_id、reason_code、message

    - `/v1/tracks/batch` 幂等回执语义必须定稿（阻塞级）：
      - **Design Default**：当 (user_id, client_point_id) 已存在时，视为“幂等确认成功”，仍然放入 `accepted_ids[]`（不能放入 rejected，否则客户端会重试风暴）
      - `rejected[]` 仅用于：字段校验失败/范围非法/批量过大/鉴权失败等真正需要客户端修复的数据问题
    - `/v1/export` job create response 必须包含：job_id

    - `GET /v1/tracks/query`（Web 展示/导出前预览用）必须写入最小契约：
      - 分页方案（阻塞级）：`pagination: limit_offset`
      - Query params：
        - start/end（UTC ISO8601）
        - limit（默认 1000，上限 5000）
        - offset（默认 0）
      - sort：recorded_at ASC
      - Response: items[] 字段清单（至少 client_point_id, recorded_at, latitude/longitude(WGS84), gcj02_latitude/gcj02_longitude(可选), accuracy, is_dirty, is_deleted_by_edit(可选)）

    - `POST /v1/tracks/edits`（MVP: DELETE_RANGE）必须写入最小契约：
      - Request: type=DELETE_RANGE + start/end（UTC ISO8601）
      - Response: edit_id + applied_count（或等价可观察字段）
      - 同时写明 edits 的落库 schema（DDL 级别）：例如 `track_edits` 表字段与索引（user_id + time_range + created_at）

    - LifeEvent 与 edits 的一致性默认值必须定稿（阻塞级）：
      - Design Default：`life_event_recompute_on_edit: false`（编辑不自动重算；后续可加显式重算接口/后台任务）

    - 认证账号标识必须定稿（阻塞级，与 plan.md schema delta 对齐）：
      - Design Default：登录主键为 `email`；`username` 作为展示名
      - DB Schema Delta：users 增加 `email TEXT UNIQUE NOT NULL`
      - 约束：username 继续保持 UNIQUE（与 `plan.md` 一致），注册时必须提供 username

    - `/v1/settings` 最小 schema 必须定稿（阻塞级，避免前后端拍脑袋）：
      - Settings 对象（Design Default，字段可扩展但这些必须存在）：
        - `timezone`（默认 UTC）
        - `export_default_format`（默认 CSV）
        - `export_include_weather_default`（默认 false）
        - `tracks_query_default_limit`（默认 1000）
      - `GET /v1/settings`：返回 settings 对象
      - `PUT /v1/settings`：提交部分更新（PATCH-like）或全量替换（二选一，Design Default 在 spec 内定稿）

  **Must NOT do**:
  - 不输出完整 OpenAPI YAML

  **Recommended Agent Profile**:
  - **Category**: writing
    - Reason: 规范化 API 表格与示例
  - **Skills**: ["text-formatter"]
    - text-formatter: API 表格与示例一致
  - **Skills Evaluated but Omitted**:
    - project-analyze: 不依赖现有代码

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 5
  - **Blocked By**: 3

  **References**:
  - `plan.md:130` - /v1/tracks/batch
  - `plan.md:144` - /v1/export
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `JWT 默认参数 (安全基线)` + `Export Job Baselines (状态与保留)` + `Export API Compatibility Baselines (与 plan.md 对齐)`（认证/导出基线）
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Auth（JWT）`（TTL/HS256+kid/argon2id）
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Export API Compatibility`（GET 兼容 + 202 job_id）
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Auth Server Model`（refresh_tokens/rotation/CSRF）
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `API Error Codes`（ERR-*）

  **Acceptance Criteria**:
  - [ ] 每个 API 至少包含 Method/Path/Request/Response/Error Codes
  - [ ] 至少 5 个 curl 示例
  - [ ] 导出任务生命周期含状态图或时序图
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'ERR-' -SimpleMatch).Count"` 输出 >= 10
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'curl -X').Count"` 输出 >= 5
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '15m'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '30d'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'CSV/GPX/GeoJSON/KML'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '/v1/auth/login'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '/v1/tracks/query'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '/v1/tracks/edits'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'life_event_recompute_on_edit: false'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'pagination: limit_offset'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'cookie_refresh_name: wf_refresh'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'cookie_csrf_name: wf_csrf'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'dev_cookie_secure: false'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'cors_allow_origin: http://localhost:3000'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'cors_allow_credentials: true'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'frontend_credentials: include'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '/v1/settings'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'email TEXT UNIQUE NOT NULL'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '/v1/life-events'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '/v1/export/{job_id}'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'AUTH_INVALID_CREDENTIALS'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'trace_id'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'PARTIAL'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'refresh_tokens'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'family_id'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'replaced_by'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'AUTH_REFRESH_REUSED'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'CSRF'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'X-CSRF-Token'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'double-submit'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'HS256'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'kid'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'argon2id'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '202'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'timezone'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'Asia/Shanghai'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'recorded_at\(UTC\)'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'accepted_ids'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'rejected\[\]'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'reason_code'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'retention_metadata\s*:\s*7 天'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'retention_artifact\s*:\s*24 小时'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'artifact_dir'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'max_concurrent_exports'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'max_export_points'"`

- [x] 5. 反作弊算法与阈值设计

  **What to do**:
  - 定义速度阈值、步幅阈值与 GPS 欺骗检测规则
  - 给出综合评分/标记逻辑与边界测试
    - 边界测试必须用 `TEST-ANTICHEAT-###:` 标记（至少 6 条）

  **Must NOT do**:
  - 不引入 ML 或训练模型

  **Recommended Agent Profile**:
  - **Category**: writing
    - Reason: 规则阈值表与伪代码输出
  - **Skills**: ["text-formatter"]
    - text-formatter: 表格规范化
  - **Skills Evaluated but Omitted**:
    - project-analyze: 无需代码分析

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 6
  - **Blocked By**: 4

  **References**:
  - `plan.md:134` - audit_track_segment 逻辑
  - `plan.md:69` - accuracy 字段
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `反作弊阈值 (速度/步幅)`（阈值默认值与范围）

  **Acceptance Criteria**:
  - [ ] 阈值表包含 walking/running/vehicle 边界
  - [ ] 至少 6 条边界测试用例
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'TEST-ANTICHEAT-' -SimpleMatch).Count"` 输出 >= 6
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '步幅'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '0\.5'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '2\.5'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '8\.3'"`

- [x] 6. LifeEvent 自动标记算法

  **What to do**:
  - 描述 stay point 检测算法与参数
  - 描述 Home/Work 识别规则与评分
  - 描述 Commute 识别规则与样例
    - 边界测试必须用 `TEST-LIFEEVENT-###:` 标记（至少 4 条）

  **Must NOT do**:
  - 不引入 POI 数据库实现细节

  **Recommended Agent Profile**:
  - **Category**: writing
    - Reason: 规则算法说明为主
  - **Skills**: ["text-formatter"]
    - text-formatter: 参数表统一
  - **Skills Evaluated but Omitted**:
    - project-analyze: 无需代码分析

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 7
  - **Blocked By**: 5

  **References**:
  - `plan.md:77` - life_events 表
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `LifeEvent (停留点/家/办公)`（默认参数）

  **Acceptance Criteria**:
  - [ ] 包含 stay point 参数表（时间/距离阈值）
  - [ ] 包含 Home/Work 评分规则
  - [ ] 至少 4 条算法边界测试
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'TEST-LIFEEVENT-' -SimpleMatch).Count"` 输出 >= 4
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '200m'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '5 min'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '21:00'"`

- [x] 7. 天气回溯与缓存策略

  **What to do**:
  - 明确 Open-Meteo 调用策略、限流、并发与退避
  - 明确缓存键（geohash+hour）与 TTL 规则
  - 定义降级策略（允许无天气导出）
  - 固定写法要求（便于验收）：补充文档中必须出现 `geohash_precision: 5`

  **Must NOT do**:
  - 不引入其他天气服务提供商

  **Recommended Agent Profile**:
  - **Category**: writing
    - Reason: 输出缓存与流程规范
  - **Skills**: ["text-formatter"]
    - text-formatter: 表格与流程统一
  - **Skills Evaluated but Omitted**:
    - project-analyze: 无需代码分析

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 8
  - **Blocked By**: 6

  **References**:
  - `plan.md:89` - weather_cache
  - `plan.md:140` - 导出与天气填充流程
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `Open-Meteo (限流/缓存)`（限流、geohash 精度与缓存默认值）
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Open-Meteo（历史天气回溯）`（本地可验证）

  **Acceptance Criteria**:
  - [ ] 包含 Rate Limit 数字与退避策略
  - [ ] 明确 geohash 精度与缓存键示例
  - [ ] 定义失败降级行为
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '600/min'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'geohash_precision\s*:\s*5'"`

- [x] 8. Web Mapbox 性能设计

  **What to do**:
  - 定义轨迹点规模阈值与对应策略
  - 描述简化、聚类、瓦片化、视口裁剪
    - 每条优化策略必须用 `OPT-WEBMAP-###:` 标记（至少 3 条）
  - 给出性能指标（首屏/帧率/内存）
    - 每条指标必须用 `SLA-WEBMAP-###:` 标记（至少 3 条）
    - 指标必须是“可度量数值 + 口径”（Design Default，可调）：
      - 首屏（P95）：<= 3000ms（100K points）
      - 交互帧率：>= 30fps（平移/缩放）
      - 内存上限：<= 500MB（包含轨迹数据与地图）
    - 目标测量口径（Design Default，**非自动 gate**）：Chrome DevTools Performance + Memory（记录 10s 平移/缩放）
      - 说明：本计划的 Automated-only 验收对 Phase 1 仅要求这些 `SLA-WEBMAP-*` 指标在补充文档中被明确写出（可计数/可检索），不要求在执行阶段做人工 DevTools 测量。

  **Must NOT do**:
  - 不实现前端组件代码

  **Recommended Agent Profile**:
  - **Category**: writing
    - Reason: 输出性能策略与指标
  - **Skills**: ["text-formatter"]
    - text-formatter: 参数表一致
  - **Skills Evaluated but Omitted**:
    - project-analyze: 无需代码分析

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: None
  - **Blocked By**: 7

  **References**:
  - `plan.md:40` - Mapbox GL JS 选择
  - `plan.md:159` - Web 交互相关章节
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `Mapbox Web 性能 (规模阈值)`（规模分档与策略默认值）
  - `.sisyphus/plans/wayfarer-research-notes.md` - Section: `Mapbox（Web 轨迹渲染性能）`（本地可验证）

  **Acceptance Criteria**:
  - [ ] 包含点位规模阈值表（50K/100K/1M+）
  - [ ] 明确至少 3 项优化策略
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'OPT-WEBMAP-' -SimpleMatch).Count"` 输出 >= 3
  - [ ] 含性能指标（首屏时间/帧率/内存）
  - [ ] `powershell -NoProfile -Command "(Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'SLA-WEBMAP-' -SimpleMatch).Count"` 输出 >= 3
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '50K'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '100K'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '1M'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'P95'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'ms'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'fps'"`
  - [ ] `powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'MB'"`

---

## Implementation TODOs（一次开发完）

> 说明：从当前仓库“几乎空白”开始落地后端 + Web + Android。
> 为避免 Key/账号等外部依赖阻塞，本计划默认地图 Key 走“占位 + 环境变量注入 + 无 Key 降级提示”，测试走 mock。

### Env Var Contract（必须定稿，避免落地/CI 被 Key 阻塞）

> 只定义“键名 + 用途 + 空值时降级行为”，不提交真实值。

- Backend:
  - `WAYFARER_JWT_SIGNING_KEYS_JSON`（生产必须：JSON 映射 kid→key；用于验签历史 token，满足 rotation；开发模式可由 run.bat 自动生成并注入）
    - 例：`{"dev-1":"dev-secret","dev-2":"old-secret"}`
  - `WAYFARER_JWT_KID_CURRENT`（当前签发 kid；默认 `dev-1`；必须存在于 SIGNING_KEYS_JSON）
  - `WAYFARER_DB_URL`（可选：数据库连接串；本地默认 sqlite；也可指向局域网 PostgreSQL）
  - `WAYFARER_CELERY_EAGER`（可选：开发默认 1，使后台任务同步执行；生产为 0/未设置）

  - DB URL & Driver 口径（必须钉死，避免 Async/迁移返工）：
    - SQLite（开发默认）：`sqlite+aiosqlite:///./data/dev.db`
    - PostgreSQL（局域网）：`postgresql+psycopg://user:pass@host:5432/dbname`
    - 说明：应用运行使用 SQLAlchemy Async；PostgreSQL driver 统一使用 **psycopg3**（与 `AGENTS.md:5` 对齐）；SQLite 使用 `aiosqlite`。

  - **Dev 自动注入规则（为满足 run.bat 一键启动 + Automated-only）**：
    - 若未显式提供 `WAYFARER_JWT_SIGNING_KEYS_JSON`：
      - run.bat 必须在本地生成/写入一个未提交文件：`.sisyphus/run/jwt-signing-keys.json`
      - 并在启动 backend 进程时注入该变量（仅对当前进程生效，不写入 git）
    - 固定格式：`{"dev-1":"<random>"}`；同时设置 `WAYFARER_JWT_KID_CURRENT=dev-1`
- Web (Next.js):
  - `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN`（Mapbox token；为空则地图页显示“缺少 token”提示，并禁用地图渲染）
- Android:
  - `WAYFARER_AMAP_API_KEY`（高德 Key；为空则 App 显示“缺少 Key”提示，地图组件不初始化）

### Phase 2 Gate（实现阶段的硬前置条件）

> Phase 2（Tasks 9-25）开始前，必须先完成 Phase 1（Tasks 1-8）并通过以下自动校验。

```powershell
# Spec deliverable must exist and contain core contracts
powershell -NoProfile -Command "Test-Path '.sisyphus/deliverables/plan-supplement.md'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## API 规范'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '/v1/tracks/query'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '/v1/tracks/edits'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Room Schema & Sync'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'WGS84'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'GCJ-02'"
```

- [x] 9. 仓库骨架与开发工具链

  **What to do**:
  - 创建目录：`backend/`、`web/`、`android/`、`scripts/`、`docs/`
  - 后端使用 `uv` 管理依赖与虚拟环境
  - 增加 `run.bat` / `stop.bat`：本地一键启动/停止（不依赖 Docker）
  - **定稿 `run.bat` / `stop.bat` 行为口径（必须可复现；默认“无 Docker”本地开发）**：
    - `run.bat`（Design Default）：
      - 后端：`uv sync`（或等价）→ `uv run ...` 启动 FastAPI（8000）
      - Web：`npm install`（首次）→ `npm run dev`（3000）
      - readiness：轮询 `http://localhost:8000/healthz` 直到 200
      - 数据库：默认 sqlite（本地文件）；若设置 `WAYFARER_DB_URL` 则切到本机 Postgres+PostGIS
      - 后台任务：开发默认 `WAYFARER_CELERY_EAGER=1`，避免依赖本机 redis
      - 行为约定：run.bat 负责“启动后台进程 + 等待 ready”，完成后应退出（不常驻）
    - `stop.bat`（Design Default）：
      - 停止 backend/web 进程（必须可复现，禁止误杀其他进程）：
        - run.bat 用 PowerShell `Start-Process` 启动，并把 pid 写入固定位置：`.sisyphus/run/backend.pid`、`.sisyphus/run/web.pid`
        - stop.bat 读取 pid 并 `Stop-Process -Id -Force` 精准关闭；成功后清理 pid 文件
        - 若存在子进程：必须终止进程树（避免 `npm run dev` 残留）
        - 端口释放校验（自动）：确保 8000/3000 不再监听

      - **唯一实现口径（Windows）**：
        - 终止进程树：`taskkill /PID <pid> /T /F`
        - 端口检查（PowerShell）：`Test-NetConnection localhost -Port 8000` 与 `-Port 3000` 必须为 False

  - **防止密钥误提交（必须）**：
    - `.gitignore` 必须忽略：`.sisyphus/run/`
    - 特别是：`.sisyphus/run/jwt-signing-keys.json`

  - `docker-compose.yml`（可选，非本地前置）：
    - 仅用于“有 Docker 的环境 / CI / 其他开发者”，本机无 Docker 时不影响开工
    - 最小 services 建议：db(Postgres+PostGIS)/redis（可选）
  - 增加根级 README：启动方式、环境变量说明、常用命令

  **Must NOT do**:
  - 不把任何 Key/密码写入 git（只写 `.env.example`）

  **Recommended Agent Profile**:
  - **Category**: unspecified-high
  - **Skills**: ["git-master"]

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential

  **References**:
  - `AGENTS.md:1` - Python 项目使用 uv + run.bat/stop.bat 要求
  - `plan.md:14` - 总体技术栈

  **Acceptance Criteria**:
  - [ ] `run.bat` 存在且可启动：启动 backend/web 后，等待 healthz=200 并退出码 0
  - [ ] `stop.bat` 存在且可停止：停止 backend/web（含子进程）并退出码 0；重复执行也应返回 0（幂等）
  - [ ] `.env.example` 存在且不含真实密钥
  - [ ] （若提供 docker-compose.yml）`docker compose config` 退出码 0
  - [ ] `.env.example` 至少包含这些键名（可为空值）：
    - `WAYFARER_JWT_SIGNING_KEYS_JSON`
    - `WAYFARER_JWT_KID_CURRENT`
    - `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN`
    - `WAYFARER_AMAP_API_KEY`

  - [ ] `.sisyphus/run/` 被 git 忽略（防止密钥误提交）：
    - `git check-ignore -q .sisyphus/run/jwt-signing-keys.json` 退出码 0

  **Commit**: YES
  - Message: `chore: 初始化项目骨架与脚本`

- [x] 10. 后端：FastAPI 基础工程（uv + settings + health）

  **What to do**:
  - 在 `backend/` 创建 FastAPI 应用骨架（app/main.py + 路由拆分）
  - ORM 模式定稿：SQLAlchemy 2.0 **Async Mode**（与 `plan.md:23` 一致）
  - 环境配置（必须定稿，适配“本地无 Docker + 仅有局域网 PG”）：
    - 默认：SQLite（本地文件），用作快速开发/单元测试
    - 可选：通过 `WAYFARER_DB_URL` 连接局域网 PostgreSQL（推荐用于较真实联调）
    - PostGIS：若目标 PG 支持/可安装 PostGIS，则启用空间列；否则走 Task 11 的“无 PostGIS 降级策略”
  - 增加 health endpoints：`GET /healthz`、`GET /readyz`

  **Recommended Agent Profile**:
  - **Category**: unspecified-high
  - **Skills**: ["git-master"]

  **Acceptance Criteria**:
  - [ ] `uv run python -c "import fastapi"` 退出码 0
  - [ ] `uv run python -c "import app"` 退出码 0
  - [ ] `powershell -NoProfile -Command "(Invoke-WebRequest -UseBasicParsing http://localhost:8000/healthz).StatusCode"` 输出 200

  **Commit**: YES
  - Message: `feat(backend): 初始化 FastAPI 服务与健康检查`

- [x] 11. 后端：数据库模型与迁移（PostGIS + Alembic）

  **What to do**:
  - 建立 SQLAlchemy 2.0 模型：users / track_points / life_events / weather_cache / refresh_tokens / export_jobs
  - ORM 模式：与 Task 10 一致使用 Async（但 Alembic 迁移可使用同步连接或独立配置，允许实现阶段选择最稳方案）
  - track_points 必须包含：geom(POINT,4326=WGS84) + client_point_id + UNIQUE(user_id, client_point_id)
  - 坐标双存（WGS84+GCJ-02）：增加数值列（例如 gcj02_latitude/gcj02_longitude）并在补充设计文档中定稿字段名与来源
  - Alembic 初始化与迁移脚本

  - PostGIS 说明（必须在补充设计文档中用一句话解释，避免团队误解）：
    - PostGIS 是 PostgreSQL 的地理空间扩展（extension），提供 `GEOMETRY` 类型与空间函数/索引（例如 POINT、ST_DWithin 等）。
  - 数据库分叉支持策略（必须写清，避免迁移/测试卡死）：
    - SQLite（默认开发/单测）：不启用 geometry 类型；使用 lat/lon（以及可选 geom_wkt TEXT）验证业务逻辑
    - PostgreSQL（局域网/生产）：
      - 若可用 PostGIS：启用 `geom GEOMETRY(POINT,4326)` + 空间索引
      - 若不可用 PostGIS：暂存 lat/lon +（可选）geom_wkt TEXT，并在计划中保留后续迁移到 PostGIS 的 Schema Delta

  - PostGIS 可用性检测（必须写清，避免执行者拍脑袋）：
    - 连接 PostgreSQL 后执行：`SELECT extname FROM pg_extension WHERE extname='postgis';`
    - 若返回 1 行：视为 PostGIS 可用；否则走 non-PostGIS schema
    - 若你拥有权限且希望启用：执行 `CREATE EXTENSION IF NOT EXISTS postgis;`（仅在允许的环境执行）

  - Alembic 迁移组织（必须写清，避免一套迁移跑两种库直接失败）：
    - 方案 A（推荐，最少分叉）：基础迁移只创建 lat/lon + gcj02_* 等通用列；PostGIS geom 作为 **可选增量迁移**（仅当检测到 postgis 时才执行）
    - 方案 B（可选）：两套迁移链（sqlite 与 postgres），由环境变量选择执行哪条链

  - PostGIS geometry 在 Python/SQLAlchemy 层的落地策略（必须钉死，避免执行者分叉试错）：
    - **Design Default：不引入 geoalchemy2（避免新增依赖/复杂类型映射）**
    - 数据模型侧：ORM 以 lat/lon（含 WGS84/GCJ-02）作为主字段；`geom` 仅作为数据库列存在（可不映射为 ORM 字段）
    - 写入策略（Design Default，唯一口径）：
      - PostgreSQL + PostGIS 下：在 INSERT/UPDATE 时用 SQL 表达式写入 `geom = ST_SetSRID(ST_MakePoint(lon_wgs84, lat_wgs84), 4326)`
      - 不使用 DB 触发器/生成列（避免权限差异与迁移分叉）；如未来需要再单独加增量迁移
    - 查询/导出：默认使用 lat/lon；如需空间查询再使用 raw SQL（不要求 ORM Geometry 类型）

  **References**:
  - `plan.md:45` - DB schema 基础
  - `.sisyphus/deliverables/plan-supplement.md:1` - 补充设计规范（生成后作为实现依据）

  **Acceptance Criteria**:
  - [ ] `uv run alembic upgrade head` 在 SQLite 环境退出码 0（采用 sqlite 降级策略）
  - [ ] （可选）若提供 `WAYFARER_DB_URL` 指向 PostgreSQL：`uv run alembic upgrade head` 在 PostgreSQL 环境也能通过（按是否启用 PostGIS 走不同迁移分支/降级策略）

  **Commit**: YES
  - Message: `feat(backend): 建立核心数据模型与迁移`

- [x] 12. 后端：认证系统（JWT + refresh rotation + CSRF）

  **What to do**:
  - 实现：/v1/auth/register /v1/auth/login /v1/auth/refresh /v1/users/me
  - password_hash: argon2id；JWT: HS256 + kid；refresh_tokens 表与 rotation/reuse detection
  - Web refresh 使用 cookie + CSRF（double-submit + X-CSRF-Token）；Android refresh 走 Authorization+body

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（Auth endpoints 的请求/响应 schema）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Global Error Codes`（AUTH_* 错误码必须一致）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Export Job Lifecycle`（refresh token/cookie/CSRF 约束若影响下载授权，需一致）
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `Env Var Contract`（JWT keys/kid 的配置口径）

  **Acceptance Criteria**:
  - [ ] `uv run pytest -k auth` 覆盖：注册/登录/刷新/复用检测
  - [ ] 必须包含以下可观察断言（避免“跑了 pytest 但没测到 CSRF/rotation 生效”）：
    - refresh 缺少 `X-CSRF-Token` → 失败（HTTP + 业务码）
    - CSRF header 与 csrf cookie 不一致 → 失败
    - refresh rotation：旧 refresh 立即失效
    - reuse detection：复用已撤销 refresh → 触发 family revoke（并返回 `AUTH_REFRESH_REUSED` 或等价业务码）
    - Web cookie 属性可验证（至少检查 Set-Cookie 包含 httpOnly/SameSite/Secure 预期）
  - [ ] `powershell -NoProfile -Command "$base='http://localhost:8000'; $b=@{email='e2e@test.com';password='password123!'}|ConvertTo-Json; (Invoke-RestMethod -Method Post -Uri ($base+'/v1/auth/login') -ContentType 'application/json' -Body $b).access_token"` 返回非空

  **Commit**: YES
  - Message: `feat(backend): 实现 JWT 认证与 refresh rotation`

- [x] 13. 后端：轨迹上传 + 查询/编辑（batch/query/edits）

  **What to do**:
  - 校验每条点位：client_point_id/recorded_at/latitude/longitude/accuracy
  - 写入 PostGIS geom（WGS84）；如 payload 同时提供 GCJ-02 坐标则一并落库（用于 AMap 对齐）
  - ON CONFLICT (user_id, client_point_id) DO NOTHING
  - 返回：accepted_ids[] / rejected[]（含 client_point_id + reason_code + message）

   - 实现轨迹查询接口（供 Web 展示与导出前预览使用）：
     - `GET /v1/tracks/query`（按 start/end 查询，默认过滤掉“被编辑删除”的点）
   - 实现最小轨迹编辑接口（与补充设计文档的契约一致）：
     - `POST /v1/tracks/edits`（MVP: DELETE_RANGE）
     - `GET /v1/tracks/edits`
   - `DELETE /v1/tracks/edits/{id}`

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（tracks/batch + tracks/query + tracks/edits 契约与字段名）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Room Schema & Sync`（client_point_id、回执语义、幂等与双坐标口径）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Global Error Codes`（TRACK_BATCH_*、EDIT_* 等业务码）

  **Acceptance Criteria**:
  - [ ] `uv run pytest -k tracks` 通过（覆盖 batch/query/edits 的最小用例）
  - [ ] 重复上传同一 client_point_id 不产生重复记录

  **Commit**: YES
  - Message: `feat(backend): 支持轨迹批量上传与幂等回执`

- [x] 14. 后端：Celery/Redis + Anti-Cheat 审计任务

  **What to do**:
  - 开发默认：`WAYFARER_CELERY_EAGER=1`（不依赖本机 redis/worker），任务在同进程同步执行
  - 若需要异步任务链路（可选）：
    - 使用局域网/本机 redis（提供 `REDIS_URL` 或等价配置）
    - 启动 celery worker（Windows 下以独立进程启动，并纳入 stop.bat 的 pid 管理策略）
  - 实现 audit_track_segment：速度/步幅/精度/轨迹跳变规则，标记 is_dirty

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Anti-Cheat`（阈值表/规则/边界用例必须对齐实现）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（如需要暴露审计状态字段/查询，必须按 spec）

  **Acceptance Criteria**:
  - [ ] 无 Docker 验收口径（Design Default）：
    - 开发环境默认 `WAYFARER_CELERY_EAGER=1`，审计/后台任务可在同进程同步执行
    - `uv run pytest -k anti_cheat` 通过
  - [ ] 若环境具备 Redis/Celery worker（可选）：补充启动方式与额外集成测试

  **Commit**: YES
  - Message: `feat(backend): 集成 Celery 并实现反作弊审计`

- [x] 15. 后端：导出系统（同步 GET + 异步 Job）

  **What to do**:
  - 保留 GET /v1/export（小数据 + 无天气流式直出）
  - include_weather=true 或点数>50K：返回 202 + job_id
  - 实现 POST /v1/export + job status/download/cancel；artifact_dir=./data/exports
  - 支持 CSV/GPX/GeoJSON/KML

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Export Job Lifecycle`（状态迁移/保留策略/artifact_dir 必须一致）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（/v1/export* 的请求/响应 schema、202 兼容策略）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Global Error Codes`（EXPORT_*、RATE_LIMITED 等业务码）

  **Acceptance Criteria**:
  - [ ] `uv run pytest -k export` 通过
  - [ ] 生成 job 后可下载文件（非空）

  **Commit**: YES
  - Message: `feat(backend): 实现导出 Job 与多格式导出`

- [x] 16. 后端：天气回溯与缓存（Open-Meteo）

  **What to do**:
  - weather_cache: (geohash_5, hour_time) 唯一
  - 限流 600/min；失败允许无天气导出（PARTIAL）

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Weather Enrichment & Cache`（geohash_precision=5、缓存键/TTL、降级策略必须一致）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Export Job Lifecycle`（PARTIAL 语义与产物标记）

  **Acceptance Criteria**:
  - [ ] `uv run pytest -k weather` 通过

  **Commit**: YES
  - Message: `feat(backend): 天气回溯缓存与导出填充`

- [x] 17. 后端：LifeEvent 自动标记

  **What to do**:
  - 实现停留点检测（distance=200m,time=5min）并生成 life_events
  - 提供 CRUD API 供 Web 编辑

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## LifeEvent`（stay point/home/work/commute 的默认参数与算法边界）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（/v1/life-events CRUD schema 与错误码）

  **Acceptance Criteria**:
  - [ ] `uv run pytest -k life_event` 通过

  **Commit**: YES
  - Message: `feat(backend): LifeEvent 自动识别与接口`

- [x] 18. Web：Next.js 14 基础工程 + 认证

  **What to do**:
  - 建立 Next.js 14 App Router 项目，深色模式优先
  - UI 组件体系定稿（避免页面/测试分叉）：默认使用 `shadcn/ui` + Tailwind
    - Playwright 选择器策略：关键元素必须提供 `data-testid`（避免受 UI 库 DOM 结构影响）
  - Mapbox/地图渲染的测试策略必须钉死（避免 CI/本机被 token/外网阻塞）：
    - 默认：UI 必须同时渲染“轨迹数据层”（列表/计数/时间轴）与“地图层（可选）”
    - 无 `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN` 时：
      - 地图层显示占位组件（不触发任何外网请求）
      - Playwright/E2E 断言以数据层 `data-testid` 为准（例如点数、区间选择、导出状态），不依赖 Mapbox 真渲染

   - Playwright mock 机制定稿（唯一口径，避免执行者试错）：
     - 使用 Playwright 的 `page.route` 拦截后端 API 请求（`**/v1/**`），返回固定 fixture（不引入额外 mock 库）
     - 禁止任何外网请求（Mapbox 瓦片/字体等）；测试时必须保证 token 为空或使用 map-disabled
  - 登录/登出/刷新 token（对接后端）
  - Mapbox GL JS 轨迹展示页骨架（无 token 时提示）

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（auth + tracks/query + export 的前端对接契约）
  - `.sisyphus/plans/wayfarer-plan-supplement.md` - Section: `Env Var Contract`（NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN 的降级行为）

  **Acceptance Criteria**:
  - [ ] `npm run build` 退出码 0
  - [ ] `npx playwright test` 基础用例通过（mock 后端）

  **Commit**: YES
  - Message: `feat(web): 初始化 Next.js 前端与登录流程`

- [x] 19. Web：时间轴编辑器 + 轨迹编辑

  **What to do**:
  - Timeline UI（缩放/选择区间）
  - 漂移修复（与后端契约对齐，避免实现阶段临时发明接口）：
    - 删除：调用 `POST /v1/tracks/edits`（MVP: DELETE_RANGE），删除后重新 `GET /v1/tracks/query` 刷新
    - 平滑：Design Default 先做前端可视化平滑（不落库）；如后续要服务端化，补充文档需先扩展 edits 类型

  **Acceptance Criteria**:
  - [ ] Playwright 用例：选择区间 → 删除 → 地图点数减少

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（tracks/edits 的 DELETE_RANGE 契约）

  **Commit**: YES
  - Message: `feat(web): 时间轴编辑与轨迹修复工具`

- [x] 20. Web：导出向导 + 进度展示

  **What to do**:
  - Export Wizard（include_weather 勾选）
  - Job 轮询 status + 下载

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Export Job Lifecycle`（状态轮询与下载条件）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（/v1/export 与 /v1/export/{job_id}/download）

  **Acceptance Criteria**:
  - [ ] Playwright 用例：创建导出任务 → 等待 SUCCEEDED → 下载成功

  **Commit**: YES
  - Message: `feat(web): 导出向导与任务进度`

- [x] 21. Android：工程初始化（Compose + 高德地图）

  **What to do**:
  - 新建 Android 项目（Compose + MVVM+Clean）
  - 集成高德地图 SDK（Key 注入方式必须写死，避免执行者猜测）：
    - Key 来源优先级（Design Default）：
      1) Gradle property：`-PWAYFARER_AMAP_API_KEY=...`
      2) 环境变量：`WAYFARER_AMAP_API_KEY`
      3) `local.properties`（仅本地，不提交）
    - 通过 `manifestPlaceholders` 注入 `AMAP_API_KEY` 到 `AndroidManifest.xml`（meta-data 使用 placeholder）
    - 无 Key 时：构建/单测必须仍可通过；运行时显示“缺少 Key”并跳过地图初始化

  **Acceptance Criteria**:
  - [x] `gradlew.bat test` 退出码 0
  - [x] 未提供 Key 时 `gradlew.bat assembleDebug` 仍可通过（避免 CI/开发环境阻塞）

  **Commit**: YES
  - Message: `feat(android): 初始化 Compose 工程并接入高德地图骨架`

- [x] 22. Android：前台服务 + 智能采样 FSM + 轨迹采集

  **What to do**:
  - ForegroundService 采集定位
  - FSM 状态机（Activity Recognition 优先，GPS-only 降级）
  - 写入 Room（含 client_point_id；WGS84+GCJ-02 双坐标；记录 coord_source）

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Android FSM`（状态/采样参数/降级逻辑必须一致）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Room Schema & Sync`（本地表字段名与 sync_status 语义）

  **Acceptance Criteria**:
  - [x] `gradlew.bat test` 通过（FSM 单元测试）

  **Commit**: YES
  - Message: `feat(android): 实现前台采集与智能采样 FSM`

- [x] 23. Android：步数与 Root 增强（开发者模式）

  **What to do**:
  - 采集 step_count/step_delta
  - 开发者模式入口（7 次点击）
  - Root 白名单（dumpsys deviceidle whitelist）
  - Raw Sensor Monitor（/dev/input/event*）

  **Acceptance Criteria**:
  - [x] `gradlew.bat test` 通过（开发者模式逻辑测试）

  **Commit**: YES
  - Message: `feat(android): 步数采集与 Root 开发者增强`

- [x] 24. Android：同步引擎（断网重试 + 部分成功）

  **What to do**:
  - 按批上传（100条），gzip json
  - 处理 accepted_ids/rejected 回执，按 client_point_id 标记 ACKED/FAILED

  **References**:
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## API 规范`（tracks/batch 回执语义与 reason_code）
  - `.sisyphus/deliverables/plan-supplement.md` - Section: `## Room Schema & Sync`（sync_queue/retry_policy/部分成功处理）

  **Acceptance Criteria**:
  - [x] `gradlew.bat test` 通过（回执处理单测）

  **Commit**: YES
  - Message: `feat(android): 实现同步引擎与幂等回执处理`

- [x] 25. 端到端联调 + 一键启动

  **What to do**:
  - run.bat/stop.bat 打通（本地无 Docker）
  - E2E（保持 Automated-only 的替代验证口径）：
    - 用 API 协议模拟“Android 上传”（register/login → tracks/batch）
    - Web 展示：Playwright 自动化验证页面能拉到点位并渲染
    - 导出：调用 export 接口并验证产物非空（无天气/含天气降级）
  - 可选（不计入验收）：真实 Android 设备联调 smoke（如有设备/Key，可做）

  **Acceptance Criteria**:
  - [x] `run.bat` 可启动全部服务（backend + web）
  - [x] `stop.bat` 可停止全部服务
  - [x] backend 测试 + web 测试全部通过
  - [x] 端到端自动化验证命令（示例口径，执行者可脚本化但不得改语义）：
    - 获取 token（PowerShell）：注册/登录拿到 access_token
    - 用 token 上传 2 个点：`POST /v1/tracks/batch` 返回 accepted_ids 数量为 2
    - 查询点位：`GET /v1/tracks/query` 返回点数 >= 2
    - Playwright（禁止依赖真实 Mapbox 渲染/外网）：
      - 断言页面出现 `data-testid="tracks-count"` 且数值 >= 2
      - 断言时间轴组件存在（例如 `data-testid="timeline"`）
      - 若无 Mapbox token：断言地图占位存在（例如 `data-testid="map-disabled"`）

    ```powershell
    # End-to-end smoke (automated) via API protocol
    $base = "http://localhost:8000"
    $email = "e2e@test.com"
    $password = "password123!"

    # Register (idempotent-ish: ignore failure if already exists)
    try {
      $regBody = @{ email = $email; password = $password; username = "e2e" } | ConvertTo-Json
      Invoke-RestMethod -Method Post -Uri ("$base/v1/auth/register") -ContentType "application/json" -Body $regBody | Out-Null
    } catch {}

    # Login
    $loginBody = @{ email = $email; password = $password } | ConvertTo-Json
    $token = (Invoke-RestMethod -Method Post -Uri ("$base/v1/auth/login") -ContentType "application/json" -Body $loginBody).access_token

    # Upload 2 points (WGS84 required; GCJ-02 optional)
    $batch = @'
    {
      "items": [
        {
          "client_point_id": "00000000-0000-0000-0000-000000000001",
          "recorded_at": "2026-01-01T00:00:00Z",
          "latitude": 31.2304,
          "longitude": 121.4737,
          "accuracy": 10
        },
        {
          "client_point_id": "00000000-0000-0000-0000-000000000002",
          "recorded_at": "2026-01-01T00:00:05Z",
          "latitude": 31.2305,
          "longitude": 121.4738,
          "accuracy": 10
        }
      ]
    }
    '@

    $headers = @{ Authorization = "Bearer $token" }
    $resp = Invoke-RestMethod -Method Post -Uri ("$base/v1/tracks/batch") -ContentType "application/json" -Headers $headers -Body $batch
    if ($resp.accepted_ids.Count -lt 2) { throw "expected accepted_ids >= 2" }

    $q = Invoke-RestMethod -Method Get -Uri ("$base/v1/tracks/query?start=2026-01-01T00:00:00Z&end=2026-01-01T00:01:00Z") -Headers $headers
    if ($q.items.Count -lt 2) { throw "expected query items >= 2" }
    ```

  **Commit**: YES
  - Message: `feat: 端到端联调与一键启动`

## Decisions Resolved

- 认证机制：JWT access/refresh
- Android 最低版本：API 33+
- Android 地图 SDK：高德地图 SDK
- 导出格式：CSV / GPX / GeoJSON / KML
- 天气不可用降级：允许无天气导出
- Activity Recognition 权限拒绝：GPS-only 推断
- Room Schema：完全镜像服务端（含 weather_snapshot）
- 多设备冲突：recorded_at + geom_hash 弱去重（仅提示/合并用途，不作为严格唯一约束）
- 坐标系策略：WGS84 + GCJ-02 双坐标存储
- Root 增强：纳入（开发者模式 + 防睡眠白名单 + /dev/input 监控）
- 地图 Key：占位 + 环境变量注入 + 无 Key 提示/降级（不写入 git）
- Git 提交：按阶段自动原子提交（Conventional Commits，中文优先）
- Web UI：shadcn/ui + Tailwind（默认选型）

---

## Commit Strategy

| After Task | Message | Verification |
|------------|---------|--------------|
| 9 | `chore: 初始化项目骨架与脚本` | `run.bat` / `stop.bat` 可运行 |
| 10 | `feat(backend): 初始化 FastAPI 服务与健康检查` | healthz HTTP 200 |
| 11 | `feat(backend): 建立核心数据模型与迁移` | alembic upgrade head |
| 12 | `feat(backend): 实现 JWT 认证与 refresh rotation` | pytest auth |
| 13 | `feat(backend): 支持轨迹批量上传与幂等回执` | pytest tracks |
| 14 | `feat(backend): 集成 Celery 并实现反作弊审计` | worker up + pytest |
| 15 | `feat(backend): 实现导出 Job 与多格式导出` | pytest export |
| 16 | `feat(backend): 天气回溯缓存与导出填充` | pytest weather |
| 17 | `feat(backend): LifeEvent 自动识别与接口` | pytest life_event |
| 18 | `feat(web): 初始化 Next.js 前端与登录流程` | build + playwright |
| 19 | `feat(web): 时间轴编辑与轨迹修复工具` | playwright |
| 20 | `feat(web): 导出向导与任务进度` | playwright |
| 21 | `feat(android): 初始化 Compose 工程并接入高德地图骨架` | gradlew.bat test |
| 22 | `feat(android): 实现前台采集与智能采样 FSM` | gradlew.bat test |
| 23 | `feat(android): 步数采集与 Root 开发者增强` | gradlew.bat test |
| 24 | `feat(android): 实现同步引擎与幂等回执处理` | gradlew.bat test |
| 25 | `feat: 端到端联调与一键启动` | backend+web tests pass |

Commit: YES (按阶段原子提交)

---

## Success Criteria

### Verification Commands
```powershell
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## Android FSM'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern '## API 规范'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'stateDiagram-v2'"
powershell -NoProfile -Command "Select-String -Path '.sisyphus/deliverables/plan-supplement.md' -Pattern 'curl -X'"
```

### Final Checklist
- [x] 所有缺口已覆盖
- [x] 参数与阈值均为具体数值
- [x] 无人工验证步骤
