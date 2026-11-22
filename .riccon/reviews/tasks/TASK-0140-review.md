---
review_id: REV-TASK-0140-001
module_id: M20
task_id: TASK-0140
reviewer:
  agent: riccon/verifier
  session: ses_dfe67f2ffffe6D0H4EXdzKGb1L
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 694d0e6fa0781d2178970b101e4042b2a4d1797c
  - 613cce3a4d9b7f19341b93a3308872192879acb0
  - 3704d38a6beecc19a46e9b1a2ffb94cf717d4664
head_commit: 3704d38a6beecc19a46e9b1a2ffb94cf717d4664
evidence:
  - EV-0750
  - EV-0751
reviewed_evidence:
  - EV-0750
  - EV-0751
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "M20 gate 前由 Architect 书面认可；任何价格维护端点/批量改价作业（M05+）上线前必须重访"
    title: "价格快照采用“成功终态读取一次”，与“最终成功尝试开始时”的旧口径仍存在在途改价窗口"
    summary: >-
      TASK-0140 按任务验收允许的第二路径，把 ADR-0022 §2.1 从“最终成功尝试开始时”修订为
      “成功终态读取一次”，并同步 docs/ARCHITECTURE.md 的流程图与风险表。实现侧
      ModelInvocationService.succeeded 在写库前只调用一次 findPricing，仅使用最终成功模型的
      价格，不混合多个价格；这与修订后的文字一致。修订明确写了“为什么没有实现尝试开始时”、
      影响边界（调用进行中运维直接改价时在途调用使用新价格）、未来最小实现范围，以及
      “任何价格维护端点或批量改价作业（M05+）上线前必须重访本节”，没有把终态读取伪装成
      “已满足开始时快照”。V11 已应用迁移的列注释（V11:151-152）和 ADR-0021 §5 的迁移片段
      （:225-226）仍保留旧文案；ADR-0022:196 已把二者标注为历史设计记录，但 ADR-0021 本身
      没有指向 §2.1 修订的交叉引用。该残余不改变 M04 无价格编辑端点时的用户可见成本结果。
    accepted_reason: >-
      任务验收明确允许“由 Architect 修订 ADR-0022/架构并在评审记录”的替代路径；当前文档与
      实现一致、差异和重访条件诚实披露，M04 不提供价格编辑端点，风险有界（单次读取、无混价）。
      残余是“在途改价窗口”和旧列注释，不是当前实现缺陷；需 Architect/Lead 在 M20 closeout
      书面认可，并在 M05 价格维护能力上线前实现尝试开始快照或再次书面接受终态语义。
    evidence: >-
      docs/adr/ADR-0022-m04-quota-and-cost.md:18、195-199；
      docs/ARCHITECTURE.md:262、427；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:54、225-226；
      apps/api/src/main/resources/db/migration/V11__model_gateway_depth.sql:151-152；
      apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java:168-191、315-337；
      .riccon/evidence/TASK-0140/EV-0750.json、EV-0751.json。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/builder-cost + riccon/architect"
    due: "M20 gate 前；若保留 Start 语义需同步修订 ADR-0021 §1 或明确为近似口径"
    title: "ModelFirstToken 把 TextBlockStartEvent 计为可见首字，与 ADR-0021 §1 的“首个非空 TextBlockDeltaEvent”存在口径漂移"
    summary: >-
      AgentRunResumeHandler.ModelFirstToken.isVisibleText（:813-820）对任何 TextBlockStartEvent
      返回 true；而 ADR-0021:54 定义首字为“第一个非空 TextBlockDeltaEvent 被发送给客户端”。
      ADR-0022 §2.6:264 写成“首个非空 TextBlockDeltaEvent（或 TextBlockStartEvent）”，
      同一段 :265 又写“首字定义沿用 ADR-0021 §1：第一个非空文本增量”，前后自相矛盾。
      AgentScope 2.0.5 的 ReActAgent 在同一轮 emits Start 后紧跟 Delta（sources 中
      emitBlockEvents/startText），正常非空文本下两者由同一循环创建、时间差在微秒级；
      项目 OpenAiCompatibleChatModel 也过滤空 content（:443-445），因此当前数值影响很小。
      但空/纯空白文本块或未来其他模型客户端仍可能让“没有可见文本”的调用得到非空
      first_token_ms，和 ADR 宣称的 TOOL 无首字语义不一致；也没有 ModelFirstToken 单元测试。
    accepted_reason: >-
      当前实现路径由项目自有 OpenAI 兼容客户端驱动，空 content 被过滤，Start 与首个 Delta
      在同一事件循环创建，实测影响为微秒级；无成本/安全/权限影响，且 ADR-0022 已披露
      “或 TextBlockStartEvent”。残余是定义漂移与文档自相矛盾，不是本任务引入的用户可见缺陷；
      在 M20 gate 前由 Architect 决定统一到 ADR-0021 §1 还是修订 ADR-0021，并补
      start-only/空白 delta/多个 ModelCallStart 的单元测试。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java:767-820；
      docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md:54；
      docs/adr/ADR-0022-m04-quota-and-cost.md:263-265；
      apps/worker/src/main/java/com/riccon/worker/model/service/OpenAiCompatibleChatModel.java:443-445；
      agentscope-core 2.0.5 sources: io/agentscope/core/ReActAgent.java（TextBlockStartEvent/Delta 发射）、
      io/agentscope/core/event/AgentEvent.java（createdAt = Instant.now().toString()）。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/builder-cost"
    due: "M20 gate 前"
    title: "ChatStreamIntegrationTest.setModelPrices 用 WHERE status='ACTIVE' 更新全局 model 表，测试隔离依赖前置 reset"
    summary: >-
      ChatStreamIntegrationTest:595-600 的 setModelPrices 没有按模型 ID 更新，而是更新所有
      ACTIVE 模型；该测试类 @BeforeEach 调用 resetModelGatewayState（WorkerIntegrationTestBase:189-192）
      把全部模型置 DISABLED，随后 chatFixture 只创建一个 ACTIVE 模型，因此当前实际只命中
      该测试自己的模型，未观察到跨测试污染；ReportArtifactIntegrationTest:819-822 已按 modelId
      精确更新。风险是未来新增第二个 ACTIVE 模型或并发/顺序变化时，更新会外溢到其他测试，
      且本类结束后不恢复价格。EV-0751 的全量 360 tests 与本次独立重跑均未出现顺序失败。
    accepted_reason: >-
      前置 reset 把 ACTIVE 集合收敛为单模型，当前无实际污染；EV-0751 全量套件与独立定向
      重跑均通过。属测试隔离强度问题，不是生产缺陷；建议后续把 chatFixture 返回的 modelId
      传入并按 ID 更新，避免依赖“只有一个 ACTIVE 模型”的隐含前提。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/chat/ChatStreamIntegrationTest.java:51-57、98-118、595-600；
      apps/worker/src/test/java/com/riccon/worker/research/ReportArtifactIntegrationTest.java:73-79、819-822；
      apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:189-193；
      .riccon/evidence/TASK-0140/EV-0751 日志（360 tests，0 skipped）。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-cost"
    due: "M20 gate 前"
    title: "报表 GENERATION 的 first_token 测试只断言非空且 >=0，不能证明数值来自 AgentScope 事件"
    summary: >-
      ReportArtifactIntegrationTest:193-228 对 GENERATION 的 firstTokenMs 只断言 isNotNull、
      isGreaterThanOrEqualTo(0)；TestModelStub 正常路径几乎立即返回，即使实现把 first_token_ms
      写成常量 0 也会通过。代码阅读确认 ModelFirstToken 使用 ModelCallStartEvent 与
      TextBlockDeltaEvent 的 createdAt 计算，并非伪造；但测试没有固定事件时间差，无法防止
      未来回归为常量/墙钟。ModelFirstToken 也没有独立单元测试覆盖 start-only、空/空白 delta、
      多个 ModelCallStartEvent、createdAt 不可解析等边界；TOOL attempt 行的 first_token_ms
      未在 IT 中断言。
    accepted_reason: >-
      当前实现经独立代码核对确实来自事件时间戳，且事件格式为 Instant.now().toString()，
      Instant.parse 可解析；测试已覆盖“非空”行为。风险是回归保护弱，不是当前行为错误；
      建议用 TestModelStub.timeSliced 设定首帧延迟并断言下限，或补 ModelFirstToken 单元测试，
      并断言 TOOL 的 model_invocation_attempt.first_token_ms 为 NULL。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/research/ReportArtifactIntegrationTest.java:193-228；
      apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java:669-693、767-820；
      apps/worker/src/test/java/com/riccon/worker/support/TestModelStub.java:378-395（usage 固定 12/8）；
      .riccon/evidence/TASK-0140/EV-0750 日志（ReportArtifactIntegrationTest 11 tests）。
created_at: "2026-11-22T13:00:00+08:00"
---

# REV-TASK-0140-001：TASK-0140 成本快照/报表成本/首字/负 token 独立评审

本文件是任务级独立评审，不参与 M20 模块 gate；模块级 AC、全量 build/lint/typecheck/test/coverage/E2E、可访问性、安全与性能仍由 M20 模块 review 承担。

## 结论

**pass（任务级）**。提交 `694d0e6` 在 `TASK-0140` 写范围内完成了 REV-TASK-0108-001 F-001/F-003/F-004/F-012 的处置：

- F-001 走验收允许的第二路径，把 ADR-0022 §2.1 与 ARCHITECTURE 如实修订为“成功终态读取一次”，写明差异、影响边界和价格维护前的重访条件；实现与文档一致，未伪称“已实现开始时快照”。
- F-003 在报表生成路径与聊天路径都增加了端到端成本断言：固定样例 prompt=12、completion=8、输入价 3.000000、输出价 6.000000 → 0.000084，并断言 PRICED、币种、pricing_version、cost_day、first_token 非空；报表 TOOL 阶段断言 USAGE_UNKNOWN。
- F-004 的 GENERATION first_token 真正来自 AgentScope `ModelCallStartEvent`/`TextBlockDeltaEvent` 的 `createdAt`；TOOL 仅 tool_calls 时保持 NULL，并在 ADR §2.6 书面说明。
- F-012 在写库前把任一为负的 prompt/completion 整组归一为 NULL，按 USAGE_UNKNOWN 记录，价格快照保留；成本快照、repository、审计都使用归一后的值，数据库 V5 CHECK 也禁止负 token。

未发现 P0/P1。存在 1 条 P2 accepted（F-001 在途改价窗口/旧列注释）与 3 条 P3 accepted（F-002 TextBlockStartEvent 口径漂移、F-003 测试全局价格更新、F-004 first_token 测试强度）。所有 finding 均有 owner、due、accepted_reason，无 open finding。

## 评审对象与范围

- 基线 `9b561c1`；代码/测试/文档提交 `694d0e6`（`end_commit`）；证据提交 `613cce3`；HEAD `3704d38`（仅 `.riccon/state.json`）。
- `git diff --name-status 9b561c1..694d0e6` 共 10 个文件：
  - `apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java`
  - `apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java`
  - `apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java`
  - `apps/worker/src/test/java/com/riccon/worker/chat/ChatStreamIntegrationTest.java`
  - `apps/worker/src/test/java/com/riccon/worker/model/ModelCostSnapshotIntegrationTest.java`
  - `apps/worker/src/test/java/com/riccon/worker/research/ReportArtifactIntegrationTest.java`
  - `docs/ARCHITECTURE.md`、`docs/adr/ADR-0022-m04-quota-and-cost.md`
  - `.riccon/state.json`、`.riccon/tasks/TASK-0140.json`
- 全部在 `TASK-0140.write_scope`：三个 worker 主类、三个测试包、ADR-0022、ARCHITECTURE、`.riccon/**`；`apps/worker/src/test/java/com/riccon/worker/chat/**` 已由 Lead 加入 scope，`ChatStreamIntegrationTest` 属允许范围。
- 未修改 `FailoverChatModel`、迁移、ADR-0009、cost API、历史 review/evidence；`613cce3` 只新增 EV-0750/0751 与任务/状态，`3704d38` 只改 `.riccon/state.json`。
- 工作树在评审开始与独立重跑后均干净；无密钥或敏感值新增。

## 证据核查

| 项           | 观察                                                                                                                                                                                               |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EV-0750 JSON | `exit_code=0`、`manual=false`、`dirty=false`、`commit=694d0e6fa0781d2178970b101e4042b2a4d1797c`、`task_id=TASK-0140`、`agent=riccon/builder-cost`                                                  |
| EV-0750 命令 | JSON 与日志头均为 `./mvnw -T1C -pl apps/worker -am test -Dtest='ModelCostSnapshotIntegrationTest,ReportArtifactIntegrationTest,ChatStreamIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| EV-0750 日志 | `ReportArtifactIntegrationTest` 11、`ChatStreamIntegrationTest` 18、`ModelCostSnapshotIntegrationTest` 6，合计 **Tests run: 35, Failures: 0, Errors: 0, Skipped: 0**；`BUILD SUCCESS`              |
| EV-0750 哈希 | SHA-256 `5e8b9cbe907095327af8d899eec8b70d1028157c2c8a96cf965b7d4854797009`；`output_bytes=63005`，与 JSON 一致                                                                                     |
| EV-0751 JSON | `exit_code=0`、`manual=false`、`dirty=false`、`commit=694d0e6`、`task_id=TASK-0140`                                                                                                                |
| EV-0751 命令 | JSON 与日志头均为 `./mvnw -T1C -pl apps/worker -am test`                                                                                                                                           |
| EV-0751 日志 | **Tests run: 360, Failures: 0, Errors: 0, Skipped: 0**；`BUILD SUCCESS`；逐类汇总存在，非只看 BUILD SUCCESS                                                                                        |
| EV-0751 哈希 | SHA-256 `ad383e1409a6f32e4330b2f69e93365e9c85a5021527b803e82f9ff141f534e9`；`output_bytes=204661`，与 JSON 一致                                                                                    |
| 独立重跑     | 与 EV-0750 相同命令，exit 0；`Tests run: 35, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；日志在临时目录，未提交                                                                          |

## AC 逐条

### 1. 价格快照：开始时读取，或 Architect 修订 ADR/架构并记录，AC-11 一致

**pass (with residual F-001)**。实现确实只读一次、只使用最终成功模型价格、无混价；文档修订诚实写明“成功终态读取一次”、未实现“尝试开始时”的原因、影响边界和重访条件（ADR-0022:195-199），ARCHITECTURE 流程图与风险表同步（:262、:427）。V11 是已应用迁移，修改列注释会改变 Flyway checksum，ADR-0022:196 把 V11/ADR-0021 旧文案标为历史设计记录；ADR-0021 本身没有交叉引用，属 F-001 残余。M04 无价格编辑端点时，AC-11 的成本公式、6 位小数、日桶、未定价/未知语义不受影响。

### 2. 报表 PRICED + 固定样例；聊天路径端到端成本断言

**pass**。

- `ReportArtifactIntegrationTest.approved_persistsPricedGenerationInvocationWithUsageAndFirstToken`（:193-228）走真实 outbox → `AgentRunRequestedHandler` → 审批 → `AgentRunResumeHandler` → AgentScope → TestModelStub → MySQL，断言两次调用、TOOL 为 USAGE_UNKNOWN、GENERATION 为 PRICED、prompt=12/completion=8、价格 3.000000/6.000000、金额 0.000084、CNY、pricing_version=v1、cost_day（独立用 Asia/Shanghai 计算）、first_token 非空。
- `ChatStreamIntegrationTest.stream_happyPath_persistsPricedInvocationFromUsage`（:98-118）走 MockMvc SSE → `ChatStreamService` → AgentScope → 同一固定样例，断言同样的 PRICED 字段与金额 0.000084；不是直接调用 `ModelInvocationService` 的单测。
- 固定样例的金额是硬编码期望 `0.000084`，测试没有复用被测的金额转换；tokens 来自 stub 固定 usage 帧（TestModelStub:381、394），价格由测试 SQL 设置。
- TOOL=USAGE_UNKNOWN 已覆盖；测试未断言 TOOL 行的价格/币种字段。按 `costSnapshot` 逻辑，价格齐全但 token 缺失时仍会保留价格快照并写 USAGE_UNKNOWN，因此这不是遗漏写库，只是本任务断言范围聚焦状态与金额；不影响 AC 判定。

### 3. 报表 TOOL/GENERATION first_token_ms

**pass (with residual F-002/F-004)**。

- GENERATION：`AgentRunResumeHandler.awaitGeneration` 从 `ModelCallStartEvent` 到首个文本事件的 `AgentEvent.createdAt` 计算，并传入 `succeeded`；**来自真实 AgentScope 事件，不是墙钟或常量伪造**。AgentEvent 的 `createdAt = Instant.now().toString()`，`Instant.parse` 可覆盖该格式；独立重跑中报表测试通过，证明该路径能解析。
- TOOL：模型仅返回 tool_calls 时没有 TextBlock，`first_token_ms` 为 NULL；ADR-0022 §2.6:264 明确写为“没有首字”而非 AgentScope 不提供。这是可接受的语义；但 AC 原文要求“或书面记录原因并由 Lead 接受”，Lead 的明确 closeout 接受尚未在证据中体现，建议 Lead 在任务 done 时记录。
- 残余：`ModelFirstToken` 把 `TextBlockStartEvent` 也算作可见首字（F-002）；GENERATION 测试只断言非空且 >=0，不能证明数值来自事件（F-004）。

### 4. 负 token

**pass**。`ModelInvocationService.succeeded`（:168-171）在写库前判断任一 token 为负，整组置 NULL；`costSnapshot`（:315-337）在价格齐全但 token 缺失时返回 USAGE_UNKNOWN、金额 NULL，价格/币种保留；`invocationRepository.succeeded` 与 `auditService.modelCompleted` 都使用 `safePromptTokens`/`safeCompletionTokens`。`ModelCostSnapshotIntegrationTest.succeeded_withNegativeUsage_neverWritesNegativeTokensOrAmount`（:155-190）覆盖负 prompt、负 completion，并验证后续合法调用仍按 0.006000 计价。数据库 V5 `chk_model_invocation_tokens`（:137-142）禁止负 token；模型价格也有非负 CHECK（V5:48-50），没有生成负金额的路径。Chat 路径的 SSE completed 事件仍会回显 provider 原始 token（见剩余风险），但写库值已归一。

### 5. 受影响测试与证据

**pass**。EV-0750（定向 35 tests）与 EV-0751（worker 全量 360 tests）均 exit 0、dirty=false、commit=694d0e6，日志/哈希/大小与 JSON 一致；独立重跑定向 35 tests 全部通过。新增测试没有 skip、空断言、吞异常或 retry；全量日志逐类汇总 360，0 skipped。

## 原 REV-TASK-0108-001 finding 对账

| 原 finding                  | 本任务处置                                                             | 本次结论                                  |
| --------------------------- | ---------------------------------------------------------------------- | ----------------------------------------- |
| F-001 价格快照时点          | ADR-0022/ARCHITECTURE 修订为“成功终态读取一次”，记录差异/影响/重访条件 | 文档路径成立；残余 F-001 accepted         |
| F-003 报表/聊天成本无断言   | 报表与聊天端到端 PRICED 固定样例断言                                   | fixed                                     |
| F-004 报表 first_token 未写 | GENERATION 从事件回填；TOOL NULL 书面说明                              | fixed（残余 F-002/F-004 为口径/测试强度） |
| F-012 负 token 负金额       | 整组归一 USAGE_UNKNOWN；DB CHECK 兜底                                  | fixed                                     |

## 独立命令结果

| 命令                                                                                                                                                                             | 结果                                                                                            |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `git diff --name-status 9b561c1..694d0e6`                                                                                                                                        | 10 文件，全部在 `write_scope`；无 FailoverChatModel/迁移/ADR-0009/cost API/历史 review/evidence |
| `git show --name-status 613cce3`                                                                                                                                                 | 仅 EV-0750/0751 日志/JSON + `.riccon/state.json` + `.riccon/tasks/TASK-0140.json`               |
| `git show --name-status 3704d38`                                                                                                                                                 | 仅 `.riccon/state.json`                                                                         |
| `shasum -a 256 .riccon/evidence/TASK-0140/*.log`                                                                                                                                 | 两个哈希与 JSON `output_sha256` 一致                                                            |
| `./mvnw -T1C -pl apps/worker -am test -Dtest='ModelCostSnapshotIntegrationTest,ReportArtifactIntegrationTest,ChatStreamIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` | exit 0；35 tests，0 failures/errors/skips；BUILD SUCCESS（独立重跑）                            |
| `grep "Tests run: 360" EV-0751 日志`                                                                                                                                             | 360 tests，0 failures/errors/skips；BUILD SUCCESS                                               |
| `git status --porcelain`（重跑后）                                                                                                                                               | 空，工作树干净                                                                                  |

## Findings

### F-001（P2，accepted）价格快照终态读取的在途改价窗口

详见 front matter。结论：修订诚实、与实现一致、重访条件明确；不应把它描述为“已实现开始时快照”。残余为 V11/ADR-0021 旧列注释和“调用中运维改价”窗口，owner Architect/Lead，M20 gate 前书面认可，M05 价格维护前重访。

### F-002（P3，accepted）TextBlockStartEvent 计入首字的口径漂移

详见 front matter。ADR-0021 §1 是首个非空 `TextBlockDeltaEvent`；实现把 `TextBlockStartEvent` 也算可见首字，ADR-0022 §2.6 同时写“或 TextBlockStartEvent”和“沿用 ADR-0021 §1”，内部矛盾。当前项目客户端过滤空 content，Start/Delta 同一事件循环，数值影响很小；但空/空白文本块与未来客户端可能产生“无可见文本却有 first_token”的语义偏差。建议二选一：移除 Start 只看非空 Delta，或修订 ADR-0021 并明确近似口径；补 ModelFirstToken 单元测试。

### F-003（P3，accepted）Chat 测试的全局价格更新

详见 front matter。`WHERE status='ACTIVE'` 依赖 `resetModelGatewayState` 的单模型前提；当前全量 360 与独立重跑无顺序失败，但建议按 modelId 精确更新。

### F-004（P3，accepted）first_token 测试强度不足

详见 front matter。GENERATION 只断言非空/>=0，不能防止常量或墙钟回归；建议用 stub 首帧延迟断言下限，或补事件级单元测试，并断言 TOOL 的 attempt first_token 为 NULL。

## 测试隔离与假绿检查

- 新增测试无 `@Disabled`、`assumeTrue`、`Thread.sleep`、retry 包装或吞异常；断言的是 DB 行、SSE 事件和固定金额。
- `ChatStreamIntegrationTest` 每个测试前 `resetModelGatewayState` 把全部模型置 DISABLED，`chatFixture` 只创建一个 ACTIVE 模型，因此 `setModelPrices` 当前只命中本测试模型；报告测试按 modelId 精确更新。未观察到跨类污染；全量 360 tests 与独立定向 35 tests 均无顺序失败（F-003 为强度建议）。
- EV-0751 不是零测试：surefire 汇总 360、逐类汇总 78 条、0 skipped；EV-0750 定向 35、0 skipped。未使用 retry 或掩盖失败的参数。

## 未验证

- M20 模块全量 `build/lint/typecheck/test/coverage/E2E/a11y/security/perf`：未运行；任务评审按任务级节奏只跑定向测试，模块 gate 由后续独立 Verifier 在最终提交上执行。
- API 全量、E2E-M04-06、前端成本页、真实浏览器可访问性：不在本任务范围，未运行。
- 真实 provider（非 TestModelStub）的 usage/首字事件格式、真实 AgentScope 事件序列中的空文本块/多模型调用：未运行；只依据 AgentScope 2.0.5 源码与 stub 集成测试判断。
- 迁移 forward/rollback 与 V11 列注释变更：未运行；本任务未改迁移。
- `model_invocation_attempt.first_token_ms` 的 TOOL/GENERATION 行为：未在 IT 中断言；ADR §2.6 的这句声明只做了代码路径核对。
- Architect 对 ADR-0022 §2.1 修订的正式署名/评审记录：未在提交中找到；建议 Lead/Architect 在 M20 closeout 记录。
- 未运行 `gate check`（按派单要求避免修改 `.riccon/state.json`）；任务评审不参与模块 gate。

## 剩余风险

- **在途改价**：M04 无价格编辑端点，风险有界；M05+ 开放价格维护前必须按 ADR §2.1 重访，否则在途调用会使用新价格，与“尝试开始时快照”预期不同。
- **首字口径**：`TextBlockStartEvent` 与 ADR-0021 §1 的定义漂移；项目客户端过滤空 content 使当前影响很小，但未来模型客户端或空/空白文本块可能造成早计时或 TOOL 非空 first_token。
- **测试强度**：Chat 测试的全局价格更新、GENERATION first_token 只断言非空；后续重构可能在测试仍绿的情况下改变语义。
- **旧列注释**：V11:151-152 与 ADR-0021:225-226 仍写“最终成功尝试开始时”；ADR-0022:196 已标历史，但 ADR-0021 本身无交叉引用，建议下次价格相关修订时补引用或新迁移改 COMMENT。
- **Chat SSE 负 token**：`ChatStreamService` 的 completed 事件仍回显 provider 原始 `promptTokens`/`completionTokens`；若上游返回负值，DB 已归一但 SSE 可能显示负 token。当前不在 F-012 验收范围，建议后续在事件边界统一归一。
- **TOOL 首字接受**：ADR §2.6 的理由充分，但 AC 要求 Lead 接受；M20 closeout 需要 Lead 明确记录，否则该 AC 的“书面接受”条件不完整。

## 独立性声明

本评审使用独立会话 `ses_dfe67f2ffffe6D0H4EXdzKGb1L`，从基线 `9b561c1` 读取提交 diff、任务/模块卡、ADR-0021/0022、ARCHITECTURE、PRODUCT/ACCEPTANCE 相关段落、worker 源码与测试、EV-0750/0751 JSON/日志，并核对 AgentScope 2.0.5 源码中的事件语义。独立重跑了 EV-0750 的定向测试（35/35 通过）；未采信 builder 总结。未修改生产源码、测试、迁移、合同、ADR、历史评审或历史证据；未提交任何代码。

## Gate

未运行 `riccon gate check --module M20`（派单明确要求避免修改 state）。任务评审不参与模块 gate；TASK-0140 可进入 Lead closeout，但 F-001 需 Architect/Lead 书面认可、F-002/F-003/F-004 需在 M20 gate 前按 accepted 字段跟踪。M20 的模块 review 仍需在最终提交上跑全量合同命令并绑定 `dod_evidence`，不能以本任务级定向证据替代。
