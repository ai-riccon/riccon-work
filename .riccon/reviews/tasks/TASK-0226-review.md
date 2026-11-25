---
review_id: REV-TASK-0226-001
module_id: M06
task_id: TASK-0226
reviewer:
  agent: riccon/verifier
  session: ses_dee6d8f92ffeBw4IhVxmPL3KxS
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 0a43a08316f5d0620d53da2fc40130419b518d65
  - 4963572f2089f1cecae4ad92d9d2fc9dc770ca1f
head_commit: 4963572f2089f1cecae4ad92d9d2fc9dc770ca1f
baseline_commit: 847079d8e598b46f474d1af0538761266a48febc
review_scope: >-
  M06 T07a（TASK-0226）独立任务评审：只审 `workflow/approval/**` 的 APPROVAL 执行器、审批人解析、
  工具参数信封加密/脱敏/哈希、决定状态机、停车信号、审计与任务级测试；复核 EV-1079 的
  commit/dirty/hash，并独立重跑 approval 定向测试、ArchUnit 与 spotless。不运行 M06 模块全量
  build/test/coverage、真实浏览器 E2E/axe、perf、Trivy/semgrep，不评审 T07b/T08/T09/T16a 的
  实现，不替代模块 review、Product 预验收或用户最终验收，也不运行 gate check。
dod:
  acceptance: true
  build: true
  tests: true
  lint: true
  review: true
  evidence: true
  scope: true
  security: true
dod_not_applicable:
  - coverage
  - e2e
  - performance
  - accessibility
  - migration
  - docs
dod_note: >-
  pass (with residual)。执行器已注册到 T06b 分派器；用户/角色审批人解析、本租户 ACTIVE、
  排除发起人、无审批人停车、AES-256-GCM（AAD=tenantId|approvalId）、params_sha256/plan_sha256、
  脱敏预览、决定条件更新/并发 409、自批/范围外/停用 403+denied 审计、跨租户不可见均有代码与
  真实 MySQL 测试支撑。独立复跑 25 个 approval 测试（0 failures/0 errors/0 skipped）、ArchUnit
  12/12、worker+全仓 spotless exit 0；EV-1079 的 commit/dirty/sha256 与 JSON 一致。build 指
  受影响模块已编译并通过测试；tests 指上述定向套件；lint 指合同 `./mvnw -q spotless:check`；
  security 指本任务代码面（租户过滤、AES-GCM、审计白名单、参数脱敏）未发现绕过路径，未运行
  Trivy/semgrep。AC-05/06/15/18 的用户可见 UI/API/E2E 不在 T07a，AC 表标 partial，不能把
  worker 测试当作完整 AC pass；coverage/E2E/perf/a11y/migration/docs 属模块 gate 或后续任务。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T06b 停车信号修复或 T15 RUNBOOK/告警口径）"
    title: "正常审批停车被记为 workflow.engine.aborted_lease_lost WARN，租约语义误导"
    summary: >-
      `WorkflowApprovalParkedException` 继承 `WorkflowNodeAbortedException(lost=true)`
      （`apps/worker/src/main/java/com/riccon/worker/workflow/approval/domain/WorkflowApprovalParkedException.java:16-21`），
      T06b 引擎的 `catch (WorkflowNodeAbortedException)` 对 `lost=true` 只记
      `workflow.engine.aborted_lease_lost` WARN 并返回
      （`apps/worker/src/main/java/com/riccon/worker/workflow/engine/service/WorkflowEngine.java:439-442`）。
      观察：EV-1079 与独立复跑日志中每个正常审批都会出现该 WARN（复跑 11 次），语义却表示“租约
      丢失/可能重复执行”；T06b 没有 PARKED 出口。功能上无状态破坏：执行器同事务提交审批、
      节点/运行 WAITING_APPROVAL、`lease_owner=NULL`，批准后从目标节点恢复并执行到 END；测试
      `WorkflowApprovalExecutorIntegrationTest`/`DecisionServiceIntegrationTest` 已证明。心跳在
      poller `finally` 中立即 close，未观察到 `lease_lost_total` 增长；但日志告警口径可能误报。
      期望：T06b 增加专用停车信号（或引擎识别该异常并降级为 INFO/专用事件），T15 RUNBOOK/告警
      说明排除正常审批停车。T07a 的 write_scope 不含 engine，无法在本任务修复。
    accepted_reason: >-
      业务状态机、重试与审计不受影响，测试与独立复跑均通过；WARN 只影响可观测性且未增加
      lease_lost 指标，属于有界 residual。T07a 不能修改 T06b engine，按“后续 T06b/T15 修复”
      接受，不阻断 T07a done，但必须在 M06 gate 前闭环或明确 RUNBOOK 口径。
    evidence: >-
      `WorkflowApprovalParkedException.java:16-21`；
      `WorkflowEngine.java:439-442`；EV-1079/独立复跑日志中
      `workflow.engine.aborted_lease_lost` WARN；`WorkflowApprovalExecutorIntegrationTest` 6/6、
      `WorkflowApprovalDecisionServiceIntegrationTest` 8/8 断言 WAITING_APPROVAL/恢复链。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/builder-b1（TASK-0229）；U-05 owner=安全评审/Product（Lead 跟踪）"
    due: "M06 gate 前 / TASK-0229 done 前"
    title: "workflow.approval.decided 审计与 ADR 写入方/API 读取白名单不一致；U-05 理由 PII 未解决"
    summary: >-
      ADR-0024 §8.1（`docs/adr/ADR-0024-m06-workflow-orchestration.md:603`）把
      `workflow.approval.decided` 的写入方登记为 api；T07a 的 Worker
      `WorkflowApprovalDecisionService` 为证明“决定与审计同事务、审计失败回滚”也用自有
      `WorkflowApprovalAuditPolicy` 写同名动作（`apps/worker/src/main/java/com/riccon/worker/workflow/approval/service/WorkflowApprovalAuditor.java:105-148`）。
      本地策略的字段集合与 API 读取白名单一致，但 `decisionComment` 允许 500
      （`WorkflowApprovalAuditPolicy.java:39,73-79`），而 API `AuditDetailsPolicy` 的全局
      `MAX_VALUE_LENGTH=200`（`apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:491`）
      仍适用于该键；读写端 `AuditDetailsReader` 用同一 API 策略 fail-closed
      （`AuditDetailsReader.java:44`）。观察：201–500 字符的合法决定理由若由 Worker 写入，
      API 审计查询/导出会抛 `RICCON-AUDIT-500`，形成“已落库但读不出”的审计行。T01 已把该
      200/500 冲突登记为 accepted P2（`REV-TASK-0219-001` F-001），TASK-0229 任务卡明确要求
      关闭并补 201/500 用例；T07a 没有生产 HTTP 入口，当前不会产生此类审计写入，但本地策略
      使 worker 路径具备写入能力。U-05（完整审批理由进审计的 PII 风险）在 ADR §8.3
      （`:620`）仍是未解决安全项，T07a 仅按临时默认实现，测试只覆盖短理由；本 review 不声称
      U-05 已接受或已解决。双写评估：worker 决定服务当前无生产调用者，T09 不能跨模块依赖
      worker；不会出现同一决定的 worker+api 双写，但 ADR 的单一写入方约定需要 T09 明确。
    accepted_reason: >-
      T07a 的决定服务没有 HTTP 入口，生产上不会写入 201–500 字符理由；T01 已把 200 上限作为
      accepted P2 跟踪，TASK-0229 显式负责关闭并补测试；U-05 仍由 ADR §8.3 的安全/Product
      owner 跟踪，M06 gate 前必须二选一（完整理由或 commentSha256）。本 review 只接受“T07a
      按临时默认实现、不得声称 U-05 已解决”的任务级 residual，不替代安全评审或模块 gate。
    evidence: >-
      `WorkflowApprovalAuditor.java:105-148`；`WorkflowApprovalAuditPolicy.java:39,73-79`；
      `apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:491`；
      `AuditDetailsReader.java:44`；`docs/adr/ADR-0024-m06-workflow-orchestration.md:603,620`；
      `.riccon/reviews/tasks/TASK-0219-review.md` F-001；`.riccon/tasks/TASK-0229.json` 验收第 4 条。
  - id: F-003
    severity: P2
    status: accepted
    owner: riccon/lead
    due: "TASK-0229 开工前 / M06 gate 前"
    title: "决定状态机在 worker 实现但生产写入方是 API；T09 将重复实现，测试未覆盖生产路径"
    summary: >-
      `WorkflowApprovalDecisionService` 位于 `apps/worker/.../workflow/approval/service`，当前
      除测试外没有任何生产调用者；`apps/api/pom.xml` 不依赖 worker，ArchUnit
      `ModuleBoundaryTest.java:57-66` 明确 api/worker 不能互相编译依赖。ADR-0024 §5.4/§8.1 把
      审批决定与 decided 审计放在 API，TASK-0229 的 write_scope 是
      `apps/api/.../workflow/approval/**`。因此 T09 必须复制条件更新、自批/范围/停用校验、
      409 语义与审计，或另建共享契约；当前 25 个测试验证的是 worker 路径，不是用户最终调用的
      API 路径。期望：T09 实现前明确权威实现，至少补 API 侧与 worker 行为一致的 parity/集成
      测试，避免 self-approval/assignee/audit/409 语义漂移。该问题属于任务拆分/模块边界缺口，
      不是 T07a 代码错误，但模块 gate 不能把 worker 测试当作 API 决定证据。
    accepted_reason: >-
      T07a 任务卡显式要求实现决定域；worker 实现本身通过 25 个真实 MySQL 测试，当前无生产
      调用者，不会造成现网双写或越权。风险在下一任务 T09 的实现路径，T07a 无法在本任务
      消除；作为跨任务设计 residual 接受，由 Lead/Architect 在 T09 开工前确定权威实现。
    evidence: >-
      `WorkflowApprovalDecisionService.java:58`；`apps/api/pom.xml`（无 worker 依赖）；
      `platform/platform-architecture-tests/src/test/java/com/riccon/architecture/ModuleBoundaryTest.java:57-66`；
      `.riccon/tasks/TASK-0229.json`；`git grep WorkflowApprovalDecisionService` 仅 main 类与测试。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "T07b done 前"
    title: "T07b 接口未完全闭合：plan 哈希上游集合、密文读取与一次执行唯一键依赖"
    summary: >-
      `WorkflowApprovalParamsService.openToolParams` 需要 keyId/ciphertext/expectedSha256
      （`WorkflowApprovalParamsService.java:124-131`），但
      `WorkflowApprovalRepository.findApproval` 的投影不含 `params_ciphertext`/`params_key_id`
      （`WorkflowApprovalRepository.java:120-122`、`WorkflowApprovalJdbc.java:91-98`），T07b
      必须自行查询或在 approval 包增加读取方法；同时 T07b 必须使用审批密文解密后的参数，
      不能重新渲染工具节点的 `argsTemplate`，否则审批绑定的 `params_sha256` 失去意义。
      `WorkflowApprovalPlanHasher.hash` 接受显式 `upstreamSummaries`
      （`WorkflowApprovalPlanHasher.java:66-101`），但 T07b 在工具节点执行时
      `execution.upstreamOutputs()` 会包含审批节点自身的输出（`WorkflowNodeExecution.java:31`）；
      若直接复用而未剔除审批节点，plan 哈希会漂移并触发 `WF_PLAN_DRIFT`。审批创建时的正确集合
      是审批节点的上游（见 `WorkflowApprovalExecutorIntegrationTest.java:254-271` 的重算）。
      “一个审批最多执行一次”最终依赖 T07b 的 `workflow_tool_invocation` 唯一键
      `(tenant_id, workflow_approval_id)`（V15 `workflow_tool_invocation`，`uk_workflow_tool_invocation_approval`），
      T07a 无法在本任务证明。期望：T07b 明确使用审批节点上游摘要、自行加载密文、依赖唯一键
      并补集成测试；接口 Javadoc 或 T07b 设计记录该口径。
    accepted_reason: >-
      T07a 已提供可用的 open/hash 原子接口，且漂移会失败关闭而非绕过审批；T07b 尚未实现，
      风险是下一任务的实现口径和交付依赖，不是 T07a 当前行为缺陷。作为跨任务接口 residual
      接受，T07b done 前必须由 T07b 集成测试覆盖密文读取、plan 重算和唯一键。
    evidence: >-
      `WorkflowApprovalParamsService.java:124-131`；`WorkflowApprovalRepository.java:120-122`；
      `WorkflowApprovalJdbc.java:91-98`；`WorkflowApprovalPlanHasher.java:66-101`；
      `WorkflowNodeExecution.java:31`；`WorkflowApprovalExecutorIntegrationTest.java:254-271`；
      `apps/api/src/main/resources/db/migration/V15__workflow_studio.sql` 的
      `uk_workflow_tool_invocation_approval`。
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/builder-b1
    due: "T16a / M06 module gate 前"
    title: "AC-05/06/15/18 的用户可见 UI/API/E2E 不在 T07a，不能把 worker 测试当作完整 AC pass"
    summary: >-
      T07a 提供 worker 侧执行器、审批人解析、决定与审计，但 `docs/PRODUCT.md:822-835` 的
      AC-05/06/15/18 要求审批待办/详情界面、批准/拒绝入口、发布提示、角色矩阵、E2E/a11y 和
      安全套件；这些分别由 T08（运行历史/审批聚合）、T09（审批待办/详情/决定 API）、T13（前端
      审批）、T16a（E2E/a11y/越权/注入）交付。当前没有 API 端点或 UI 证据，worker 集成测试
      只能证明领域行为。期望：模块 review 对 AC-05/06/15/18 绑定 T08/T09/T13/T16a 的 API/UI/E2E
      证据后再判完整 pass；本任务 AC 表标 partial。
    accepted_reason: >-
      任务卡把 T07a 限定在 worker 域，UI/API/E2E 在后续任务；把完整 AC 判 false 会误伤本任务，
      但把 worker 测试判完整 pass 会违反 AC 对账规则。作为任务级 residual 接受，模块 gate 必须
      用 T16a/T09 的 UI/E2E 证据闭环 AC-05/06/15/18。
    evidence: >-
      `docs/PRODUCT.md:822-835`；`.riccon/tasks/TASK-0228.json`、`TASK-0229.json`、T13/T16a 任务卡；
      本 review 的 AC 对账表。
reviewed_evidence:
  - EV-1079
evidence:
  - EV-1079
created_at: "2026-11-25T16:08:19+08:00"
---

# REV-TASK-0226-001：M06 T07a 审批模型/参数加密/assignee/决定独立评审

## 结论

**Pass（带 residual）**。TASK-0226 的 worker 侧 APPROVAL 执行器、审批人解析、参数信封加密与
脱敏预览、计划绑定、决定状态机、自批/范围/停用拒绝与审计均落地；独立复跑 25 个 approval 测试
全部通过（0 failures/0 errors/0 skipped），ArchUnit `ModuleBoundaryTest` 12/12，合同
`./mvnw -q spotless:check` exit 0；EV-1079 的 commit/dirty/sha256 与 JSON 一致。未发现 open
P0/P1；保留 3 个 accepted P2（F-001 停车 WARN 语义、F-002 decided 审计/U-05、F-003 T09 决定
实现路径）和 2 个 accepted P3（F-004 T07b 接口、F-005 AC UI/E2E 延后），均有 owner/due/
accepted_reason。本评审不运行 `gate check`、不修改源码/测试/契约/PRODUCT/AGENTS/.riccon 状态，
不替代 M06 模块 review、Product 预验收或用户最终验收。

## 评审范围与提交

| 项       | 值                                                                                                                                                                                                |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| baseline | `847079d8e598b46f474d1af0538761266a48febc`（T06b done）                                                                                                                                           |
| 代码提交 | `0a43a08316f5d0620d53da2fc40130419b518d65`（T07a feat）→ `4963572f2089f1cecae4ad92d9d2fc9dc770ca1f`（信号/结果类型收敛）                                                                          |
| 任务卡   | `.riccon/tasks/TASK-0226.json`：status=review，end_commit=4963572；只改 `approval/**` + 对应测试                                                                                                  |
| 设计依据 | ADR-0024 §4.4/§4.7/§5/§8.1/§8.3；V15 `workflow_approval`/`workflow_approval_assignee`；`contracts/openapi.yaml` 1.6.0-m06 审批 schema                                                             |
| 范围审计 | `git diff --name-status 847079d..4963572` 共 29 个文件，全部为新增 `apps/worker/.../workflow/approval/**` 与测试；无 contracts/PRODUCT/AGENTS/V15/API/J2/T06b/T07b 改动；`git diff --check` clean |

## 执行器（APPROVAL）

- **注册与分派**：`WorkflowApprovalExecutor` 是 `@Component` 且实现 `WorkflowNodeExecutor`，
  `WorkflowNodeDispatcher` 通过 `ObjectProvider<WorkflowNodeExecutor>` 收集；同类型重复会启动
  失败（`WorkflowNodeDispatcher.java:35-53`）。独立复跑 `WorkflowEngineWiringTest` 1/1、
  `WorkflowNodeDispatcherTest` 3/3，证明 Spring 装配与重复守卫未因 T07a 新增 Bean 破坏。
- **配置与目标**：执行器校验 `actionKind`、恰好一条出边、`targetNodeKey` 必须是直接后继；
  MCP_TOOL 后继必须是工具节点且 `toolId` 非空；MODEL 后继必须是模型节点
  （`WorkflowApprovalExecutor.java:147-296`）。配置非法以 `WF_INVALID_GRAPH` /
  `WF_TOOL_NOT_AUTHORIZED` 失败，不当作无审批人放行。
- **审批人解析**：`WorkflowApprovalAssigneeResolver` 按“本租户 → ACTIVE → 角色/用户范围 →
  排除发起人”解析（`WorkflowApprovalAssigneeResolver.java:58-134`）。USER 要求 1..50 且不能
  同时配角色；ROLE 仅接受 `TENANT_ADMIN/WORKFLOW_EDITOR/MEMBER` 且不能同时配用户。SQL 全部
  带 `tenant_id`、`status='ACTIVE'`、`id <> :excludeUserId`
  （`WorkflowApprovalJdbc.java:507-565`）。用户审批写 `workflow_approval_assignee`；角色审批不写
  该表，决定时按 `user_role` 联接复查。
- **无审批人**：解析结果为空仍创建一条 PENDING 审批（无 assignee 行），运行/节点进入
  `WAITING_APPROVAL` 并写 `WF_NO_APPROVER` + 固定中文“需先配置非发起人审批人”，不自动放行、
  不跳过下游（`WorkflowApprovalExecutor.java:210-227`、`WorkflowApprovalJdbc.java:164-179`）；
  `WorkflowApprovalExecutorIntegrationTest.noApprover_keepsRunWaiting` 断言 run status/error、
  审批 PENDING、assignee 空、下游无 SKIPPED、审计 `assigneeCount=0`。
- **参数加密与脱敏**：`WorkflowApprovalParamsService.sealToolParams` 按封闭占位符渲染
  `argsTemplate`（不解释表达式），递归键排序得到规范化 JSON，计算 `params_sha256`，生成
  ≤16 键/单值 ≤200/≤3800 字节的脱敏预览，并复用 `ApprovalParamsCrypto` 以租户 DEK
  (`purpose=approval-params`) 做 AES-256-GCM，AAD=`tenantId|approvalId`
  （`WorkflowApprovalParamsService.java:89-111`；`AesGcmEnvelope.java:144-148`）。敏感键在任何
  层级命中即失败关闭；密文/密钥/哈希缺失时 `openToolParams` 统一 `WF_PLAN_DRIFT`。
  `toolApproval_encryptsParamsAndBindsPlan` 用真实审批 ID 解密成功、错误审批 ID 解密失败、
  预览不含 `Bearer token-value`、`apiKey` 参数 fail-closed。
- **计划哈希**：`WorkflowApprovalPlanHasher` 绑定 schema 版本、workflow/version、审批节点、
  actionKind、assignee 配置、target 节点/工具、上游摘要，字段排序稳定
  （`WorkflowApprovalPlanHasher.java:66-101`）；集成测试按同一输入重算得到相同
  `plan_sha256`。注意 T07b 重算的上游集合口径见 F-004。
- **审计 requested 与事务**：`WorkflowApprovalAuditor.requested` 使用 T01 维护的 worker
  `AuditDetailsPolicy` 白名单，审计异常不捕获；执行器 `@Transactional(noRollbackFor =
WorkflowApprovalParkedException.class)` 保证审批/assignee/节点/运行等待与 requested 审计
  同事务提交，审计失败整体回滚（`WorkflowApprovalAuditorTest.requested_propagatesAuditFailure`）。
  节点/运行推进全部为条件更新（`… WHERE status='RUNNING'` + 租约/当前节点条件），返回 0 时
  回滚或按租约丢失处理。

## 决定状态机

- **入口与校验**：`WorkflowApprovalDecisionService.decide` 在单事务内按“拒理由校验 → 读审批
  （tenant 过滤）→ 自批/停用/范围校验 → 审批状态 → 运行状态 → 加载发布图”执行
  （`WorkflowApprovalDecisionService.java:119-150`）。拒绝理由必填、任何决定 ≤500 字符，按
  Unicode 码点计数（`WorkflowApprovalDecisionCommand.java:30-50`）。
- **批准**：`approve` 条件更新 `PENDING→APPROVED`，0 行即 `CONCURRENT_DECISION`；审批节点
  `WAITING_APPROVAL→SUCCEEDED` 并写 `{"decision":"APPROVED"}`；运行
  `WAITING_APPROVAL→PENDING`、`current_node_key=targetNodeKey`、清 error/租约、累加
  `approval_wait_ms`；审计失败整体回滚（`WorkflowApprovalDecisionService.java:162-211`、
  `WorkflowApprovalJdbc.java:181-236`）。测试证明批准后可从目标节点继续到 END 并产出结果。
- **拒绝**：条件更新 `PENDING→REJECTED`；审批节点 `FAILED` + `WF_APPROVAL_REJECTED`；运行
  `FAILED`、`result_status=NONE`、清 result；审批节点下游全部写 `SKIPPED`
  （已有尝试 `skipPendingNode`，无尝试直接插入 SKIPPED），不产生结果/产物；审计同事务
  （`WorkflowApprovalDecisionService.java:223-271`、`:341-356`）。测试断言审批节点 FAILED、
  run FAILED、下游 SKIPPED 与 `result_json=null`。
- **拒绝/并发**：重复决定先被 `status != PENDING` 拦为 409；并发两个决定由
  `WHERE status='PENDING'` 兜底，一个 OK、一个 `WorkflowApprovalConflictException`，只落一条
  SUCCESS 审计（`WorkflowApprovalDecisionServiceIntegrationTest.concurrentDecisions_onlyOneSucceeds`）。
- **禁自批/范围/停用**：顺序为自批 → `isActiveUser` → assignee/角色范围；命中写
  `outcome=DENIED` 审计后抛 `WorkflowApprovalForbiddenException`，审批/运行保持等待；审计失败
  不被吞掉（`WorkflowApprovalDecisionService.java:297-330`）。测试覆盖 SELF_APPROVAL、
  OUT_OF_SCOPE、APPROVER_INACTIVE，且 denied 审计不含理由内容。
- **跨租户不可见**：所有查询经 `TenantJdbc` 且 SQL 显式 `tenant_id`；跨租户审批 ID 返回
  `WorkflowApprovalNotFoundException`，测试断言原租户审批/运行未变化
  （`WorkflowApprovalDecisionServiceIntegrationTest.crossTenantApproval_notVisible`）。

## 停车机制

T07a 用 `WorkflowApprovalParkedException extends WorkflowNodeAbortedException(lost=true)`
让 T06b 引擎走“丢弃本段结果”出口：引擎捕获后不写任何终态并返回
（`WorkflowApprovalParkedException.java:16-21`、`WorkflowEngine.java:439-442`）。这在本任务内
**功能正确**：执行器已在同事务提交审批与 WAITING_APPROVAL，租约清空，poller 随后关闭心跳；
决定路径负责恢复。测试与独立复跑均确认 run/node 停在 `WAITING_APPROVAL`、`lease_owner=NULL`、
批准后恢复执行、拒绝后 FAILED/SKIPPED。

但语义偏差真实存在：正常审批停车每次都会记 `workflow.engine.aborted_lease_lost` WARN，
把“正常等待”误报成“租约丢失/可能重复执行”；T06b 没有 PARKED 出口。未观察到
`riccon_workflow_lease_lost_total` 增长（心跳在引擎返回后立即 close），因此不是状态机/
重试/审计缺陷，而是可观测性/告警口径缺陷。按 F-001 accepted，要求 T06b 小修复或 T15
RUNBOOK 明确排除，M06 gate 前闭环。

## 审计

- **requested**：details 严格为 ADR §8.1 的
  `workflowRunId/workflowApprovalId/nodeId/assigneeCount/actionKind/targetToolName?`，由 worker
  `AuditDetailsPolicy` 校验；审计失败让审批停车整体回滚（fail-closed）。
- **decided**：ADR 登记写入方为 api；T07a 为验证“决定与审计同事务”在 worker 用本地
  `WorkflowApprovalAuditPolicy` 写同名动作，字段与 API 读取白名单一致
  （`workflowRunId/workflowApprovalId/nodeId/decision/approverUserId/targetToolName?/decisionComment?`）。
  偏差有两处：① `decisionComment` 本地允许 500，而 API 读取策略全局上限 200，201–500 字符
  会形成“写得进、读不出”的审计行（T01 F-001 已 accepted P2，TASK-0229 必须关闭）；② U-05
  完整理由进审计的 PII 风险在 ADR §8.3 仍未解决，T07a 测试只用短理由，不能声称已接受。
  双写评估：worker 决定服务当前无生产调用者，T09 在 API 实现，不存在同一决定的 worker+api
  双写；但 ADR 的单一写入方约定需 T09 明确。按 F-002 accepted。
- **denied**：自批/范围外/停用写 `outcome=DENIED`，不写理由，不改变审批/运行；审计失败直接
  抛出，不吞异常。
- **审计内容**：requested/decided 均不含工具参数、密文、提示词、凭据或文档正文；工具名是
  受控快照；测试断言 denied 审计不含“自批”理由。

## T07b / T09 接口

- **T07b 参数重放**：`openToolParams(tenantId, approvalId, keyId, ciphertext, expectedSha256)`
  明确可用；密文缺失/key 不可用/认证失败/哈希不符统一 `WF_PLAN_DRIFT`，不会退回 preview 明文。
  但 `WorkflowApprovalRepository.findApproval` 投影不含 ciphertext/key_id，T07b 需自行查询或
  增加读取方法；T07b 必须使用解密后的审批参数，不能重新渲染工具节点的 `argsTemplate`，否则
  `params_sha256` 绑定失去意义。plan 哈希的上游摘要集合必须在 T07b 用“审批节点的上游”重算
  （工具节点的 `upstreamOutputs` 含审批节点自身输出，直接复用会漂移）；“一个审批最多一次”
  依赖 T07b `workflow_tool_invocation` 唯一键。按 F-004 accepted，T07b done 前用集成测试锁定。
- **T09 决定实现**：ADR 把 decided 写入方定为 api；api/worker 不能编译依赖（ArchUnit 57-66），
  当前 `WorkflowApprovalDecisionService` 无生产调用者。TASK-0229 必须在 API 侧实现决定并补
  parity/集成测试，不能把 T07a 的 25 个 worker 测试当作 API 端点证据。按 F-003 accepted。
- **AC 用户可见结果**：AC-05/06/15/18 的待办/详情/入口/文案/E2E 不在 T07a；本任务 AC 表标
  partial，模块 review 必须绑定 T08/T09/T13/T16a 的 API/UI/E2E。按 F-005 accepted。

## AC 对账（task 级）

| AC        | 原文用户可见结果（摘要）                                                                                                 | T07a 支撑                                                                                            | T07a 判定                                                                                           |
| --------- | ------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| M06-AC-05 | 待办只显示分配给自己；详情显示动作/工具/范围/脱敏参数；批准继续并产出；拒绝终止、跳过、无结果、记录审批人/时间/决定/理由 | 批准/拒绝/节点终态/下游 SKIPPED/审计/理由；真实 MySQL 集成测试                                       | **partial（task）**：worker 领域通过；待办/详情 UI、E2E 归 T08/T09/T13/T16a，不能据此判完整 AC pass |
| M06-AC-06 | 自批入口禁用、直接调用 403+拒绝审计；范围外/停用/他租户不能审批且不泄露；无可用审批人保持等待并提示配置                  | SELF_APPROVAL/OUT_OF_SCOPE/APPROVER_INACTIVE 403+denied；无审批人 WAITING+WF_NO_APPROVER；跨租户 404 | **partial（task）**：worker 域通过；发布提示/UI/E2E 归 T03/T09/T16a                                 |
| M06-AC-15 | 审批人必须在节点范围且非发起人；角色/范围/404/403；审批权限矩阵                                                          | 自批/范围/停用/跨租户；assignee/角色解析                                                             | **partial（task）**：仅审批子集；取消/停用/移交/权限矩阵 API/UI 归 T05/T08/T09/T16a                 |
| M06-AC-18 | 未授权/停用工具不调用；写工具必须审批；审批摘要 ACL 最小可见；提示注入不改变流程                                         | 工具 ACTIVE/授权失败关闭；参数密文/plan/预览；敏感键失败                                             | **partial（task）**：工具执行/一次唯一键/T07b 与注入 E2E 归 T07b/T16a                               |

结论：TASK-0226 的三条任务验收成立；AC-05/06/15/18 只完成 worker 子集，模块 review 仍需
UI/E2E 证据。

## 证据复核与独立重跑

- **EV-1079**：`manual=false`、commit=`4963572`、`dirty=false`、exit=0；命令为
  `./mvnw -q -pl apps/worker -am test -Dtest='com.riccon.worker.workflow.approval.**.*Test'
-Dsurefire.failIfNoSpecifiedTests=false && ./mvnw -q -pl platform/platform-architecture-tests
-am test -Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false && ./mvnw -q
spotless:check`；输出 14,149 bytes，SHA-256
  `d613c62a392c29f8c9115bead3ab729c189e95a3acb8be33058a54fc8076dcdf`，与 JSON 一致。
  EV-1078（commit 0a43a08）为历史，被 EV-1079 取代。
- **独立复跑**（HEAD=4963572，工作树仅 .riccon 元数据变动）：同一命令 exit 0；surefire XML
  汇总 approval 25 tests（AuditPolicy 6、Auditor 2、DecisionService 8、Executor 6、
  PlanHasher 3），0 failures/0 errors/0 skipped；ArchUnit `ModuleBoundaryTest` 12/12，0 skip；
  `WorkflowEngineWiringTest` 1/1、`WorkflowNodeDispatcherTest` 3/3。
- **Spotless**：合同 `./mvnw -q spotless:check`（无 `-DspotlessFiles` 过滤）exit 0。
- **未运行**：模块全量 build/test/coverage、真实浏览器 E2E/axe、perf/k6、Trivy/semgrep、
  T07b/T08/T09/T16a 实现与迁移回滚（V15 由 T01 负责）。这些不作为 T07a 失败，但模块 gate/
  后续任务必须补。

## 未验证 / 残余风险

1. **F-001 停车 WARN**：正常审批会误报 `workflow.engine.aborted_lease_lost`；状态机正确但
   日志/告警口径需 T06b/T15 修复。
2. **F-002 审计/U-05**：201–500 字符理由在 worker 与 API 读取白名单间不一致；U-05 未解决；
   TASK-0229/M06 gate 前必须二选一并补测试。
3. **F-003 T09 双实现**：worker 决定服务无生产调用者；T09 需确定权威实现并补 parity。
4. **F-004 T07b 接口**：密文读取、plan 上游集合、一次唯一键需 T07b 集成测试锁定。
5. **F-005 AC UI/E2E**：AC-05/06/15/18 的 UI/E2E 未在 T07a 验证。
6. 未覆盖的边界：审批决定与取消的窄竞态（run 状态冲突 409 已有代码，但未做取消竞态测试）；
   审计仓储 DB 失败的集成级回滚（仅单元传播断言）；跨租户 assignee 配置过滤（SQL 有
   `tenant_id`，但测试未构造他租户 user）；201–500 字符决定的完整路径。均由 T09/T16a 或
   T07b 补，不作为 T07a 失败。

## 是否可 done

可进入 done（**pass**）。任务卡的独立 Verifier 评审条件已满足：执行器/决定/参数/assignee/
审计有真实 MySQL 测试与独立复跑；无 open P0/P1；3 个 P2（F-001/F-002/F-003）和 2 个 P3
（F-004/F-005）均 accepted 且有 owner/due/accepted_reason。本评审不把任务标 done、不运行
`gate check --module M06`，也不替代 M06 模块 gate、Product 预验收或用户最终验收。
