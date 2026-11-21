# TASK-0137 修复验证：k6 角色 Cookie 隔离与 PERF-M04-002（2026-11-21T13:55+08:00）

> 非 EV。权威证据：`.riccon/evidence/TASK-0137/EV-0667.json`（perf 矩阵，exit 1 见下）。
> 修复提交：`dffadf5`。

## 根因与修复

- 根因：k6 VU 级默认 cookie jar 会与显式 `Cookie` 头合并；同一 VU 连续登录平台/租户/员工后，
  jar 中的成员会话覆盖平台显式 Cookie，`GET /api/v1/admin/model-gateway/switches` 返回 403。
- 修复（`e2e/perf/mvc-smoke.js`）：
  - 新增 `clearCookieJar()`，在 `login()` 开始、登录 POST 前、登录完成后，以及 `api()` 每次请求前、
    `http.batch` 批量提交前清空 VU jar；所有会话继续通过显式 Cookie 头传递。
  - `login()` 不再让匿名 CSRF 会话与登录 Cookie 在 jar 中重叠；登录后立即清空。
  - quota 场景新增运行时自检：三份 Cookie 必须互不相同；`GET /api/v1/auth/session` 分别断言
    `platform/platform-admin/PLATFORM_ADMIN`、`tenant-alpha/alpha-admin/TENANT_ADMIN`、
    `tenant-alpha/alpha-member/MEMBER`。
  - quota 场景设置**租户与用户**日限额均为 `used+1`：前序 first_token/failover 即使执行关闭也会累计
    用量，用户日窗口可能超过 seed 的 1000，否则 50 个请求会全部被用户窗口拒绝，无法证明租户级零超卖。
  - 清理改为 `try/finally`，恢复开关原值与租户/用户两级限额原值；清理失败写入
    `m04_quota_cleanup=0` 并让阈值失败。
  - 拒绝响应新增 `code` 断言：49 个 429 必须都是 `RICCON-QUOTA-429`。
- `scripts/real-stack/run.mjs` 的 perf 说明同步为“恢复开关与两级限额原值”。

## 运行环境

- host：macOS 24.6.0（darwin/amd64），Intel Core i7-9750H，12 vCPU，16 GiB。
- k6：`k6 v2.4.0 (commit/dd9395906d, go1.26.11, darwin/amd64)`。
- profile：50 VUs / DB pool 25 / fake model 1 / 45s。
- 命令：`REAL_EVIDENCE_TASK=TASK-0137 node scripts/real-stack/run.mjs --mode=perf`
- 矩阵退出码：**1**（PERF-M04-001 的 API p95 预算未过；PERF-M04-002 通过）。

## PERF-M04-002（quota，退出码 0）

- `m04_quota_allowed count=1`、`m04_quota_rejected count=49`、`m04_quota_cleanup rate=1`；
  49 个拒绝均为 `RICCON-QUOTA-429`；无 5xx/其他状态。
- 结论：50 VU 争用剩余 1 次，零超卖；开关与两级限额恢复原值（DB 复核
  `quotaEnforcementEnabled=false`，租户 10000/200000，用户 1000/20000）。

## PERF-M04-001（退出码/指标）

| 场景                  | 首字 p95 | API p95                      | 降级成功率 | 退出码 |
| --------------------- | -------- | ---------------------------- | ---------- | ------ |
| first_token           | 33ms     | **508.483ms**                | —          | 99     |
| failover server_error | 27ms     | 440.271ms                    | 100%       | 0      |
| failover rate_limited | 30ms     | **525.528ms**                | 100%       | 99     |
| failover unauthorized | 25ms | 435.759ms | 100% | 0 |
| failover timeout | 1062ms | 404.265ms | 100% | 0 |
| cost（30/180 天） | — | 63.989ms（成本查询 p95 63.75ms） | — | 0 |

- 首字、降级成功率与成本预算通过；**API p95 在 first_token 与 failover rate_limited 仍超 500ms**
  （V2 F-003）。未修改阈值/脚本口径，真实数据见 `perf-m04-*.json`。
- 上一轮同机数据 first_token API p95 732.981ms、failover server_error 673.529ms；本轮 508.483/440.271，
  说明该指标在 500ms 边界附近波动，F-003 仍需独立定位。

## 证据

- `EV-0667`（perf 矩阵，exit 1，`dirty=false`，commit `43cedf4` 上录制；代码提交 `dffadf5`）。
- `perf-m04-matrix.json`、`perf-m04-first_token.json`、`perf-m04-failover-*.json`、
  `perf-m04-quota.json`、`perf-m04-cost.json`、`perf-m04-*.log`。
