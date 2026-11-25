---
review_id: REV-TASK-0225-001
module_id: M06
task_id: TASK-0225
reviewer:
  agent: riccon/verifier
  session: ses_deed501d9ffereawxi5gSNhc0n
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - a99a2ffbe9b25db4c24cfc962d2e8f4343dc5bdd
  - e73d501af3dd4dd45ad6bf12b539928e6ab3ed46
head_commit: 0b81ee4538e8eadede4aabf5b1e490886b42d148
code_commit: e73d501af3dd4dd45ad6bf12b539928e6ab3ed46
evidence_commit: b62fea55457542c9d8beb2032fcef9ab3287108e
baseline_commit: 0a57d5486c1203913340c2e8729501b15a07851c
task_card_baseline_commit: 0a57d5486c1203913340c2e8729501b15a07851c
review_scope: >-
  M06 T06b（TASK-0225）独立任务评审：只审 `workflow/engine/**` 与 `workflow/node/**` 的串行
  状态机、START/END/RETRIEVAL/MODEL/CONDITION 节点、超时/取消/租约丢失/重试/END 恢复、结果与引用、
  input/outputSummary、图严格解析与运行入口绑定、模板四类占位符、5 MiB/256 KiB/步数上限、
  APPROVAL/MCP_TOOL 缺失的 fail-closed、`@Profile("!test")` 测试隔离、检索协作式超时与模型
  Reactor 超时；复核 EV-1071/EV-1072 的 commit/dirty/hash/bytes 并独立复跑 engine/node/queue/
  heartbeat 测试与 ArchUnit。评审期间发现 22 个 T06b Java 文件未通过 worker 全量 `spotless:check`，
  且 EV 的 `-DspotlessFiles` 过滤实际匹配 0 文件，作为阻断发现记录。不运行 M06 模块全量
  build/test/coverage、真实浏览器 E2E/axe、perf、Trivy/semgrep、T07a/T07b 审批与工具、T08 运行
  历史/重试/取消 API、T16a/T16b，也不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: false
  build: true
  tests: true
  lint: false
  review: true
  evidence: false
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
  acceptance 记 false：串行状态机与五类节点的主路径在独立复跑中通过，但 F-002（MODEL 节点把
  REPORT 路由链折叠成单模型，跨模型 M04 降级不生效）是相对 ADR-0024 §4.10 的 open P2；若 Lead
  认定“节点固定单模型”是有意设计，应显式 accepted（owner/due/reason）或在 ADR 记录后再评估。
  build 记 true 指 `mvn test` 已编译全部受影响模块；tests 记 true 指独立复跑 EV-1072 的
  engine/node/queue/heartbeat 测试 157 例（21 个类，0 failures/0 errors/0 skipped）与
  ArchUnit 12/12；lint 记 false 指 T06b 的 22 个 Java 文件未通过 `spotless:check`（engine 9 +
  node 13，全部为本任务文件），合同 `commands.lint` 会失败；evidence 记 false 指 EV-1072 的
  spotless 段用 `-DspotlessFiles='src/(main|test)/...'` 匹配 0 个文件、exit 0 属假绿，其测试与
  ArchUnit 段仍有效；scope 记 true 指 diff 全部落在 TASK-0225 write_scope；security 记 true 仅指
  本任务 ACL/提示词/日志脱敏代码面未发现新增越权路径，未运行 Trivy/semgrep。coverage/E2E/
  performance/accessibility/migration/docs 属 M06 模块 gate 或后续任务，本任务不判定。
findings:
  - id: F-001
    severity: P2
    status: open
    owner: riccon/builder-b2
    due: "T06b done 前：spotless:apply 后重跑受影响测试，并用合同 lint 命令重新取证"
    title: "22 个 T06b Java 文件未通过 worker 全量 spotless:check；EV-1072 的 spotless 过滤参数匹配 0 文件，属假绿"
    summary: >-
      观察：`./mvnw -q -pl apps/worker spotless:check` exit 1；Spotless 报告 22 个文件需要格式化
      （engine 9：domain 3、persistence 1、service 2、config 1、engine 根测试 2；node/service 13；
      node/domain 与 node 根测试 0），第一个打印的是
      `src/test/java/com/riccon/worker/workflow/node/service/ModelNodeExecutorTest.java`。同一时刻
      `./mvnw -q -pl apps/api spotless:check` 与 `./mvnw -q -pl platform/platform-architecture-tests
      spotless:check` 均 exit 0；worker 全量 22 个违规与 engine/node 子集 22 个违规完全相等，说明
      全部违规都属于 T06b，不是 T15 或其他任务的外部失败。
      期望：合同 `commands.lint` 的 `./mvnw -q spotless:check` 在提交上 exit 0；观察：worker 的
      T06b 文件失败，模块 gate 的 lint 会失败。
      假绿：EV-1072（和 EV-1071）的 spotless 段使用
      `-DspotlessFiles='src/(main|test)/java/com/riccon/worker/workflow/(engine|node)/.*\.java'`。
      在 Spotless 3.11.0 中该参数按绝对路径匹配；复跑同一命令只输出 `BUILD SUCCESS`，没有
      `Spotless.Java is keeping ... files` 行，实际选中 0 个文件，因此永远 exit 0。把同一参数改成
      能匹配绝对路径的 `-DspotlessFiles='.*workflow/(engine|node)/.*\.java'` 后，Spotless 选中 51
      个文件、报告 22 个需要修改，证明过滤未生效而不是文件已格式化。EV 的 note
      “本任务 Java 文件 spotless”与观察不符。
      为排除工作树/Spotless 缓存污染，另在 `git archive 859a2c4` 的干净导出复跑同一命令，仍
      exit 1，输出 `ModelNodeExecutorTest.java` + “Violations also present in 21 other files”，
      即 22 个文件；这与 T15 的 REV-TASK-0236-002 与 EV-1076“全仓残余仅 ModelNodeExecutorTest”
      的记录不一致，T15 的残余归因应更正为 22 个，且全部归 TASK-0225。
      修复：在仓库根执行 `./mvnw -q spotless:apply`（或 `./mvnw -q -pl apps/worker spotless:apply`，
      只对 engine/node 文件生效），确认 diff 仅格式变化后重跑受影响测试；用合同命令
      `./mvnw -q spotless:check`（不加或使用能匹配绝对路径的正确正则 `-DspotlessFiles`）录制
      clean EV，并更新 EV note。格式问题通常为 P2，但它是 T4 DoD 的 lint 门禁，且当前证据把
      lint 说成已通过，必须修复后才能复审。
    evidence: >-
      `.riccon/evidence/TASK-0225/EV-1072.json:5`（spotless 过滤命令）、`:9`（exit 0）、
      `:18`（note 声称本任务 Java 文件 spotless）；
      `apps/worker/src/test/java/com/riccon/worker/workflow/node/service/ModelNodeExecutorTest.java`
      （第一个违规文件）；
      `./mvnw -q -pl apps/worker spotless:check` exit 1，22 needs changes；`-DspotlessFiles='.*workflow/(engine|node)/.*\.java'`
      选中 51 文件、22 违规；`git archive 859a2c4` 干净导出同一全量命令仍 exit 1（首行
      `ModelNodeExecutorTest.java` + “Violations also present in 21 other files”）；EV 原过滤命令
      `-DspotlessFiles='src/(main|test)/...'` 无 `keeping` 行且 exit 0；API/platform spotless exit 0；
      `.riccon/reviews/tasks/TASK-0236-review-002.md:83-101`、EV-1076 note（T15 已记录 T06b 残余）
  - id: F-002
    severity: P2
    status: open
    owner: riccon/builder-b2
    due: "T06b done 前（或 Lead 显式 accepted 并给出 owner/due/accepted_reason，M06 gate 前复核）"
    title: "MODEL 节点把 REPORT 路由链折叠成单模型，跨模型 M04 降级不生效，偏离 ADR §4.10"
    summary: >-
      `ModelNodeExecutor.java:191` 用 `new ModelRoute(route.purpose(), route.mode(),
      route.failoverEnabled(), List.of(routed))` 只保留选中的 `RoutedModel` 构造调用链；
      `FailoverChatModel.java:467` 的 `flatten` 只遍历 `modelRoute.models()`，因此降级只会在该
      模型的多个 key 之间发生，不会切到 REPORT 链中的下一个模型。ADR-0024 §4.10（:421）要求
      “复用 ModelRouter（purpose=REPORT）与 M04 降级/端点允许列表/配额”；期望主模型不可用且链中
      还有健康备用模型时按链顺序降级，观察会耗尽选中模型的 key 后直接 `WF_MODEL_UNAVAILABLE`，
      即使 REPORT 链中还有可用模型。若节点显式配置 `modelId`，合理的实现至少应传入该模型在链中的
      后缀（或明确只固定该模型并记录 ADR/配置语义）；当前无测试断言传给 `createModel` 的 route
      内容（`ModelNodeExecutorTest` 只 mock 返回 `chatModel`），所以该行为未被锁定。
      修复方向：无 `modelId` 时传完整 `route`；有 `modelId` 时传从该模型开始的后缀，或把“单模型
      固定”写入 ADR/节点配置语义并补一个降级测试。若 Lead 认为单模型固定是产品意图，必须把
      F-002 标 accepted（owner/due/reason），不能静默保留。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/node/service/ModelNodeExecutor.java:191`；
      `apps/worker/src/main/java/com/riccon/worker/model/service/FailoverChatModel.java:467`；
      `docs/adr/ADR-0024-m06-workflow-orchestration.md:419-425`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/node/service/ModelNodeExecutorTest.java:105-126`
  - id: F-003
    severity: P2
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（由 T08/T15 或 ADR 修订复核）"
    accepted_reason: >-
      `workflow_version` 由 V15 的 BEFORE UPDATE/DELETE 触发器保护为不可变；`RUN_GRAPH_SQL` 以
      `(tenant_id, workflow_version_id, workflow_id)` JOIN 版本行，错误版本绑定会直接取不到图并
      fail-closed；引擎再校验 `v.node_count` 与解析节点数一致，可发现截断/错位。未做 `graph_sha256`
      重算属于对 ADR §4.1“启动时校验 graph_sha256”的实现偏差，但剩余风险主要是“同节点数的图被
      意外改写”：能改 `graph_json` 的 DB 高权限主体同样能改 `graph_sha256`，逐字节校验对其帮助有限；
      正常应用路径无法写版本行。风险有界，不阻塞 T06b；M06 gate 前应在 ADR 记录该实现口径，或用
      API 同一规范化算法（`WorkflowGraphJson.sha256`）重算并比对，二选一闭环。
    title: "运行入口未校验 graph_sha256，只做 tenant/version/workflow JOIN + node_count"
    summary: >-
      ADR-0024 §4.1（:303）要求运行时“启动时校验 graph_sha256 与租户/版本绑定”。
      `WorkflowEngineJdbc.RUN_GRAPH_SQL` 未查询 `v.graph_sha256`，`WorkflowEngine.java:161-163`
      只比较 `graph.nodes().size()` 与 `run.versionNodeCount()`。注释说明了 MySQL JSON 会规范化
      键序/空白、不能逐字节重算，但 API 的 `WorkflowGraphJson.sha256` 是对规范化 JSON 计算的；
      运行侧解析图后再规范化重算在技术上可行。当前实现防住了错绑（JOIN）和部分截断（node_count），
      防不住同节点数的内容篡改/意外损坏。该限制不改变本次测试结论，但应在模块 gate 前记录或补齐。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/persistence/WorkflowEngineJdbc.java:49-59`；
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/service/WorkflowEngine.java:157-169`；
      `apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraphJson.java:158-166`；
      `apps/api/src/main/resources/db/migration/V15__workflow_studio.sql:503-513`；
      `docs/adr/ADR-0024-m06-workflow-orchestration.md:303`
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T16a 真实 stack 启动覆盖）"
    accepted_reason: >-
      `@Profile("!test")` 是为了避免共享 Worker 测试上下文启动真实调度槽位与手工领取竞争；
      `WorkflowEngineWiringTest` 已在非 test profile 验证 `WorkflowRunExecutor`/`WorkflowEngine`/
      `WorkflowRuntimeProperties` 可装配，T06a 的 `WorkflowRunSchedulerTest` 用可用执行器 mock 验证
      槽位注册。剩余风险是“完整生产 profile 的 Worker context 没有一次自动化启动”，但所有执行器
      都是普通 `@Component`、依赖均为既有 Bean，真实 stack 由 T16a/preview 启动覆盖；不扩大读写
      边界，属测试覆盖残余而非功能缺陷。
    title: '生产 profile 没有完整 Worker context 启动覆盖；@Profile("!test") 使所有 Spring IT 不加载真实 WorkflowRunExecutor'
    summary: >-
      `WorkflowEngine.java:79` 在 test profile 不注册；`WorkflowEngineIntegrationTest.java:60`
      手工构造引擎；`WorkflowQueueDefaultContextTest.java:54` 也使用 `@ActiveProfiles("test")`，
      因此即使它启动完整 `WorkerApplication`，也不会装配真实引擎（日志会走
      `workflow.queue.scheduler.executor_missing claim_slots=0`）。`WorkflowEngineWiringTest.java:35-41`
      用最小 `ApplicationContextRunner` + mock 验证非 test profile 装配，但没有覆盖完整上下文的
      Bean 依赖。生产/测试行为差异是有意的、可接受的；建议 T16a 的真实 stack 启动断言或 T15/RUNBOOK
      记录一次“默认 profile 启动 + executor 存在 + 槽位注册”的 smoke，M06 gate 前复核。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/service/WorkflowEngine.java:74-80`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/engine/WorkflowEngineIntegrationTest.java:59-60`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/queue/config/WorkflowQueueDefaultContextTest.java:49-54`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/engine/config/WorkflowEngineWiringTest.java:35-53`；
      `apps/worker/src/main/java/com/riccon/worker/workflow/queue/service/WorkflowRunScheduler.java:94-102`
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T08/T15 成本口径复核）"
    accepted_reason: >-
      `invocationService.start` 先插入 `model_invocation`，再单独
      `linkModelInvocation` 写 `workflow_run_id/workflow_node_run_id`；进程在这两步之间崩溃时，该次
      调用不会关联到工作流，工作流成本/调用列表会少一条归属。link 失败本身会让节点 WF_INTERNAL
      fail-closed，不会静默继续；窗口只在进程崩溃时出现，影响限于单次调用成本归属，不扩大租户/
      执行边界，也不改变模型输出。M06 gate 前由 T08/T15 复核是否需要在插入时直接带工作流列或加
      对账任务。
    title: "模型调用创建与 workflow 关联是两步窗口，崩溃时 invocation 未关联"
    summary: >-
      `ModelNodeExecutor.java:192-208` 先 `invocationService.start(...)` 得到 handle，再
      `repository.linkModelInvocation(...)`；`WorkflowEngineJdbc.java:258-263` 只按
      `(tenant_id, id)` 更新，不检查 `workflow_run_id IS NULL` 或既有归属。正常路径和 link 失败
      路径有测试；崩溃窗口无测试。ADR-0024 §4.10 要求 `model_invocation` 复用并写入工作流列，
      当前实现满足稳态口径，但两步窗口是已知残余。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/node/service/ModelNodeExecutor.java:192-208`；
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/persistence/WorkflowEngineJdbc.java:257-263`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/engine/WorkflowEngineIntegrationTest.java:623-640`；
      `docs/adr/ADR-0024-m06-workflow-orchestration.md:419-425`
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T08/T16a 或下一次 T06b 修改）"
    accepted_reason: >-
      引用快照的写入已由串行集成测试断言（2 条 citation JSON），但“成功 RETRIEVAL 后崩溃、从
      `workflow_node_run.citations_json` 重建状态再继续 END”这条路径没有集成用例；现有
      `WorkflowExecutionState.recordSuccess` 的反序列化是 Jackson 3 record + enum + UUID 的常规
      映射，失败会 fail-closed 为 WF_INTERNAL，不会产生错误结果。风险有界，由 T08/T16a 的恢复/
      重试/E2E 或下一次 T06b 修改补测试；不影响本次状态机主路径验收。
    title: "引用快照只验证写入，未验证崩溃恢复时的反序列化 round-trip"
    summary: >-
      `WorkflowExecutionState.java:220-240` 在 load 时用
      `objectMapper.readValue(citationsJson, TypeReference<List<WorkflowCitation>>)` 恢复引用；
      集成测试只在 `WorkflowEngineIntegrationTest.java:155,165` 断言写入 JSON 的元素数，恢复用例
      `endRecovery_rebuildsResult` 没有 citations，重试用例 `failureThenRetry...` 的检索节点也没有
      引用。若字段/枚举映射在 Jackson 3 下出现偏差，首次真实恢复会以 WF_INTERNAL 失败（fail-closed，
      但可用性受损）。建议补一个“RETRIEVAL 成功 + END 前崩溃 + 重新 execute 恢复”的用例。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/service/WorkflowExecutionState.java:220-240`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/engine/WorkflowEngineIntegrationTest.java:145-166,588-621,266-325`
  - id: F-007
    severity: P3
    status: open
    owner: riccon/builder-b2
    due: "T06b done 前（一行状态机修复 + 定向测试）"
    title: "步数上限路径不处理遗留 RUNNING 尝试，运行 FAILED 后节点仍 RUNNING，POST /retry 不可用"
    summary: >-
      `WorkflowEngine.java:700-712` 的 `handleStepLimit` 只处理 `latest == PENDING` 和
      `latest == null`，没有 `handleRunTimeout`（:661-668）那样的 `latest == RUNNING` 分支。可达
      路径：崩溃留下 RUNNING 尝试 → 租约过期重领 → `attemptCount` 已到 `maxSteps` → 循环在调用
      `failStaleAttempt` 之前先进入 `handleStepLimit`；此时当前节点保持 RUNNING，运行被置
      `FAILED/WF_STEP_LIMIT`。`apps/api/.../WorkflowRunService.java:294-301` 的 `POST /retry`
      要求最新节点 `FAILED`，因此该运行 retry 返回 409，与 ADR §4.6 “WF_INTERNAL 保证 POST /retry
      可用”的容错目标不一致。触发需要“步数预算耗尽 + 遗留 RUNNING”同时成立，概率低，但确实是状态机
      漏洞；修法是在 `handleStepLimit` 增加 RUNNING → `finishNodeFailure(FAILED, WF_STEP_LIMIT)`
      分支，并补一个 maxSteps 很小的用例。现有 `stepLimit_failsRun` 只覆盖 PENDING 分支。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/service/WorkflowEngine.java:692-716`；
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/service/WorkflowEngine.java:653-680`；
      `apps/api/src/main/java/com/riccon/api/workflow/run/service/WorkflowRunService.java:294-301`；
      `apps/worker/src/test/java/com/riccon/worker/workflow/engine/WorkflowEngineIntegrationTest.java:475-504`；
      `docs/adr/ADR-0024-m06-workflow-orchestration.md:365-376`
  - id: F-008
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前（T15/T16a 或 ADR 口径记录）"
    accepted_reason: >-
      任务要求的是“协作式、不硬杀 JDBC”，实现确实在查询前/后检查 deadline，超时后丢弃结果并失败，
      不会把超时结果写库；剩余风险是“查询本身”没有 120s 上限：MySQL/JDBC 未配置
      `setQueryTimeout`/`socketTimeout`，慢查询或锁等待会占住执行槽位超过 120s，甚至绕过 30min
      运行超时检查（引擎阻塞在 JDBC 调用内，心跳线程仍可续期）。这是有界的数据库/基础设施故障，
      不产生错误数据、不扩大租户边界；建议 T15 在连接串或检索 JDBC 增加 query timeout，或把
      “检索超时只保证结果不落库、不保证 DB 调用中止”写入 ADR/RUNBOOK 后接受。
    title: "检索超时为协作式检查，未设置 JDBC 查询超时，慢查询可长期占用执行槽位"
    summary: >-
      `RetrievalNodeExecutor.java:100-109` 在 `agentContextRepository.search` 前检查
      `deadline().requireRemaining()`，查询后再检查；`AgentContextJdbc`/application.yml 没有
      `Statement.setQueryTimeout` 或 MySQL `socketTimeout`。ADR §4.4 给 RETRIEVAL 120s 超时，
      §4.6 要求“尽力中断”；当前实现能保证“超时后不写结果”，但不能保证“120s 内结束节点/释放
      槽位”。这与任务说明“协作式、不硬杀 JDBC”一致，故接受，但应在模块 gate 前记录或补 JDBC 超时。
    evidence: >-
      `apps/worker/src/main/java/com/riccon/worker/workflow/node/service/RetrievalNodeExecutor.java:83-118`；
      `apps/worker/src/main/resources/application.yml:14`（JDBC URL 无 socketTimeout）；
      `apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java:95-106`；
      `docs/adr/ADR-0024-m06-workflow-orchestration.md:338-368`
evidence:
  - EV-1071
  - EV-1072
reviewed_evidence:
  - EV-1071
  - EV-1072
notes: >-
  任务卡 status=review、owner=riccon/builder-b2、baseline=0a57d54、end_commit=e73d501、
  evidence=[EV-1071, EV-1072]；评审时 HEAD=0b81ee4（e73d501 之后只有 T15 收口/评审与 .riccon
  记录提交，engine/node 代码 diff 为空）。EV-1072：commit=b62fea5（短 SHA 与 end_commit 不同，
  但 `git diff e73d501 b62fea5 -- apps platform` 为空，b62fea5 只多
  `.riccon/reviews/tasks/TASK-0236-review.md`；代码归属仍为 e73d501）、dirty=false、manual=false、
  exit_code=0、日志 80,362 B、SHA-256
  `f86f1de3a22d708cff3558a42c1c26a283f4399dac935c668a063e65a15a0693` 与 EV JSON 一致；
  EV-1071 为 e73d501 前的旧配置快照，已被 EV-1072 取代。独立复跑 EV-1072 的测试段（engine/
  node/queue/heartbeat）exit 0，surefire 显示 21 个类 157 tests、0 failures/0 errors/0 skipped；
  ModuleBoundaryTest 12/12；EV 原命令的 spotless 段因过滤匹配 0 文件而 exit 0，不能作为 lint
  证据。另跑 `./mvnw -q -pl apps/worker spotless:check` exit 1（22 个 T06b 文件），
  `./mvnw -q -pl apps/api spotless:check` 与 platform 同命令 exit 0。`resultTitle` 实际在
  `contracts/openapi.yaml` 的 `WorkflowNodeConfig` 中（:8209），并非“不在契约”；它不在
  `WorkflowResult` 的输出字段中，因此 worker 解析但不写入结果与当前契约一致，T08/T12a 再决定
  展示口径。T15 REV-TASK-0236-002 与 EV-1076 把全仓残余归为单个 `ModelNodeExecutorTest`，
  独立全量检查实际是 22 个 T06b 文件（见 F-001），该归因需要 Lead/后续评审更正。评审期间未改
  源码/测试/契约/PRODUCT/AGENTS/.riccon 状态；只写本评审文件；未运行 gate check、全量 E2E/
  perf/coverage/security。
created_at: "2026-11-25T13:56:00+08:00"
---

# REV-TASK-0225-001：M06 T06b 串行引擎/节点执行独立评审

## 结论

**不通过（fail）**。

串行状态机和五类节点的功能主路径是扎实的：START→END 单路径推进、尝试/`current_node_key`、失败/重试上游不重跑、下游 SKIPPED、条件 TRUE/FALSE 不默认分支、运行/节点超时、取消、租约丢失丢弃结果、END 崩溃恢复、终态更新带 `RUNNING + lease_owner` 条件，都有真实 MySQL 集成测试或定向单测覆盖；独立复跑 157 个 workflow 测试与 ArchUnit 12/12 通过。没有发现 P0/P1 功能或安全缺陷。

但本任务仍不能 done，原因有两条：

1. **F-001（P2, open）**：T06b 的 **22 个 Java 文件**未通过合同 `spotless:check`（engine 9 + node 13，全部是本任务文件）。EV-1072 的 spotless 段使用 `-DspotlessFiles='src/(main|test)/...'`，在 Spotless 3.11.0 下匹配 **0 个文件**并 exit 0，属于假绿；EV note 的“本任务 Java 文件 spotless”不成立。模块 gate 的 `commands.lint` 会因此失败。
2. **F-002（P2, open）**：MODEL 节点把 REPORT 路由链折叠成单个模型（`List.of(routed)`），`FailoverChatModel` 只会在这一个模型的 key 间降级，不会切到链中的备用模型，偏离 ADR §4.10“复用 M04 降级”。需要一个修复或显式产品/架构接受。

F-003/F-004/F-005/F-006/F-008 为 accepted P2/P3 残余（graph_sha256、生产 profile 启动覆盖、模型调用两步窗口、引用恢复 round-trip、检索 JDBC 超时）；F-007 为 open P3（步数上限 + 遗留 RUNNING 节点的 retry 可用性）。本评审 fail 的直接依据是 `dod.lint=false`/`dod.evidence=false`（F-001）与 F-002 这个 open P2；F-003 等已按 owner/due/reason 接受，不单独阻断。等待修复/接受后的复审。

## 评审范围与提交

- baseline `0a57d54`；代码 `a99a2ff`（实现）+ `e73d501`（复用 T15 运行限制配置）；EV-1072 录制于 `b62fea5`，相对 `e73d501` 只多 T15 评审文档，代码相同。
- `git diff --name-only 0a57d54..e73d501` 共 51 个文件，全部在 `apps/worker/src/{main,test}/java/com/riccon/worker/workflow/{engine,node}/**`，与任务 `write_scope` 完全一致。
- 未触碰 `contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、`V15__workflow_studio.sql`、`apps/api/**`、`workflow/queue/**`、`workflow/heartbeat/**`、`workflow/config/**`、J2 outbox、T06a、T15；T15 的 `WorkflowRuntimeProperties` 只是被引用，未被本任务修改。
- 评审时 HEAD=`0b81ee4`（之后只有 T15 收口、T15 评审和 `.riccon` 记录），`git diff e73d501..HEAD -- <T06b 四个路径>` 为空。

## 状态机

实现是 `WorkflowEngine` 单循环，按 `workflow_run.current_node_key` 推进：

- **串行推进**：每次执行一个节点；成功后按唯一出边（条件节点按 branch）推进；END 成功后写 `result_json` 并把运行置 `SUCCEEDED`。
- **尝试**：最新尝试是 `PENDING` 时复用同一行和 ID（重试/崩溃恢复不重复插入）；最新是 `RUNNING` 时先把遗留尝试 `FAILED`（WF_INTERNAL）再插入 `attempt+1`；最新是 `SUCCEEDED` 时直接推进，不重跑；其他情况插入新尝试。尝试唯一键 `(tenant, run, node_key, attempt)` 是最后守卫。
- **当前节点**：`updateCurrentNode` 带 `status='RUNNING' AND lease_owner=:leaseOwner`，0 行即丢弃本次执行。
- **终态**：`finishRunSucceeded`/`finishRunFailed` 都带 `status='RUNNING' AND lease_owner`；节点终态 `FINISH_NODE_ATTEMPT_SQL` 只允许 `RUNNING -> SUCCEEDED/FAILED/CANCELLED`；`INSERT_NODE_ATTEMPT` 的 PENDING/终态时间戳和 `duration_ms` 处理正确。
- **失败/重试**：节点失败写稳定错误码、下游全部 `SKIPPED`、运行 `FAILED`；当前节点保持失败尝试；重试时上游 `SUCCEEDED` 输出从 `WorkflowExecutionState` 重建，不重跑；下游 `SKIPPED` 由 API 恢复为 PENDING（T08）。
- **跳过**：条件节点只走一条分支，未走分支及其全部可达下游插 `SKIPPED` 行（无行则插一次；PENDING 则更新）；有单入边结构保证不会误跳共享下游。
- **运行超时**：按 `execution_ms + 本段实际时间` 与 `run_timeout_minutes` 比较；当前节点 `RUNNING` 置 `CANCELLED/WF_RUN_TIMEOUT`，PENDING 通过 `closePendingNode` 关闭，无行则补一条 CANCELLED；下游 `SKIPPED`，运行 FAILED。已完成节点保留。
- **节点超时**：按节点类型取 `MODEL 300s`/`RETRIEVAL 120s`/其余运行剩余预算的较小值；`WorkflowNodeTimeoutException` 再判断是否已超运行预算，分别映射 `WF_RUN_TIMEOUT`（节点 CANCELLED）与 `WF_NODE_TIMEOUT`（节点 FAILED）。
- **取消**：循环顶部、节点成功后、节点执行中的 `WorkflowNodeGuard` 都检查；取消时当前节点 CANCELLED、剩余 PENDING/RUNNING 节点 CANCELLED，已完成节点保留；不写运行终态（API 已把运行置 CANCELLED）。
- **租约丢失**：节点前、节点后、异常路径都检查 `lease.lost()`，一律不写结果、不写终态，交给新持有者。
- **END 崩溃恢复**：当前节点是 `SUCCEEDED` 的 END 但运行仍 RUNNING 时，用存储的节点输出重新 `assemble` 结果并写运行成功；超过大小上限则 `WF_RESULT_LIMIT/OVER_LIMIT`。
- **脏尝试/重复推进**：节点尝试不属于发布图时 `WorkflowExecutionState.load` 抛 WF_INTERNAL；`startNodeAttempt` 返回 0 时放弃；条件输出缺 `branch` 时 fail-closed。

验证：`WorkflowEngineIntegrationTest` 26 例覆盖串行时间线/引用/结果、条件 TRUE/FALSE/非法、失败+重试不重跑上游、运行超时、取消、租约丢失、结果/节点大小上限、步数上限、遗留 RUNNING、END 恢复、非法图/非法输入/版本节点数不一致、非 RUNNING 不执行、脏节点、条件输出缺 branch、初始租约丢失、节点超时、内部异常、END 缺结果。状态机主路径成立。

**残余**：F-007（步数上限路径不处理遗留 RUNNING）、执行时间在崩溃段的丢失（见剩余风险）、取消与写节点终态竞态下可能留下 PENDING 行（T08 的取消 API 可补，见剩余风险）。

## 节点执行

- **START**：扫描所有节点的 `queryTemplate`/`promptTemplate`，用 `{{input.<field>}}` 白名单校验运行输入必填项；摘要只列字段名，不写值。
- **END**：`resultFrom` 必须是图中已执行的非 END 上游；按来源类型组装 `TEXT/JSON` 结果；END 自身 `output_json` 只存来源/格式/大小，避免把 5 MiB 结果复制成节点输出；引用使用引擎聚合去重后的列表。
- **RETRIEVAL**：`queryTemplate` 渲染；以运行发起人作为 ACL 主体调用 `AgentContextRepository.search(..., false, ...)`，`unrestricted` 固定 false；`topK` 1..20；命中数写 `hitCount`；引用截断到 20 条、片段 500 字符，并批量读取 `knowledge_document.checksum` 写入 `sourceVersion`；查询前后检查 deadline，超时丢弃结果。
- **MODEL**：固定 `purpose=REPORT` 解析路由；`modelId` 必须在链内；分类必须有 labels 且输出精确命中；固定服务端系统提示，用户/上游内容只进 user message；模型流用 `.timeout(remaining)` + 200ms 轮询 guard/deadline，取消时 dispose；调用记录复用 M04 `ModelInvocationService`，`run_id/conversation_id/message_id=NULL`，随后写 `workflow_run_id/workflow_node_run_id`；失败/取消/超时分别记 failed/cancelled。
- **CONDITION**：封闭算子 EQ/NE/GT/GTE/LT/LTE/IN；受控源 RUN_INPUT 和 `hitCount/classificationLabel/decision/status`，字段与节点类型强校验；左右类型必须一致；缺值/类型不符/算子与右值形态不匹配都 `WF_CONDITION_INVALID`，不默认分支；输出 `branch=TRUE/FALSE`。
- **模板占位符**：只允许 `{{input.<field>}}`、`{{node.<id>.summary}}`、`{{node.<id>.classificationLabel}}`、`{{node.<id>.hitCount}}`；未知、未闭合、非标量、未执行节点、缺失字段都失败，不静默空串。
- **大小/步数**：单节点 `output_json` 256 KiB；运行所有节点输出+引用+最终结果 5 MiB（T15 可配）；每次尝试计 1 步，超过 `maxSteps` 运行 `WF_STEP_LIMIT`；都不静默截断。
- **缺失执行器**：APPROVAL/MCP_TOOL 在 T07a/b 前没有执行器，dispatcher 明确抛 `WF_INTERNAL`，节点失败、下游 SKIPPED、运行 FAILED，不会卡在 RUNNING 或静默跳过。

验证：`ModelNodeExecutorTest` 11、`RetrievalNodeExecutorTest` 8、`WorkflowConditionEvaluatorTest` 15、`WorkflowResultAssemblerTest` 10、`WorkflowTemplateRendererTest` 9、`WorkflowNodeDispatcherTest` 3；集成测试用真实 START/END/CONDITION/引擎 + 可控 RETRIEVAL/MODEL 桩。

**残余**：F-002（跨模型降级）、F-005（模型调用关联两步窗口）、F-006（引用恢复 round-trip）、F-008（检索 JDBC 查询超时）。集成测试使用 RETRIEVAL/MODEL 桩，真实两个执行器与引擎循环的组合只在单测层面验证；见“未验证”。

## 图与运行绑定

- `WorkflowGraphJson` 逐层白名单解析（graph/node/config/condition/edge），拒绝未知字段、未知枚举、错误类型、超限；节点 key `[A-Za-z0-9_-]{1,64}`；`schemaVersion=1`；节点/边上限。配置白名单刻意不含 `script/code/http/cron/parallel/loop/subflow`，节点类型封闭为 START/END/RETRIEVAL/MODEL/MCP_TOOL/APPROVAL/CONDITION；未知类型（测试用 `SCRIPT`）在运行入口 `WF_INVALID_GRAPH` 拒绝，不执行任何节点。
- `WorkflowGraph.validateForRun` 校验：恰好 1 个 START、≥1 个 END、入度 ≤1、END 无出边、非 END 恰好 1 条出边、条件节点恰好 TRUE/FALSE 两条且带 condition、所有节点从 START 可达、无环、`resultFrom` 必须是 END 的真实祖先（沿唯一入边回溯）。单入边 + 单出边（条件两支）+ 无环 + 全可达共同保证串行、无并行/合并、无循环、无子流程。
- 运行入口再次解析并校验，`node_count` 必须与版本快照一致；图结构错误 `WF_INVALID_GRAPH`，不执行任何节点。
- `WORKFLOW_ENGINE` 只从 `workflow_version.graph_json` 读取快照，JOIN 同时绑定 `tenant_id`、`workflow_version_id`、`workflow_id`；发布版本不可变由 V15 触发器保护。

**F-003**：没有查询/校验 `graph_sha256`，只做 JOIN + node_count；接受理由见 finding。对“错绑”足够，对“同节点数内容被意外改写”不够；M06 gate 前应记录或补规范化重算。

## 测试 profile 与生产/测试差异

- `WorkflowEngine` 标 `@Profile("!test")`，原因写在类注释：Worker 集成测试共享上下文会启动真实调度槽位，若测试同时手工领取/构造 PENDING 运行，会与后台槽位竞争同一行。
- test profile 下 `WorkflowRunScheduler.start()` 会走 `executor_missing`、不注册槽位；T06a 的 `WorkflowRunSchedulerTest.executorMissing_registersNoSlots` 锁定该行为；`start_registersOneTaskPerSlot` 用 `executorAvailable=true` 的 mock 锁定“有执行器则注册槽位”。
- `WorkflowEngineWiringTest` 用非 test profile 的最小 `ApplicationContextRunner` 验证引擎作为唯一 `WorkflowRunExecutor` Bean 可装配、`WorkflowRuntimeProperties` 存在、分派器可收集执行器。
- 生产/测试行为差异是**有意且可接受**的：生产 profile 装配真实引擎，测试 profile 惰性不领运行；风险是“完整生产 profile context 没有自动化启动覆盖”，见 F-004（P3 accepted，T16a 真实 stack 覆盖）。
- `WorkflowQueueDefaultContextTest` 虽然启动完整 `WorkerApplication`，但使用 `@ActiveProfiles("test")`，因此不能替代生产 profile 启动测试；这点应在 T15/T16a 的启动证据中写清楚。

## 超时语义（检索/模型）

- **模型**：Reactor `.timeout(remaining)` 强制超时，subscribe 的等待循环每 200ms 检查 `lost/cancelled/deadline`；超时/取消时 dispose 订阅。符合“尽力中断模型流”。
- **检索**：查询前后检查 deadline，不硬杀 JDBC；超时后不写结果、节点失败。这与任务说明“协作式、不硬杀 JDBC”一致，但 JDBC 查询本身没有 120s 上限（无 `setQueryTimeout`/`socketTimeout`），慢查询可占槽位超过预算，见 F-008。ADR 的“尽力中断”对模型成立，对检索只保证“结果不落库”，不保证“DB 调用中止”；建议在 RUNBOOK/ADR 写明或补 JDBC 超时。

## 证据复核

| 检查                  | 命令/对象                                                                                | 结果                                                                     |
| --------------------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| EV-1072 JSON          | `.riccon/evidence/TASK-0225/EV-1072.json`                                                | commit=b62fea5、dirty=false、manual=false、exit=0、bytes=80362           |
| EV-1072 日志哈希      | `shasum -a 256 EV-1072-*.log`                                                            | `f86f1de3...a0693`，与 EV JSON 一致                                      |
| 提交归属              | `git diff e73d501 b62fea5 -- apps platform`                                              | 空；b62fea5 仅多 T15 评审文档；代码归属 e73d501                          |
| 独立复跑测试          | `./mvnw -q -pl apps/worker -am test -Dtest='...engine.**,node.**,queue.**,heartbeat.**'` | exit 0；21 个类 157 tests，0 failures/0 errors/0 skipped                 |
| ArchUnit              | `./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest`  | 12/12，0 failures/errors                                                 |
| EV 原 spotless 段     | `-DspotlessFiles='src/(main\|test)/java/.../workflow/(engine\|node)/.*\.java'`           | **匹配 0 文件**，仅 `BUILD SUCCESS`，无 `keeping` 行；不能作为 lint 证据 |
| 正确过滤对照          | `-DspotlessFiles='.*workflow/(engine\|node)/.*\.java'`                                   | 选中 51 文件，**22 needs changes**                                       |
| worker 全量 spotless  | `./mvnw -q -pl apps/worker spotless:check`                                               | **exit 1**，22 个文件；全部为 T06b                                       |
| 干净导出复核          | `git archive 859a2c4` 后同一命令                                                         | **exit 1**，22 个文件（ModelNodeExecutorTest + 21 other），排除缓存污染  |
| API/platform spotless | 对应模块 `spotless:check`                                                                | exit 0                                                                   |

结论：EV-1072 的测试/ArchUnit 段真实有效；spotless 段是假绿，不能支撑“lint 通过”。F-001 记录该问题。

## AC 对账（仅任务子集，不冒充模块 AC）

| AC                           | 任务子集结果                                                                                                | 证据/残余                                                                |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| M06-AC-04 节点时间线与状态   | **pass（任务子集）**：引擎持久化节点状态、开始/结束、耗时、当前节点、摘要；集成测试断言                     | UI 刷新/高亮、审批等待态归 T12a/T07a；本评审不判 UI                      |
| M06-AC-07 失败/重试          | **pass（任务子集）**：A 成功保留、B FAILED、C/END SKIPPED、运行 FAILED；重试 B、A 不重跑、C 继续；单测+集成 | “B 重试仍失败/可再次重试”的 API/UI 归 T08/T12a；本任务不声称整条 AC pass |
| M06-AC-09 条件分支           | **pass（任务子集）**：TRUE/FALSE 只走一支，另一支 SKIPPED；类型不符 WF_CONDITION_INVALID、不默认分支        | UI/等价表单归 T10/T12a                                                   |
| M06-AC-13 运行详情摘要与引用 | **pass（任务子集）**：input/outputSummary、citations 快照、结果 JSON、错误脱敏；不写完整系统提示/凭据       | sourceStatus/ACL 遮蔽/UI 归 T08/T12a/T16a；引用恢复 round-trip 见 F-006  |
| M06-AC-17 限制               | **pass（任务子集）**：引擎执行节点数/步数/节点超时/运行超时/结果大小，稳定错误码、OVER_LIMIT；配置来自 T15  | 并发准入、界面可见、E2E 归 T05/T08/T15/T16a                              |

## 未验证

- M06 模块全量 build/test/coverage、E2E/axe、perf、Trivy/semgrep；本任务只做任务级定向验证。
- 真实浏览器中的运行详情/时间线/引用/结果 UI（T12a）与 `@a11y`（T16a）。
- 真实 `ModelNodeExecutor`/`RetrievalNodeExecutor` 在引擎循环内的组合（集成测试使用桩；两者只有单测）；真实模型/检索外部服务行为不在本任务。
- 生产 profile 完整 Worker context 的自动化启动（F-004）；真实 stack 启动由 T16a/preview 覆盖。
- MODEL 跨模型降级行为（F-002 未实现，无法验证预期路径）。
- T07a/T07b 的 APPROVAL/MCP_TOOL 执行、T08 的 retry/cancel/history API、T16a/T16b 的回滚/性能。
- 清理/删除、保留 180 天、跨租户运行可见性：属 T08/T16a/TEN-M06。

## 剩余风险

1. **崩溃段执行时间丢失**：`execution_ms` 只在节点成功/失败/超时路径累加；进程在节点执行中崩溃时，该段不计入运行预算。重领后 `claim_attempts`（T06a）和节点超时仍兜底，wall-clock 可能略超 30min，但运行不会永久 RUNNING；建议 T15/T08 在指标/超时说明中记录。
2. **取消与节点终态竞态**：若 API 在节点成功写入后、`addExecutionMs` 前取消，`addExecutionMs` 返回 0 且引擎直接返回，可能留下 PENDING 行；运行已是 CANCELLED，不会执行后续节点。T08 的取消路径或后续 worker 轮次可补齐，M06 gate 前复核 UI 是否接受“未执行节点无行/等待”的展示。
3. **T15 残余归因不完整**：T15 评审把全仓 spotless 残余写成单个 `ModelNodeExecutorTest`，实际为 22 个 T06b 文件；不影响 T15 自身（其文件已 clean），但 Lead 需更新残余归因和 TASK-0225 修复范围。
4. **`resultTitle` 契约事实**：它确实在 `contracts/openapi.yaml` 的 `WorkflowNodeConfig`（:8209），只是不在 `WorkflowResult`；worker 解析但不写结果与当前契约一致，T08/T12a 需要决定是否在结果/详情展示。
5. **检索/工具外部调用无法强杀**：模型已用 Reactor dispose，检索仅有协作式 deadline；F-008 接受该残余。
6. **模型调用成本归属**：两步关联窗口（F-005）只在崩溃时少一条 workflow 归属，不影响结果。

## Findings 摘要

| ID    | 级别 | 状态     | 主题                                            | 所有者/到期               |
| ----- | ---- | -------- | ----------------------------------------------- | ------------------------- |
| F-001 | P2   | open     | 22 个 T06b 文件 spotless 失败；EV spotless 假绿 | builder-b2 / T06b done 前 |
| F-002 | P2   | open     | MODEL 折叠 REPORT 链，跨模型降级缺失            | builder-b2 / T06b done 前 |
| F-003 | P2   | accepted | graph_sha256 未校验，只有 JOIN+node_count       | builder-b2 / M06 gate 前  |
| F-004 | P3   | accepted | 生产 profile 完整 context 启动无自动化覆盖      | builder-b2 / M06 gate 前  |
| F-005 | P3   | accepted | 模型调用创建/关联两步窗口                       | builder-b2 / M06 gate 前  |
| F-006 | P3   | accepted | 引用恢复 round-trip 未测                        | builder-b2 / M06 gate 前  |
| F-007 | P3   | open     | 步数上限 + 遗留 RUNNING 节点导致 retry 409      | builder-b2 / T06b done 前 |
| F-008 | P3   | accepted | 检索协作式超时不限制 JDBC 查询时长              | builder-b2 / M06 gate 前  |

## 验证环境

- macOS，JDK 21.0.13，Maven Wrapper，Testcontainers MySQL 8.4.13，仓库 `/Users/ricky/Documents/projects/riccon-work`。
- 评审时 HEAD `0b81ee4`；代码 `e73d501`；EV 录制 `b62fea5`；任务卡 `baseline=0a57d54`、`end=e73d501`。
- 评审期间未运行 `riccon gate check`（按任务要求）。
