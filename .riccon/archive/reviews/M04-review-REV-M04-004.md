---
review_id: REV-M04-004
previous_review: REV-M04-003
module_id: M04
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e03bda5e8ffexbPqdZ2ZasFHH2
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
review_kind: module
head_commit: 672400f
verdict: fail
reviewed_evidence:
  - EV-0522
  - EV-0523
  - EV-0525
  - EV-0526
  - EV-0527
  - EV-0528
  - EV-0534
  - EV-0537
  - EV-0543
  - EV-0544
  - EV-0545
  - EV-0546
  - EV-0552
  - EV-0553
  - EV-0554
  - EV-0555
  - EV-0556
  - EV-0557
  - EV-0560
  - EV-0564
  - EV-0583
  - EV-0591
  - EV-0600
  - EV-0601
  - EV-0602
  - EV-0603
  - EV-0604
  - EV-0605
  - EV-0606
  - EV-0607
  - EV-0610
  - EV-0613
  - EV-0622
  - EV-0634
  - EV-0635
  - EV-0636
  - EV-0639
  - EV-0640
  - EV-0641
  - EV-0642
  - EV-0643
  - EV-0644
  - EV-0645
  - EV-0646
  - EV-0647
  - EV-0648
  - EV-0649
  - EV-0650
  - EV-0651
  - EV-0652
  - EV-0653
  - EV-0654
  - EV-0655
  - EV-0656
  - EV-0657
  - EV-0658
  - EV-0659
  - EV-0669
  - EV-0670
  - EV-0671
  - EV-0672
  - EV-0673
  - EV-0674
  - EV-0675
  - EV-0676
  - EV-0677
  - EV-0678
  - EV-0679
  - EV-0680
  - EV-0681
  - EV-0682
  - EV-0683
  - EV-0684
  - EV-0685
  - EV-0686
  - EV-0687
  - EV-0688
dod:
  acceptance: true
  build: true
  tests: true
  coverage: true
  e2e: true
  review: true
  docs: true
  evidence: true
dod_evidence:
  acceptance:
    - EV-0669
    - EV-0675
    - EV-0676
    - EV-0677
    - EV-0678
    - EV-0681
    - EV-0682
    - EV-0683
    - EV-0684
    - EV-0685
    - EV-0686
  build:
    - EV-0687
  tests:
    - EV-0675
  coverage:
    - EV-0675
  e2e:
    - EV-0676
  docs:
    - EV-0688
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/builder-quota（TASK-0135）"
    title: "API 配额存储故障指标名已改为合同单数名 riccon_quota_store_failure_total"
    evidence: "EV-0670；EV-0681 apiMetricStoreFailureContractName=passed"
  - id: F-002
    severity: P1
    status: fixed
    owner: "riccon/builder-core（TASK-0136）"
    title: "SchemaVersionChecker 已在真实 Worker 启动运行；启用时 schema.version.checked、禁用时 RICCON-WORKER-002 恰好一次"
    evidence: "EV-0671；EV-0681 workerSchemaVersionCheckRuns/workerRollbackWarn=passed"
  - id: F-003
    severity: P1
    status: fixed
    owner: "riccon/builder-gateway-e2e（TASK-0137）"
    title: "PERF-M04-001 已通过：首字 p95 32ms、JSON API p95 61.777ms、降级 4 模式 100%"
    evidence: "EV-0669（clean exit 0）；.riccon/evidence/TASK-0137/perf-m04-*.json"
  - id: F-004
    severity: P1
    status: fixed
    owner: "riccon/builder-gateway-e2e（TASK-0137）"
    title: "PERF-M04-002 已通过：50 并发配额争用 1 allowed / 49 rejected、cleanup rate 1"
    evidence: "EV-0669；.riccon/evidence/TASK-0137/perf-m04-quota.json"
  - id: F-005
    severity: P2
    status: fixed
    owner: "riccon/ops（TASK-0138）"
    title: "restore-drill 演练库授权/回收已写入 RUNBOOK；credential check 在授权后通过"
    evidence: "EV-0672；EV-0683；EV-0684"
  - id: F-011
    severity: P1
    status: open
    owner: "riccon/builder-gateway-e2e / riccon/ops"
    title: "全量真实栈在新卷上确定性失败：run.mjs --mode=full 同时启动 API 与 Worker，Worker 在 Flyway 迁移完成前读到 schema 7/8 并以 RICCON-WORKER-001 退出"
    evidence: "EV-0679；EV-0680；ac18/full-stack-failure-0679-0680/real-stack-worker.log"
  - id: F-012
    severity: P2
    status: open
    owner: "riccon/builder-web / riccon/ops"
    title: "合同 test/coverage 命令的 web 套件两次 flake（不同用例：审计页超时、用户页断言），第三次通过；单文件与 web 单独全量均通过"
    evidence: "EV-0673；EV-0674；EV-0675"
  - id: F-013
    severity: P2
    status: accepted
    owner: "riccon/architect / riccon/builder-gateway-e2e"
    title: "PERF API p95 测量口径收窄为稳态 JSON API（排除登录/会话/setup；SSE TTFB 单列），需 Architect 在 M05 前对齐 PRODUCT/ADR 措辞或补认证预算"
    evidence: "EV-0669；e2e/perf/README.md"
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/builder-key / riccon/builder-gateway-e2e"
    title: "AC-07 在途轮换仍只证明旧密文可解密，未覆盖真实并发流式请求（V1 F-006）"
    evidence: "TASK-0115-report-002 F-006"
  - id: F-007
    severity: P2
    status: open
    owner: "riccon/lead"
    title: "TASK-0115 gate write_scope 按 baseline..HEAD 计算会把修复提交算到 TASK-0115（V1 F-014）"
    evidence: "TASK-0115-report-002 F-014"
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/builder-route"
    title: "非 503 Worker detail 仍写入 run.error_message；当前为静态脱敏文本（V1 F-013）"
    evidence: "TASK-0115-report-002 F-013"
  - id: F-009
    severity: P3
    status: open
    owner: "riccon/ops"
    title: "restore-drill 报告仍为 partial（J1 skipped）；V2 以独立 API/Worker 在恢复库补跑 J1 与历史读取"
    evidence: "EV-0683；EV-0684"
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/builder-route"
    title: "AC-01 仍无单条 UI 保存→下一次 CHAT 请求的跨组件 E2E（V1 残余）"
    evidence: "TASK-0115-report-002 AC-01 残余"
created_at: "2026-11-21T16:05:00+08:00"
---

# M04 模型网关纵深 · 模块评审（最终修复复验）

## 结论

**verdict: fail（唯一门禁阻断：F-011）。** 四条 V2 P1 与 F-005 已修复并经独立复验；18 条 AC 全部通过，contract build/test/coverage/E2E/a11y/security 与 perf/迁移回滚证据均成立。但 **F-011**：`run.mjs --mode=full` 在干净卷上确定性失败——它同时启动 API 与 Worker，Worker 在新的 `SchemaVersionChecker` 生效后先于 Flyway 迁移完成读取 schema，以 `RICCON-WORKER-001 expected=11 actual=7/8` 退出。该命令是本轮要求的全量真实栈入口，也是新装/本地 `run` 路径的回归；修复很小（API 先起并等 health，再起 Worker），但不能由 Verifier 修改。

不声称 Product 预验收、用户验收或发布批准；M04 仍 `building`，`acceptance: product=pending user=pending`。

## 本轮修复复核（独立执行）

| 修复                | 修复证据                                            | V2 独立复核                                                                                                                                       |
| ------------------- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| F-001 指标名        | TASK-0135 `76f7e9b`，EV-0670（35 tests）            | EV-0681 抓取到 `riccon_quota_store_failure_total{reason="lock_wait"} 1.0`，复数名不再出现；API 2 主 + 3 辅助全通过。                              |
| F-002 SchemaChecker | TASK-0136 `4c6dc63`，EV-0671（启动上下文 12 tests） | EV-0681 主 Worker 日志 `schema.version.checked expected=11 actual=11`；禁用 Worker 60s 内 `RICCON-WORKER-002` 恰好 1 次、无 `RICCON-WORKER-001`。 |
| F-003/F-004 perf    | TASK-0137 `efabac9`，EV-0669 clean exit 0           | 盲核报告：first_token 首字 p95 32ms、JSON API p95 61.777ms；failover 4 模式成功率 1.0；quota 1 allowed/49 rejected；cost p95 64.5ms；pool=50。    |
| F-005 restore       | TASK-0138 `29ac099`，EV-0672                        | EV-0683 结构/解密 passed（RTO 4.6s、RPO 24h）；EV-0684 恢复库 J1 与历史运行详情 passed。                                                          |

## 最终证据（最终提交、clean tree）

| 命令                                                                                     | 结果                                                                                                                                                                                                                                                                      | EV                                                                           |
| ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`                      | **exit 0**；Java **1101 tests**（api 642 / worker 356 / platform-common 87 / platform-domain 4 / architecture 12），0 fail/error/skip；web 493/57 files；Java 覆盖率 apps line 90.79%/branch 73.17%，platform line 91.60%/branch 75.41%；web lines 86.98%/branches 80.01% | **EV-0675**（前两次 flake EV-0673/0674 保留）                                |
| `./mvnw -q -T1C -DskipTests package && pnpm -w build`                                    | exit 0                                                                                                                                                                                                                                                                    | **EV-0687**                                                                  |
| `docker compose ... up -d --wait && pnpm -w e2e`                                         | exit 0；40 passed                                                                                                                                                                                                                                                         | **EV-0676**                                                                  |
| `pnpm -w a11y`                                                                           | exit 0；15 passed（四页 axe/375px/键盘）                                                                                                                                                                                                                                  | **EV-0677**                                                                  |
| `trivy fs --scanners vuln,secret,misconfig ...`                                          | exit 0（仅 ADR-0011 已登记例外）                                                                                                                                                                                                                                          | **EV-0678**                                                                  |
| `REAL_EVIDENCE_TASK=TASK-0116 node scripts/real-stack/run.mjs --mode=full`               | **exit 1**：Worker `RICCON-WORKER-001 expected=11 actual=7` / `actual=8`，worker health 120s 超时                                                                                                                                                                         | **EV-0679 / EV-0680（失败）**；日志副本 `ac18/full-stack-failure-0679-0680/` |
| AC-18/指标/WARN：Verifier 手动排序栈（API 迁移完成→Worker）+ `m04-v2-runtime-verify.mjs` | **exit 0**；15 项 checks 全 passed                                                                                                                                                                                                                                        | **EV-0681**                                                                  |
| 回滚重放（一次性 `riccon_rollback_v2`，MySQL 8.4.13）                                    | exit 0；MAX(version)=10、2 行归档、每组 1 行、旧唯一键/成本列/旧应用历史可读                                                                                                                                                                                              | **EV-0682**                                                                  |
| `restore-drill --apply --keep --allow-partial` + 主密钥/pepper                           | exit 0；credential check passed；RTO 4.6s、RPO 24h                                                                                                                                                                                                                        | **EV-0683**                                                                  |
| 恢复库 API/Worker/假模型 J1 + 历史详情                                                   | exit 0；J1 SUCCEEDED、历史 run 可读                                                                                                                                                                                                                                       | **EV-0684**                                                                  |
| `backfill-cost-day`（dry-run → apply → apply）                                           | exit 0；pending 1→updated 1→0                                                                                                                                                                                                                                             | **EV-0685**                                                                  |
| `rewrap-credentials`（mk-1→mk-2，双密钥窗口）                                            | exit 0；2 DEK rewrap、2 凭据解密/fingerprint 通过                                                                                                                                                                                                                         | **EV-0686**                                                                  |
| `REAL_EVIDENCE_TASK=TASK-0137 node scripts/real-stack/run.mjs --mode=perf`               | exit 0；矩阵全 passed                                                                                                                                                                                                                                                     | **EV-0669**                                                                  |
| 文档核对（RUNBOOK 授权/回收、WARN、B-21、ADR accepted）                                  | exit 0                                                                                                                                                                                                                                                                    | **EV-0688**                                                                  |

**测试数说明**：REV-M04-003 中“465 tests”是 Verifier 统计口径错误（只统计了 12:20 之后的报告，漏掉先完成的 platform 模块）。EV-0641 实际已覆盖 5 个模块约 1047 tests；本轮 EV-0675 为 1101 tests，**不是**只跑了部分模块。

## AC 对账（最终）

| AC    | 结论                      | 证据                                                                                                                                                        |
| ----- | ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-01 | pass                      | E2E-M04-01（EV-0676）；IT-MODEL-101（EV-0640）。F-010 P3 残余。                                                                                             |
| AC-02 | pass                      | `M04V2VerifierBoundaryTest` 4/4（EV-0640）；E2E-M04-07a/07b 无写入口（EV-0676/0677）；路由审计 fail-closed（EV-0640）。                                     |
| AC-03 | pass                      | ModelFailoverIntegrationTest 17/17（EV-0640）；perf failover 4 模式 100%（EV-0669）。                                                                       |
| AC-04 | pass                      | IT-MODEL-104 + E2E-M04-03（EV-0676）。                                                                                                                      |
| AC-05 | pass                      | ChatModelUnavailableApiTest 6/6（EV-0640）。                                                                                                                |
| AC-06 | pass                      | IT-MODEL-105 + ModelFailoverIntegrationTest（EV-0640）。                                                                                                    |
| AC-07 | pass（F-006 P2 accepted） | E2E-M04-04 + IT-MODEL-106（EV-0640）。                                                                                                                      |
| AC-08 | pass                      | QuotaAdmissionApiTest 6/6（EV-0640）；E2E-M04-05（EV-0676）。                                                                                               |
| AC-09 | pass                      | IT-QUOTA-102 50 并发 1/49（EV-0640）；PERF-M04-002 1/49（EV-0669）。                                                                                        |
| AC-10 | pass                      | QuotaAdminApiTest 7/7（EV-0640）。                                                                                                                          |
| AC-11 | pass                      | CostApiTest 4/4（EV-0640）；E2E-M04-06（EV-0676）。                                                                                                         |
| AC-12 | pass                      | CostAdminApiTest + `M04V2VerifierBoundaryTest`（EV-0640）；E2E-M04-06（EV-0676）。                                                                          |
| AC-13 | pass                      | E2E-M04-07a/07b（EV-0676/0677）。                                                                                                                           |
| AC-14 | pass                      | EV-0677 15/15（四页 axe/375px/键盘）。                                                                                                                      |
| AC-15 | pass                      | M04VerifierProductionEndpointTest + GatewaySecurityTest（EV-0640）。                                                                                        |
| AC-16 | pass                      | EV-0676 E2E-M04-08；EV-0681 J1/J2 HTTP；M03/M10 stub 回归（EV-0676）。最终全量真实栈浏览器证据受 F-011 阻断，见边界。                                       |
| AC-17 | pass（F-013 P2 accepted） | EV-0669：首字 32ms、JSON API 61.777ms、降级 100%、配额 1/49、成本 64.5ms。测量口径见 F-013。                                                                |
| AC-18 | pass                      | 开关关闭 J1/J2、4 条审计（EV-0681）；`RICCON-WORKER-002` 恰好一次（EV-0681）；回滚重放/历史可读（EV-0682/0684）；rewrap/restore/backfill（EV-0683..0686）。 |

## 威胁模型 B-18..B-21

| 威胁                  | 结论   | 依据                                                                                                                                                                          |
| --------------------- | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B-18 越权与凭据边界   | closed | EV-0640/0646/0681：写越权 403/404、端点拒绝、无 key/内部地址。                                                                                                                |
| B-19 配额与成本正确性 | closed | IT-QUOTA-102、PERF-M04-002 1/49（EV-0669）、IT-COST-101..104（EV-0640）、TEN-032/033。                                                                                        |
| B-20 可观测性         | closed | Worker 7 `metrics-check` 通过；API 2 主 + `riccon_cost_report_queries_total`/`riccon_quota_store_failure_total`（单数）/`riccon_quota_over_limit_observed_total`（EV-0681）。 |
| B-21 性能与迁移/回滚  | closed | EV-0669；EV-0681 WARN；EV-0682 回滚；EV-0683/0684 恢复；EV-0685 backfill；EV-0686 rewrap；RTO 4.6s、RPO 24h。                                                                 |

## Findings

### F-011（P1 open）全量真实栈在新卷上确定性失败

- **观察**：`run.mjs --mode=full` 先 `docker compose down -v`，随后 `startApplications` 同时起 API 与 Worker。Worker 的 `SchemaVersionChecker` 在 Flyway 尚未完成时读到 `actual=7`（第一次）/`actual=8`（第二次），以 `RICCON-WORKER-001 schema version mismatch: expected=11` 退出；脚本等待 worker health 120s 超时，exit 1。
- **证据**：EV-0679、EV-0680；`ac18/full-stack-failure-0679-0680/real-stack-worker.log:165/226`。
- **影响**：本轮要求的全量真实栈（browser 25 + checks/security/m04/m03/m10/j2）无法在最终提交上运行；新装/本地 `commands.run`（api+worker 并发）同样会在首次空库启动时让 Worker 退出。生产发布按 ADR-0021 §10.2“迁移 Job → API → Worker”不受影响，但验证/开发入口是 T4 门禁回归。
- **期望**：`run.mjs --mode=full` 在起 Worker 前先启动 API 并等待 `/actuator/health`（或显式跑迁移 Job），再起 Worker；`commands.run`/本地文档同步“先迁移后 Worker”或 Worker 对启动期 schema 未就绪做有限重试（不得放宽版本等值断言）。修完后重跑全量真实栈并重录 EV；本 Verifier 不改 `scripts/real-stack/**`。

### F-012（P2 open）合同 test/coverage 的 web 套件 flake

- **观察**：EV-0673 `src/pages/admin/audit/index.test.tsx` 超时 15s；EV-0674 `src/pages/admin/users/index.test.tsx` 断言失败（不同用例、同一命令）；两次单文件运行均通过（14/14、13/13），web 单独全量通过，第三次合同命令 EV-0675 全绿。失败发生在 Maven `verify` 结束后 web 套件立即启动的窗口，surefire fork 退出有 30s 延迟日志。
- **影响**：合同命令在噪声环境下不稳定，会间歇性阻塞模块 gate；本次保留失败 EV，未用参数掩盖。
- **期望**：定位 surefire fork 退出/资源交接或 web 测试对时序的依赖；修复后连续两次全量通过；owner builder-web/ops，最迟 M05 gate 前。

### F-013（P2 accepted）PERF API p95 测量口径

- `m04_api_ms` 现只统计稳态 JSON API（run detail、成本查询），排除登录/会话/setup；SSE 提交 TTFB 单列 `m04_chat_submit_ttfb_ms`。阈值未变，业务 API p95 61.8ms、首字 p95 32ms。PRODUCT 原文只说“不含模型与外部 MCP 调用”，未明确排除认证。接受理由：认证是一次性 setup，SSE 由首字预算约束；但 Architect 应在 M05 前对齐 PRODUCT/ADR 措辞或补认证 p95 预算，避免口径漂移。

### 其余

- F-006（P2 accepted）AC-07 在途并发；F-008（P2 accepted）非 503 detail 入库；F-007（P2 open）TASK-0115 write_scope 误报；F-009（P3 open）restore-drill 报告 partial；F-010（P3 accepted）AC-01 跨组件 E2E 残余。

## 边界与下一步

- **Not verified**：最终提交上的全量真实栈 browser 25 与 real-stack checks（F-011）；pre-fix EV-0646 的 25 browser 不能替代最终提交。J1/J2 在最终代码上由 EV-0681 以 HTTP + 真实 Worker 验证，浏览器 UI 由 stub E2E EV-0676 覆盖。
- `deploy_preview`/`deploy_prod` not-applicable（M09）；不声称生产可用或发布批准。
- 建议 Lead：先建 `kind: fix` 修 F-011（排序/迁移前置），再重跑 `run.mjs --mode=full` 并重录真实栈 EV；F-012 同期稳定化。修完前 gate 应保持失败；不要以 review 文本或 task done 强行通过。
- 工具：符号影响面用 CodeGraph 核对 `SchemaVersionChecker`；未使用 Serena Java LSP（避免与全量 Maven 并发触碰 `target/`），本轮无生产符号编辑。
