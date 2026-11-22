---
review_id: REV-TASK-0105-001
module_id: M04
task_id: TASK-0105
reviewer:
  agent: riccon/verifier
  session: ses_e053898b1ffeLLwGmyauUPugpC
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - d2a815b50616e85e0b23dc88eb596fee0871c1c0
  - ef02ff485dd52243bba0aba3ff4461ff6e6694c1
head_commit: f97674e85e10585c691acb6631e90628ba5ce27b
task_end_commit: d2a815b50616e85e0b23dc88eb596fee0871c1c0
evidence_commit: ef02ff485dd52243bba0aba3ff4461ff6e6694c1
evidence:
  - EV-0550
  - EV-0552
reviewed_evidence:
  - EV-0550
  - EV-0552
findings:
  - id: F-001
    severity: P1
    status: open
    owner: "riccon/builder-failover + riccon/architect + riccon/lead（scope/ADR）"
    due: "M04 gate 前"
    title: "ROUTED 空链按 updated_by IS NULL 回退 M02 单模型：掩盖空链并可能路由到链外模型"
    summary: >-
      ModelRouteResolver 在 `model_routing_entry` 查询结果为空且 `model_routing.updated_by IS NULL` 时
      回退到 `baselineChain()`（最早 ACTIVE 模型）。ADR-0021 §4.1 第 5 步与 §5.6 要求 ROUTED 全链为空
      直接 `RICCON-MODEL-503`、不得切换到链外模型；ADR 也没有授权这个运行时兼容分支。V11 只为 CHAT/REPORT
      播种 `model_routing` 行（updated_by 保持 NULL），条目只在迁移时存在 ACTIVE 模型才播种；因此该分支
      在真实迁移状态可达：迁移时无 ACTIVE 模型、之后新增模型；或播种模型在管理员首次保存链之前被停用。
      此时 API GET 显示空链/停用模型，Worker 却按 M02 选另一个模型，既不返回 503，也不在链内执行。
      管理员保存过链后 updated_by 非空，空链 503 的测试（IT-M04-06）是真实通过的；但该测试用
      `markChainConfigured(true)` 跳过了未保存状态，未覆盖上述可达路径。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelRouteResolver.java:116-125；
      apps/worker/src/main/java/com/riccon/worker/model/persistence/ModelRoutingJdbc.java:94-101；
      apps/api/src/main/resources/db/migration/V11__model_gateway_depth.sql:365-383；
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:289-317；
      apps/worker/src/test/java/com/riccon/worker/support/TestDataFactory.java:536（既有 J1/J2 夹具不播种路由链，
      当前依赖该回退）；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §4.1 第 5 步、§5.6、§8.3 B4 验收“无链外回退”。
  - id: F-002
    severity: P2
    status: open
    owner: "riccon/builder-failover"
    due: "M04 gate 前"
    title: "429 未读取 Retry-After，冷却固定 60 秒"
    summary: >-
      ADR-0021 §5.1 要求 429 的 `cooldown_until=now+min(Retry-After,300s)`，未解析时默认 60 秒。
      `OpenAiCompatibleChatModel.translateStatus` 只按状态码建异常，没有读取 `HttpResponse` 的
      `Retry-After`；`CredentialHealthService.rateLimited` 恒写 60 秒。对要求 300 秒冷却的上游，
      Worker 会在 60 秒后重新打到同一 key，可能延长限流并放大降级。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/OpenAiCompatibleChatModel.java:584-616；
      apps/worker/src/main/java/com/riccon/worker/model/service/CredentialHealthService.java:154-158；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §5.1 429 行。
  - id: F-003
    severity: P2
    status: open
    owner: "riccon/builder-failover"
    due: "M04 gate 前"
    title: "key 成功后不重置 consecutive_failures，连续失败语义失真"
    summary: >-
      `onSuccess` 只更新 `last_used_at`，不清零 `consecutive_failures`；`UPDATE_FAILURE` 用
      `consecutive_failures + 1 >= 3` 决定冷却。一个 key 在历史上累计 3 次失败后，即使其后长期成功，
      下一次失败也会立即触发 30 秒冷却，不再是“连续”失败。ADR §3.2 列定义为“连续可重试失败次数”，
      §5.1 使用该语义；现有 IT-M04-09 只验证累计到阈值，不验证成功后的重置。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/CredentialHealthService.java:105-114；
      apps/worker/src/main/java/com/riccon/worker/model/repository/ModelRepository.java:147-149；
      apps/worker/src/main/java/com/riccon/worker/model/persistence/CredentialJdbc.java:66-77、108-114；
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:409-433。
  - id: F-004
    severity: P2
    status: open
    owner: "riccon/builder-failover"
    due: "M04 gate 前"
    title: "外层降级 Flux 未传播取消，客户端断开后上游读循环可能继续到下一帧/超时"
    summary: >-
      `FailoverChatModel.stream` 没有注册 `sink.onCancel`/`onDispose`；`runAttempt` 只有在下一条响应到达后
      才检查 `sink.isCancelled()`，且 `responses.toIterable()` 的内部订阅在生成器返回时不会被释放。
      因此取消不会像内层 `OpenAiCompatibleChatModel` 那样立即关闭 `InputStream`，要等下一帧或
      first-token timeout（生产 30 秒）。这违反 ADR §5.4“取消/断开时关闭当前 InputStream”和
      CODING-STANDARDS §13“客户端断开后停止上游模型调用”；现有 `cancellingAfterFirstIncrementStopsStreamPromptly`
      只测内层客户端，没有覆盖降级包装。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/FailoverChatModel.java:193-197、360-419；
      apps/worker/src/main/java/com/riccon/worker/model/service/OpenAiCompatibleChatModel.java:137-139；
      apps/worker/src/test/java/com/riccon/worker/model/OpenAiCompatibleChatModelStreamingTest.java:128-141；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §5.4。
  - id: F-005
    severity: P2
    status: open
    owner: "riccon/builder-failover"
    due: "M04 gate 前"
    title: "同一 provider 多模型共享 key 池时，已 INVALID/冷却的 key 仍可能在本次请求内被再次尝试"
    summary: >-
      resolver 为每个链条目独立读取 `findPlatformPool(providerId)`，`flatten` 会把同一 credential 放进多个
      模型的候选；`runAttempt` 不复查 credential 的 DB 状态。场景：A、B 同 provider 且只有 key K；
      A/K 返回 401 后将 K 置 INVALID，下一次候选 B/K 仍会发起 HTTP。虽然 `invalidate` 因 0 行不再重复审计，
      但会浪费尝试预算（默认 3 次）并可能与“401/403 自动失效后 fail-closed”的语义冲突。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelRouteResolver.java:145-154；
      apps/worker/src/main/java/com/riccon/worker/model/service/FailoverChatModel.java:360-375、465-473；
      apps/worker/src/main/java/com/riccon/worker/model/service/CredentialHealthService.java:123-139；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §5.1、§6.4。
  - id: F-006
    severity: P2
    status: open
    owner: "riccon/builder-failover + riccon/builder-cost（B7）"
    due: "M04 gate 前"
    title: "报表 TOOL/GENERATION 的 model_invocation 不记录 first_token_ms（首字后失败与成功都为空）"
    summary: >-
      `AgentRunRequestedHandler.failInvocation` 和 `AgentRunResumeHandler.failInvocation` 固定传
      `firstTokenMs=null`；`AgentRunResumeHandler` 成功收尾也传 null。当报表生成阶段已经输出文本
      （`model.emitted()=true`）后失败时，ADR §5.3 要求 `model_invocation.failed.first_token_ms` 非空，
      当前只有 attempt 行有值。Chat 路径 `ChatStreamService.fail/failStream` 已正确传 firstTokenMs；
      J2 生成阶段尚未做到，B7 会继续改这些文件，需在本任务或 B7 明确收口。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java:432-446；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java:606-620、429-438；
      apps/worker/src/main/java/com/riccon/worker/chat/service/ChatStreamService.java:365-396；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §5.3。
  - id: F-007
    severity: P2
    status: open
    owner: "riccon/builder-failover + riccon/builder-route（B1a policy）/ riccon/lead"
    due: "M04 gate 前"
    title: "链空失败审计缺少 ADR 要求的 details.reason=CHAIN_EMPTY"
    summary: >-
      ADR §4.1 第 5 步要求链空时写 `model.invocation.failed` 且 `details.reason=CHAIN_EMPTY`；
      `AuditService.modelUnavailable` 只写 durationMs/errorCode/retryable/emitted，并以 purpose 作为
      targetId。`AuditDetailsPolicy` 对 `model.invocation.failed` 未允许 `reason`，B4 写入范围也不含该 policy
      （属 B1a），所以 builder 的注释选择“不新增键”。结果是平台管理员无法按 ADR 语义筛出链空失败；
      指标 `chain_empty` 仍存在，但审计证据不完整。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/audit/service/AuditService.java:185-203；
      apps/worker/src/main/java/com/riccon/worker/audit/domain/AuditDetailsPolicy.java:27-29；
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelRouteResolver.java:194-212；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §4.1 第 5 步。
  - id: F-008
    severity: P2
    status: open
    owner: "riccon/builder-gateway-e2e（B11a）/ riccon/builder-route（B2）/ riccon/lead"
    due: "M04 gate 前"
    title: "API 代理丢弃 Worker 的 RICCON-MODEL-503 与中文文案，员工实际收到 RICCON-CHAT-500"
    summary: >-
      Worker 全链失败时返回 HTTP 500 + `code=RICCON-MODEL-503` + 固定中文文案
      （ChatStreamIntegrationTest 明确断言 500）。API `ChatService.mapWorkerCall` 只按 HTTP 状态映射，
      Worker 500 走 default，员工看到 `RICCON-CHAT-500`/“chat upstream failed”，不是 M04-AC-05 要求的
      稳定模型错误码与“模型服务暂不可用，请稍后重试。”。这不是 B4 写范围内的代码（API 不在 scope），
      但 AC-05 的模块级结果不会通过；必须由 B11a/B2/Lead 在浏览器 E2E 之前补映射，或明确改变
      Worker/API 的内部状态契约。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/chat/service/ChatStreamService.java:214-226；
      apps/worker/src/test/java/com/riccon/worker/chat/ChatStreamIntegrationTest.java:148-167；
      apps/api/src/main/java/com/riccon/api/chat/service/ChatService.java:640-665；
      apps/api/src/main/java/com/riccon/api/error/ErrorCodes.java:71、110；
      docs/PRODUCT.md M04-AC-05；ADR-0021 §7.3。
  - id: F-009
    severity: P3
    status: open
    owner: "riccon/builder-failover + riccon/verifier（V1）"
    due: "M04 gate 前"
    title: "8 类失败矩阵与 BASELINE allowlist 的测试证据不完整"
    summary: >-
      `IT-MODEL-103`（§11.1 只列 429/5xx/401/超时）已真实覆盖；但任务验收写的是“8 类失败矩阵”，
      当前没有独立用例覆盖 403、3xx、INVALID_RESPONSE、CONFIGURATION/解密失败、首字后空闲超时。
      `switches_baseline_usesEarliestActiveModel` 只证明选最早 ACTIVE 模型；测试 profile
      `allow-legacy-endpoints=true` 会跳过 allowlist，BASELINE 的“仍走 allowlist”没有集成负例。
      代码路径存在且与 ROUTED 共用 ModelEndpointGuard，V1 需用 SEC-M04-004/IT-MODEL-109 补齐。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:91-282；
      apps/worker/src/test/resources/application-test.yml:17；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §11.1 IT-MODEL-103/109；
      TASK-0105 acceptance 第 2、4 条。
  - id: F-010
    severity: P3
    status: open
    owner: "riccon/lead"
    due: "TASK-0105 closeout / M04 gate"
    title: "任务证据列表仍含失败的 EV-0551；gate 将 TASK-0121 的测试基础设施提交算作 TASK-0105 越界"
    summary: >-
      EV-0551 是 exit 1 的全量（e527990，失败在 API quota/M10 用例），不能作为通过证据；EV-0552 是
      ef02ff4 上 exit 0 的全量（1026 tests、0 skipped、worker branch 70.66%），覆盖 B4 代码。任务 JSON 仍列
      EV-0550/EV-0551，Lead 应把 EV-0552 补入 reviewed evidence 或注明 EV-0551 为历史失败。另：
      `gate check --module M04` 把 ef02ff4 改的 application-test.yml/WorkerIntegrationTestBase 报为
      TASK-0105 越界；B4 提交 `git diff --name-only d2a815b^ d2a815b` 全部落在 scope 内，这是 gate
      baseline..HEAD 区间问题，不是 B4 代码越界。
    evidence: >-
      .riccon/evidence/TASK-0105/EV-0551.json（exit_code=1）；
      .riccon/evidence/TASK-0121/EV-0552.json（exit_code=0，commit ef02ff4）；
      node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M04（2026-11-21T05:16+08:00，exit 1，
      TASK-0105 out-of-scope 三文件）；
      git diff --name-only d2a815b^ d2a815b。
  - id: F-011
    severity: P3
    status: open
    owner: "riccon/builder-failover"
    due: "M04 gate / OPS-M04-02a"
    title: "credential_cooldown_total 的近似计数语义未说明，阈值并发下可能多计"
    summary: >-
      `recordFailure` 先读 `current` 再无条件 `recordCredentialFailure`，只有 `current+1>=3` 时计数；
      并发请求可能都读到 2 并各自 +1 冷却指标；429 每次都会 +1，即使只是延长同一 key 的冷却。
      该指标实际上是“冷却事件近似次数”，不是唯一 key 数或冷却区间数，但类注释/ADR 未说明。
      OPS-M04-02a 若按精确值告警会误判，需要在指标注释/RUNBOOK 写明近似口径。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelMetrics.java:149-159；
      apps/worker/src/main/java/com/riccon/worker/model/service/CredentialHealthService.java:154-171；
      apps/worker/src/main/java/com/riccon/worker/model/persistence/CredentialJdbc.java:66-87。
notes: >-
  任务级独立评审，不替代 M04 模块评审、Product 预验收或用户验收；未修改任务状态，未把 TASK-0105 标 done。
  本次复跑了 B4 定向套件（ModelFailoverIntegrationTest、ModelAttemptBudgetIntegrationTest、ModelMetricsTest、
  ModelGatewaySwitchPropertiesTest，23 tests、0 failure/error/skip、exit 0）；未重复跑全量，EV-0552 是 ef02ff4 上的
  全量证据且其后提交未改 apps/platform 代码。M04 gate 当前仍失败（TASK-0105 review、其他 planned 任务、无模块 review、
  本 review 的 P1），属模块未完成状态。
created_at: "2026-11-21T05:20:00+08:00"
---

# REV-TASK-0105-001：TASK-0105 / M04-B4 独立评审

## 结论

**任务级 fail**。B4 的主干实现（平台 key 池、同 key 仅 5xx/429 重试、尝试上限/预算、首字后不重试不重放、
7 个指标、401/403 的 platform/SYSTEM 审计）有真实代码和测试；但 **F-001（P1）**：ROUTED 空链在
`updated_by IS NULL` 时静默回退 M02 单模型，可能掩盖空链并路由到链外模型，违反 ADR-0021 §4.1/§5.6
与任务验收“无链外回退”。P1 未修复前不能 closeout；其余 P2/P3 不推翻测试结论，但需在模块 gate 前处理。

## 验证范围与方法

- 读 `TASK-0105.json`、ADR-0021 §4/§5/§6/§7.4/§10.6/§11.1、CODING-STANDARDS §10–§12、B5/B6 评审、
  `git show --stat d2a815b`、`git diff d2a815b^..d2a815b`、EV-0550/EV-0551/EV-0552。
- 写范围：`d2a815b` 的 50 个文件全部落在任务 scope（model/chat/agent/两个 Handler/AuditService/
  ReportArtifactIntegrationTest 等）；`git diff --name-only d2a815b^ d2a815b` 未发现 API、contracts、
  Flyway 迁移或构建配置改动。B4 未改其他任务测试；`ReportArtifactIntegrationTest` 的 429→400+503
  是同一任务显式 scope 内改动（见下文）。
- 独立复跑：`./mvnw -q -T1C -pl apps/worker -am test -Dtest='ModelFailoverIntegrationTest,
ModelAttemptBudgetIntegrationTest,ModelMetricsTest,ModelGatewaySwitchPropertiesTest'
-Dsurefire.failIfNoSpecifiedTests=false` → **23 tests、0 failures、0 errors、0 skipped、exit 0**。
- 证据核对：EV-0550（d2a815b、dirty=false、exit 0、sha256/bytes 与记录一致）；EV-0552
  （ef02ff4、dirty=false、exit 0、全量 1026 tests、0 skipped；worker line 87.69%、branch 70.66%）。
  EV-0551 为 e527990 上 exit 1 的历史全量，不作为通过证据。
- `riccon gate check --module M04`（05:16+08:00）exit 1：预期包含 TASK-0105 review、其他 planned 任务、
  无模块 review；其 TASK-0105 out-of-scope 三文件来自 TASK-0121 的 ef02ff4，不是 B4 代码越界（F-010）。

## 逐条验收

| #   | 任务验收                                             | 结果                               | 证据与说明                                                                                                                                                                                                                                                                          |
| --- | ---------------------------------------------------- | ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | 链路解析 + 内层 `maxAttempts=1` + 尝试顺序/上限/预算 | pass（正常链）                     | `ModelRoutingJdbc.SELECT_ENTRIES` 按 position、ACTIVE、OPENAI_COMPATIBLE 过滤；resolver 再过滤端点/平台池；`flatten` 模型×key 顺序；`ModelRoutingProperties` 1..5；application.yml 内层 max-attempts=1；IT-MODEL-103/IT-M04-01/02、IT-M04-10 与本次复跑通过。兼容回退分支见 F-001。 |
| 2   | 8 类失败矩阵、首字后不重试不重放                     | partial                            | 已测 429/5xx/401/超时；5xx/429 同 key 一次退避后换候选；首字后断流 `emitted=true`、attempt=`FAILED`、无第二请求（IT-MODEL-104、ChatStreamIntegrationTest）；403/3xx/INVALID_RESPONSE/CONFIGURATION/首字后超时无独立用例（F-009）。                                                  |
| 3   | 尝试表/审计（`emitted`、SYSTEM/platform）            | pass（含 F-006/F-007）             | 每尝试一行 `model_invocation_attempt`；401 后 `model.credential.invalidated` 为 tenant=platform、actor=SYSTEM（IT-MODEL-105）；`model.invocation.failed` 含 `emitted`（ChatStreamIntegrationTest）；链空审计缺 `reason=CHAIN_EMPTY`（F-007）。                                      |
| 4   | 开关回退 BASELINE 且仍走 allowlist、无链外回退       | **fail**                           | BASELINE 选最早 ACTIVE 模型的用例通过，resolver 对 BASELINE 与 ROUTED 共用 `ModelEndpointGuard`；但 F-001 的未配置空链回退会在 ROUTED 状态选链外模型，直接违反“无链外回退”。                                                                                                        |
| 5   | BYO 回退移除 + 注册 7 个路由/降级/key 指标           | pass（含 F-002/F-003/F-005/F-011） | `ModelRepository` 只查 `platform`，旧 tenant→platform 回退已删；租户级 key 被忽略（IT-M04-04）；7 指标名称/标签/占位与 `M04MetricsContractTest` 一致，无 tenant 标签；Retry-After、累计失败重置、同 provider key 复用、冷却近似值见 findings。                                      |
| 6   | J1/J2 回归                                           | pass                               | EV-0550 含 ChatStream/ReportArtifact/Hitl 等；EV-0552 全量 1026 tests、0 skipped；`ReportArtifactIntegrationTest` 的 outbox 重试用例在新 503 语义下仍通过。                                                                                                                         |

## 重点结论

### 1. 路由解析与兼容回退（F-001）

- `ModelRoutingJdbc` 的 SQL 已做 `m.status='ACTIVE'`、`p.status='ACTIVE'`、`OPENAI_COMPATIBLE` 与 position 排序；
  `ModelEndpointGuard` 复用 B2 的允许列表/URL/标签校验；`findPlatformPool` 过滤 ACTIVE + cooldown 并按
  `priority, created_at, id`；BASELINE 也走同一 guard 与平台池。路径本身符合 §4.1 第 1–4 步。
- 问题在解析结果的“空链”分支：`entries.isEmpty() && !configured(purpose)` 时调用 `baselineChain()`。
  `configured` 只看 `model_routing.updated_by` 是否非空，V11 迁移播种的行永远为 NULL；一旦播种模型被停用
  或迁移时没有 ACTIVE 模型，SQL 过滤后 entries 为空，Worker 就静默选另一个 ACTIVE 模型。ADR 没有此例外，
  且它与 AC-05“链中所有模型与 key 均不可用 → 503”以及 §5.6“不自动切换到链外模型”冲突。
- `resolve_configuredChainEmpty_returns503` 通过 `markChainConfigured(true)` 只覆盖“保存后空链”，
  不能证明未保存状态；现有 J1/J2 测试通过 `TestDataFactory.createModelCatalog` 不播种路由链，依赖该回退。
  修复建议：删除回退、让测试夹具播种 CHAT/REPORT 链；若产品确实需要迁移兼容，必须修订 ADR 并让
  API/Worker 对“未配置”状态有同一可见语义，同时补未保存/停用场景的测试。

### 2. 降级状态机

- 尝试顺序：`flatten` 按模型 position × key priority；同 key 仅 5xx/429 允许一次重试（`retriedKeys`）；
  每次尝试写 STARTED/终态并计数；`maxAttempts` 硬上限 1..5；首字前预算用 `min(firstTokenTimeout, 剩余预算)`；
  预算耗尽不发起下一次 HTTP（IT-M04-10 断言第三模型 requestCount=0）。
- 首字后：`emitted` 在首个非空 TextBlock 置位；失败走 `interrupted()` → 502/504 + 固定中文文案 + 保留已输出；
  不再进入候选循环；attempt=FALSE/FAILED、first_token_ms 非空、ChatStream 审计 emitted=true；测试与
  `ChatStreamIntegrationTest` 均覆盖。首字前 401 的 `onAttemptFailure` 在 emitted=false 分支执行，符合 §5.1。
- `failoverEnabled=false` 只尝试一次，且不写 failover 审计（IT-M04-05）。
- 取消：内层 `OpenAiCompatibleChatModel` 的 `onCancel/onDispose` 存在，但外层降级 Flux 未传播取消（F-004）。

### 3. BYO 移除与 key 健康

- `ModelRepository.findActiveCredential` 的“先查请求租户、再回退 platform”已删除；`findActivePlatformCredential`、
  `findPlatformPool` 都在 `PlatformTenant.ID` 作用域执行，查询带 `tenant_id='platform'`。IT-M04-04 用更低 priority
  的租户 key + 401 证明租户凭据不被使用，平台 key 成功。
- 401/403：`CredentialHealthService.invalidate` 先 `ACTIVE→INVALID` 条件更新，再写 platform/SYSTEM 审计；
  0 行（并发已失效）不重复审计；测试断言 tenant=platform、actor=SYSTEM 且 attempt/credential 引用保留。
- 429/5xx 的健康状态存在 F-002（Retry-After）、F-003（成功不重置）、F-005（同 provider key 跨模型复用）
  三处偏差；不影响本次已测的 401/5xx 主路径，但模块 gate 前应修。

### 4. 7 指标

- `ModelMetrics` 注册 `riccon_model_requests{provider,model,outcome}`、`riccon_model_failover{from,to,reason}`、
  `riccon_model_attempt_duration_seconds`（histogram，带 `_bucket` 预置）、
  `riccon_model_credential_invalid{provider}`、`riccon_model_credential_cooldown{provider}`、
  `riccon_model_chain_empty{purpose}`（CHAT/REPORT 两条 0 值）、`riccon_agent_runs_inflight`；
  `M04MetricsContractTest`/`metrics_exposeSevenFrozenSeries` 与本次复跑均通过，标签无 tenant/userId/runId。
- 预置 0 值通过构造时创建 placeholder 与 Timer 的 0 样本实现；`credential_cooldown_total` 实际是近似事件计数（F-011）。

### 5. 审计与敏感信息

- `modelFailoverTriggered` details 严格为 `purpose/fromModelId/toModelId/reason/attempt`，不含 credential ID、
  provider 地址或 key；`modelCredentialInvalidated` details 为 `providerId/credentialId/maskedHint/reason`，
  tenant=platform、actor=SYSTEM；`model.invocation.failed` 含 `emitted`；均通过 `AuditDetailsPolicy`。
- 代码/日志未发现 key、Authorization、baseUrl 或上游响应正文；`git show d2a815b` 密钥正则扫描无命中；
  `ApiKeyRoutingStub`/Fixture 的 `sk-*` 仅测试字面量，不是真实密钥。
- 链空审计缺 `reason=CHAIN_EMPTY`（F-007）；报表调用 first_token_ms 缺失（F-006）。

### 6. 写范围与 ReportArtifactIntegrationTest 改动

- B4 的 50 个文件全部在 `TASK-0105.write_scope`；未改 API/contracts/V11/构建配置。B5 的 BYO/401 收口项
  在 Worker 侧已完成（平台池查询 + 401 失效 + SYSTEM 审计），与 B5 评审 F-001 的交接一致。
- `ReportArtifactIntegrationTest` 从 `rateLimitNext(1)` 改为 `forceStatus(400)`：在新语义下 429 会在同一
  模型调用内被 FailoverChatModel 重试并可能成功，无法触发 outbox 重试；400 产生不可重试的
  INVALID_RESPONSE，单模型链耗尽后按 ADR §5.3 统一 503，`EventProcessingException.retryable` 为 true 才能
  验证 outbox 层重试。断言从 `RICCON-MODEL-429` 改为 `RICCON-MODEL-503` 与 ADR“上游 401/403/429 不直接
  暴露给员工、全链失败 503”一致，**不是掩盖**；第二次 poll 前 `stub.reset()` 使重试成功。该改动在任务
  scope 内且测试仍验证“只产生一个审批/产物、不重复执行工具”。
- 未发现改其他任务测试；`WorkerIntegrationTestBase`/application-test.yml 的改动来自 ef02ff4/TASK-0121，
  gate 的越界行是区间归属问题（F-010）。

### 7. 证据与覆盖率

- EV-0550：commit d2a815b、dirty=false、exit 0、输出 102,289 bytes/sha256 与记录一致；命令包含
  ModelFailover/AttemptBudget/ModelRegistry/ChatStream/ReportArtifact/Hitl/M04MetricsContract/AuditDetailsPolicy
  等，是合法的定向 L2 证据。
- EV-0552：commit ef02ff4、dirty=false、exit 0；surefire 聚合 **1026 tests、0 failures、0 errors、0 skipped**；
  worker line 87.69%、branch 70.66%（≥80/70）；ef02ff4 已包含 d2a815b，且 ef02ff4..HEAD 只改
  `.riccon`/任务状态，未改 apps/platform 代码，因此全量覆盖 B4。
- EV-0551 是 exit 1 的历史全量（API quota/M10 连接池失败），不能作为通过证据；任务 JSON 仍列它，
  Lead closeout 需补 EV-0552/注明（F-010）。

## 未验证

- 未在真实浏览器运行 E2E-M04-01..08、E2E-M04-07a/07b、a11y、k6 性能、Trivy 安全扫描、V11 迁移/回滚演练；
  这些属于 B11a/B11b/V1/V2/B10 的模块 gate 证据，本次任务级评审不替代。
- 未独立验证 403、3xx、非法 JSON/error payload、DEK 解密失败、首字后空闲超时的降级行为（F-009）。
- 未执行 M04 模块全量合同 build/lint/typecheck/E2E；EV-0552 是 Lead 在 ef02ff4 上的全量 verify，
  模块 review 仍需 V1/V2 在最终提交上独立执行并绑定 `dod_evidence`。
- 未对 API `ChatService.mapWorkerCall` 做端到端浏览器验证；F-008 是基于静态代码和 Worker 测试的结论。

## 剩余风险

- F-001 修复前，M04-AC-05 的“链中所有模型不可用 → 503”在未保存/迁移播种状态下不可靠。
- F-008 修复前，员工侧 AC-05 的稳定错误码与中文文案实际不可达（API 返回 RICCON-CHAT-500）。
- F-002/F-003/F-005 影响 key 健康与降级预算，F-004 影响取消资源释放，F-006/F-007 影响审计/运行详情完整性；
  都应在 M04 gate 前修复或由 Lead/Architect 书面接受。
- 模块 gate 继续失败（planned 任务、无模块 review），不是 B4 单任务可关闭的状态。

## 证据链接

- `.riccon/evidence/TASK-0105/EV-0550.json` 与 `.riccon/evidence/TASK-0105/EV-0550-*.log`
- `.riccon/evidence/TASK-0121/EV-0552.json` 与 `.riccon/evidence/TASK-0121/EV-0552-*.log`
- `.riccon/evidence/TASK-0105/EV-0551.json`（历史失败，不作为通过证据）
- 本次复跑日志：`/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/verify-TASK-0105-051259.log`
  （exploratory，已记入本文；未录 EV）
