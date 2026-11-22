---
review_id: REV-M04-005
previous_review: REV-M04-004
module_id: M04
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e03bda5e8ffexbPqdZ2ZasFHH2
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
review_kind: module
head_commit: 795ed5b
verdict: pass
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
  - EV-0689
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
    - EV-0689
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
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/builder-key / riccon/builder-gateway-e2e"
    due: "M05 gate 前"
    title: "AC-07 在途轮换仍只证明旧密文可解密，未覆盖真实并发流式请求（V1 F-006）"
    evidence: "TASK-0115-report-002 F-006"
  - id: F-007
    severity: P2
    status: fixed
    owner: "riccon/lead"
    title: "TASK-0115 write_scope 误报已由 Lead 收窄 baseline/end 消除；gate 不再报告 TASK-0115 越界"
    evidence: "TASK-0115 baseline 6bc0c43 / end 93c6498；2026-11-21T16:14 gate 输出无 TASK-0115 write_scope 错误"
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/builder-route"
    due: "M05 gate 前"
    title: "非 503 Worker detail 仍写入 run.error_message；当前为静态脱敏文本（V1 F-013）"
    evidence: "TASK-0115-report-002 F-013"
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/ops"
    due: "M09 交付前"
    title: "restore-drill 报告仍为 partial（J1 skipped）；V2 以独立 API/Worker 在恢复库补跑 J1 与历史读取"
    evidence: "EV-0683；EV-0684"
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/builder-route"
    due: "M05 gate 前"
    title: "AC-01 仍无单条 UI 保存→下一次 CHAT 请求的跨组件 E2E（V1 残余）"
    evidence: "TASK-0115-report-002 AC-01 残余"
  - id: F-011
    severity: P1
    status: fixed
    owner: "riccon/builder-gateway-e2e（TASK-0139）"
    title: "全量真实栈启动顺序已修复：API 先迁移到 flyway=11 并 health 后再起 Worker；EV-0689 全绿"
    evidence: "EV-0689（clean exit 0，migration=passed，RICCON-WORKER-001 0 次）"
  - id: F-012
    severity: P2
    status: accepted
    owner: "riccon/builder-web / riccon/ops"
    due: "M05 gate 前"
    title: "合同 test/coverage 的 web 套件两次 flake（不同用例），第三次通过；单文件与 web 单独全量均通过"
    evidence: "EV-0673；EV-0674；EV-0675"
  - id: F-013
    severity: P2
    status: accepted
    owner: "riccon/architect / riccon/builder-gateway-e2e"
    due: "M05 gate 前"
    title: "PERF API p95 测量口径收窄为稳态 JSON API（排除登录/setup；SSE TTFB 单列），需 Architect 对齐 PRODUCT/ADR 措辞"
    evidence: "EV-0669；e2e/perf/README.md"
created_at: "2026-11-21T16:20:00+08:00"
---

# M04 模型网关纵深 · 模块评审（REV-M04-005，最终）

## 结论

**verdict: pass。** F-001..F-005、F-011 均已修复并独立复核；最终提交的全量真实栈 EV-0689 clean exit 0，`migration/checks/security/m04/m03/m10/j2/browser` 全 passed，`RICCON-WORKER-001` 0 次。18 条 AC 全部 pass，DoD 全绿；P2/P3 残余已登记 owner/到期，不阻塞 gate。

本评审是独立技术验收，**不声称** Product 预验收、用户验收或发布批准；M04 仍 `building`，`acceptance: product=pending user=pending`。

## 最终证据与验证

| 项                       | 结果                                                                                                                                                                                                               | EV                |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------- |
| 合同 test/coverage       | exit 0；Java **1101 tests**（api 642 / worker 356 / common 87 / domain 4 / arch 12），0 fail/error/skip；web 493/57；Java apps line 90.79%/branch 73.17%，platform 91.60%/75.41%；web lines 86.98%/branches 80.01% | **EV-0675**       |
| build                    | exit 0                                                                                                                                                                                                             | **EV-0687**       |
| 合同 E2E                 | exit 0；40 passed                                                                                                                                                                                                  | **EV-0676**       |
| a11y                     | exit 0；15 passed（四页 axe/375px/键盘）                                                                                                                                                                           | **EV-0677**       |
| security                 | exit 0（仅 ADR-0011 已登记例外）                                                                                                                                                                                   | **EV-0678**       |
| **全量真实栈**           | **exit 0**；`migration=passed`、`api ready (flyway=11); starting worker`、browser 25 passed、checks/security/m04/m03/m10/j2 全 passed、`RICCON-WORKER-001` 0 次                                                    | **EV-0689**       |
| perf                     | exit 0；首字 p95 32ms、JSON API p95 61.777ms、降级 4×100%、配额 1/49、cost p95 64.5ms                                                                                                                              | **EV-0669**       |
| AC-18/指标/WARN          | exit 0；15 项 checks 全 passed；`riccon_quota_store_failure_total`（单数）；Worker 7 指标；WARN 恰好一次                                                                                                           | **EV-0681**       |
| 迁移/回滚/restore/rewrap | 回滚 MAX(version)=10、多 key 收敛；restore credential/J1/历史；backfill 1→0；rewrap mk-1→mk-2                                                                                                                      | **EV-0682..0686** |
| docs                     | RUNBOOK 授权/回收、WARN、B-21、ADR accepted                                                                                                                                                                        | **EV-0688**       |

EV-0679/0680（F-011 修复前的真实栈失败）保留为历史失败证据，不用于 DoD 绑定；最终证据为 EV-0689。

## 18 条 AC（V1 / V2 分工）

| AC    | 最终结论 | 证据                                                                                                                              |
| ----- | -------- | --------------------------------------------------------------------------------------------------------------------------------- |
| AC-01 | pass     | E2E-M04-01（EV-0676/0689）；IT-MODEL-101（EV-0640）。F-010 P3 accepted。                                                          |
| AC-02 | pass     | V2 独立类 `M04V2VerifierBoundaryTest` 4/4（EV-0640）；E2E-M04-07a/07b 无写入口（EV-0676/0677）；路由审计 fail-closed（EV-0640）。 |
| AC-03 | pass     | IT-MODEL-103 / ModelFailoverIntegrationTest 17/17（EV-0640）；perf 降级 4×100%（EV-0669）；E2E-M04-02（EV-0689）。                |
| AC-04 | pass     | IT-MODEL-104 + E2E-M04-03（EV-0676/0689）。                                                                                       |
| AC-05 | pass     | ChatModelUnavailableApiTest 6/6（EV-0640）；真实栈 M04-AC-05（EV-0689）。                                                         |
| AC-06 | pass     | IT-MODEL-105 + ModelFailoverIntegrationTest（EV-0640）。                                                                          |
| AC-07 | pass     | E2E-M04-04 + IT-MODEL-106（EV-0640/0676）；F-006 P2 accepted。                                                                    |
| AC-08 | pass     | IT-QUOTA-101/105（EV-0640）；E2E-M04-05（EV-0676/0689）。                                                                         |
| AC-09 | pass     | IT-QUOTA-102 50 并发 1/49（EV-0640）；PERF-M04-002 1/49（EV-0669）。                                                              |
| AC-10 | pass     | IT-QUOTA-103（EV-0640）；E2E-M04-05（EV-0676/0689）。                                                                             |
| AC-11 | pass     | IT-COST-101..103（EV-0640）；E2E-M04-06（EV-0676/0689）。                                                                         |
| AC-12 | pass     | IT-COST-104 + `M04V2VerifierBoundaryTest`（EV-0640）；E2E-M04-06（EV-0676/0689）。                                                |
| AC-13 | pass     | E2E-M04-07a/07b（EV-0676/0677）；真实栈四页（EV-0689）。                                                                          |
| AC-14 | pass     | EV-0677 15/15（四页 axe/375px/键盘）。                                                                                            |
| AC-15 | pass     | SEC-M04-001/002/009、IT-MODEL-107（EV-0640/0689）。                                                                               |
| AC-16 | pass     | EV-0676 E2E-M04-08；EV-0689 真实栈 browser 25 + J2/M03/M10 全 passed。                                                            |
| AC-17 | pass     | EV-0669（F-013 P2 accepted 仅涉及测量口径措辞）。                                                                                 |
| AC-18 | pass     | EV-0681 开关关闭 J1/J2、WARN 恰好一次；EV-0682 回滚/历史；EV-0683/0684 restore；EV-0685/0686 rewrap/backfill；EV-0689 真实栈。    |

## 威胁模型 B-18..B-21

| 威胁 | 结论   | 依据                                                                                                                                                                     |
| ---- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| B-18 | closed | EV-0640/0646/0689：写越权 403/404、端点拒绝、无 key/内部地址。                                                                                                           |
| B-19 | closed | IT-QUOTA-102、PERF-M04-002 1/49（EV-0669）、IT-COST-101..104、TEN-032/033。                                                                                              |
| B-20 | closed | Worker 7 `metrics-check`；API 2 主 + `riccon_cost_report_queries_total`/`riccon_quota_store_failure_total`（单数）/`riccon_quota_over_limit_observed_total`（EV-0681）。 |
| B-21 | closed | EV-0669；EV-0681 WARN；EV-0682 回滚；EV-0683/0684 恢复；EV-0685 backfill；EV-0686 rewrap；EV-0689 真实栈。RTO 4.6s、RPO 24h。                                            |

## 残余 findings

- **F-006（P2 accepted，M05 gate 前）** AC-07 真实并发在途轮换未覆盖；由 E2E 影响说明 + 旧密文可解证据承接。
- **F-008（P2 accepted，M05 gate 前）** 非 503 Worker detail 入 `run.error_message`；当前为静态脱敏文本，风险有界。
- **F-012（P2 accepted，M05 gate 前）** 合同 test/coverage 的 web 套件在 EV-0673/0674 两次 flake（审计页超时、用户页断言），EV-0675 通过；单文件与 web 单独全量均通过。保留失败 EV，未用参数掩盖；owner builder-web/ops。
- **F-013（P2 accepted，M05 gate 前）** PERF API p95 口径收窄为稳态 JSON API（登录/setup 排除，SSE TTFB 单列）；阈值未变，业务 API 61.8ms、首字 32ms。Architect 需对齐 PRODUCT/ADR 措辞。
- **F-009（P3 accepted，M09 前）** restore-drill 报告仍 partial；V2 已用独立栈补 J1/历史。
- **F-010（P3 accepted，M05 gate 前）** AC-01 缺单条 UI 保存→下一次 CHAT 的跨组件 E2E。

## 边界与 gate 建议

- **已核实 F-007**：TASK-0115 的 baseline/end 已收窄为 `6bc0c43..93c6498`，当前 gate 不再报告 TASK-0115 write_scope 越界。
- **未验证**：生产 K8s/Helm 部署（M09）、真实供应商端点、跨区域容灾、生产规模迁移窗口；`deploy_preview`/`deploy_prod` not-applicable。
- gate 当前仍会因 **TASK-0116 / TASK-0139 处于 review 未 done**、TASK-0116 write_scope 按 `baseline..HEAD` 把 TASK-0135..0139 的修复文件算入而失败。建议 Lead：关闭 TASK-0116/TASK-0139（review 通过后），并把 TASK-0116 的 baseline/end 调整到本轮修复之后的提交（与 TASK-0115 同样处理）；review verdict pass 与 P1 已清零后，gate 才应通过。本 Verifier 不修改任务状态为 done。
- 工具：符号影响面使用 CodeGraph 核对 `SchemaVersionChecker`；未使用 Serena Java LSP（避免与全量 Maven 并发触碰 `target/`）；本轮无生产符号编辑。
