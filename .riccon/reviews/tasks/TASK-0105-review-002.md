---
review_id: REV-TASK-0105-002
previous_review: REV-TASK-0105-001
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
  - e9c57ee80d6760c0bd92eba4547d7c874b525a43
head_commit: 0604c75048689ef3641a6dfd55ed603579b410eb
task_end_commit: e9c57ee80d6760c0bd92eba4547d7c874b525a43
evidence:
  - EV-0550
  - EV-0557
reviewed_evidence:
  - EV-0550
  - EV-0557
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: "riccon/builder-failover"
    title: "ROUTED 空链不再回退 M02 单模型；BASELINE 保留单模型且仍受 allowlist/key 池约束"
    summary: >-
      e9c57ee 删除 ModelRouteResolver 的 `updated_by`/`isChainConfigured` 兼容分支、ModelRoute.configured
      字段与 ModelRoutingJdbc/Repository 的 configured 查询；ROUTED 空条目、全部停用、端点不允许或无可用 key
      均走 chainEmpty 稳定 503，不读目录、不建 model_invocation、不发起 HTTP。TestDataFactory 在 CHAT/REPORT
      无条目时显式播种 position=1，J1/J2 不再依赖回退；BASELINE 仍走 findActiveCatalog + ModelEndpointGuard +
      platform key pool，空目录 503。IT-M04-12/13/14/11 与 EV-0557 通过。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelRouteResolver.java:107-149；
      apps/worker/src/main/java/com/riccon/worker/model/persistence/ModelRoutingJdbc.java:24-36；
      apps/worker/src/main/java/com/riccon/worker/model/domain/ModelRoute.java:8-20；
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:322-417；
      apps/worker/src/test/java/com/riccon/worker/support/TestDataFactory.java:586-612；
      grep 全仓 apps/worker/src/main 已无 isChainConfigured/configured( 回退调用；
      EV-0557（e9c57ee、dirty=false、exit 0、worker 336 tests、0 skip）。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/builder-failover（B10 观测）"
    due: "2026-12-04"
    title: "429 未读取 Retry-After，冷却固定 60 秒"
    summary: >-
      ADR §5.1 的默认值就是 60 秒，Retry-After 解析是优化而非正确性阻断；当前实现不会越过 ADR 默认冷却，
      且 provider 侧限流仍有 60 秒保护。按 Lead 建议作为 P2 accepted 跟踪；B10 在 RUNBOOK/告警中记录
      provider Retry-After 行为，后续再补解析。代码位置同 REV-TASK-0105-001 F-002。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/CredentialHealthService.java:154-158；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §5.1 429 行。
  - id: F-003
    severity: P2
    status: fixed
    owner: "riccon/builder-failover"
    title: "成功后重置 consecutive_failures，连续失败语义恢复"
    summary: >-
      e9c57ee 的 UPDATE_LAST_USED 同时清零 consecutive_failures 与 cooldown_until；IT-M04-11 先制造
      2 次 5xx 失败，再让成功调用后断言 consecutive_failures=0。F-003 已关闭。成功清除 429 冷却的
      并发语义另见 F-013（P3 accepted）。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/persistence/CredentialJdbc.java:108-121；
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:399-417。
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/builder-failover + riccon/verifier（V1）"
    due: "2026-12-04"
    title: "外层降级 Flux 未传播取消，客户端断开后可能等下一帧/超时"
    summary: >-
      已知残余：外层 FailoverChatModel 没有 sink.onCancel/onDispose，取消延迟受 first-token timeout
      （生产 30 秒）上限约束，不会重复输出或泄露内容。按 Lead 建议 P2 accepted；V1/B10 需在取消场景
      用真实客户端断开验证并登记。REV-TASK-0105-001 F-004 的代码证据仍适用。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/FailoverChatModel.java:193-197、360-419；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §5.4。
  - id: F-005
    severity: P2
    status: accepted
    owner: "riccon/builder-failover + riccon/verifier（V1）"
    due: "2026-12-04"
    title: "同一 provider 多模型共享 key 池时，已 INVALID 的 key 仍可能在本次请求内被再次尝试"
    summary: >-
      已知残余：route 快照按模型分别取 pool，同一 credential 可出现在多个模型候选；401 置 INVALID 后
      后续模型仍可能再发一次 HTTP。无安全泄露，尝试数受 maxAttempts（≤5）约束；按 Lead 建议 P2 accepted，
      V1 需补同 provider 多模型的 401/冷却负向用例。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelRouteResolver.java:121-139；
      apps/worker/src/main/java/com/riccon/worker/model/service/FailoverChatModel.java:360-375、465-473。
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/builder-failover + riccon/builder-cost（B7）"
    due: "B7 closeout"
    title: "报表 TOOL/GENERATION 的 model_invocation 不记录 first_token_ms"
    summary: >-
      已知残余：J2 生成阶段的 model_invocation.first_token_ms 仍为 null；attempt 明细已有每尝试
      first_token_ms，且 AC-04 的员工流式首字/中断语义由 ChatStreamService 正确落库。按 Lead 建议
      P2 accepted，由 B7 在成本/调用详情收口时补齐；V1 在 J2 生成失败用例中核对。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java:432-446；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java:606-620、429-438。
  - id: F-007
    severity: P2
    status: fixed
    owner: "riccon/builder-failover"
    title: "链空失败审计写入 details.reason=CHAIN_EMPTY"
    summary: >-
      AuditService.modelUnavailable 现在写 `reason=CHAIN_EMPTY`，Worker AuditDetailsPolicy 允许该键，
      IT-M04-06/IT-M04-12 断言审计 details 包含该 reason。F-007 的 Worker 侧已关闭；但 API 读取侧
      未同步，形成新的端到端回归 F-012。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/audit/service/AuditService.java:173-203；
      apps/worker/src/main/java/com/riccon/worker/audit/domain/AuditDetailsPolicy.java:27-31；
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:317-319、357-361。
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/builder-gateway-e2e（B11a）+ riccon/builder-route（B2）"
    due: "M04 gate 前（模块级仍必须关闭）"
    title: "API 代理尚未把 Worker 的 RICCON-MODEL-503/中文文案映射给员工"
    summary: >-
      任务级按 Lead 建议 accepted：这是 B11a/B2 的跨任务收口，不在 B4 写范围，不能在本任务修。
      M04-AC-05 要求员工看到稳定模型错误码与“模型服务暂不可用，请稍后重试。”，而 ChatService.mapWorkerCall
      仍按 HTTP 状态把 Worker 500 映射为 RICCON-CHAT-500；B11a/B2 必须在模块 E2E 前修正并补断言。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/chat/service/ChatService.java:640-665；
      apps/api/src/main/java/com/riccon/api/error/ErrorCodes.java:71、110；
      docs/PRODUCT.md M04-AC-05；REV-TASK-0105-001 F-008。
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/verifier（V1）"
    due: "M04 gate 前"
    title: "8 类失败矩阵与 BASELINE allowlist 的独立测试证据仍不完整"
    summary: >-
      按 Lead 建议 P3 accepted：IT-MODEL-103 已覆盖 429/5xx/401/超时；403、3xx、INVALID_RESPONSE、
      CONFIGURATION/解密失败、首字后空闲超时以及 BASELINE allowlist 负例由 V1 在模块安全测试中补齐。
      e9c57ee 未改变该测试范围。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:91-282；
      apps/worker/src/test/resources/application-test.yml:17；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §11.1 IT-MODEL-103/109。
  - id: F-010
    severity: P3
    status: fixed
    owner: "riccon/lead"
    title: "任务证据与 baseline/end 已改为 EV-0550/EV-0557、f97674e/e9c57ee"
    summary: >-
      0604c75 已把 EV-0551 替换为 EV-0557，baseline/end 改为 f97674e/e9c57ee；`git diff --name-only
      f97674e e9c57ee` 的 11 个文件全部在更新后的 TASK-0105 scope 内，未改 API/contracts/迁移。
      原 F-010 已关闭；最终提交缺少 full verify/coverage 的问题另见 F-014。
    evidence: >-
      .riccon/tasks/TASK-0105.json:19-20、30-33、43-44；
      .riccon/evidence/TASK-0105/EV-0557.json；
      git diff --name-only f97674e e9c57ee。
  - id: F-011
    severity: P3
    status: fixed
    owner: "riccon/builder-failover"
    title: "credential_cooldown_total 近似口径已写入代码注释"
    summary: >-
      ModelMetrics.credentialCooldown 的 Javadoc 已明确“冷却事件近似次数”，说明 429 每次延长冷却都会
      计数、并发阈值可能多计，告警按趋势而非精确值使用；F-011 关闭。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelMetrics.java:149-159。
  - id: F-012
    severity: P1
    status: open
    owner: "riccon/builder-route（B1a/B2）+ riccon/lead（scope）"
    due: "M04 gate 前"
    title: "API AuditDetailsPolicy 未同步 reason，M10 审计查询/导出遇到 CHAIN_EMPTY 事件会 500"
    summary: >-
      e9c57ee 让 Worker 在 `model.invocation.failed` 写 `details.reason=CHAIN_EMPTY`，并只改了 Worker
      AuditDetailsPolicy；API 侧 AuditDetailsPolicy 的 MODEL_INVOCATION_FAILED 白名单仍是
      modelId/durationMs/errorCode/retryable/emitted，没有 reason。API AuditDetailsReader 在读取时
      对同一白名单 fail-closed（AuditQueryService 逐条 toView），所以任何包含链空事件的 M10 审计查询
      或导出都会抛 AuditDetailsInvalidException → RICCON-AUDIT-500。M04-AC-16 要求 M10 审计零回归，
      这是修复引入的跨模块回归，必须同步 API policy 并补 API 审计查询用例。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:132-133；
      apps/api/src/main/java/com/riccon/api/audit/service/AuditDetailsReader.java:29-46；
      apps/api/src/main/java/com/riccon/api/audit/service/AuditQueryService.java:113-116、164-178；
      apps/worker/src/main/java/com/riccon/worker/audit/service/AuditService.java:192；
      复现（HEAD 编译类）：printf 'import com.riccon.api.audit.domain.AuditDetailsPolicy; ...' | jshell
      --class-path apps/api/target/classes:platform/platform-domain/target/classes →
      “REJECTED: audit detail key is not allowed for model.invocation.failed: reason”。
  - id: F-013
    severity: P3
    status: accepted
    owner: "riccon/builder-failover + riccon/architect"
    due: "2026-12-04"
    title: "成功调用清除 cooldown_until 可能抵消并发 429 设置的冷却"
    summary: >-
      成功路径 UPDATE_LAST_USED 现在同时把 cooldown_until 置 NULL；若同一 key 的并发请求刚因 429 设置
      冷却，另一个成功请求会提前解除。该行为不在 ADR §5.1 的成功定义中，但能避免健康 key 被历史冷却
      长期阻塞。按 P3 accepted 由 Architect/B4 在 V1 并发 429+成功用例后确认是否保留。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/model/persistence/CredentialJdbc.java:108-121；
      apps/worker/src/main/java/com/riccon/worker/model/service/CredentialHealthService.java:105-114；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §5.1、§5.5。
  - id: F-014
    severity: P2
    status: open
    owner: "riccon/lead + riccon/builder-failover"
    due: "M04 gate 前"
    title: "修复提交最终证据是 worker test，不是 full verify/coverage"
    summary: >-
      EV-0557 命令是 `-pl apps/worker -am test -Dtest='com.riccon.worker.**'`（worker 336 tests、0 skip），
      不是 `./mvnw verify`/coverage；EV-0552 的 full verify 在 ef02ff4（B4 修复前），不能证明 e9c57ee 的
      JaCoCo/ArchUnit/API 回归。TASK-0105 是 AI 路由高风险任务，按 CODING-STANDARDS §10.1 最终提交应跑
      一次受影响模块全量；请在 closeout 前补 full verify（至少 worker verify+jacoco，最好整仓 verify）。
    evidence: >-
      .riccon/evidence/TASK-0105/EV-0557.json；
      .riccon/evidence/TASK-0121/EV-0552.json（ef02ff4，早于 e9c57ee）；
      docs/CODING-STANDARDS.md §10.1。
  - id: F-015
    severity: P3
    status: accepted
    owner: "riccon/verifier（V1）"
    due: "M04 gate 前"
    title: "IT-M04-12 只测 resolver，未覆盖 ChatStream/Report 实际空链入口"
    summary: >-
      IT-M04-12 直接调用 modelRouter.resolve 并断言无 HTTP/无 invocation；代码顺序确实是 resolve 在
      invocationService.start 之前（ChatStreamService/两个 Handler），但没有一条从员工请求或报告事件进入
      的 503/无 invocation 端到端断言。按 P3 accepted 由 V1 补 ChatStream/Report 空链用例，避免只证明
      resolver 单点。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/model/ModelFailoverIntegrationTest.java:322-362；
      apps/worker/src/main/java/com/riccon/worker/chat/service/ChatStreamService.java:213-226；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java:298-314。
notes: >-
  任务级复评，不替代 M04 模块评审、Product 预验收或用户验收；不修改任务状态、不标 done。
  F-001/F-003/F-007/F-010/F-011 已关闭；F-012 是 e9c57ee 引入的新 P1，因此 verdict 仍为 fail。
  本次复跑 ModelFailoverIntegrationTest（17 tests、0 failure/error/skip、exit 0）；EV-0557 为 e9c57ee
  上 worker 336 tests（0 skip）的独立记录，full verify/coverage 仍缺（F-014）。
created_at: "2026-11-21T05:42:00+08:00"
---

# REV-TASK-0105-002：TASK-0105 / M04-B4 复评（F-001 修复）

## 结论

**任务级 fail**。**F-001 已实质关闭**：ROUTED 空链不再回退 M02 单模型，代码/测试/证据与修复说明一致；
BASELINE 单模型、显式测试夹具播种、成功重置连续失败也都成立。但修复把
`reason=CHAIN_EMPTY` 写入 Worker 审计，却**没有同步 API `AuditDetailsPolicy`**：API 审计读取仍是
fail-closed，M10 审计查询/导出遇到链空事件会返回 `RICCON-AUDIT-500`（**F-012，P1**）。这是
M04-AC-16 的 M10 回归，因此本复评不能 pass；此外最终提交只有 worker test、缺 full verify/coverage
（F-014，P2）。其余原 P2/P3 按 Lead 建议记录为 accepted（F-002/F-004/F-005/F-006/F-008/F-009）。

## F-001 关闭证据

- `ModelRouteResolver.resolve` 已删除 `entries.isEmpty() && !configured(purpose)` 分支；ROUTED 只做
  `entries(purpose)` → 逐条 target/guard/platform pool 过滤，`models.isEmpty()` 抛 `chainEmpty`；
  `ModelRoute` 不再有 `configured` 字段，`ModelRoutingJdbc`/`ModelRoutingRepository` 的
  `isChainConfigured`/`configured` 已删除。全仓 `apps/worker/src/main` grep 无残留调用。
- ROUTED 空链不读目录：解析路径不再调用 `findActiveCatalog()`；`chainEmpty` 只写指标/审计并抛
  `RICCON-MODEL-503`，调用方在 `invocationService.start` 之前解析，所以不建 `model_invocation`、
  不发起 HTTP。
- `TestDataFactory.createModelCatalog` 在 CHAT/REPORT 无条目时显式插入 position=1 路由，避免 J1/J2
  夹具继续依赖回退；显式 `fixture.route(...)` 的多模型测试不受影响。
- BASELINE：`baselineChain()` 选最早 ACTIVE，仍走 `ModelEndpointGuard`（允许列表/URL/标签）与
  `findPlatformPool`（ACTIVE/cooldown + priority）；BASELINE 空目录/无可用 key 抛 503。
- 新测试是 Worker resolver/integration 级，不是 API 空链测试：IT-M04-12（ROUTED 无条目 503、无 HTTP、
  model_invocation=0、chain_empty+1、审计 reason）、IT-M04-13（BASELINE 空链可用）、IT-M04-14
  （BASELINE 空目录 503）、IT-M04-11（成功重置失败计数）、IT-M04-06（reason 断言）均真实执行。
  局限：IT-M04-12 未直接走 ChatStream/Report 入口（F-015，P3 accepted，V1 补）。
- EV-0557：`e9c57ee`、dirty=false、exit 0、worker **336 tests、0 failure/error/skip**；本次独立复跑
  `ModelFailoverIntegrationTest` **17 tests、0 failure/error/skip、exit 0**。

## 新 P1：API 审计读取策略未同步（F-012）

- Worker `AuditDetailsPolicy` 已允许 `model.invocation.failed` 的 `reason`，`AuditService` 写入
  `reason=CHAIN_EMPTY`。
- API `AuditDetailsPolicy` 仍只允许 `modelId/durationMs/errorCode/retryable/emitted`；
  `AuditDetailsReader.read` 调用同一校验，`AuditQueryService.toView` 在分页映射时逐条读取，任一
  chain-empty 事件就会抛 `AuditDetailsInvalidException`，查询/导出返回 `RICCON-AUDIT-500`。
- 用 HEAD 编译类直接复现：
  `printf ... | jshell --class-path apps/api/target/classes:platform/platform-domain/target/classes`
  → `REJECTED: audit detail key is not allowed for model.invocation.failed: reason`。
- 影响：M10 审计查询/导出回归（M04-AC-16），平台/租户管理员无法读取包含链空事件的审计页；不是
  Worker 正确性问题，但必须由 API policy 的 owner 在本任务 closeout/M04 gate 前修复并补 API 读取用例。
  `AuditDetailsPolicy` 属 B1a/B2 写范围，B4 当前 scope 不含 API；Lead 需建 fix 或扩 scope。

## P2/P3 处理（按 Lead 建议）

| Finding                                | 状态                  | owner / 到期                               | 理由摘要                                                                               |
| -------------------------------------- | --------------------- | ------------------------------------------ | -------------------------------------------------------------------------------------- |
| F-002 429 Retry-After                  | accepted P2           | builder-failover/B10，2026-12-04           | ADR 默认 60s 仍生效；Retry-After 属优化，不阻断                                        |
| F-004 取消传播                         | accepted P2           | builder-failover + V1，2026-12-04          | 延迟受 first-token timeout 上限，内容不重复；V1 真实断开验证                           |
| F-005 同 provider key 复用             | accepted P2           | builder-failover + V1，2026-12-04          | 仅多模型共享池边界，无安全泄露，maxAttempts 有上限                                     |
| F-006 报表 first_token_ms              | accepted P2           | B4 + B7，B7 closeout                       | attempt 明细已有 first_token；AC-04 聊天路径已正确，B7 补调用详情                      |
| F-008 API 503 映射                     | accepted P2（任务级） | B11a + B2，M04 gate 前（模块级仍必须关闭） | B4 范围外；AC-05 员工可见结果由 B11a/B2 承接                                           |
| F-009 失败矩阵/BASELINE allowlist 测试 | accepted P3           | V1，M04 gate 前                            | IT-MODEL-103 已覆盖四类；V1 补 403/3xx/INVALID/CONFIGURATION/首字后超时/allowlist 负例 |
| F-010 证据/baseline                    | fixed                 | Lead                                       | EV-0551 已替换为 EV-0557；baseline/end 与 scope 已更新                                 |
| F-011 cooldown 近似值                  | fixed                 | builder-failover                           | 代码注释已明确近似口径                                                                 |
| F-013 成功清 cooldown                  | accepted P3           | builder-failover/architect，2026-12-04     | 可能抵消并发 429 冷却；V1 并发用例确认是否保留                                         |
| F-015 IT-M04-12 入口覆盖               | accepted P3           | V1，M04 gate 前                            | resolver 级真实；ChatStream/Report 空链入口由 V1 补                                    |

## 范围与证据

- `git diff --name-only f97674e e9c57ee`：11 个文件，全部在 0604c75 更新后的 TASK-0105 scope；
  未改 API/contracts/迁移/构建配置。`AuditDetailsPolicy` 与 `TestDataFactory` 是必要追加：
  前者让 Worker 链空审计符合 ADR §4.1，后者让 J1/J2 夹具显式播种路由；但前者遗漏 API 同步（F-012）。
- `TASK-0105.json` evidence 为 EV-0550/EV-0557，baseline=f97674e、end=e9c57ee；EV-0557
  commit/sha256/bytes 与日志一致，dirty=false、exit 0。
- EV-0552（ef02ff4）仍是 B4 修复前的 full verify；不能作为 e9c57ee 的最终全量证据（F-014）。
  EV-0557 是 worker test，不含 JaCoCo/ArchUnit/API；高风险管理任务需在 closeout 前补 full verify。

## 未验证

- 未运行 M10 API 审计查询/导出 E2E；F-012 由静态读取链和 jshell 直接复现，未做浏览器 E2E。
- 未运行 M04 模块 gate 的全量 build/lint/typecheck/test/coverage/E2E/a11y/security；EV-0557 是
  worker test，模块 gate 仍需 V1/V2 在最终提交上独立执行并绑定 `dod_evidence`。
- 未验证 403/3xx/INVALID_RESPONSE/CONFIGURATION/首字后超时、BASELINE allowlist 负例（F-009，V1）。
- 未验证真实客户端取消后的资源释放（F-004）与同 provider 多模型 401 复用（F-005）。

## 剩余风险

- F-012 未修复前，任何链空事件都会使 M10 审计查询/导出失败；这是当前唯一阻断本任务 P1。
- F-014 未补 full verify/coverage 前，B4 修复后的 JaCoCo/ArchUnit/API 回归没有最终证据。
- F-008 未由 B11a/B2 完成前，M04-AC-05 的员工可见 503 文案仍不可达；F-002/F-004/F-005/F-006 是已登记
  accepted 风险，需按 owner/到期跟进。
- M04 gate 仍失败（多个 planned 任务、无模块 review、F-012/F-014），这是模块未完成状态，不是
  B4 主路径失败。
