---
review_id: REV-TASK-0224-001
module_id: M06
task_id: TASK-0224
reviewer:
  agent: riccon/verifier
  session: ses_def55da57ffeZ1VyzMx63SOf70
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - a7870c43ec488b48add4064e6a7be312ef1cb827
  - 0fea9304a4509904cca84ea61cce9c2c0339fbf3
head_commit: 4a709bb4f0d7b5a4669bea29a0523c2e0068a73c
code_commit: a7870c43ec488b48add4064e6a7be312ef1cb827
evidence_commit: 0fea9304a4509904cca84ea61cce9c2c0339fbf3
baseline_commit: a65282be9f4ca24e523b51b9274a593354a4f243
task_card_baseline_commit: a65282be9f4ca24e523b51b9274a593354a4f243
review_scope: >-
  M06 T06a（TASK-0224）独立任务评审：`workflow_run` 持久队列领取（4 列、`FOR UPDATE SKIP LOCKED`、
  batch=1、条件更新、claim_attempts/WF_INTERNAL）、非法租户 fail-closed、独立槽位池/心跳池、
  先心跳后执行、租约续期与丢失/取消、启动校验、空队列退避、宽限停机、指标/日志/审计、TM-85
  跨租户领取边界，以及 EV-1063 的 commit/dirty/hash 与独立复跑。不运行模块全量 build/test/
  coverage/E2E/perf/security 扫描，不评审 T06b 串行引擎/节点执行、T15 RUNBOOK/演练或 T16a
  真实浏览器旅程，不替代 M06 模块 review、Product 预验收或用户最终验收。
dod:
  acceptance: false
  build: true
  tests: true
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
  acceptance 记 false：F-001（P1）导致 Worker 在生产默认配置（outbox 调度开启）启动失败，直接
  违反任务验收中“不触碰 J2 outbox/与既有调度池隔离”的要求。build/tests 记 true 指本次定向
  Maven 编译、57 个 workflow 测试、ArchUnit 12/12 与 spotless 全绿并在独立复跑中 exit 0；这些
  绿测由 `WorkerIntegrationTestBase` 关闭 outbox 调度，未覆盖默认启动路径，不能否证 F-001。
  security 记 true 仅指本任务 TM-85 源码边界评审（4 列、租户上下文、denied 审计）未发现新增
  越权路径；未运行 Trivy/semgrep。coverage/E2E/perf/a11y/migration 属 M06 模块 gate 或 T15/T16a，
  本任务不判定。AC-04/17 的完整对账仍依赖 T05/T15/T16a，本评审不把 T06a 子集冒充整条 AC pass。
findings:
  - id: F-001
    severity: P1
    status: open
    owner: riccon/builder-b2
    due: "TASK-0224 done 前（修复并补启动回归测试）"
    title: "两个 ScheduledExecutorService Bean 使 Spring Boot 不再创建 taskScheduler，默认配置下 Worker 因 OutboxLaneScheduler 缺 TaskScheduler 无法启动"
    summary: >-
      `WorkflowQueueConfiguration` 将 `workflowRunSlotExecutor` 与 `workflowHeartbeatExecutor`
      声明为两个 `ScheduledExecutorService` Bean（`WorkflowQueueConfiguration.java:40-55`）。Spring Boot
      4.1.2 的 `TaskSchedulingConfigurations$TaskSchedulerConfiguration` 使用
      `@ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})`；这两个 Bean
      使自动配置的 `taskScheduler`（`spring.task.scheduling.pool.size=4`）不再创建。EV-1063 日志第 74 行
      已出现 `TaskSchedulerRouter` 的 INFO：`More than one ScheduledExecutorService bean exists within
      the context, and none is named 'taskScheduler' ... [workflowRunSlotExecutor, workflowHeartbeatExecutor]`。
      Worker 默认配置 `riccon.worker.outbox.scheduling-enabled: true`（`application.yml:136`），而
      `OutboxLaneScheduler` 构造器第 3 个参数要求 `TaskScheduler`（`OutboxLaneScheduler.java:63-72`）。
      独立启动探针（`WorkerApplication` + test profile，outbox enabled、fake DB、schema 校验关闭）在
      context refresh 阶段失败：`No qualifying bean of type
      'org.springframework.scheduling.TaskScheduler' available`，`outboxLaneScheduler` 无法创建。
      期望：Worker 正常启动，outbox 调度继续使用 `spring.task.scheduling.pool.size`，workflow 槽位/心跳
      使用独立池；观察：默认配置下 Worker 启动失败，J1/J2 outbox 与 connector `@Scheduled` 一起不可用。
      这不仅是隔离性问题，而是对 J2 的运行时回归，违反 TASK-0224 验收“不触碰 J2 outbox”和
      “与 `spring.task.scheduling.pool.size`/`OutboxLeaseHeartbeat` 隔离”的实质要求。
      为什么 EV-1063 没发现：`WorkerIntegrationTestBase.java:67` 把
      `riccon.worker.outbox.scheduling-enabled=false` 固化在测试基类，15 个真实 MySQL 集成测试和
      `WorkerApplicationTests` 都不实例化 `OutboxLaneScheduler`，所以定向 57 个测试不可能暴露该启动回归。
      修复方向：为 Boot 提供显式 `TaskScheduler`（例如 `ThreadPoolTaskScheduler`，命名 `taskScheduler`，
      继续消费 `spring.task.scheduling.pool.size`），或把 workflow 执行器封装在不是
      `ScheduledExecutorService` Bean 的端口/包装类型内，避免关闭 Boot 的调度自动配置；并在
      `apps/worker/src/test/java/com/riccon/worker/workflow/queue/**` 或受影响测试范围增加“outbox enabled
      时 Worker 上下文可启动且 `TaskScheduler`/`OutboxLaneScheduler` 存在”的回归测试。P1 必须修复，
      不得 accepted。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/workflow/queue/config/WorkflowQueueConfiguration.java:40-55；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxLaneScheduler.java:63-72；
      apps/worker/src/main/resources/application.yml:136；
      apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java:67；
      .riccon/evidence/TASK-0224/EV-1063-2026-11-25T11-02-52-868+08-00-mvnw-q-pl-apps-worker-am-test-dtest-com-riccon-w.log:74；
      独立启动探针输出：`No qualifying bean of type 'org.springframework.scheduling.TaskScheduler' available`
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-b2
    due: "M06 gate 前"
    title: "WF_INTERNAL 的节点失败记账与运行终态不在同一事务，DB/图快照异常时 POST /retry 可能不可用"
    summary: >-
      `WorkflowRunQueueJdbc.markClaimExhausted`（`WorkflowRunQueueJdbc.java:231-280`）先把
      `workflow_run` 置 `FAILED`，再单独更新 `workflow_node_run` 或补建失败尝试；节点记账异常时只写
      `workflow.claim.exhausted_node_bookkeeping_failed` ERROR 日志并返回 true（`:268-279`）。API
      `WorkflowRunService.retry` 要求最新节点状态为 `FAILED`（`WorkflowRunService.java:294-301`），
      若节点记账失败，运行虽已是 WF_INTERNAL/FAILED，`POST /retry` 会以 409“node is not a failed node”
      拒绝，和 OPS-M06-008“保证 POST /retry 可用”存在残余缺口。该路径只在运行终态已写入后遇到 DB
      异常或发布图快照不可解析时触发，失败会留日志、指标/审计仍可记录，非静默；现有集成测试覆盖了
      节点存在/不存在两条成功路径，未覆盖记账失败路径。
    accepted_reason: >-
      只在运行已进入 FAILED 后的节点记账异常或图快照损坏时发生，属于有界的数据/基础设施故障路径；
      实现优先保证运行终态与 claim 耗尽指标/审计，避免把运行留在 RUNNING 后无限重领。该风险不扩大
      跨租户读取或执行边界，也不影响正常耗尽路径的验收；由 T08/T15 在 retry 错误提示或后续
      事务化修复中处理，M06 gate 前复核是否仍接受。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/workflow/queue/persistence/WorkflowRunQueueJdbc.java:231-280；
      apps/api/src/main/java/com/riccon/api/workflow/run/service/WorkflowRunService.java:294-301；
      apps/worker/src/test/java/com/riccon/worker/workflow/queue/service/WorkflowRunQueuePollerIntegrationTest.java:137-182；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:1013
evidence:
  - EV-1063
reviewed_evidence:
  - EV-1063
notes: >-
  任务卡 status=review、owner=riccon/builder-b2、baseline=a65282b、end=a7870c4、evidence=[EV-1063]；
  评审时 HEAD=4a709bb（相对 0fea930 只多 T14/T14b 的 `.riccon` 评审/状态提交，`git diff a7870c4..HEAD
  -- apps platform contracts docs` 为空）。EV-1063 命令为 `./mvnw -q -pl apps/worker -am test
  -Dtest='com.riccon.worker.workflow.**.*Test' -Dsurefire.failIfNoSpecifiedTests=false && ./mvnw -q -pl
  platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest
  -Dsurefire.failIfNoSpecifiedTests=false && ./mvnw -q spotless:check`，commit=a7870c4、dirty=false、
  exit_code=0、manual=false、agent=unknown；日志 SHA-256 为 `886bd05f…`，与 JSON 的
  `output_sha256` 一致；agent unknown 只影响归属，不影响代码/干净状态。独立复跑同一命令 exit 0：
  surefire reports 显示 workflow 11 个测试类共 57 tests（4 个 heartbeat IT + 6 个 claimer IT + 5 个
  poller IT，共 15 个真实 MySQL 集成测试），fail/error/skip 均为 0；ArchUnit `ModuleBoundaryTest`
  12/12，spotless:check 通过。评审期间未修改源码/测试/契约/PRODUCT/AGENTS/.riccon 状态；临时启动
  探针与编译产物已清理，`git status --porcelain` 为空；未运行 gate check、全量模块测试、coverage、
  E2E、perf、axe 或安全扫描。
created_at: "2026-11-25T11:26:32+08:00"
---

# REV-TASK-0224-001：M06 T06a 运行队列/租约/调度池/心跳独立评审

## 结论

**不通过（fail）**。T06a 的队列领取、租约心跳、调度退避、指标/审计和 TM-85 跨租户列约束本身实现完整，
57 个定向测试（含 15 个真实 MySQL）与 ArchUnit/spotless 全绿；但 **F-001（P1，open）** 是一个启动级回归：
`WorkflowQueueConfiguration` 暴露的两个 `ScheduledExecutorService` Bean 使 Spring Boot 不再创建
`taskScheduler`，而 Worker 默认开启 outbox 调度，`OutboxLaneScheduler` 构造器要求 `TaskScheduler`，
导致默认配置下 Worker 无法启动。该问题在 EV-1063 的定向测试和 `WorkerApplicationTests` 中都被
`WorkerIntegrationTestBase` 的 `outbox.scheduling-enabled=false` 掩盖（假绿路径），必须在 TASK-0224
done 前修复并补启动回归测试。

## 验证了什么

### 1. 队列与领取

- `WorkflowRunClaimer.CLAIM_COLUMNS` 恰好为 `id, tenant_id, status, lease_until` 四列；
  `CLAIM_SQL` 使用 `(status='PENDING') OR (status='RUNNING' AND lease_until <= NOW(6))`、
  `ORDER BY id`、`LIMIT 1`、`FOR UPDATE SKIP LOCKED`；`CLAIM_UPDATE_SQL` 仍带 `(tenant_id,id)` 与状态/租约
  条件，过期 RUNNING 才递增 `claim_attempts`（`WorkflowRunClaimer.java:36-47,50-62,89-118`）。
  `WorkflowRunClaimSqlTest` 静态断言列集合、无 `SELECT *`、SKIP LOCKED/LIMIT 1 与条件更新。
- 每个槽位一次只领 1 个运行：`WorkflowRunClaimer` 固定 `LIMIT 1`，启动校验强制
  `RICCON_WORKFLOW_CLAIM_BATCH == 1`（`WorkflowQueueStartupValidator.java:49-78`），调度器池大小按并发配置
  （`WorkflowQueueConfiguration.java:40-55`），不是“一次领 N 个再串行执行”。
- 非法 tenant fail-closed：`WorkflowRunQueuePoller.process` 先 `TenantId.of`，失败时不打开目标租户上下文、
  不读业务列、不执行，只记 `claim_failures_total{reason=INVALID_TENANT}` 与 platform
  `tenant.access.denied` 审计（`WorkflowRunQueuePoller.java:220-230`；`WorkflowQueueAuditor.java:55-79`）；
  单测与真实 MySQL IT 均断言 `TenantContext` 最终为空、运行保持 RUNNING。
- 重复领取防护：claim 后 `findClaimState` 校验 `status=RUNNING` 且 `lease_owner` 匹配，否则放弃
  （`WorkflowRunQueuePoller.java:231-247`）；真实 MySQL 覆盖“第二次轮询不二次执行”、并发 6 槽位领取
  6 行不重复、租约未到期不重领、未来租约不阻塞 PENDING。
- `claim_attempts` 超过 `MAX_CLAIM_ATTEMPTS=5` 时 `markClaimExhausted` 把运行置 `FAILED/WF_INTERNAL`、
  当前节点置 FAILED（无节点行时从发布版本图快照补建一次失败尝试），并记录
  `claim_exhausted_total`、`workflow.run.failed` 审计与 ERROR 日志
  （`WorkflowRunQueueJdbc.java:231-280`；`WorkflowRunQueuePoller.java:249-267`）；真实 MySQL 覆盖
  已有节点与无节点两条路径。未新增 outbox 表/事件，`agent_run_outbox`/`OutboxPoller` 未被修改。

### 2. 调度、租约与心跳

- 槽位池与心跳池是独立 `ScheduledExecutorService`，命名/线程前缀/守护线程/大小分别来自
  `RICCON_WORKFLOW_SCHEDULER_POOL_SIZE` 与 `RICCON_WORKFLOW_HEARTBEAT_CONCURRENCY`；没有复用
  `spring.task.scheduling.pool.size` 或 outbox 线程（`WorkflowQueueConfiguration.java:13-30,40-55`；
  `WorkflowLeaseHeartbeatIntegrationTest.pools_areIndependent`）。
- `WorkflowRunQueuePoller.process` 在调用执行器前同步 `heartbeat.start()`，首轮续期失败/丢失/取消时不执行；
  单测用 `inOrder(heartbeat, executor)` 锁定“先心跳后执行器”，真实 MySQL 验证租约被重领时置 lost 并
  计数（`WorkflowLeaseHeartbeat.java:82-140`；`WorkflowRunQueuePollerTest.java:94-113`；
  `WorkflowLeaseHeartbeatIntegrationTest.java:88-136`）。
- 心跳续期只在 `status='RUNNING'` 且 `lease_owner` 匹配时成功；0 行时区分 CANCELLED 与丢失，异常
  fail-closed 视为丢失并停止自调度（`WorkflowLeaseHeartbeat.java:98-140,201-231`）。续期间隔使用
  `RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS`，启动校验 `interval*3 <= lease`、调度/心跳池 ≥ 并发、
  连接池 ≥ 并发 + 4（`WorkflowQueueStartupValidator.java:49-78`；单测覆盖边界与错误信息）。
- 空队列按 1000→2000→4000→5000 指数退避，领取成功复位为轮询周期；领取/执行异常不逃逸到调度线程；
  `RICCON_WORKFLOW_CLAIM_ENABLED=false` 不注册槽位；`@PreDestroy` 取消当前任务并等待
  `RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS`（`WorkflowRunScheduler.java:87-187`；
  `WorkflowRunSchedulerTest.java:27-143`、`WorkflowClaimBackoffTest`）。
- T06b 未注册 `WorkflowRunExecutor` 时 `executorAvailable()` 为 false，调度器只记
  `workflow.queue.scheduler.executor_missing claim_slots=0` 并不领取任何运行；单测覆盖
  （`WorkflowRunSchedulerTest.java:110-123`）。该行为安全，代价是 T06a 单独部署时队列保持惰性，
  等 T06b 引擎 Bean 出现后自动生效。

### 3. 指标、日志与审计

- 实现了 `riccon_workflow_queue_depth{status=PENDING|WAITING_APPROVAL}`、
  `riccon_workflow_queue_oldest_pending_age_seconds`、`riccon_workflow_runs_active`、
  `riccon_workflow_claim_failures_total{reason}`、`riccon_workflow_lease_lost_total`、
  `riccon_workflow_claim_exhausted_total`、`riccon_workflow_heartbeat_renew_failures_total`
  （`WorkflowQueueMetrics.java:63-124`）；Gauge 名/标签由单测锁定，claim/耗尽计数在真实 MySQL IT 中断言。
- `queueStats()` 只做跨租户状态计数与最老 PENDING 年龄聚合，不返回租户 ID/运行 ID/业务字段
  （`WorkflowRunQueueJdbc.java:124-137,289-302`）；这是 §10.2 指标所需的最小聚合，评审未发现通过
  该路径读取租户业务内容。`queue_depth` 缓存 1 秒，DB 失败保留上一份快照并 WARN。
- 日志只带 runId/tenantId/leaseOwner/状态/错误类型；非法租户拒绝写 platform denied 审计，耗尽写
  `workflow.run.failed`（details 白名单通过 `AuditDetailsPolicy`，不含输入/结果/图/工具参数），
  与 TM-85/§4.3 一致（`WorkflowQueueAuditorTest`、`WorkflowRunQueuePollerIntegrationTest`）。
- TM-85：claim 路径只读取 4 列，领取后立即打开 `TenantContext`，后续 `findClaimState`/`renew`/终态
  更新全部经 `TenantJdbc` 并带 `tenant_id`；静态列集合断言 + 真实 MySQL 非法 tenant 用例覆盖。

### 4. 测试与证据

- EV-1063：command 为定向 worker workflow 测试 + `ModuleBoundaryTest` + `spotless:check`；
  `commit=a7870c4`、`dirty=false`、`exit_code=0`、`manual=false`；日志 44,357 B，SHA-256
  `886bd05f038cf627121a680a9aaa5ece871df88bface7c3f7328f503763ac18c`，与 EV JSON 一致；
  `agent=unknown` 只影响归属记录，不影响干净证据。
- 独立复跑同一命令 exit 0：`apps/worker/target/surefire-reports` 的 11 个 workflow 测试类共 57 tests，
  fail/error/skip=0；其中 4 个 `WorkflowLeaseHeartbeatIntegrationTest`、6 个
  `WorkflowRunClaimerIntegrationTest`、5 个 `WorkflowRunQueuePollerIntegrationTest` 共 15 个真实 MySQL
  集成测试；ArchUnit `ModuleBoundaryTest` 12/12；spotless 通过。测试未使用 `@Disabled`/`assumeTrue`
  跳过，真实 MySQL 用例覆盖领取、并发去重、非法 tenant、耗尽、池独立与指标。
- 复跑的是 a7870c4 的代码状态；HEAD 4a709bb 相对 a7870c4 的 `apps/platform/contracts/docs` diff 为空，
  其余提交只动 `.riccon`。

### 5. 范围与回归

- `git diff --name-status a65282b a7870c4` 只有 33 个文件：20 个 queue/heartbeat 生产类 + 13 个测试/
  测试支持类，全部落在 TASK-0224 的 4 个 `write_scope` glob 内；未改 `contracts/**`、`docs/PRODUCT.md`、
  `AGENTS.md`、`V15__workflow_studio.sql`、`apps/api/**`、`agent_run_outbox` 或 J2 outbox。
- 但“未改 J2 源码”不等于“J2 无回归”：F-001 通过 Spring Boot 调度自动配置使 Worker 默认启动失败，
  影响 J1/J2 outbox 与 connector `@Scheduled`，是本任务必须修复的运行时回归。
- T06b 依赖：`WorkflowRunExecutor` 端口与 `WorkflowLease` 契约已就位；执行器在租户作用域内运行，
  必须在节点边界检查 `lease.lost()/cancelled()`，否则租约过期重领时可能双执行——这是 T06b 的
  实现责任，T06a 已通过心跳/租约/条件更新提供基础，但本评审不能替 T06b 证明。

## 未验证

- 未运行 M06 模块全量 `build/test/coverage/e2e/perf/security` 扫描，未运行真实浏览器 E2E/axe/键盘旅程；
  这些属模块 gate 或 T16a。
- 未运行 T06b 串行引擎/节点执行/审批/工具调用；未验证 T15 RUNBOOK、告警阈值、备份/回滚演练；
  这些按计划归后续任务，不因本评审判 T06a fail。
- 未在真实生产 Compose/K8s 中启动 Worker（本次独立启动探针使用 `WorkerApplication` + test profile +
  fake DB，仅为复现 F-001，不冒充 E2E 证据）。
- 未验证 `POST /retry` 的完整 API 行为，仅检查其“最新节点必须 FAILED”的前置条件并用于 F-002 的影响分析。

## Findings

### F-001（P1，open）：新增两个 `ScheduledExecutorService` Bean 关闭了 Boot 的 `taskScheduler` 自动配置，默认配置下 Worker 无法启动

- **观察**：`WorkflowQueueConfiguration.java:40-55` 暴露 `workflowRunSlotExecutor` 与
  `workflowHeartbeatExecutor` 两个 `ScheduledExecutorService`。EV-1063 日志第 74 行出现
  `TaskSchedulerRouter` 提示“More than one ScheduledExecutorService bean exists ... none is named
  'taskScheduler' ... [workflowRunSlotExecutor, workflowHeartbeatExecutor]”。独立启动探针在 outbox
  scheduling 开启时 refresh 失败：`No qualifying bean of type
'org.springframework.scheduling.TaskScheduler' available`，失败 bean 为 `outboxLaneScheduler`。
- **期望**：Worker 在默认 `application.yml`（`outbox.scheduling-enabled: true`）下正常启动，outbox
  继续使用 `spring.task.scheduling.pool.size` 的调度池，workflow 使用自己的槽位/心跳池；观察结果违反
  任务验收“不触碰 J2 outbox”和“与 `spring.task.scheduling.pool.size`/`OutboxLeaseHeartbeat` 隔离”。
- **影响**：生产默认配置 Worker 启动失败，J1/J2 outbox、connector `@Scheduled` 全部不可用；属于发布阻断，
  不得 accepted。
- **测试盲区**：`WorkerIntegrationTestBase.java:67` 固定 `outbox.scheduling-enabled=false`，所以
  EV-1063 的 57 个测试和 `WorkerApplicationTests` 都不会创建 `OutboxLaneScheduler`，无法发现此回归。
- **修复要求**：显式提供 `TaskScheduler` Bean（推荐 `ThreadPoolTaskScheduler`，命名 `taskScheduler`，
  继续消费 `spring.task.scheduling.pool.size`/线程前缀），或把 workflow 执行器封装在非
  `ScheduledExecutorService` Bean 的端口内，恢复 Boot 的调度自动配置；同时增加默认 outbox 配置下
  Worker 上下文启动 / `OutboxLaneScheduler` Bean 存在的回归测试。

### F-002（P3，accepted）：WF_INTERNAL 节点记账失败时 retry 可能 409

`WorkflowRunQueueJdbc.markClaimExhausted` 先提交运行 FAILED，再更新/补建节点失败尝试；节点记账异常只记
`workflow.claim.exhausted_node_bookkeeping_failed` ERROR 并返回 true（`:231-280`），而
`WorkflowRunService.retry` 要求最新节点为 FAILED（`:294-301`）。在 DB/图快照异常时，运行已是
WF_INTERNAL，但 `POST /retry` 会拒绝，残余违反 OPS-M06-008。该路径有界、非静默，按 P3 accepted 交由
T08/T15 在 retry 错误路径或事务化修复中复核；M06 gate 前重新确认是否仍接受。

## 剩余风险

- **非法 tenant 行的活性**：`WorkflowRunQueuePoller.process` 在 tenant 校验失败时直接返回（`:220-230`），
  该行保持 RUNNING，租约到期后被反复领取、反复 denied，永远到不了 `claim_attempts` 耗尽/WF_INTERNAL。
  这是 TM-85 fail-closed 的直接结果（非法 tenant 不能打开上下文），且非法 tenant 只能来自损坏数据；
  `claim_failures_total`/denied 审计可告警。建议 T15/运维提供清理/隔离步骤，或在未来评估受限的
  跨租户终态记账；本任务不把它作为 fail 理由。
- `leaseOwner` 将“实例前缀 + #slot-N”整体截断到 128 字符（`WorkflowRunQueuePoller.java:316-319`）；
  若 `RICCON_INSTANCE_ID` 极长，多个槽位可能得到相同租约标识，弱化重复执行防护。正常短实例 ID 不受影响；
  T15 RUNBOOK 应要求实例 ID 唯一且长度受控。
- 宽限停机的“等待在途槽位到 grace 超时”没有直接单测（`WorkflowRunSchedulerTest.stop_cancelsSlotsAndStopsRescheduling`
  只覆盖 activeSlots=0）；代码路径简单，但 T15/T16b 可在真实停止场景补证。
- `WorkflowQueueMetrics.stats()` 在聚合查询异常时不推进 `cachedAtNanos`，DB 故障期间每次 scrape 会重试
  聚合查询；不影响正确性，但会放大故障期 DB 压力。
- 启动校验读取部署键 `riccon.db.pool-size`，而 Hikari 实际池大小可被
  `spring.datasource.hikari.maximum-pool-size` 覆盖（测试 profile 即 3）；类注释与 T15 预检已说明，
  但生产误改 Hikari 自有键时校验可能放行。
- T15/T16b RUNBOOK/演练、T06b 引擎，以及真实 E2E/perf/axe/security 扫描未在本任务验证。

## 证据

- EV-1063（clean，a7870c4，exit 0）：`.riccon/evidence/TASK-0224/EV-1063.json` 与
  `.riccon/evidence/TASK-0224/EV-1063-2026-11-25T11-02-52-868+08-00-mvnw-q-pl-apps-worker-am-test-dtest-com-riccon-w.log`
- 独立复跑：上述 EV 相同命令，exit 0；57 workflow tests（15 真实 MySQL）、ArchUnit 12/12、spotless 通过。
- 代码范围：`git diff --name-status a65282b a7870c4`（33 files，全部在 TASK-0224 write_scope）。
- F-001 启动探针：`WorkerApplication` + test profile，`riccon.worker.outbox.scheduling-enabled=true`、
  fake DB、`riccon.worker.schema.validation-enabled=false`，观察 `No qualifying bean of type
'org.springframework.scheduling.TaskScheduler' available`；该探针为临时独立验证，不属于 EV，已清理。
