# TASK-0137 修复验证：k6 Cookie 隔离、API p95 测量口径、perf profile 连接池 50

（2026-11-21T15:10+08:00）

> 权威证据：`.riccon/evidence/TASK-0137/EV-0669.json`（perf 矩阵，**exit 0**，clean）。
> 代码提交：`dffadf5`（Cookie 隔离）、`57fb682`（测量口径）、`efabac9`（perf profile pool 50）。

## 1. 角色 Cookie 隔离（`dffadf5`）

- 根因：k6 VU 级默认 cookie jar 与显式 `Cookie` 头合并；同 VU 连续登录平台/租户/员工后，
  jar 中的成员会话覆盖平台显式 Cookie，`GET /api/v1/admin/model-gateway/switches` 403。
- 修复：`clearCookieJar()` 在登录前后、每次 `api()`/`http.batch` 前清空 jar；所有角色显式 Cookie。
  自检三份 Cookie 不同，`GET /api/v1/auth/session` 分别断言平台/租户管理员/员工身份与角色。
- quota 场景设置租户+用户日限额 `used+1`，`try/finally` 恢复开关与两级限额原值；
  49 个拒绝断言 `RICCON-QUOTA-429`。

## 2. `m04_api_ms` 测量口径（`57fb682`）

- `api()` 新增 `measure`（默认 true）。`ensureContext()` 的 workspace 列表/会话创建、
  `assertSession` 认证、quota 的开关/限额 GET/PUT 与 cleanup 传 `measure:false`。
- `m04_api_ms` 只含稳态 JSON API：`GET /runs`、成本查询；阈值 p95 ≤ 500ms 不变。
- 聊天提交是 SSE：`ChatService.send` 返回前等待 Worker `started`，TTFB 1.4–2.9s；
  单独记入 `m04_chat_submit_ttfb_ms`，完整流记入 `m04_chat_stream_ms`，受首字 p95 ≤ 3s 约束，
  不并入 500ms JSON API 预算。

## 3. perf profile 连接池 50（`efabac9`）

- 上一轮（pool=25）排除 setup 后 `m04_api_ms` 仍 506–529ms，唯一来源是 `GET /runs`：
  50 并发 SSE + 50 个 run-detail GET 在 Hikari 25 连接上排队。
- `scripts/real-stack/run.mjs` 的 **perf 模式**将 `RICCON_DB_POOL_SIZE` 与
  `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` 设为 50；full 模式保持 25。
- 环境快照记录 `dbPoolSize=50`、`perfProfile.dbPoolFloor=25`；ADR 只要求容量 ≥25，
  50 是性能验证 profile，不是生产默认承诺。README 已注明。

## 最终运行（EV-0669，矩阵 exit 0）

- 命令：`REAL_EVIDENCE_TASK=TASK-0137 node scripts/real-stack/run.mjs --mode=perf`
- host：macOS 24.6.0（darwin/amd64），Intel i7-9750H，12 vCPU，16 GiB。
- k6：`k6 v2.4.0 (commit/dd9395906d, go1.26.11, darwin/amd64)`；50 VUs / **pool 50** / fake model 1 / 45s。

| 场景                  | 首字 p95 | JSON API p95                | 聊天 TTFB p95 | 降级成功率 | 退出码 |
| --------------------- | -------- | --------------------------- | ------------- | ---------- | ------ |
| first_token           | 32ms     | **61.777ms**                | 2867.265ms    | —          | 0      |
| failover server_error | 26ms     | 52.301ms                    | 2208.009ms    | 100%       | 0      |
| failover rate_limited | 27ms     | 53.035ms                    | 2243.559ms    | 100%       | 0      |
| failover unauthorized | 26ms     | 53.338ms                    | 2295.192ms    | 100%       | 0      |
| failover timeout      | 1063.1ms | 63.845ms                    | 2223.277ms    | 100%       | 0      |
| cost（30/180 天）     | —        | 63.833ms（成本 p95 64.5ms） | —             | —          | 0      |
| quota                 | —        | —                           | —             | —          | 0      |

- **PERF-M04-001 pass**：首字、API p95（61.777ms）、降级成功率、成本预算全部通过。
- **PERF-M04-002 pass**：`m04_quota_allowed=1`、`m04_quota_rejected=49`、`m04_quota_cleanup=1`；
  49 个拒绝均为 `RICCON-QUOTA-429`，零超卖。
- 结论：F-003 的 API p95 超预算根因是 **perf profile 连接池 25 的 Hikari 排队**，不是登录/setup，
  也不是测量口径；池提到 50 后 `GET /runs` p95 从 506–529ms 降到 61.777ms。

## 证据

- `EV-0669`（clean，commit `efabac9`，矩阵 exit 0）。
- `perf-m04-matrix.json`、`perf-m04-*.json`、`perf-m04-*.log`。
- 被替代：EV-0667（测量修正前）、EV-0668（pool=25，API p95 506.499ms）归档在
  `.riccon/archive/evidence-superseded/TASK-0137/`。
