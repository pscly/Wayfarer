# Wayfarer 研究摘录（用于补充设计的本地依据）

> 目的：把关键研究结论落到仓库内的可引用文件里，供 `plan-supplement.md` 使用。
> 注意：本文件摘录自本次规划会话中的研究输出（librarian 背景任务），用于“设计默认值/基线”的可追溯来源。

## Android FSM（智能采样）

### 状态集合
- IDLE
- STATIONARY
- WALKING
- RUNNING
- CYCLING
- DRIVING
- UNKNOWN

### 默认采样参数（Design Default）

| State | interval | minInterval | priority | minDistance |
|------|----------|-------------|----------|-------------|
| IDLE | N/A | N/A | PASSIVE | N/A |
| STATIONARY | 120s | 60s | LOW_POWER | 50m |
| WALKING | 5s | 3s | BALANCED_POWER | 5m |
| RUNNING | 3s | 2s | HIGH_ACCURACY | 3m |
| CYCLING | 3s | 2s | HIGH_ACCURACY | 5m |
| DRIVING | 5s | 3s | HIGH_ACCURACY | 20m |
| UNKNOWN | 10s | 5s | BALANCED_POWER | 10m |

### 默认转换阈值（Design Default）
- STILL → STATIONARY: speed <0.5 m/s for 120s
- WALKING → RUNNING: speed >2.5 m/s for 10s OR activity=RUNNING
- RUNNING → WALKING: speed <2.0 m/s for 30s
- ANY → DRIVING: speed >8.3 m/s for 30s OR activity=IN_VEHICLE
- STOP DETECT: speed <0.5 m/s for 120s
- debounce: 3–5s

### 权限/能力降级（Design Default）
- Activity Recognition 拒绝：GPS-only 推断（仅基于 speed + 最近 N 秒的速度变化）
- GPS 不可用：进入 UNKNOWN，使用较低频率（10s/10m）等待恢复
- 离线：继续本地记录；网络恢复后由 Sync Engine 批量上传

---

## 反作弊阈值（速度/步幅/GPS 欺骗）

### 速度阈值（Design Default）
- walking: 0.5–2.5 m/s
- running: 2.5–6.5 m/s
- vehicle: >8.3 m/s (30 km/h)

### 步幅阈值（Design Default）
- walking: 0.4–1.0 m/step
- running: 0.8–2.0 m/step
- absolute bounds: 0.3–2.5 m/step

### 规则（Design Default）
- steps_no_distance: 100 steps with <10m distance → suspicious
- distance_no_steps: 100m with <50 steps → suspicious
- impossible_step_rate: >4 steps/sec → cheating
- gps_accuracy_suspicious: avg <1m & variance too low → suspicious
- teleportation: impossible speed jump between consecutive points

---

## LifeEvent（停留点/家/办公/通勤）

### Stay Point Detection（Design Default）
- distance_threshold: 200m
- time_threshold: 5 min

### Home Detection（Design Default）
- night hours: 21:00–07:00
- min night stay: 2h
- min nights ratio: 50%
- cluster radius: 100m

### Work Detection（Design Default）
- weekday hours: 09:00–18:00
- min visit duration: 2h
- min distance from home: 500m
- cluster radius: 150m

### Commute（Design Default）
- proximity threshold: 300m
- morning window: 07:00–10:00 (Home→Work)
- evening window: 17:00–20:00 (Work→Home)
- min occurrences: 3

---

## Open-Meteo（历史天气回溯）

### 设计输入（Design Default）
- Rate Limit (free tier): 600/min, 5,000/hour, 10,000/day
- Endpoint: archive historical
- 支持多坐标批量（comma-separated lat/lon）

### 缓存策略（Design Default）
- geohash precision: 5 (~5km)
- key: (geohash_5, hour_time)
- historical (>5 days): immutable, TTL = infinite
- recent (<=5 days): TTL = 1h (或改用 forecast)

### 失败策略（Design Default）
- 429: exponential backoff
- API 不可用：允许无天气导出（weather_snapshot 为空）

---

## Mapbox（Web 轨迹渲染性能）

### 规模分档（Design Default）
- <50K: 直接 GeoJSON source
- 100K–500K: 简化 + 聚类
- 1M+: geojson-vt 或 vector tiles

### 常用技术（Design Default）
- Douglas-Peucker / tolerance 简化
- Supercluster 聚类
- viewport culling（按视口加载与卸载）

---

## Auth（JWT）

### JWT 默认参数（Design Default）
- access_ttl: 15m
- refresh_ttl: 30d
- refresh_rotation: enabled
- refresh_reuse_detection: enabled

### JWT 签名与密钥（Design Default）
- signing_alg: HS256
- header: include kid
- key_rotation: 支持多 kid 验签，签发使用 current kid

### 密码哈希与策略（Design Default）
- password_hash: argon2id
- password_policy: min_length=12

### Token 存储（Design Default）
- Android: Keystore + EncryptedSharedPreferences（仅存 refresh 或 session material；access 可内存）
- Web: httpOnly cookie 存 refresh；access 用内存或短期 cookie

---

## Room/Sync（本地存储与同步）

### 本地表（Design Default）
- track_points_local（服务端 track_points 的镜像字段，含 weather_snapshot）
- life_events_local（服务端 life_events 的镜像字段）
- sync_queue（待同步队列，记录批次与重试状态）

### sync_status 枚举（Design Default）
- 0=NEW（本地新写入）
- 1=QUEUED（进入上传队列）
- 2=UPLOADING（上传中）
- 3=ACKED（服务端确认）
- 4=FAILED（失败，等待重试）

### sync_queue 字段（Design Default）
- id (UUID)
- user_id (UUID)
- created_at (TIMESTAMPTZ)
- batch_start_time (TIMESTAMPTZ)
- batch_end_time (TIMESTAMPTZ)
- item_count (INT)
- payload_sha256 (TEXT)
- status (SMALLINT) 0=READY,1=SENDING,2=ACKED,3=FAILED
- attempt_count (INT)
- next_retry_at (TIMESTAMPTZ)
- last_error (TEXT)

### 部分成功语义（Design Default）
- 服务端 batch API 返回：accepted_ids[] + rejected[]（按 client_point_id）
- 客户端按 client_point_id 标记 ACKED/FAILED，FAILED 进入重试
- 幂等：client_point_id 唯一；服务端 ON CONFLICT DO NOTHING

### 多设备冲突（Design Default）
- recorded_at + geom hash 去重：同一 recorded_at 且坐标相同视为重复

---

## API 规范（端点清单与错误码）

### 端点清单（Design Default）
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

### 错误码结构（Design Default）
- HTTP status + JSON body:
  - code: string（稳定业务码，如 AUTH_INVALID_CREDENTIALS）
  - message: string（面向用户/开发者）
  - details: object|null（字段级错误、调试信息）
  - trace_id: string|null（便于排查）

---

## Web Dev Cookie/CORS（本地 http://localhost）

### Design Default
- refresh cookie name: wf_refresh
- csrf cookie name: wf_csrf
- dev_cookie_secure: false
- cors_allow_origin: http://localhost:3000
- cors_allow_credentials: true
- frontend_credentials: include


---

## Export Job（导出任务生命周期）

### Job 状态（Design Default）
- CREATED
- RUNNING
- PARTIAL（允许无天气导出时的降级结果）
- SUCCEEDED
- FAILED
- CANCELED

### 保留策略（Design Default）
- job 元数据保留 7 天
- 产物文件（CSV/GPX/GeoJSON/KML）保留 24 小时（可配置）

### 产物存储（Design Default）
- artifact_dir: ./data/exports (docker volume)
- file_naming: {user_id}/{job_id}.{ext}
- max_concurrent_exports: 2 per user
- max_export_points: 5_000_000 (超过则强制异步并分片/提示)

---

## Track Point Identity（幂等与回执匹配）

### 客户端点位唯一标识（Design Default）
- client_point_id: UUID（客户端生成，写入本地表；上传时随 payload 发送）
- 去重键：
  - 幂等（同设备重试）：(user_id, client_point_id)
  - 多设备重复：recorded_at + geom_hash（作为“重复提示/弱去重”）

### geom_hash 口径（Design Default）
- 输入：WGS84 lat/lon
- 量化：lat_round=round(lat, 5), lon_round=round(lon, 5)  # ~1m 量级
- 表达：f"{lat_round},{lon_round}"
- 哈希：sha256(hex)（或直接使用该字符串作为 key）

### 服务端存储（Design Default）
- track_points 增加字段：client_point_id UUID NOT NULL
- 约束：UNIQUE(user_id, client_point_id)
- batch 回执：返回 accepted_ids[] / rejected[]（含 client_point_id + reason）

---

## Geom Mapping（Server ↔ API ↔ Room）

### 服务端（PostGIS）
- track_points.geom: GEOMETRY(POINT, 4326)

### API 表示（Design Default）
- 字段：latitude(float), longitude(float)
- 坐标系：WGS84 (EPSG:4326)
- 约束：
  - latitude in [-90, 90]
  - longitude in [-180, 180]

### Room 表示（Design Default）
- Room 需要同时存储 WGS84 + GCJ-02 两套坐标（Confirmed）：
  - 建议列：`lat_wgs84`, `lon_wgs84`, `lat_gcj02`, `lon_gcj02`（REAL）
  - `coord_source`（TEXT/SMALLINT）：GPS / AMap / Unknown
- 写入服务端时：以 WGS84 生成 PostGIS POINT（若 PostGIS 可用）；同时可把 GCJ-02 数值列一并上送/落库用于 AMap 渲染一致性。

---

## 坐标系策略（WGS84 / GCJ-02）

> 说明：本项目同时涉及 Web(Mapbox) 与 Android(高德地图)。为避免“偏移/对不齐”的长期坑，规划决策为 **WGS84 + GCJ-02 双坐标存储**。

### 设计决策（Confirmed）
- 存储/API/导出以 **WGS84(EPSG:4326)** 为基准（满足 PostGIS geom 与导出/跨平台互操作）。
- 同时存储 **GCJ-02** 数值坐标（用于 Android AMap 渲染与与国内坐标系生态对齐）。

### API 约定（Design Default）
- 请求必填：`latitude`, `longitude`（WGS84）
- 请求可选：`gcj02_latitude`, `gcj02_longitude`, `coord_source`

### Android 约定（Design Default）
- 本地落库：WGS84 + GCJ-02 双坐标 + `coord_source`。
- 上传：随 batch payload 一并上送双坐标（避免服务端做 GCJ→WGS 的近似逆转换）。


---

## Export API Compatibility（与 plan.md 同步导出描述对齐）

### 规范优先级（Design Default）
- 目标：保留 plan.md 描述的 GET 导出体验，但在 include_weather=true 或大数据量时转为异步 Job。

### 行为（Design Default）
- GET /v1/export?start&end&format&include_weather
  - include_weather=false 且数据量 <= 50K points：StreamingResponse 直接返回文件
  - include_weather=true 或数据量 > 50K points：返回 202 + JSON(body.job_id)
- POST /v1/export：显式创建导出任务（推荐给 Web UI 使用）
- GET /v1/export/{job_id}：查询状态
- GET /v1/export/{job_id}/download：下载文件
- POST /v1/export/{job_id}/cancel：取消

### timezone 参数（Design Default）
- 参数名：timezone
- 默认值：UTC
- 合法值：IANA TZ（如 Asia/Shanghai）或 UTC
- 行为：仅影响导出文件中时间字段的格式化时区；服务端查询窗口仍使用 recorded_at(UTC)

---

## Auth Server Model（JWT Refresh 落地细节）

### refresh token 表（Design Default）
- refresh_tokens:
  - id (UUID)
  - user_id (UUID)
  - token_hash (TEXT)
  - family_id (UUID)  # rotation 家族
  - issued_at (TIMESTAMPTZ)
  - expires_at (TIMESTAMPTZ)
  - revoked_at (TIMESTAMPTZ nullable)
  - replaced_by (UUID nullable)  # 指向新 token
  - user_agent (TEXT nullable)
  - ip (TEXT nullable)
  - created_at (TIMESTAMPTZ)

### rotation / reuse detection（Design Default）
- refresh 时：
  - 旧 token 标记 revoked_at，并记录 replaced_by
  - 生成新 token（同 family_id）
- reuse detection：
  - 如果一个已 revoked 的 refresh token 再次被使用：撤销该 family_id 下全部 token，并强制重新登录

### cookie 与 CSRF（Design Default）
- refresh token 放 httpOnly+Secure cookie（SameSite=Lax 默认；如跨站需要则 SameSite=None 且 Secure）
- refresh endpoint 需 CSRF 防护：
  - 同源 + 自定义 header（如 X-CSRF-Token）+ double-submit

---

## API Error Codes（稳定业务码）

### 错误码列表（Design Default，至少 10 个）
- AUTH_INVALID_CREDENTIALS
- AUTH_TOKEN_EXPIRED
- AUTH_TOKEN_INVALID
- AUTH_REFRESH_REUSED
- AUTH_FORBIDDEN
- TRACK_BATCH_INVALID_ITEM
- TRACK_BATCH_TOO_LARGE
- EXPORT_FORMAT_UNSUPPORTED
- EXPORT_JOB_NOT_FOUND
- EXPORT_JOB_NOT_READY
- RATE_LIMITED
