---
review_id: REV-TASK-0225-002
module_id: M06
task_id: TASK-0225
previous_review: REV-TASK-0225-001
reviewer:
  agent: riccon/verifier
  session: ses_deed501d9ffereawxi5gSNhc0n
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - a90a66e9a7cd8f1abe452d4f9c81803f9a5dfa14
head_commit: a90a66e9a7cd8f1abe452d4f9c81803f9a5dfa14
code_commit: a90a66e9a7cd8f1abe452d4f9c81803f9a5dfa14
evidence_commit: a90a66e9a7cd8f1abe452d4f9c81803f9a5dfa14
baseline_commit: 8d970bc4da0851d078e80e3ceb4dcbcaf76400a0
task_card_baseline_commit: 8d970bc4da0851d078e80e3ceb4dcbcaf76400a0
review_scope: >-
  M06 T06b（TASK-0225）第二轮独立任务复审：只审 REV-TASK-0225-001 的修复，即 F-001（22 个
  T06b Java 文件 spotless、EV-1072 文件过滤假绿、T15 归因）、F-002（MODEL 节点跨模型降级）、
  F-007（步数耗尽遗留 RUNNING 节点）；复核 a90a66e 的范围/语义差异与 EV-1077 的
  commit/dirty/exit/hash/bytes，并独立复跑 161 个 workflow 测试 + ArchUnit 12 + worker/全仓
  完整 `spotless:check`（无过滤参数）。F-003/F-004/F-005/F-006/F-008 维持上一轮 accepted，
  不重开。不重审 T06b 全量功能，不运行 M06 模块全量 build/test/coverage、真实浏览器 E2E/axe、
  perf、Trivy/semgrep，不评审 T07a/T07b/T08/T16a/T16b，不替代模块 review/Product 预验收/
  用户最终验收，不运行 gate check。
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
  pass (with residual)。F-001 已修复：`./mvnw -q -pl apps/worker spotless:check` 与
  `./mvnw -q spotless:check`（均无 `-DspotlessFiles`）独立复跑 exit 0；22 个原违规 T06b 文件
  已由 a90a66e 格式化，新增 `ModelNodeFailoverTest` 也通过全量 spotless；EV-1077 的命令包含
  worker+全仓完整 spotless、commit=a90a66e、dirty=false、exit 0，日志 SHA-256 与 JSON 一致。
  F-002 已修复：`routeFor` 无 modelId 传完整 REPORT 链、有 modelId 传链后缀；
  `ModelNodeFailoverTest` 用真实 `FailoverChatModel` 证明主模型失败后切备用模型成功。
  F-007 已修复：`handleStepLimit` 增加 RUNNING→FAILED/WF_STEP_LIMIT 分支，
  `stepLimitWithStaleRunningNode_failsNode` 断言节点 FAILED，满足 `POST /retry` 的 FAILED 前置。
  独立复跑 22 个类 161 tests、0 failures/0 errors/0 skipped，ArchUnit 12/12。F-003/F-004/
  F-005/F-006/F-008 保持 accepted（各 finding 有 owner/due/accepted_reason），本轮不重开。
  EV-1071/EV-1072 保留为历史；EV-1072 的 spotless 段因过滤匹配 0 文件属假绿，已由 EV-1077
  取代，按 Lead 指令不因此 fail。T15 的归因更正记录见 F-001：实际为 22 个 T06b 文件，而非
  REV-TASK-0236-002/EV-1076 记载的单个 ModelNodeExecutorTest；T15 自身 11 个 config/metrics
  文件仍 clean，T15 的 pass 不重开。build 指 `mvn test` 编译受影响模块；tests 指上述定向 161
  例与 ArchUnit；lint 指本任务 Java spotless（worker+全仓），未运行 `pnpm -w lint` 与模块全量；
  security 指本修复未新增 ACL/端点/数据边界，未运行 Trivy/semgrep。coverage/E2E/perf/a11y/
  migration/docs 属模块 gate 或后续任务，本任务不判定。
findings:
  - id: F-001
    severity: P2
    status: fixed
    owner: riccon/builder-b2
    due: "已修复（T06b done 前）"
    title: "F-001 已关闭：22 个 T06b 文件完成格式化，worker 与全仓 spotless 无过滤 exit 0；EV-1077 取代 EV-1072 的假绿段"
    summary: >-
      独立验证：`./mvnw -q -pl apps/worker spotless:check` exit 0，`./mvnw -q spotless:check`
      exit 0，均未使用 `-DspotlessFiles`；a90a66e 修改的 22 个原违规 T06b 文件已格式化，新增
      `ModelNodeFailoverTest` 也通过同一全量检查。EV-1077 命令为
      `mvnw test ... && ArchUnit && ./mvnw -q -pl apps/worker spotless:check && ./mvnw -q spotless:check`，
      commit=a90a66e、dirty=false、exit=0、bytes=80301、日志 SHA-256
      `bbca0d7168a09698f9f9938f02f36d9a505d1569805a3385fffaaaea325de95e` 与 JSON 一致；独立复跑
      同一命令 exit 0。上一轮 EV-1072 的 `-DspotlessFiles='src/(main|test)/...'` 匹配 0 文件、
      exit 0 的假绿段不再作为 lint 证据；EV-1071/EV-1072 保留为历史，EV-1077 为权威 clean EV。
      T15 归因更正：REV-TASK-0236-002/EV-1076 记载“全仓残余仅 ModelNodeExecutorTest”不准确，
      实际为 22 个 T06b 文件；T15 的 config/metrics 文件 clean，T15 pass 不重开，本 review 记录
      该更正，Lead 可在 T15/模块记录中同步。
    evidence: >-
      `.riccon/evidence/TASK-0225/EV-1077.json:5`（完整 spotless 命令）、`:7`（commit a90a66e）、
      `:8`（dirty=false）、`:9`（exit 0）、`:11`（SHA-256）、`:12`（80301 B）、`:18`（161 tests +
      ArchUnit + 完整 lint）；`apps/worker/src/test/java/com/riccon/worker/workflow/node/service/ModelNodeExecutorTest.java`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/node/service/ModelNodeFailoverTest.java`；
      `./mvnw -q -pl apps/worker spotless:check` exit 0；`./mvnw -q spotless:check` exit 0；
      `.riccon/reviews/tasks/TASK-0236-review-002.md:83-101`（旧归因，已被本 review 更正）
  - id: F-002
    severity: P2
    status: fixed
    owner: riccon/builder-b2
    due: "已修复（T06b done 前）"
    title: "F-002 已关闭：MODEL 节点无 modelId 传完整 REPORT 链、有 modelId 传链后缀，真实 FailoverChatModel 验证跨模型降级"
    summary: >-
      `ModelNodeExecutor.routeFor`（当前 `:163-183`）在无 `modelId` 时直接返回完整 `route`；有
      `modelId` 时定位链中下标并返回 `List.copyOf(route.models().subList(index, size))`，保留从
      指定模型起始的后缀；不在链内仍抛 `WF_MODEL_UNAVAILABLE`。`invoke` 使用该 `callRoute` 构造
      M04 `FailoverChatModel`，不再用 `List.of(routed)` 折叠单模型。`ModelNodeExecutorTest`
      新增 `execute_withoutModelId_passesFullReportChain`（:142）和
      `execute_withModelId_passesSuffixChain`（:161），用 `ArgumentCaptor` 断言传给
      `createModel` 的链内容；`ModelNodeFailoverTest.execute_primaryUnavailable_failsOverToFallback`
      （:120）用真实 `FailoverChatModel`、主模型 `ModelRegistry.resolve` 抛错、备用模型 client
      成功返回，断言 attempt 记录为 `[PRIMARY_ID, FALLBACK_ID]` 且调用记录以备用模型成功。独立
      复跑 161 个 workflow 测试通过；未发现新的 P1/P2。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/node/service/ModelNodeExecutor.java:124,163-183,196`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/node/service/ModelNodeExecutorTest.java:142-181`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/node/service/ModelNodeFailoverTest.java:120-169`；
      `docs/adr/ADR-0024-m06-workflow-orchestration.md:419-425`
  - id: F-007
    severity: P3
    status: fixed
    owner: riccon/builder-b2
    due: "已修复（T06b done 前）"
    title: "F-007 已关闭：handleStepLimit 增加 RUNNING→FAILED/WF_STEP_LIMIT，遗留 RUNNING 节点不再阻塞 POST /retry"
    summary: >-
      `WorkflowEngine.handleStepLimit`（当前 `:958-1010`）在原有 PENDING/null 分支之前增加
      `latest.status()==RUNNING` 分支：调用 `finishNodeFailure(..., FAILED, WF_STEP_LIMIT, ...)` 把
      遗留 RUNNING 尝试写成 FAILED，再 `state.put(FAILED)`，随后仍执行 `skipDownstream` 与
      `failRun(WF_STEP_LIMIT)`。`WorkflowEngineIntegrationTest.stepLimitWithStaleRunningNode_failsNode`
      （:507）用 `maxSteps=1` 与预置 RUNNING START 行断言运行 `FAILED/WF_STEP_LIMIT`、节点
      `FAILED/WF_STEP_LIMIT`、错误信息含“步数上限”、模型未调用。节点最新尝试为 FAILED，满足
      `WorkflowRunService.retry`（:296-299）的“运行 FAILED + 节点 FAILED”前置；本任务未直接
      调用 HTTP retry（T08 覆盖 409 路径）。原覆盖 PENDING 的 `stepLimit_failsRun` 与新增 RUNNING
      用例均通过。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/service/WorkflowEngine.java:958-1010`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/engine/WorkflowEngineIntegrationTest.java:504-527`；
      `apps/api/src/main/java/com/riccon/api/workflow/run/service/WorkflowRunService.java:296-299`；
      `docs/adr/ADR-0024-m06-workflow-orchestration.md:365-376`
  - id: F-003
    severity: P2
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（由 T08/T15 或 ADR 修订复核）"
    accepted_reason: >-
      沿用 REV-TASK-0225-001 F-003，本轮不重开：`workflow_version` 由 V15 的 BEFORE UPDATE/DELETE
      触发器保护，`RUN_GRAPH_SQL` 以 `(tenant_id, workflow_version_id, workflow_id)` JOIN，错误
      版本绑定会取不到图并 fail-closed；引擎再校验 `node_count`。未重算 `graph_sha256` 的剩余风险
      是“同节点数内容被意外改写”，能改 graph_json 的 DB 高权限主体也能改 hash，逐字节校验帮助
      有限；正常应用路径无法写版本行。M06 gate 前由 T08/T15 或 ADR 修订记录/补齐。
    title: "graph_sha256 未在运行入口重算，只有 tenant/version/workflow JOIN + node_count"
    summary: "继承 REV-TASK-0225-001 F-003；本轮修复未触碰图绑定代码。"
    evidence: "`.riccon/reviews/tasks/TASK-0225-review.md:124-150`"
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T16a 真实 stack 启动覆盖）"
    accepted_reason: >-
      沿用 REV-TASK-0225-001 F-004，本轮不重开：`@Profile("!test")` 避免共享测试上下文启动真实
      槽位；`WorkflowEngineWiringTest` 验证非 test profile 装配，T06a 槽位测试覆盖执行器可用路径；
      完整生产 profile 启动由 T16a/preview 覆盖。属测试覆盖残余，不扩大读写边界。
    title: "生产 profile 完整 Worker context 启动无自动化覆盖"
    summary: "继承 REV-TASK-0225-001 F-004；本轮修复未改 profile/调度装配。"
    evidence: "`.riccon/reviews/tasks/TASK-0225-review.md:151-177`"
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T08/T15 成本口径复核）"
    accepted_reason: >-
      沿用 REV-TASK-0225-001 F-005，本轮不重开：`invocationService.start` 与
      `linkModelInvocation` 是两步；进程在两步之间崩溃时该次调用缺少工作流归属，link 失败本身
      fail-closed 为 WF_INTERNAL；影响限于单次成本归属，不扩大租户/执行边界，也不改变模型输出。
    title: "模型调用创建/关联两步窗口"
    summary: "继承 REV-TASK-0225-001 F-005；本轮修复未改调用记录代码。"
    evidence: "`.riccon/reviews/tasks/TASK-0225-review.md:178-201`"
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T08/T16a 或下一次 T06b 修改）"
    accepted_reason: >-
      沿用 REV-TASK-0225-001 F-006，本轮不重开：引用写入已有串行集成断言；崩溃恢复时的
      citations JSON 反序列化是 Jackson 3 record/enum/UUID 常规映射，失败会 fail-closed WF_INTERNAL；
      由 T08/T16a 或下一次 T06b 修改补恢复用例，不影响本轮修复主路径。
    title: "引用快照崩溃恢复 round-trip 未覆盖"
    summary: "继承 REV-TASK-0225-001 F-006；本轮修复未改引用恢复代码。"
    evidence: "`.riccon/reviews/tasks/TASK-0225-review.md:202-223`"
  - id: F-008
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T15/T16a 或 ADR 口径记录）"
    accepted_reason: >-
      沿用 REV-TASK-0225-001 F-008，本轮不重开：检索超时为查询前后协作式检查，超时结果不落库；
      JDBC 查询本身没有 120s 上限（无 `setQueryTimeout`/`socketTimeout`），慢查询可占槽位超过预算，
      属有界数据库/基础设施故障，不产生错误数据、不扩大租户边界；M06 gate 前由 T15/T16a 补
      JDBC 超时或写入 ADR/RUNBOOK。
    title: "检索协作式超时不限制 JDBC 查询时长"
    summary: "继承 REV-TASK-0225-001 F-008；本轮修复未改检索执行器。"
    evidence: "`.riccon/reviews/tasks/TASK-0225-review.md:246-269`"
evidence:
  - EV-1071
  - EV-1072
  - EV-1077
reviewed_evidence:
  - EV-1077
notes: >-
  任务卡 status=review、owner=riccon/builder-b2、baseline=8d970bc、end_commit=a90a66e、
  evidence=[EV-1071, EV-1072, EV-1077]；评审时 HEAD=a90a66e。EV-1077：commit=a90a66e、
  dirty=false、manual=false、exit_code=0、日志 80,301 B、SHA-256
  `bbca0d7168a09698f9f9938f02f36d9a505d1569805a3385fffaaaea325de95e` 与 EV JSON 一致；命令
  含 worker+全仓完整 spotless，无 `-DspotlessFiles`。独立复跑同一命令 exit 0：22 个 workflow
  测试类 161 tests、0 failures/0 errors/0 skipped，ModuleBoundaryTest 12/12，worker 与全仓
  spotless 均 exit 0。EV-1071/EV-1072 保留为历史；EV-1072 的 spotless 段因过滤匹配 0 文件
  属假绿，已被 EV-1077 取代，按 Lead 指令不因此 fail。a90a66e 只改 T06b engine/node 主代码与
  测试 23 个文件，无 T15/contracts/PRODUCT/AGENTS/V15/API/J2 改动；对主代码做去空白归一化比较，
  除 F-002 的 `routeFor`/`invoke` 与 F-007 的 `handleStepLimit` 分支外，其余文件仅格式化/
  移除未用 import，无语义变更。T15 归因更正：REV-TASK-0236-002/EV-1076 的“全仓残余仅
  ModelNodeExecutorTest”实际为 22 个 T06b 文件；T15 自身 config/metrics 文件仍 clean，T15
  pass 不重开，本 review 记录更正，Lead 收口时可同步到 T15/模块记录。EV-1077 与
  `.riccon/tasks/TASK-0225.json`/`.riccon/state.json` 当前未提交，代码提交 a90a66e 本身 clean；
  模块 gate 前由 Lead 提交证据/状态。评审期间未改源码/测试/契约/PRODUCT/AGENTS/.riccon 状态；
  只写本评审文件；未运行 gate check、全量 E2E/perf/coverage/security。
created_at: "2026-11-25T14:20:00+08:00"
---

# REV-TASK-0225-002：M06 T06b 修复复审

## 结论

**通过（pass，with residual）**。

上一轮 `REV-TASK-0225-001` 的三个阻断项都已闭合：

1. **F-001（P2, fixed）**：22 个 T06b Java 文件已格式化；`./mvnw -q -pl apps/worker spotless:check` 与 `./mvnw -q spotless:check`（均无 `-DspotlessFiles`）独立复跑 exit 0。EV-1072 的 spotless 假绿段已由 EV-1077 取代，EV-1071/EV-1072 仅作历史保留；按 Lead 指令不因此 fail。
2. **F-002（P2, fixed）**：`ModelNodeExecutor.routeFor` 无 `modelId` 传完整 REPORT 链、有 `modelId` 传链后缀；`ModelNodeExecutorTest` 两个 route 断言 + `ModelNodeFailoverTest` 用真实 `FailoverChatModel` 验证主模型失败→备用模型成功。
3. **F-007（P3, fixed）**：`handleStepLimit` 增加遗留 `RUNNING → FAILED/WF_STEP_LIMIT` 分支；`stepLimitWithStaleRunningNode_failsNode` 断言运行与节点失败，满足 `POST /retry` 的节点 FAILED 前置。

F-003/F-004/F-005/F-006/F-008 维持上一轮 accepted，不重开。独立复跑 161 tests + ArchUnit 12/12 + 完整 spotless 通过；无 P0/P1/P2 open。T15 的 22 文件归因更正记录在本 review（T15 自身文件 clean，T15 pass 不重开）。

## F-001：spotless 与 EV-1072 假绿

- **全量 spotless**：`./mvnw -q -pl apps/worker spotless:check` exit 0；`./mvnw -q spotless:check` exit 0；未加 `-DspotlessFiles`。上一轮 22 个违规文件（engine 9 + node 13）在 a90a66e 中被格式化，新增 `ModelNodeFailoverTest` 也通过同一检查。
- **EV-1077 真实性**：命令为
  `./mvnw -q -pl apps/worker -am test -Dtest='...engine.**,node.**,queue.**,heartbeat.**' -Dsurefire.failIfNoSpecifiedTests=false && ./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest ... && ./mvnw -q -pl apps/worker spotless:check && ./mvnw -q spotless:check`；commit=a90a66e、dirty=false、exit=0、bytes=80301、SHA-256 `bbca0d71...95e` 与 EV JSON 一致。独立复跑同一命令 exit 0：22 个类 161 tests、0 failures/0 errors/0 skipped，ArchUnit 12/12，worker/全仓 spotless exit 0。
- **旧证据处理**：EV-1071/EV-1072 保留在任务 evidence 中作历史；EV-1072 的 `-DspotlessFiles='src/(main|test)/...'` 匹配 0 文件、spotless 段 exit 0 属假绿，不能再作为 lint 证据；EV-1077 是权威 clean EV。按 Lead 指令记录，不作为本轮 fail。
- **T15 归因更正**：`REV-TASK-0236-002` 与 EV-1076 记载“全仓残余仅 `ModelNodeExecutorTest`”，独立全量检查实际为 **22 个 T06b 文件**；T15 的 11 个 config/metrics 文件仍 clean，T15 的 pass 不重开。本 review 明确记录更正，建议 Lead 在 T15/模块记录同步；T06b 的 22 个文件已全部修复。

## F-002：MODEL 跨模型降级

- `ModelNodeExecutor.routeFor`（`ModelNodeExecutor.java:163-183`）：
  - `modelId == null` 返回完整 REPORT `route`（`:166`）；
  - 有 `modelId` 时按 `target().model().id()` 找下标，返回 `List.copyOf(subList(index, size))` 后缀（`:178-182`）；
  - 不在链内仍 `WF_MODEL_UNAVAILABLE`。
- `invoke` 使用该 `callRoute` 创建 `FailoverChatModel`，不再 `List.of(routed)` 折叠。
- 测试：
  - `ModelNodeExecutorTest.execute_withoutModelId_passesFullReportChain`（:142）用 `ArgumentCaptor<ModelRoute>` 断言传给 `createModel` 的链为 `[MODEL_ID, FALLBACK_MODEL_ID]` 且 `failoverEnabled=true`。
  - `execute_withModelId_passesSuffixChain`（:161）指定备用模型，断言链路为 `[FALLBACK_ID]`。
  - `ModelNodeFailoverTest.execute_primaryUnavailable_failsOverToFallback`（:120）用真实 `FailoverChatModel`：主模型 `ModelRegistry.resolve` 抛 `ModelException(UNAVAILABLE, TRANSPORT)`，备用 client 返回“备用模型输出”；断言 attempt 记录顺序 `[PRIMARY_ID, FALLBACK_ID]`、调用记录以备用模型成功（attempt=2）。
- 结论：跨模型降级恢复，无新 P1/P2。`modelId` 后缀语义在单元层锁定；真实 `FailoverChatModel` 在集成层证明切换。

## F-007：步数耗尽遗留 RUNNING

- `WorkflowEngine.handleStepLimit`（`:958-1010`）在 PENDING/null 分支前增加：
  - `latest.status() == RUNNING` → `finishNodeFailure(..., FAILED, WF_STEP_LIMIT, message, ...)`；
  - `state.put(FAILED)`；
  - 之后仍 `skipDownstream` + `failRun(WF_STEP_LIMIT)`。
- 测试 `WorkflowEngineIntegrationTest.stepLimitWithStaleRunningNode_failsNode`（:507）：
  - `maxSteps=1`，预置 RUNNING START 行；
  - 断言 run `FAILED/WF_STEP_LIMIT`，节点 `FAILED/WF_STEP_LIMIT` 且错误信息含“步数上限”，模型未调用。
- `WorkflowRunService.retry`（:296-299）要求运行 FAILED 且最新节点 FAILED，本修复满足；本任务不直接调用 HTTP retry（T08 验证 409/202 路径）。

## 证据复核

| 检查             | 命令/对象                                  | 结果                                                                                        |
| ---------------- | ------------------------------------------ | ------------------------------------------------------------------------------------------- |
| EV-1077 JSON     | `.riccon/evidence/TASK-0225/EV-1077.json`  | commit=a90a66e、dirty=false、manual=false、exit=0、bytes=80301                              |
| EV-1077 日志哈希 | `shasum -a 256 EV-1077-*.log`              | `bbca0d71...95e`，与 JSON 一致                                                              |
| 独立复跑测试     | EV-1077 同一命令                           | exit 0；22 个类 161 tests，0 failures/0 errors/0 skipped                                    |
| ArchUnit         | `ModuleBoundaryTest`                       | 12/12，0 failures/errors                                                                    |
| worker spotless  | `./mvnw -q -pl apps/worker spotless:check` | exit 0（无过滤参数）                                                                        |
| 全仓 spotless    | `./mvnw -q spotless:check`                 | exit 0（无过滤参数）                                                                        |
| 范围             | `git diff --name-only 8d970bc a90a66e`     | 23 个文件，全部在 T06b engine/node 主代码与测试；无 T15/contracts/PRODUCT/AGENTS/V15/API/J2 |
| 语义回归         | 去空白归一化比较主代码                     | 除 F-002 `routeFor`/`invoke` 与 F-007 `handleStepLimit` 外仅格式化/移除未用 import          |

## 范围与回归

- a90a66e 共 23 个文件：engine/node 主代码 12、测试 11（含新增 `ModelNodeFailoverTest`）；全部落在 TASK-0225 `write_scope`。
- 未改 `contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、`V15__workflow_studio.sql`、`apps/api/**`、J2 outbox、T15/queue/heartbeat；未启动 M06b。
- 对 12 个主代码文件做去空白/去注释归一化比较：`WorkflowEngine` 只有 `handleStepLimit` RUNNING 分支；`ModelNodeExecutor` 只有 `routeFor`/`invoke` 语义变化；`WorkflowResultAssembler` 只移除未用 `WorkflowNodeType` import；其余（Graph/GraphJson/Jdbc/ExecutionState/End/Retrieval/Start/Dispatcher/Template）归一化后完全一致，Spotless 未引入行为变化。
- 任务卡与 state 当前有未提交的 `.riccon` 变更，EV-1077 为未跟踪文件；代码提交 a90a66e 本身 clean。模块 gate 前由 Lead 提交证据/状态；本评审只写 review 文件。

## 未验证

- M06 模块全量 build/test/coverage、E2E/axe、perf、Trivy/semgrep；本任务只做定向修复验证。
- T08 的 `POST /retry` HTTP 409/202 路径（F-007 只断言节点 FAILED 前置）；T07a/T07b 审批/工具；T16a/T16b 真实 stack/回滚。
- F-003（graph_sha256）、F-004（生产 profile 启动）、F-005（模型调用两步窗口）、F-006（引用恢复 round-trip）、F-008（检索 JDBC 超时）仍为 accepted residual，本轮不重开；M06 gate 前复核。
- 真实模型/检索外部服务；`ModelNodeFailoverTest` 使用真实 `FailoverChatModel`，但 registry/client 为 mock，未发起网络。

## 剩余风险

- F-003..F-008 的 accepted 残余维持上一轮结论；无新增 P0/P1/P2。
- EV-1071/EV-1072 的历史记录仍含 EV-1072 假绿；EV-1077 为权威 clean EV，任务 evidence 保留旧条目不影响本轮结论，但 Lead 在收口时可注明“EV-1077 取代 EV-1072 的 spotless 段”。
- T15 的 22 文件归因更正目前记录在本 review；T15 评审文件本身仍写“仅 ModelNodeExecutorTest”，建议 Lead 在 T15/模块记录同步，不要回改历史 review。
- 任务卡/状态/EV-1077 未提交；模块 gate 前需由 Lead 提交并确认 worktree 干净性。

## Findings 摘要

| ID    | 级别 | 状态     | 主题                            | 处置                                           |
| ----- | ---- | -------- | ------------------------------- | ---------------------------------------------- |
| F-001 | P2   | fixed    | spotless 22 文件 / EV-1072 假绿 | 已格式化；worker+全仓 spotless exit 0；EV-1077 |
| F-002 | P2   | fixed    | MODEL 跨模型降级                | routeFor 全链/后缀；Failover 测试              |
| F-007 | P3   | fixed    | 步数耗尽遗留 RUNNING            | handleStepLimit RUNNING→FAILED；用例           |
| F-003 | P2   | accepted | graph_sha256 未重算             | 继承上一轮；M06 gate 前                        |
| F-004 | P3   | accepted | 生产 profile 启动覆盖           | 继承上一轮；T16a                               |
| F-005 | P3   | accepted | 模型调用两步窗口                | 继承上一轮；T08/T15                            |
| F-006 | P3   | accepted | 引用恢复 round-trip             | 继承上一轮；T08/T16a                           |
| F-008 | P3   | accepted | 检索 JDBC 超时                  | 继承上一轮；T15/T16a                           |
