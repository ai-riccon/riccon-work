---
review_id: REV-TASK-0224-002
module_id: M06
task_id: TASK-0224
previous_review: REV-TASK-0224-001
reviewer:
  agent: riccon/verifier
  session: ses_def55da57ffeZ1VyzMx63SOf70
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 41634aada79c81a8f9c588c379aa9dd1dd29abbe
  - ccdc9b6eaf26bcc4971c325310b46e548a057313
head_commit: ccdc9b6eaf26bcc4971c325310b46e548a057313
code_commit: 41634aada79c81a8f9c588c379aa9dd1dd29abbe
evidence_commit: 41634aada79c81a8f9c588c379aa9dd1dd29abbe
baseline_commit: ccdc97ed8e0b967587ca750e1e82fef30874c067
task_card_baseline_commit: ccdc97ed8e0b967587ca750e1e82fef30874c067
review_scope: >-
  M06 T06a 第二轮复审，只审 REV-TASK-0224-001 F-001 的修复及相关回归：默认配置
  （outbox scheduling-enabled=true）下 Worker context 启动、显式 taskScheduler 唯一性与 outbox/workflow
  三池隔离、WorkflowQueueDefaultContextTest 是否真实未关闭 outbox 调度、OutboxLaneScheduler
  default+heavy 启动、J2 outbox/connector @Scheduled 装配回归、drainPendingRuns 测试隔离、范围与
  EV-1067 复核，以及独立重跑默认 context + T06a 测试 + ArchUnit。不复审 T06a 全部队列细节（见
  REV-TASK-0224-001），不运行模块全量 E2E/perf/coverage/security，不评审 T06b/T15/T16b。
dod:
  acceptance: true
  build: true
  tests: true
  regression: true
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
dod_note: >-
  F-001（P1）已修复：显式 `taskScheduler` 恢复默认配置 Worker 启动，`WorkflowQueueDefaultContextTest`
  在未关闭 outbox 调度的情况下启动真实 Worker 上下文，并断言唯一 `taskScheduler`、outbox 使用它、
  workflow 槽位/心跳池独立。EV-1067 与独立复跑均通过（58 个 workflow 测试、16 个真实 MySQL 用例、
  ArchUnit 12/12、spotless）。acceptance 记 true，但按 **pass (with residual)** 理解：F-002（T06b 前
  executor_missing 不注册槽位）与 F-003（上一轮 F-002 节点记账/retry）为 accepted P3；T15 的
  `taskScheduler` 复用约束已记录到 TASK-0236。未运行 J2 全量 outbox/connector 业务回归、模块 E2E/perf/
  coverage/security，不能把本轮启动装配通过当成那些范围的通过。
findings:
  - id: F-001
    severity: P1
    status: fixed
    owner: riccon/builder-b2
    title: "显式 taskScheduler 恢复默认配置 Worker 启动；原 P1 已关闭"
    summary: >-
      `WorkflowQueueConfiguration` 现显式提供名为 `taskScheduler` 的 `ThreadPoolTaskScheduler`
      （`WorkflowQueueConfiguration.java:56-66`），池大小与线程前缀继续取
      `spring.task.scheduling.pool.size` / `spring.task.scheduling.thread-name-prefix`；workflow
      槽位池/心跳池仍为两个独立 `ScheduledExecutorService`，通过 `@Qualifier` 注入，不与共享池共用线程。
      新增 `WorkflowQueueDefaultContextTest` 不继承 `WorkerIntegrationTestBase`、不设置
      `riccon.worker.outbox.scheduling-enabled=false`，用独立 MySQL 容器启动完整 `WorkerApplication`
      默认上下文；断言 outbox scheduling 为 true、legacy 为 false、`OutboxLaneScheduler` 存在、
      `enabledLanes()` 非空、唯一 `taskScheduler` 为 `ThreadPoolTaskScheduler`、三池互不相同、
      outbox/工作流调度字段分别绑定共享池/独立端口（`WorkflowQueueDefaultContextTest.java:53-56,
      161-193`）。EV-1067 日志显示默认 context 正常启动并输出
      `outbox.lane.scheduler.started lanes=[DEFAULT, HEAVY]`（日志第 99 行），且不再出现上一轮的
      `TaskSchedulerRouter`“More than one ScheduledExecutorService bean”提示或
      `No qualifying bean of type TaskScheduler` 错误。独立复跑 EV-1067 同命令 exit 0：58 个 workflow
      测试（16 个真实 MySQL：15 个既有集成用例 + 1 个默认 context 用例）、ArchUnit 12/12、
      spotless 通过；F-001 已关闭。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/workflow/queue/config/WorkflowQueueConfiguration.java:56-66；
      apps/worker/src/test/java/com/riccon/worker/workflow/queue/config/WorkflowQueueDefaultContextTest.java:53-56,161-193；
      .riccon/evidence/TASK-0224/EV-1067-2026-11-25T11-40-47-570+08-00-mvnw-q-pl-apps-worker-am-test-dtest-com-riccon-w.log:99；
      独立复跑 EV-1067 同命令 exit 0（58 workflow tests / ArchUnit 12/12 / spotless）
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "T06b 落地前 / M06 gate 前"
    title: "T06b 前没有 WorkflowRunExecutor 时调度器只记 executor_missing、不注册槽位"
    summary: >-
      `WorkflowRunScheduler.start()` 在 `poller.executorAvailable()` 为 false 时只记录
      `workflow.queue.scheduler.executor_missing claim_slots=0` 并返回，不注册任何槽位
      （`WorkflowRunScheduler.java:94-97`）；`WorkflowRunQueuePoller.pollOnce` 也在 executor 缺失时
      先返回，不领取 PENDING 运行。EV-1067 默认 context 日志与既有单测均显示该路径
      （`WorkflowRunSchedulerTest.executorMissing_registersNoSlots`），没有把运行领走后无人推进。
    accepted_reason: >-
      T06a 只提供队列/租约/调度端口，执行器是 T06b 的 Bean；在 T06b 未落地前保持队列惰性，比领走运行
      后无人执行安全，且不会产生外部副作用。T06b 注册 `WorkflowRunExecutor` 后启动器会自动注册槽位；
      该行为已有单测锁定，风险有界，不阻塞 T06a 验收。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/workflow/queue/service/WorkflowRunScheduler.java:94-97；
      apps/worker/src/test/java/com/riccon/worker/workflow/queue/service/WorkflowRunSchedulerTest.java:110-123；
      .riccon/evidence/TASK-0224/EV-1067-2026-11-25T11-40-47-570+08-00-mvnw-q-pl-apps-worker-am-test-dtest-com-riccon-w.log:69,100
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前"
    title: "上一轮 F-002：WF_INTERNAL 节点记账失败时 POST /retry 可能 409（继续 accepted）"
    summary: >-
      `WorkflowRunQueueJdbc.markClaimExhausted` 把运行置 FAILED 后，节点失败记账在独立语句中执行；
      节点记账异常只记 `workflow.claim.exhausted_node_bookkeeping_failed` ERROR 并返回 true
      （`WorkflowRunQueueJdbc.java:231-280`），而 API `WorkflowRunService.retry` 要求最新节点为
      FAILED（`WorkflowRunService.java:294-301`）。在 DB/图快照异常时运行已是 WF_INTERNAL，但
      `POST /retry` 会拒绝；该路径有界、非静默，且不影响正常耗尽路径。本轮未修改该代码，继续按
      REV-TASK-0224-001 的 accepted 记录跟踪。
    accepted_reason: >-
      只发生在运行已进入 FAILED 后的节点记账异常或图快照损坏；实现优先保证终态与耗尽指标/审计，
      避免运行留在 RUNNING 后无限重领。不扩大租户读取/执行边界；由 T08/T15 在 retry 错误提示或后续
      事务化修复中复核，M06 gate 前确认是否仍接受。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/workflow/queue/persistence/WorkflowRunQueueJdbc.java:231-280；
      apps/api/src/main/java/com/riccon/api/workflow/run/service/WorkflowRunService.java:294-301；
      .riccon/reviews/tasks/TASK-0224-review.md:267-289
evidence:
  - EV-1063
  - EV-1067
reviewed_evidence:
  - EV-1063
  - EV-1067
notes: >-
  任务卡 status=review、owner=riccon/builder-b2、baseline=ccdc97e、end=41634aa（短 SHA，完整为
  41634aada79c81a8f9c588c379aa9dd1dd29abbe）、evidence=[EV-1063, EV-1067]；HEAD=ccdc9b6 只多
  T15 的 `.riccon/tasks/TASK-0236.json`/state 更新，`git diff 41634aa..HEAD -- apps platform contracts
  docs` 为空。EV-1067：commit=41634aa、dirty=false、exit_code=0、manual=false、agent=riccon/builder-b2；
  日志 47,626 B，SHA-256 `961f8c2ca9837fde925370839e92188746f2a2fe217ea910f6b20df2e480c4af` 与 EV JSON
  一致；独立复跑同一命令 exit 0。T15 复用约束已由 ccdc9b6 写入 TASK-0236 验收：复用 T06a 显式
  `taskScheduler`，不要在 workflow/config 重复声明同名 Bean。评审期间未修改源码/测试/契约/PRODUCT/
  AGENTS/.riccon 状态；只写本评审文件；未运行 gate check、全量 E2E/perf/coverage/security。
created_at: "2026-11-25T11:51:15+08:00"
---

# REV-TASK-0224-002：M06 T06a F-001 修复与默认启动复审

## 结论

**通过（pass）**。上一轮 `REV-TASK-0224-001` 的 P1 F-001 已修复：`WorkflowQueueConfiguration`
显式提供唯一的 `taskScheduler`，默认配置（outbox scheduling enabled）下 Worker 完整 context 可以启动，
`OutboxLaneScheduler` 正常输出 `lanes=[DEFAULT, HEAVY]`，workflow 槽位池/心跳池仍与共享调度池隔离。
EV-1067 clean 且独立复跑通过；58 个 workflow 测试、ArchUnit 12/12、spotless 均通过。F-002（T06b 前
`executor_missing`）与 F-003（上一轮节点记账/retry 残余）为 accepted P3，不作为新阻断。

## F-001 修复验证

- **显式共享池**：`WorkflowQueueConfiguration.java:56-66` 提供 `@Bean(name = "taskScheduler")`
  `ThreadPoolTaskScheduler`，池大小取 `spring.task.scheduling.pool.size`、线程前缀取
  `spring.task.scheduling.thread-name-prefix`，并设置守护线程与 remove-on-cancel。该 Bean 与 Boot
  自动配置的 `taskScheduler` 同名，使 `OutboxLaneScheduler` 与 `@Scheduled` 重新获得调度器。
- **唯一性**：`WorkflowQueueDefaultContextTest` 用
  `applicationContext.getBeansOfType(TaskScheduler.class)).containsOnlyKeys("taskScheduler")`
  锁定只有一个 `TaskScheduler`（`WorkflowQueueDefaultContextTest.java:192`）；`taskScheduler`
  必须是 `ThreadPoolTaskScheduler`（`:173-174`）。
- **outbox 使用它**：测试用 `ReflectionTestUtils.getField(outboxLaneScheduler, "taskScheduler")`
  断言同一实例（`:188-189`）；EV-1067 默认 context 日志第 99 行显示
  `outbox.lane.scheduler.started lanes=[DEFAULT, HEAVY]`。
- **workflow 池独立**：测试断言 `slotExecutor`、`heartbeatExecutor` 与共享池的内部 executor 互不
  相同，`runTaskScheduler` 与 `heartbeatTaskScheduler` 不同，且槽位/心跳池大小分别取
  `WorkflowQueueProperties` 的配置（`:175-186`）；`WorkflowRunScheduler` 的 `taskScheduler` 字段
  绑定独立 `runTaskScheduler`（`:190-191`）。
- **默认配置真实启动**：`WorkflowQueueDefaultContextTest` 不继承 `WorkerIntegrationTestBase`，因此
  不继承后者的 `riccon.worker.outbox.scheduling-enabled=false`；它使用独立 MySQL 容器、执行 V1–V15
  迁移并启动完整 `WorkerApplication`（`:49-83`），断言
  `riccon.worker.outbox.scheduling-enabled=true`、legacy=false、`OutboxLaneScheduler` 存在且
  `enabledLanes()` 非空（`:165-171`）。EV-1067 日志显示该 context 启动成功，随后 Hikari 正常关闭。
- **上一轮失败信号消失**：EV-1067 日志不再出现 `TaskSchedulerRouter` 的“More than one
  ScheduledExecutorService bean”提示，也没有 `No qualifying bean of type TaskScheduler`；独立复跑
  同样通过（exit 0）。
- **独立复跑**：执行 EV-1067 原命令（workflow 测试 + `ModuleBoundaryTest` + `spotless:check`），
  exit 0；surefire reports 显示 12 个 workflow 测试类共 58 tests，fail/error/skip 均为 0；其中
  15 个既有真实 MySQL 集成用例 + 新增 `WorkflowQueueDefaultContextTest` 1 个真实 MySQL 用例，
  共 16 个真实 MySQL 用例；ArchUnit 12/12。

## 默认启动、J2 outbox 与 connector `@Scheduled`

- `OutboxLaneScheduler` 的默认启动日志明确为 `lanes=[DEFAULT, HEAVY] heavyConcurrency=2`，说明
  default/heavy 两条通道都装配成功（EV-1067 第 99 行；独立复跑同样输出）。
- 共享 `taskScheduler` 唯一后，Spring 的 `ScheduledAnnotationBeanPostProcessor` 直接解析该
  `TaskScheduler`；EV-1067 没有 `TaskSchedulerRouter` fallback 提示，说明 `@Scheduled` 与 outbox
  不再落到单线程 fallback。
- J2 的 `OutboxLaneScheduler`/`OutboxPoller`/`agent_run_outbox` 源码与 V15 未改动（本轮 diff 只含
  T06a 三个文件）；`ConnectorSyncScheduler` 的 `@Scheduled` 仍由同一唯一 `taskScheduler` 解析。
- 本任务只验证启动装配层；未运行 J2 outbox 全量投递/失败重试或 connector 真实同步行为，J2 业务级
  回归仍由 M02/M05/M06 模块测试与 T15/T16 覆盖，不能把本轮启动通过写成 J2 全链路 pass。

## 测试隔离与 `drainPendingRuns`

- `WorkflowQueueTestSupport.drainPendingRuns()` 现在更新 `PENDING` 与 `RUNNING` 两类行，统一写
  `status='RUNNING'`、`lease_owner='test-drain'`、1 天租约（`WorkflowQueueTestSupport.java:54-64`）。
- 它只在三个队列/心跳集成测试类的 `@BeforeEach` 中调用，且都在创建本测试 fixture **之前**：
  `WorkflowRunClaimerIntegrationTest.java:41-44`、`WorkflowRunQueuePollerIntegrationTest.java:73-76`、
  `WorkflowLeaseHeartbeatIntegrationTest.java:63-66`。因此测试自己随后创建的短租约/过期租约
  （claim 过期重领、lease_taken_over、cancelled、并发领取等）不会被 drain 覆盖，真实租约/续期断言
  仍由测试自己的行和状态驱动。
- 该 helper 只存在于 `src/test`，不进入生产代码，也不会改变 claim/renew/lease SQL；它只是隔离跨测试
  残留的全局 `workflow_run` 行，避免前一个用例的短租约在后续用例执行期间到期后“抢单”。这不是对
  生产租约逻辑的替代或放宽。
- 当前 Surefire 未配置 parallel（未发现 `parallel`/`groups` 过滤配置），测试类顺序执行；若未来启用并行
  或 external 共享热栈同 namespace 并行分片，这个全局 UPDATE 可能影响其他测试，需要收窄/加锁；本轮
  EV 与独立复跑均串行通过。该风险属 test isolation residual，不影响 T06a 功能验收。

## 范围与回归

- `git diff --name-status ccdc97e 41634aa` 只有 3 个文件：
  `WorkflowQueueConfiguration.java`、`WorkflowQueueDefaultContextTest.java`、
  `WorkflowQueueTestSupport.java`，全部落在 TASK-0224 的 `write_scope`（queue/** 主代码或测试）内。
- 未改 `contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、`V15__workflow_studio.sql`、`apps/api/**`、
  `agent_run_outbox`/J2 源码；EV-1067 的 ArchUnit 12/12 与 spotless:check 通过。
- T15 约束已记录：`ccdc9b6` 更新 `.riccon/tasks/TASK-0236.json:20`，要求“复用 T06a 显式 taskScheduler，
  不要在 workflow/config 重复声明同名 Bean；RUNBOOK 说明 outbox/工作流槽位/心跳三池与 Hikari 容量关系”。
  当前 `taskScheduler` 位于 `WorkflowQueueConfiguration` 是 T06a 写范围内的最小修复；T15 不要新增
  第二个同名 Bean，否则会与唯一性/启动测试冲突。
- 任务卡 `end_commit` 记录为短 SHA `41634aa`，EV-1067 与评审使用完整
  `41634aada79c81a8f9c588c379aa9dd1dd29abbe`；git 解析可接受短 SHA，不影响证据归属。

## 未验证

- 未运行 M06 模块全量 build/test/coverage/E2E/perf/security、真实浏览器 E2E/axe/键盘旅程；属模块 gate
  或 T16a。
- 未运行 J2 outbox 全量投递/失败重试、connector 真实同步；本轮只确认默认启动装配与调度器唯一性。
- 未评审 T06b 串行引擎/节点执行、T15 RUNBOOK/告警/回滚演练、T16b；这些按计划归后续任务。
- 未验证 `WorkflowQueueDefaultContextTest` 在 external 共享热栈并行分片下的表现；当前 EV/独立复跑为
  标准 Testcontainers 串行。

## Findings

### F-001（P1，fixed）：默认配置 Worker 启动失败已修复

- 显式 `taskScheduler` Bean（`WorkflowQueueConfiguration.java:56-66`）使 Boot 不再因 workflow 的
  两个 `ScheduledExecutorService` 回退；`OutboxLaneScheduler` 与 `@Scheduled` 均获得唯一共享调度器。
- `WorkflowQueueDefaultContextTest.java:53-56,161-193` 在未关闭 outbox 调度的完整 Worker context 中
  断言唯一 `taskScheduler`、outbox 字段绑定、三池独立与池大小；EV-1067 第 99 行确认
  `outbox.lane.scheduler.started lanes=[DEFAULT, HEAVY]`，无 `TaskSchedulerRouter` fallback。
- 独立复跑 EV-1067 同命令 exit 0，58 tests（16 真实 MySQL）/ArchUnit 12/12/spotless 通过。上一轮
  F-001 的启动回归已关闭，无残余 P1。

### F-002（P3，accepted）：T06b 前 `executor_missing` 不注册槽位

`WorkflowRunScheduler.start()` 在 `poller.executorAvailable()` 为 false 时只记
`workflow.queue.scheduler.executor_missing claim_slots=0` 并返回（`WorkflowRunScheduler.java:94-97`）；
`WorkflowRunQueuePoller.pollOnce` 也不会领取运行。`WorkflowRunSchedulerTest.executorMissing_registersNoSlots`
锁定该行为。T06b 注册 `WorkflowRunExecutor` 后自动启用槽位；在此之前队列惰性比领走无人执行安全。
owner=riccon/builder-b2，due=T06b 落地前/M06 gate 前，不阻塞。

### F-003（P3，accepted）：上一轮节点记账/retry 残余继续跟踪

`WorkflowRunQueueJdbc.markClaimExhausted` 的运行终态与节点失败记账不在同一事务；节点记账失败时只
ERROR 日志并返回 true，API `WorkflowRunService.retry` 要求最新节点 FAILED，极端 DB/图快照异常下
`POST /retry` 可能 409。该路径有界、非静默，不影响正常耗尽路径；按上一轮 accepted 继续跟踪，
owner=riccon/builder-b2，due=M06 gate 前，由 T08/T15 在 retry 错误提示或后续事务化修复中复核。

## 剩余风险

- `WorkflowQueueDefaultContextTest` 对 `enabledLanes()` 只断言非空（`:167`），未精确锁定
  `DEFAULT`+`HEAVY`；EV-1067 日志已证实两通道，但若环境覆盖 `RICCON_WORKER_OUTBOX_LANES` 为单通道，
  测试仍可能通过。建议 T15/T06b 前补精确集合断言；当前行为正确，不构成阻断。
- 显式 `ThreadPoolTaskScheduler` 只复制了 pool size/前缀/daemon/remove-on-cancel，未消费
  `spring.task.scheduling.shutdown.*` 或自定义 `TaskDecorator`；当前 `application.yml` 只设置 pool
  size/thread-name-prefix，故与 Boot 行为等价。T15 若引入 shutdown/TaskDecorator 配置需同步扩展该 Bean。
- `drainPendingRuns` 是全局测试 helper；当前串行测试无影响，未来并行/external 共享 namespace 需收窄
  范围或加锁。
- 新默认 context 测试类未标 `@Tag("integration")`（不继承 base）；当前 CI 无 tag 过滤，EV 已包含它；
  若后续按 tag 选择测试，需补 tag 或纳入 T15/T16 的默认启动回归清单。
- 任务卡 `end_commit` 为短 SHA；建议 Lead/状态维护时归一为完整 SHA（不影响本次技术判定）。

## 证据

- **EV-1067**：commit `41634aada79c81a8f9c588c379aa9dd1dd29abbe`、dirty=false、exit_code=0、
  manual=false、agent=riccon/builder-b2；日志 47,626 B，SHA-256
  `961f8c2ca9837fde925370839e92188746f2a2fe217ea910f6b20df2e480c4af`，与 EV JSON 一致；日志第 99 行
  `outbox.lane.scheduler.started lanes=[DEFAULT, HEAVY]`，无 TaskSchedulerRouter fallback。
- **EV-1063**：上一轮 clean 基线（a7870c4），用于保留 T06a 队列/租约/心跳主体证据；本轮修复不改变其
  结论。
- 独立复跑：EV-1067 同命令 exit 0；12 个 workflow 测试类共 58 tests（16 真实 MySQL）、ArchUnit 12/12、
  spotless 通过。
- 范围：`git diff --name-status ccdc97e 41634aa`（3 files，全部在 T06a write_scope）；T15 复用约束见
  `.riccon/tasks/TASK-0236.json:20`。
