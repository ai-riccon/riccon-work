# TASK-0137 修复验证：k6 角色 Cookie 隔离、测量口径与 PERF-M04-002（2026-11-21T14:30+08:00）

> 权威证据：`.riccon/evidence/TASK-0137/EV-0668.json`（perf 矩阵，exit 1 见下）。
> 代码提交：`57fb682`（测量口径）；Cookie 隔离提交：`dffadf5`。

## 修复 1：角色 Cookie 隔离（`dffadf5`）

- 根因：k6 VU 级默认 cookie jar 会与显式 `Cookie` 头合并；同一 VU 连续登录平台/租户/员工后，
  jar 中的成员会话覆盖平台显式 Cookie，`GET /api/v1/admin/model-gateway/switches` 返回 403。
- 修复：`clearCookieJar()` 在 `login()` 开始、登录 POST 前、登录完成后，以及每次 `api()`/`http.batch`
  前清空 jar；所有角色用显式 Cookie 发送。运行时自检三份 Cookie 互不相同，并用
  `GET /api/v1/auth/session` 分别断言平台/租户管理员/员工身份与角色。
- quota 场景设置租户与用户日限额均为 `used+1`（前序场景在关闭执行时仍累计用量），
  `try/finally` 恢复开关与两级限额原值；49 个 429 断言 `RICCON-QUOTA-429`。

## 修复 2：`m04_api_ms` 测量口径（`57fb682`）

- `api()` 新增 `measure`（默认 true）。以下调用传 `measure:false`，不计入 `m04_api_ms`：
  `ensureContext()` 的 workspace 列表/会话创建、`assertSession` 认证、quota 场景的开关/限额
  GET/PUT 与 cleanup。
- 聊天提交是 SSE：`ChatService.send` 返回前等待 Worker `started` 事件，TTFB 约 1.4–2.5s；
  单独记入 `m04_chat_submit_ttfb_ms`，完整流记入 `m04_chat_stream_ms`，受首字 p95 ≤ 3s 预算约束，
  不并入 500ms 的 JSON API 预算。
- `m04_api_ms` 现在只含稳态 JSON API：`GET /runs`、成本查询；阈值保持 p95 ≤ 500ms。

## 最终运行（EV-0668）

- 命令：`REAL_EVIDENCE_TASK=TASK-0137 node scripts/real-stack/run.mjs --mode=perf`
- host：macOS 24.6.0（darwin/amd64），Intel i7-9750H，12 vCPU，16 GiB。
- k6：`k6 v2.4.0 (commit/dd9395906d, go1.26.11, darwin/amd64)`；50 VUs / pool 25 / fake model 1 / 45s。
- 矩阵退出码：**1**（仅 first_token API p95 506.499ms > 500ms）。

| 场景                  | 首字 p95 | JSON API p95                 | 聊天 TTFB p95 | 降级成功率 | 退出码 |
| --------------------- | -------- | ---------------------------- | ------------- | ---------- | ------ |
| first_token           | 32ms     | **506.499ms**                | 2008.709ms    | —          | 99     |
| failover server_error | 27ms     | 418.282ms                    | 1530.483ms    | 100%       | 0      |
| failover rate_limited | 25ms     | 400.842ms                    | 1441.335ms    | 100%       | 0      |
| failover unauthorized | 24ms     | 399.052ms                    | 1443.179ms    | 100%       | 0      |
| failover timeout      | 1061ms   | 402.639ms                    | 1904.203ms    | 100%       | 0      |
| cost（30/180 天）     | —        | 71.194ms（成本 p95 71.75ms） | —             | —          | 0      |
| quota                 | —        | —                            | —             | —          | 0      |

- **PERF-M04-002 pass**：`m04_quota_allowed count=1`、`m04_quota_rejected count=49`、
  `m04_quota_cleanup rate=1`，全部拒绝为 `RICCON-QUOTA-429`；零超卖。
- **PERF-M04-001**：首字、降级成功率、成本预算通过；`m04_api_ms` 排除认证/setup 后
  **first_token 仍 506.499ms（超 500ms）**。同代码上一轮为 528.671ms、failover-rate_limited 602.937ms，
  本轮后者 400.842ms，说明该指标在 500ms 边界波动。`m04_api_ms` 的唯一来源是 `GET /runs`：
  median 约 363ms、p95 506–529ms，热点是运行详情查询/50 并发流下的 DB 竞争，不是登录或 setup。
  聊天 SSE TTFB 单独记录（1.4–2.5s），不属于 500ms JSON API 预算。
- 未修改阈值、未排除任何业务 JSON API；F-003 仍需独立优化 run-detail 查询/连接池。

## 证据

- `EV-0668`（clean，commit `57fb682`；matrix 因 F-003 exit 1）。
- `perf-m04-matrix.json`、`perf-m04-*.json`、`perf-m04-*.log`。
- 被替代：EV-0667（测量口径修正前的矩阵）归档在
  `.riccon/archive/evidence-superseded/TASK-0137/`。
