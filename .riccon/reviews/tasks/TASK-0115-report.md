---
review_id: REV-TASK-0115-001
module_id: M04
task_id: TASK-0115
title: M04-V1 独立验收报告（AC-01、AC-03..16；不判 AC-02/17/18）
verifier: riccon/verifier
reviewer:
  agent: riccon/verifier
  session: ses_e0426351dffeuTc5rUnx2hbjke
  model: opencode-go/deepseek-v4.1-flash#max
  independent: true
verdict: fail
reviewed_commits:
  - 42233ffa503c12d7a96eba8be63709754a2485ad
  - 6e01571a7af67af846b872027c38064374b62e98
  - 5e81a6b039654898fdef309509cccb67c3efad84
  - 71a0d988585a5d19806baa837ce6ce7f14a2e7d2
  - 7024e83cb57ff699d0239781ec13eacf8e4135f5
  - c1e2b94f39e78c2dc34367a62d432361d4b30569
  - ab90fc65175139b6bcc6dcd8f6333d1505f661c7
created_at: "2026-11-21T11:00:00+08:00"
evidence:
  - EV-0623
  - EV-0624
  - EV-0625
  - EV-0626
  - EV-0627
  - EV-0628
findings:
  - id: F-001
    severity: P1
    status: open
    owner: "riccon/builder-route / riccon/lead（到期 M04 gate 前）"
    title: "API 日志打印 Worker detail 原文，canary 扫描发现 key 明文与内部 baseUrl"
  - id: F-002
    severity: P1
    status: open
    owner: "riccon/builder-route / riccon/lead（到期 M04 gate 前）"
    title: "契约/RUNBOOK/B10 要求的 /api/v1/admin/model-gateway/switches 未实现"
  - id: F-003
    severity: P1
    status: open
    owner: "riccon/builder-gateway-e2e（到期 M04 gate 前）"
    title: "B11b 四页 a11y 测试未真正恢复路由顺序且断言命中旧成功提示，导致 E2E-M04-02 在全量真实栈必失败"
  - id: F-004
    severity: P2
    status: open
    owner: "riccon/builder-gateway-e2e / riccon/ops（到期 M04 gate 前）"
    title: "checks.mjs 把“最新 CHAT run”当作 J1 run，M04 用例后置时稳定失败"
  - id: F-005
    severity: P2
    status: open
    owner: "riccon/lead（到期 M04 gate 前）"
    title: "5 条历史 EV 来自 dirty 工作树，模块 gate 持续失败"
  - id: F-006
    severity: P2
    status: open
    owner: "riccon/builder-key / riccon/builder-gateway-e2e（到期 M04 gate 前）"
    title: "AC-07 在途轮换只证明旧密文可解密，未覆盖真实并发流式请求"
  - id: F-007
    severity: P3
    status: open
    owner: "riccon/verifier（V2）"
    title: "AC-09 的“调高限额后被拒绝的 run 可成功重试”没有单独用例"
  - id: F-008
    severity: P3
    status: open
    owner: "riccon/builder-gateway-e2e（V2）"
    title: "AC-01 缺少“UI 保存路由后下一次 CHAT 请求使用新链”的单条跨组件 E2E"
  - id: F-009
    severity: P3
    status: open
    owner: "riccon/builder-web（到期 M04 gate 前）"
    title: "AC-14 的“焦点可见”只有 toBeFocused，没有焦点指示样式断言"
  - id: F-010
    severity: P3
    status: open
    owner: "riccon/builder-failover（V2）"
    title: "3xx 重定向没有直接负向用例，只有 HttpClient.Redirect.NEVER 代码事实"
  - id: F-011
    severity: P3
    status: open
    owner: "riccon/lead"
    title: "TASK-0024 历史证据文件被 real-stack --list 结果覆盖并提交"
  - id: F-012
    severity: P3
    status: open
    owner: "riccon/builder-gateway-e2e（V2）"
    title: "AC-05 未断言“已上传文件保留”"
---

# M04-V1 独立验收报告

## 结论

**M04-V1：fail。** 15 条 AC 中 13 条可判 pass（部分带残余），但发现 **3 个 P1**：

1. **AC-15 日志泄露（产品缺陷）**：API 将 Worker 返回的 `detail` 原文写进 WARN 日志；canary 扫描在 surefire 报告与 EV-0623 日志中发现 `apiKey=sk-live-...` 与内部 `baseUrl`。
2. **开关端点缺失（契约缺陷）**：`/api/v1/admin/model-gateway/switches` 在 `apps/api/src/main` 无任何 controller，影响 AC-02 开关段、AC-18 与 B10 `quota-drill`。
3. **E2E-M04-02 全量真实栈失败（测试假绿 + 顺序污染）**：B11b 四页 a11y 测试的“恢复顺序”没有真正保存，断言命中了旧的成功提示；随后 B11a 的 E2E-M04-02 在错误的链顺序上失败。

因此 **AC-03 判 fail**（合同 E2E 证据为红），**AC-15 判 fail**（日志边界）；其余 13 条 AC 的已验证部分为 pass，但模块 gate 在 P1 修复、真实栈全量重跑和 V2 完成前不能通过。

本报告只判 **AC-01、AC-03..16**；**不判 AC-02 整条、AC-17、AC-18**（归 V2）。本报告不替代模块 review。

## 执行命令与证据（全部 dirty=false）

| 验证                                                                       | 结果                                                                                                                                                       | 证据                                         |
| -------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `./mvnw -q -T1C verify jacoco:report && pnpm -w test -- --coverage`        | exit 0；Java **1075 tests / 0 fail / 0 error / 0 skip**；Web **493 tests / 57 files passed / 0 skip**                                                      | EV-0623（commit `42233ff`）                  |
| 覆盖率                                                                     | API **92.25% line / 74.76% branch**；Worker **87.95% / 70.71%**；platform-common **90.83% / 75.41%**；Web **86.98% lines / 80.01% branches**（门槛 80/70） | EV-0623 + `target/site/jacoco/jacoco.csv`    |
| `docker compose ... up -d --wait && pnpm -w e2e`                           | exit 0；**40 passed / 0 failed / 0 skipped**（含 E2E-M04-01/04/05/06/07a/07b/08）                                                                          | EV-0624（commit `6e01571`）                  |
| `pnpm -w a11y`                                                             | exit 0；**15 passed**，`withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa'])`，无规则过滤                                                                   | EV-0625（commit `5e81a6b`）                  |
| `trivy fs --scanners vuln,secret,misconfig ...`                            | exit 0；7 个依赖目标 0 vuln/secret/misconfig；仅 ADR-0011 的两条 CVE 被 ignore                                                                             | EV-0626（commit `71a0d98`）                  |
| `REAL_EVIDENCE_TASK=TASK-0115 node scripts/real-stack/run.mjs --mode=full` | **exit 1**；browserE2e **24 passed / 1 failed**；securityChecks/m04Checks failed；compose/seed/m10/j2/m03 passed                                           | EV-0627（commit `7024e83`）                  |
| `./mvnw -q -T1C -DskipTests package && pnpm -w build`                      | exit 0                                                                                                                                                     | EV-0628（commit `c1e2b94`）                  |
| SEC-M04-005 canary 扫描                                                    | **命中 1 处**：`ChatService` WARN 日志含 `apiKey=sk-live-...` 与内部 `baseUrl`                                                                             | `.riccon/evidence/TASK-0115/canary-scan.log` |

## AC 对账（V1：AC-01、AC-03..16）

| AC        | 结论                      | 独立证据与说明                                                                                                                                                                                                                                                                                                                                                                         |
| --------- | ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M04-AC-01 | **pass（with residual）** | E2E-M04-01（EV-0624 第 28 条）验证两条链顺序/启停/保存/端点标签；IT-MODEL-101（EV-0623 `ModelRoutingApiTest` + `M04VerifierGatewaySecurityTest.ac01`）验证保存后立即读到新链、停用保留位置、启用恢复；真实栈 key 生命周期（EV-0627 第 16 条）验证路由保存审计。残余：没有单条“UI 保存后下一次 CHAT 请求”的跨组件 E2E（F-008）。                                                        |
| M04-AC-03 | **fail**                  | IT-MODEL-103 全绿（EV-0623 `ModelFailoverIntegrationTest` 17 tests：超时/5xx/429/401、attempt 数、切换审计）；但 **E2E-M04-02 在 EV-0627 全量真实栈失败**，run detail 显示 `fake-chat-primary`，期望 `fake-chat-secondary`；根因是 B11b a11y 测试未恢复链顺序（F-003）。产品侧 worker 日志显示 secondary 连续失败后 primary 成功，降级本身发生；但合同 E2E 证据当前为红，不能判 pass。 |
| M04-AC-04 | **pass**                  | E2E-M04-03（EV-0627 第 15 条）验证首字后断流保留内容、统一文案“回答已中断，已保留已生成内容，可重试。”、重试不重复、审计 `emitted=true`；IT-MODEL-104（EV-0623）验证不重试/不重放。                                                                                                                                                                                                    |
| M04-AC-05 | **pass（with residual）** | `M04-AC-05` 真实栈（EV-0627 第 17 条）断言 HTTP 503、`RICCON-MODEL-503`、固定中文文案、重试入口、无 provider/key/地址泄露；`ChatModelUnavailableApiTest` 5 tests 映射 Worker 500/503 与 SSE 首字前失败（EV-0623）。残余：未断言“已上传文件保留”（F-012）。                                                                                                                             |
| M04-AC-06 | **pass**                  | IT-MODEL-105（EV-0623）401 立即失效、下一把 key 成功、全失效稳定 503、platform/SYSTEM 审计；SEC-M04-006 生命周期审计白名单 + 审计失败回滚（EV-0623）；真实栈 key 新增/轮换/停用审计（EV-0627 第 16 条）。                                                                                                                                                                              |
| M04-AC-07 | **pass（with residual）** | E2E-M04-04（EV-0624 第 29 条）验证新增/轮换/停用、掩码、保存前影响说明；IT-MODEL-106（EV-0623）验证轮换先插新后停旧、旧密文不变/仍可解密、HMAC fingerprint 去重；`M04VerifierGatewaySecurityTest.ac06_ac07` 独立验证恢复审计与审计查询。残余：真实并发在途流未覆盖（F-006）。                                                                                                          |
| M04-AC-08 | **pass**                  | E2E-M04-05（EV-0624 第 25 条、EV-0627 第 11 条）验证限额/用量/`+08:00` 重置时间、429 文案、租户只读、拒绝审计；IT-QUOTA-101/105（EV-0623）验证日/月/用户限额与在途 run 不受调低影响。                                                                                                                                                                                                  |
| M04-AC-09 | **pass（integration）**   | IT-QUOTA-102（EV-0623）50 并发恰好 1 成功/49 个 429、无 5xx、`used_count=1`；配额性能门槛由 V2 `PERF-M04-002`。残余：调高限额后重试成功无单独断言（F-007）。                                                                                                                                                                                                                           |
| M04-AC-10 | **pass**                  | E2E-M04-05（EV-0624/0627）验证租户只读、平台仅聚合、员工无入口；IT-QUOTA-103（EV-0623）验证跨租户 404、平台无用户明细、配额变更前后值审计。                                                                                                                                                                                                                                            |
| M04-AC-11 | **pass**                  | E2E-M04-06（EV-0624 第 26 条、EV-0627 第 12 条）验证 6 位快照/2 位展示、未定价/用量未知/未分桶单列、免责文案、明细快照展开；IT-COST-101/102/103（EV-0623）验证公式、历史不改写、Asia/Shanghai 日桶。                                                                                                                                                                                   |
| M04-AC-12 | **pass**                  | IT-COST-104（EV-0623 `CostAdminApiTest` 3 tests）验证平台无用户字段、租户明细、跨租户 404；E2E-M04-06 验证员工无入口/403 且不含金额。                                                                                                                                                                                                                                                  |
| M04-AC-13 | **pass**                  | E2E-M04-07a（EV-0624 第 30 条）两页五态/375px/键盘/axe/无写入口；E2E-M04-07b（EV-0624 第 27 条）四页五态/中文引导/空态/错误态/403/成功态/掩码；真实栈四页（EV-0627 第 13 条）通过。                                                                                                                                                                                                    |
| M04-AC-14 | **pass（with residual）** | EV-0625 15/15 a11y；E2E-M04-07a/07b 覆盖三条键盘路径、375px 无横向滚动、axe 无 serious/critical、中文文案、无密钥；真实栈四页测试通过。残余：“焦点可见”只断言 `toBeFocused`，未断言焦点指示样式（F-009）。                                                                                                                                                                             |
| M04-AC-15 | **fail**                  | 端点标签/不可写/生产拒绝演示端点/非允许列表 400 等由 `M04VerifierGatewaySecurityTest.ac15`、`M04VerifierProductionEndpointTest`、`ProductionEndpointPolicyApiTest`、`SEC-M04-001` 矩阵覆盖并通过；但 **API 日志泄露 Worker detail 中的 key 明文与内部 baseUrl**（F-001），直接违反本 AC 的“日志”要求。                                                                                 |
| M04-AC-16 | **pass**                  | E2E-M04-08（EV-0624 第 31 条）J1/J2/M03/M10 回归 + 运行详情模型标识；IT-MODEL-108a/108b（EV-0623）三模型端点/配额/成本契约；EV-0627 中 J1/J2/M03/M10 真实栈用例 24 条通过（仅 M04-02 失败），验证历史调用仍可读。                                                                                                                                                                      |

**AC-02 模型/key/开关段（V1 只提供证据，不判整条）**

- 模型/key 写越权：`SEC-M04-003a` / `TEN-031` 由 `M04VerifierGatewaySecurityTest.ten031` 独立覆盖（租户管理员/员工读写路由、模型启停、端点目录、平台 key 一律 403；跨 provider 凭据 404；响应不含链/地址/掩码），EV-0623。
- 路由审计与审计 fail-closed：IT-MODEL-102（EV-0623）覆盖。
- **开关段：fail**。契约、`docs/ARCHITECTURE.md:296`、`docs/RUNBOOK.md:422/571`、`scripts/ops/quota-drill.mjs:391/405` 都要求 `/api/v1/admin/model-gateway/switches`，但 `apps/api/src/main` 无 controller（F-002）。因此平台管理员无法读/写开关，租户管理员/员工的 403 语义也无从成立；B10 的 quota false→true 演练无法按 RUNBOOK 执行。

## 专项核对

| 项                        | 结果                                | 证据                                                                                                                                                                                                              |
| ------------------------- | ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SEC-M04-001/002`         | pass                                | `ManagedEndpointPolicyTest`（platform-common 87 tests，EV-0623）、`ProductionEndpointPolicyApiTest` 2 tests、`M04VerifierProductionEndpointTest`                                                                  |
| `SEC-M04-004`             | pass                                | IT-MODEL-101 拒绝非允许列表 provider（EV-0623）；Worker BASELINE/ROUTED 均走端点守卫（IT-M04-13/14）                                                                                                              |
| `SEC-M04-005`             | **fail**                            | 响应/列表/审计/DB 无明文；**日志/证据扫描命中**（F-001、`canary-scan.log`）                                                                                                                                       |
| `SEC-M04-006/007/008/009` | pass（008 有 P2 残余）              | SEC-M04-006/007 EV-0623；SEC-M04-008 IT-MODEL-106 + E2E-M04-04；SEC-M04-009 EV-0623 + EV-0624                                                                                                                     |
| `IT-MODEL-101..109`       | pass（108a/108b 契约 pass）         | EV-0623：`ModelRoutingApiTest`、`ModelRoutingAuditSecurityTest`、`ModelFailoverIntegrationTest`（17）、`ModelCredentialLifecycleApiTest`（5）、`ModelContract108a/108b`、`V11MigrationTest`（16）均 0 skip/0 fail |
| `E2E-M04-01..04/08`       | 01/04/08 pass；02 **fail**；03 pass | EV-0624（01/04/08）；EV-0627（02 失败、03 通过）                                                                                                                                                                  |
| E2E-M04-07a/07b           | pass                                | EV-0624、EV-0625、EV-0627 第 13 条                                                                                                                                                                                |
| V11 结构                  | pass                                | `M04VerifierGatewaySecurityTest.v11StructureAndQuotaInsertRollback` 独立断言无 provider FK、成本列无 CHECK、配额 PK 顺序、六表存在、事务内 INSERT；`V11MigrationTest` 16 tests（EV-0623）覆盖 forward/rollback    |
| TM-39/40/41/42/43/49      | pass                                | 路由/降级/多 key/审计白名单证据均在本轮 EV-0623/0624/0627                                                                                                                                                         |
| TM-44                     | partial（accepted）                 | 429 固定 60s 冷却、未读 Retry-After，沿用 REV-TASK-0105-003 F-002 accepted                                                                                                                                        |
| TM-50                     | **fail**                            | 开关 API 缺失（F-002）；DB/ENV 生效读路径由 IT-MODEL-109 覆盖                                                                                                                                                     |
| TM-53                     | **fail**                            | 日志泄露（F-001）                                                                                                                                                                                                 |
| B-18                      | **不能关闭**                        | F-001（凭据明文入日志）+ F-002（开关端点缺失）                                                                                                                                                                    |
| B-20（Worker 7 指标）     | pass                                | IT-M04-08 注册 7 指标（EV-0623）；`metrics-check` self-test 由 TASK-0112 EV-0606 覆盖；API 2 主指标归 V2                                                                                                          |

## Findings

### F-001（P1，open）API 日志打印 Worker detail 原文

- 位置：`apps/api/src/main/java/com/riccon/api/chat/service/ChatService.java:658-662`。
- 观察值：`chat.worker.rejected status=500 code=RICCON-MODEL-503 detail=model route is empty; provider=mock-openai baseUrl=http://10.9.8.7:8080/v1 apiKey=sk-live-0123456789` 出现在 WARN 日志；命中位置 `apps/api/target/surefire-reports/TEST-...ChatModelUnavailableApiTest.xml:773`、EV-0623 日志第 1623 行、`canary-scan.log` 第 6/8 行。
- 期望值：AC-15 / SEC-M04-005 / TM-53 要求日志不出现 key 明文、Authorization、内部地址或堆栈。
- 影响：Worker 是内部服务，但其 detail 是不可信文本；一旦上游错误把凭据/地址带入 detail，API 会原样落日志；本轮测试 canary 已证明该路径。
- 建议修复：日志只记录 `status`/`code` 或脱敏后的长度/摘要；增加“捕获日志并断言 canary 不出现”的回归测试；重跑 `canary-scan.log`。`ChatService.java:720` 的 `LOG.warn(..., exception)` 也建议只记录异常类型/错误码，避免堆栈/上游内容。

### F-002（P1，open）开关端点缺失

- 契约：`contracts/openapi.yaml:3041-3095` 定义 GET/PUT `/api/v1/admin/model-gateway/switches`（`platform.model.read`/`platform.model.manage`，409 `ENV_OVERRIDE_ACTIVE`，审计 `model.gateway.switch.changed`）。
- 文档/脚本：`docs/ARCHITECTURE.md:296`、`docs/RUNBOOK.md:422`（“GET ... 可读”）、`:571`（quota-drill 命令）、`scripts/ops/quota-drill.mjs:391/405`。
- 观察值：`grep -rn "model-gateway" apps/api/src/main/java` 无结果；`find apps/api/src/main/java -iname "*gateway*"` 无结果。Spring 无映射时返回 404。
- 影响：AC-02 开关段（V2）无法验证平台读/写与租户/员工 403；AC-18 的开关切换只能依赖环境变量/DB；B10 的 quota false→true 演练无法按 RUNBOOK 执行；TASK-0112 的对应验收没有可执行证据。
- 建议：实现 controller + service（复用 Worker `ModelGatewaySwitchService` 语义/审计），补运行时契约测试（200/403/409/审计 fail-closed），再重跑 quota-drill。

### F-003（P1，open）E2E-M04-02 全量真实栈失败与 B11b 假绿

- 位置：`e2e/real/tests/m04-quota-cost.spec.ts:496-540`（B11b 四页 a11y 的路由恢复）与 `e2e/real/tests/m04-routing.spec.ts:63-92`（E2E-M04-02）。
- 观察值：EV-0627 中 `E2E-M04-02` 失败，run detail 只有 `fake-chat-primary` 成功；`real-stack-worker.log:82-85` 显示 `fake-secondary` 连续两次失败后由 primary 成功（降级本身发生）。DB：CHAT 链在 E2E-M04-02 前为 `[secondary, primary, disabled]`（seed 是 `[primary, secondary, disabled]`）；`audit_event` 只有 2 条 `model.routing.updated`，版本从 1 到 3，证明 a11y 测试实际只保存了一次，第二次“恢复”没有落库。
- 根因：a11y 测试的恢复步骤用 `expect(page.getByText(/已保存对话链（版本 v/))` 命中第一次保存后仍可见的成功提示，第二次保存即使没有发生也能通过；且 `上移` 的 `.first()` 可能落在不可用按钮上。
- 影响：AC-03 的合同 E2E 证据红；全量真实栈 suite exit 1；后续 checks.mjs 也受链顺序影响。
- 建议：E2E-M04-02 自己通过 API 显式设置 `[primary, secondary]` 链（不依赖前序测试）；a11y 恢复步骤断言版本号精确递增/清空提示/用 API 核对顺序；修复后重跑全量真实栈，不使用 grep 定向。

### F-004（P2，open）checks.mjs 的 J1 run 选择错误

- 位置：`scripts/real-stack/checks.mjs:309-324`。
- 观察值：checks 取 `tenant-alpha` 最新 CHAT run，M04 浏览器用例后置时该 run 属于 M04（含 FAILED invocation），`j1ChatInvocation.status` 为 `FAILED`，断言 `SUCCEEDED` 失败；EV-0627 `securityChecks/m04Checks: failed`。
- 影响：全量真实栈 suite 稳定失败；不是产品回归（`real-stack-j2`、J1 用例均 passed）。
- 建议：按 seed 的 J1 会话/消息或固定时间窗选择 run，或在浏览器 M04 用例前执行 checks；不要用“最新 run”。

### F-005（P2，open）5 条 dirty EV 阻断 gate

- `gate check --module M04` 报 `EV-0577, EV-0579, EV-0580, EV-0597, EV-0598` 来自 dirty 工作树。
- 影响：即使 P1 修复，gate 仍失败；owner Lead 需重录或移除这些 EV，或记录书面接受（但 dirty EV 不能建立 done）。

### F-006（P2，open）AC-07 真实在途轮换未覆盖

- `IT-MODEL-106` 只模拟“轮换前取得密文快照，轮换后仍可解密”；E2E-M04-04 只验证页面影响说明与掩码。没有真实并发流式请求在轮换期间完成/可重试且不重复输出的用例。
- 影响：AC-07 的“在途请求要么正常完成，要么以可重试错误结束且不重复输出”仍是设计推断。
- 建议：B5/B11a 增加一条并发流式 + rotate 的真实栈或集成用例，或由 Lead 书面接受为残余。

### F-007..F-012（P3）

- F-007 AC-09：调高限额后被拒绝请求重试成功无单独用例（IT-QUOTA-105 已证明限额变更立即生效，风险低）。
- F-008 AC-01：没有“UI 保存后下一次 CHAT 请求”的单条跨组件 E2E；当前由 IT-MODEL-101 + worker 路由测试 + E2E-M04-01 组合推断。
- F-009 AC-14：焦点可见只断言 `toBeFocused`，没有 computed outline/box-shadow 断言。
- F-010：3xx 重定向无直接负向 worker 用例；代码 `HttpClient.Redirect.NEVER` 与端点策略存在。
- F-011：`e272fff` 把 `.riccon/evidence/TASK-0024/real-report.json` 覆盖为一次 `--list` 结果（与 M02 历史证据不一致），建议恢复或注明废弃。
- F-012：AC-05 未断言已上传文件在模型全链失败后保留。

## 未验证 / 不声称

- **AC-02 整条、AC-17、AC-18 不判**；AC-02 只提供模型/key/开关段证据，且开关段因 F-002 失败。
- **性能** `PERF-M04-001/002`、**迁移/回滚/备份恢复** `OPS-M04-01`、**API 2 主指标 + 3 辅助指标** `OPS-M04-02b` 归 V2；本轮未运行 k6、未在一次性 MySQL 重放回滚/备份、未抓取 API Prometheus。
- **真实栈全量未通过**（EV-0627 exit 1）：AC-03 不判 pass；AC-04/05/07/13/16 使用其中已通过的具体用例，但模块 gate 需修复 F-003/F-004 后重跑全量。
- 本轮未修改生产源码、迁移、契约、`e2e/**`；只新增 `apps/api/src/test/java/com/riccon/api/security/m04/**` 与 `.riccon/**` 证据/报告。
- 未做真实设备/移动端人工 UX、生产环境、真实供应商账单验证；这些不在 V1 范围。

## 模块 gate 建议

**当前不能通过。** `gate check --module M04` 仍为 FAIL，原因包括：

1. TASK-0115 `in_progress`、TASK-0116 `planned`（预期）；
2. 5 条 dirty EV（F-005）；
3. 无模块 review（需 V2/Lead 在最终提交写 `.riccon/reviews/M04-review.md`）；
4. 本报告的 P1 必须修复并重新取证：F-001（日志）、F-002（开关端点）、F-003（真实栈 E2E 假绿/顺序）。

建议 Lead 以 `kind: fix` 建立修复任务（可合并到 B11b/B2/B4 之后），修复后由 V2 在最终提交重跑合同 test/coverage/E2E/a11y/security + 全量真实栈；V1 的 13 条 pass 可复用但需引用 V2 的新 EV。
