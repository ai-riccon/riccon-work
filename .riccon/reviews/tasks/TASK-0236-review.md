---
review_id: REV-TASK-0236-001
module_id: M06
task_id: TASK-0236
reviewer:
  agent: riccon/verifier
  session: ses_deef64e32ffeS2SrRH2prIWhLP
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - a176c8d9e29ccd9caf1f5be9763dcffc24c486ee
  - 0a57d5486c1203913340c2e8729501b15a07851c
head_commit: a99a2ffbe9b25db4c24cfc962d2e8f4343dc5bdd
code_commit: 0a57d5486c1203913340c2e8729501b15a07851c
evidence_commit: 0a57d5486c1203913340c2e8729501b15a07851c
baseline_commit: a176c8d9e29ccd9caf1f5be9763dcffc24c486ee
task_card_baseline_commit: a176c8d9e29ccd9caf1f5be9763dcffc24c486ee
out_of_scope_commits:
  - 67390fe8bdb71b046d00f55220d79e34e49f50a5
  - ec7a5a88cfc92056e24eb6228aa8ff4628d1abed
  - a99a2ffbe9b25db4c24cfc962d2e8f4343dc5bdd
related_reviews:
  - REV-TASK-0219-001
  - REV-TASK-0223-002
  - REV-TASK-0224-002
review_scope: >-
  M06 T15（TASK-0236）独立任务评审：`GET /api/v1/workflows/runtime-limits` 的
  `workflow.read`/字面路径/租户只读/disabledReason/租户保留策略/+08:00；kill switch 下
  创建/重试 503、取消可用、运行限制可读；API/Worker 启动 fail-closed 校验（限制/池/lease/
  包大小/开关组合）；Worker 复用 T06a 显式 `taskScheduler`、审批等待/feature/claim/
  Hikari 缺口指标与停止领取/优雅停机；RUNBOOK M06-0..12 与 V1–V15/57/58/4/restore 15/4/
  无窗口回滚/首次禁用备份/180 天 M09/5 MiB/包大小/告警；EV-1068/1069/1070 的 commit/dirty/
  exit/hash/bytes；以及在干净导出 `0a57d54` 上独立复跑 52 个定向测试 + ArchUnit 12 +
  默认 context 1 + spotless。评审期间 T06b 提交 `a99a2ff`（TASK-0225 仍 in_progress），
  另在 `a99a2ff` 干净导出复跑同一组 T15 测试，只验证 T15 回归，不复审 T06b 串行引擎/节点
  实现；不评审 T08 历史详情/审批聚合、不执行 T16a E2E/axe/perf、不执行 T16b 回滚演练脚本、
  不替代 M06 模块 review/Product 预验收/用户最终验收。
dod:
  acceptance: true
  build: true
  tests: true
  lint: false
  review: true
  docs: true
  security: true
  evidence: false
  scope: true
dod_not_applicable:
  - coverage
  - e2e
  - performance
  - accessibility
  - migration
dod_note: >-
  acceptance 记 true 仅指 T15 任务范围，按 **pass (with residual)** 理解：AC-17 的“租户管理员
  只读查看本租户限制、不能修改”由运行限制端点和 10 个 API/服务用例（5 个集成 + 5 个
  服务单测）覆盖；AC-17 的界面可见（E2E-M06-09）、AC-23 的关闭态旅程与 OPS-M06-01
  回滚演练分别归 T16a/T16b，本轮未执行，因此不是 M06-AC-17/23 的模块级 E2E/运维 pass。
  AC-16 的审计代码面不在 T15 交付内，由 T01/T04/T05/T08/T09 承接，本评审不把 AC-16
  记为 T15 pass，也不据此判 T15 fail。build 由
  干净导出中的 Maven test 编译支撑；tests 由 52 个定向用例 + ArchUnit 12 + 默认 context 1
  在干净导出 `0a57d54` 与 T06b 落地后的 `a99a2ff` 上 exit 0 支撑；lint 记 false 因为独立
  `spotless:check` 在 worker
  的 6 个 T15 文件上 exit 1；docs 记 true 因为 M06 章节覆盖 §10.5 的 11 项且数字核对通过，
  但有 2 个 P3 文档收口项；security 记 true 仅指新端点 authz/平台租户 404/租户保留隔离与
  “只读、不写审计、不含密钥”，不等于 Trivy 全量安全 DoD；evidence 记 false 因为
  EV-1068/1069/1070 全部 dirty=true，不能作为 T15 done 的证据。
findings:
  - id: F-001
    severity: P1
    status: open
    owner: riccon/builder-b1
    due: "T15 done 前（主工作树已干净，重录 clean EV）"
    title: "EV-1068/1069/1070 均为 dirty=true，不能作为 T15 done 的证据"
    summary: >-
      三条 EV 的 `commit` 均为 `0a57d54`、`exit_code=0`、`manual=false`，日志
      `# cwd` 均为主工作树 `/Users/ricky/Documents/projects/riccon-work`，`output_bytes`
      与 `output_sha256` 全部与日志文件一致（EV-1068 98,055 bytes /
      b6ee779c…；EV-1069 447 bytes / e95470ba…；EV-1070 6,791 bytes / fa7585fb…）。
      但 `.riccon/evidence/TASK-0236/EV-1068.json:8`、`EV-1069.json:8`、`EV-1070.json:8`
      的 `dirty` 均为 `true`。录制时 T06b 的
      `apps/worker/src/main/java/com/riccon/worker/workflow/engine/`、`.../node/` 及测试目录
      尚未提交；评审期间 T06b 已提交 `a99a2ff`，当前 `git status --porcelain` 的非
      `.riccon` 变更已为空（`.riccon/`、`.codegraph/`、`node_modules/` 按内核 `gitSnapshot`
      规则不计入 dirty），但三条 EV 仍是历史 dirty 记录，不会因之后提交而变 clean。
      `docs/CODING-STANDARDS.md:201` 要求“先提交，再在主工作树用 `riccon evidence run` 取证”，
      `docs/COLLABORATION.md:71` 明确 strict 下 `dirty: true` 会让 gate 失败。独立复跑
      （见下文）证明 `0a57d54` 与 `a99a2ff` 的 T15 定向测试 + ArchUnit 12 + 默认 context 1
      均通过，但复跑不能替代记录在 `.riccon/evidence/` 的 clean EV。主工作树现已干净，
      owner `riccon/builder-b1` 应立即重录同一组命令（或合同允许的等价命令），更新任务卡
      evidence；Lead 负责确认主工作树干净、T06b/TASK-0225 收口与重录排期，再进入复审。
    evidence: >-
      .riccon/evidence/TASK-0236/EV-1068.json:7-15；EV-1069.json:7-15；EV-1070.json:7-15；
      三份日志第 1-6 行的 command/cwd/exit_code；评审起始时 `git status --porcelain` 显示
      T06b engine/node 未跟踪，评审期间 `a99a2ff` 提交后非 `.riccon` 工作树已干净；
      docs/CODING-STANDARDS.md:201；docs/COLLABORATION.md:71。
  - id: F-002
    severity: P2
    status: open
    owner: riccon/builder-b1
    due: "T15 done 前"
    title: "Worker 的 6 个 T15 文件未通过 spotless:check，commands.lint 会失败"
    summary: >-
      在干净导出 `0a57d54` 上执行 `./mvnw -q -pl apps/worker spotless:check` 返回 exit 1；
      T06b 落地后的 `a99a2ff` 仍为 exit 1（T15 上述文件仍在违规列表，T06b 另有自己的格式
      债务，归 TASK-0225）。T15 的违规文件全部是本任务新增：
      `apps/worker/src/test/java/com/riccon/worker/workflow/metrics/config/WorkflowRuntimeConfigGuardTest.java`、
      `.../metrics/persistence/WorkflowApprovalWaitJdbcIntegrationTest.java`、
      `.../metrics/service/WorkflowOperationsMetricsTest.java`、
      `apps/worker/src/main/java/com/riccon/worker/workflow/metrics/persistence/WorkflowApprovalWaitJdbc.java`、
      `.../config/WorkflowDatabasePoolGuard.java`、`.../config/WorkflowRuntimeConfigValidator.java`。
      同一干净导出中 `./mvnw -q -pl apps/api spotless:check` 为 exit 0，说明 API 侧已格式化。
      合同 `commands.lint` 为 `./mvnw -q spotless:check && pnpm -w lint`；虽然 T15 的定向
      `mvn test` 不执行 spotless，但 CI/模块质量门会因此失败。该问题不改变测试行为，属格式
      门禁 P2；修法是 `spotless:apply` 后提交格式 diff，并重跑 `spotless:check`。
    evidence: >-
      干净导出 `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/t15-verify-0a57d54`
      的 spotless 日志；`./mvnw -q -pl apps/worker spotless:check` exit 1；API 同命令 exit 0；
      AGENTS.md `commands.lint`。
  - id: F-003
    severity: P3
    status: open
    owner: riccon/builder-b1
    due: "T15 done 前"
    title: "RUNBOOK 告警表混用全名与简写指标，approval/result/run/node 名称可能不可直接使用"
    summary: >-
      T15 实现的审批等待指标全名为 `riccon_workflow_approval_oldest_wait_seconds`
      （`WorkflowOperationsMetrics.java:79-82`，RUNBOOK:1503 也用了全名），但
      `docs/RUNBOOK.md:1645` 写作 `riccon_workflow_approvals_pending` /
      `approval_oldest_wait_seconds`；同表 1646/1648 的 `run_duration_seconds`、
      `node_duration_seconds`、`result_size_bytes` 也省略了 `riccon_workflow_` 前缀。
      运维按告警表复制的名称可能匹配不到实际指标，影响审批等待/结果大小/运行耗时的告警配置。
      这是文档收口问题，不影响代码行为；应在 T15 done 前统一为 ADR-0024 §10.2 的完整指标名。
    evidence: >-
      docs/RUNBOOK.md:1645-1648 与 1503 的对照；
      apps/worker/src/main/java/com/riccon/worker/workflow/metrics/service/WorkflowOperationsMetrics.java:79-82；
      docs/adr/ADR-0024-m06-workflow-orchestration.md:731。
  - id: F-004
    severity: P3
    status: open
    owner: riccon/builder-b1
    due: "T15 done 前"
    title: "RUNBOOK 恢复章节仍写“M04 起为 11”，与本文件 M06 起为 15 不一致"
    summary: >-
      T15 已把 `docs/RUNBOOK.md:255` 更新为“M06 起为 15”，但同一文件恢复章节
      `docs/RUNBOOK.md:1694` 仍写“worker 镜像期望版本等于 DB（M04 起为 11）”。该行不是
      T15 新增，但 T15 是 RUNBOOK 的写入任务，且 M06 回滚/恢复步骤正依赖 schema 15 口径；
      保留旧值可能让运维在 `RICCON-WORKER-001` 处置时误判期望版本。应与 M06-8/M06-9 的
      schema 15 口径统一。
    evidence: >-
      docs/RUNBOOK.md:255、1694；V15 已使 `SchemaVersionChecker.EXPECTED_SCHEMA_VERSION=15`
      （apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java:61）；
      ADR-0024 §10.4/§10.5。
evidence:
  - EV-1068
  - EV-1069
  - EV-1070
reviewed_evidence:
  - EV-1068
  - EV-1069
  - EV-1070
created_at: "2026-11-25T13:10:07.487+08:00"
---

# REV-TASK-0236-001：任务 TASK-0236（T15）独立评审

## 结论

**不通过（fail）**。T15 的代码面在干净导出 `0a57d54` 和包含 T06b 的 `a99a2ff` 上均通过了
52 个定向测试、ArchUnit 12/12 和默认 context 启动测试，运行限制端点、kill switch 回归、
启动校验、Worker 指标与 RUNBOOK M06 章节的主体实现与 ADR-0024 §4.8/§10.1/§10.2/§10.5
一致；但 **EV-1068/1069/1070 全部 `dirty=true`**（F-001，P1），不能作为 T15 done 的证据；
**Worker 6 个新文件未通过 `spotless:check`**（F-002，P2），`commands.lint` 会失败；另有
2 个 RUNBOOK 文档收口 P3。评审期间 T06b 已提交 `a99a2ff`，主工作树非 `.riccon` 文件已干净，
builder 现在即可重录 clean EV；按“P0/P1 open 即 fail”规则，本任务保持 `review`，不进入
done，也不能启动 M06 gate。

## 评审范围与基线

- 任务卡：`.riccon/tasks/TASK-0236.json`，owner `riccon/builder-b1`，status `review`，
  baseline `a176c8d`，end `0a57d54`，evidence `[EV-1068, EV-1069, EV-1070]`。
- 代码提交：`0a57d5486c1203913340c2e8729501b15a07851c`（feat T15，2026-11-25 12:41:40 +08:00）。
- 本评审独立在 `git archive 0a57d54` 的干净导出中复跑，目录不含 T06b 的
  `workflow/engine/**`、`workflow/node/**`，避免并行 WIP 污染结论。评审期间 T06b 提交
  `a99a2ff`；另用 `git archive a99a2ff` 复跑同一组 T15 测试，验证 T06b 落地后 T15 不回归，
  但不把 T06b 的实现质量计入本评审。
- 未启动 M06 gate，未修改源码/测试/契约/PRODUCT/AGENTS/.riccon 状态，未执行全量 E2E/perf。

## API：运行限制与 kill switch

### `GET /api/v1/workflows/runtime-limits`

- **路径与权限**：`WorkflowRuntimeLimitsController.java:43-45` 使用字面路径
  `/api/v1/workflows/runtime-limits` 和 `@RequirePermission(PermissionCodes.WORKFLOW_READ)`。
  `WorkflowRuntimeLimitsApiTest` 5 个用例覆盖：
  - `member_readsDeploymentLimits`：普通员工 200，返回 maxNodes 50、maxSteps 200、模型/检索/
    MCP 超时 300/120/60、runTimeoutSeconds 1800、租户/流程并发 10/5、resultMaxBytes 5,242,880、
    retentionDays 180、`retentionPolicy=DEPLOYMENT_DEFAULT`、`generatedAt` 以 `+08:00` 结尾；
  - `tenantRetention_marksTenantPolicy`：租户 `retention_days=90` 时返回 90 + `TENANT_POLICY`；
  - `withoutReadPermission_returns403`：无 `workflow.read` 返回 403 `RICCON-AUTH-403`；
  - `platformTenant_returns404`：平台租户返回 404 `TENANT_404`，不泄露端点存在性；
  - `writeMethods_areNotAllowed`：PUT/POST 均 405，且写请求不改变只读值。
- **租户只读与部署配置**：`WorkflowRuntimeLimitsService.current`（`:68-91`）从
  `WorkflowProperties` 读取部署限制，从租户行读取 `retentionDays`；只有与部署默认不同才标
  `TENANT_POLICY`。服务不写审计（读取部署配置/保留策略不是配置变更），响应字段与
  `contracts/openapi.yaml` 的 `WorkflowRuntimeLimits` 一致，`generatedAt` 固定 Asia/Shanghai。
- **disabledReason**：开启时返回 null；关闭时优先返回
  `RICCON_WORKFLOW_DISABLED_REASON`（strip 后），未配置/空白时回退
  `workflow orchestration is disabled by deployment configuration`（`:99-105`）。该回退与
  T05 `WorkflowRunUnavailableException` 的 503 说明一致；`WorkflowRuntimeLimitsServiceTest`
  覆盖开启、关闭回退、关闭带原因、租户策略、租户不存在 5 个用例。
- **字面路径优先**：Spring MVC 的 PathPattern 将字面段优先于 `{workflowId}`；集成测试直接请求
  字面路径返回 200，若被 `{workflowId}` 捕获会因 "runtime-limits" 不是合法 workflowId 返回
  400/404，因此该用例同时证明了优先级。

### kill switch

- 关闭态 `WorkflowRuntimeLimitsDisabledApiTest` 4 个用例：
  - `limits_whenDisabled_remainsReadable`：`featureEnabled=false`、`disabledReason=只读演练`，
    限制值仍为部署配置（maxNodes 50），说明关闭态可读；
  - `createRun_whenDisabled_returns503`：创建返回 503 `RICCON-WF-503`，运行数为 0，
    `workflow.run.requested` 审计为空；
  - `cancelRun_whenDisabled_stillWorks`：取消返回 202，运行变 `CANCELLED`，
    `workflow.run.cancelled` 审计 1 条；
  - `retryRun_whenDisabled_returns503`：重试返回 503，运行仍 `FAILED`，
    `workflow.run.retried` 审计为空。
- 复用 T05 的 `WorkflowRunService.requireFeatureEnabled()`（`:148` 创建、`:282` 重试；
  `cancel` 在 `:221` 不调用它），行为与 ADR-0024 §4.8 “创建/重试 503、取消仍可用”一致。
- 关闭态的“审批决定/复制模板 503”与“历史只读”分别由 T09/T08/T14 的端点在各自任务中处理；
  本轮未重复，不把它们的覆盖状态算作 T15 pass/fail，也不在本评审中声称这些 AC 子句已覆盖。

### 启动校验（API 侧）

- `WorkflowRuntimeStartupValidator.afterPropertiesSet`（`:63-66`）执行三组校验：
  - 运行限制为正、`disabledReason` ≤200、`runTimeoutMinutes*60` 不溢出；
  - 与 Worker 共享的 `WORKER_CONCURRENCY`、`CLAIM_BATCH==1`、调度池/心跳池 ≥ 并发、
    `HEARTBEAT_INTERVAL*3 <= LEASE`、轮询/退避为正且退避 ≥ 轮询、shutdown grace ≥0、
    `RICCON_DB_POOL_SIZE >= WORKER_CONCURRENCY+4`；
  - `RICCON_DB_MAX_ALLOWED_PACKET >= 8 MiB` 且 `>= RESULT_MAX_BYTES + 1 MiB`。
    错误信息均带可操作的 `RICCON_*` 键；`WorkflowRuntimeStartupValidatorTest` 25 个用例覆盖默认
    通过、19 组非法限制、关闭原因超长、池不足、包大小不足/余量不足/大结果上限。
- API 的实际 Hikari 池由测试栈单独覆盖（`application-test.yml` max pool 3），validator 按合同
  部署键而非反射实际池校验，注释与 ADR §10.1 的“部署键为权威”一致；这一取舍不构成缺陷，但
  部署侧单独覆盖 `spring.datasource.hikari.maximum-pool-size` 时，API 侧不会像 Worker 那样
  暴露 `riccon_workflow_db_pool_shortfall`，需靠 RUNBOOK 预检兜底（见剩余风险）。

## Worker：指标、Hikari 与停止领取

- **复用 T06a `taskScheduler`**：`WorkflowRuntimeConfiguration` 只有
  `@EnableConfigurationProperties(WorkflowRuntimeProperties.class)`，不声明 `taskScheduler`、
  `workflowRunSlotExecutor`、`workflowHeartbeatExecutor` 同名 Bean（`:10-16`）；T06a
  `WorkflowQueueConfiguration` 继续提供唯一的共享 `taskScheduler` 与两个独立池。干净导出中的
  `WorkflowQueueDefaultContextTest` 通过：`getBeansOfType(TaskScheduler.class)` 只含
  `taskScheduler`，共享池与槽位/心跳池互不相同，outbox 与 workflow 调度器分别绑定正确，
  且 Hikari max pool 3 时 `RICCON-WORKFLOW-022` WARN 正常出现。
- **指标**：`WorkflowOperationsMetrics` 注册
  `riccon_workflow_approvals_pending`、`riccon_workflow_approval_oldest_wait_seconds`、
  `riccon_workflow_feature_enabled`、`riccon_workflow_claim_enabled`（`:73-95`）。
  T06a 已注册队列深度/最老 PENDING/租约丢失/claim 失败/claim 耗尽/心跳失败/活跃槽位；
  T15 未重复注册这些名称。`WorkflowOperationsMetricsTest` 3 个用例覆盖计数/最老等待、
  feature/claim 开关、DB 失败时保留上一份快照且 Gauge 仍可读。
- **审批等待查询**：`WorkflowApprovalWaitJdbc` 使用
  `SELECT COUNT(*), COALESCE(MAX(TIMESTAMPDIFF(SECOND, created_at, UTC_TIMESTAMP(6))),0)
FROM workflow_approval WHERE status='PENDING'`（`:21-26`）；只返回聚合计数/秒数，不含审批
  正文、参数或租户标识，符合“指标聚合读取”的边界。真实 MySQL 集成测试插入 2 条 PENDING
  （600s/60s）和 1 条 APPROVED（900s），断言 pending=2、oldest 在 600–700s，证明只统计
  PENDING 且按 UTC 计算。
- **Hikari 容量观测**：`WorkflowDatabasePoolGuard` 读取 Hikari 实际
  `maximumPoolSize`，注册 `riccon_workflow_db_pool_shortfall`（0 为满足，正数为缺口），低于
  `workerConcurrency+4` 时记录 `RICCON-WORKFLOW-022` WARN（`:69-91`）。非 Hikari 数据源只记
  INFO 并跳过指标，不错误阻断测试切片。该设计不替代 T06a 对
  `RICCON_DB_POOL_SIZE` 的 fail-closed，符合 ADR §10.1/§10.2 与 OPS-M06-022。
- **Worker 启动校验**：`WorkflowRuntimeConfigValidator` 补 T06a 未覆盖的运行限制/关闭原因/
  包大小（`:64-67`），并把 `ENABLED=false` 但 `CLAIM_ENABLED=true` 记为 WARN
  `RICCON-WORKFLOW-021`，不阻断启动；这与 ADR §4.8/§10.4 的回滚顺序（先关 API 受理、
  再停 Worker 领取）一致。`WorkflowRuntimeConfigGuardTest` 9 个用例覆盖默认通过、非法上限、
  关闭原因超长、包大小不足/余量不足、关闭但领取不阻断、Hikari 缺口/满足/非 Hikari。
- **停止领取/优雅停机**：T15 未改动 T06a 的 `WorkflowRunScheduler`；其 `@PostConstruct` 在
  `claimEnabled=false` 时不注册槽位，`@PreDestroy` 取消后续任务并按
  `shutdownGraceSeconds` 等待在途，超时记 `workflow.queue.scheduler.shutdown_grace_exceeded`。
  干净导出的默认 context 测试与 T06a 既有测试均通过，未发现 T15 引入冲突。

## RUNBOOK：M06 章节与数字

`docs/RUNBOOK.md:1375-1672` 新增 M06-0..M06-12，覆盖 ADR-0024 §10.5 的 11 项：

| §10.5 要求                     | RUNBOOK 位置        | 核对结果                                                                                                                                            |
| ------------------------------ | ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| 配置表/启动校验/三池/Hikari    | M06-1（1391-1435）  | 全部 `RICCON_WORKFLOW_*`/`RICCON_DB_*` 键；明确 M06 槽位池 ≠ `RICCON_WORKER_SCHEDULER_POOL_SIZE`，Hikari 是共用连接池；容量公式 `POOL >= 并发+4`    |
| 开关/停止领取/优雅停机         | M06-3（1465-1477）  | 关受理 503/取消可用/历史只读；关 `CLAIM_ENABLED` 停止领取；SIGTERM `@PreDestroy` 宽限；feature/claim 指标漂移观察                                   |
| 队列/租约/claim/Hikari 排障    | M06-4（1479-1497）  | 队列深度、最老 PENDING、lease_lost、claim_exhausted/claim_failures、心跳失败、Hikari pending/timeout、shortfall；空队列退避 1000→5000；重复领取语义 |
| 审批等待占满准入               | M06-5（1499-1508）  | 准入含 `WAITING_APPROVAL`、执行槽位不含；approvals_pending/oldest_wait>24h；取消/补审批人；不自动放行                                               |
| 失败重试/工具重审批/未知副作用 | M06-6（1510-1519）  | 只重试 FAILED 节点；写工具每次新审批；`WF_SIDE_EFFECT_UNKNOWN`/`WF_INTERNAL` 不自动重放/无限重试                                                    |
| V15 迁移预检/失败处置          | M06-7（1521-1547）  | `MAX(version)=14`、TRIGGER/binlog/`max_allowed_packet`、备份；DDL 非事务失败矩阵；repair/备份恢复；V15 9 张表/6 权限/2 触发器（总 4）               |
| 无窗口回滚                     | M06-8（1549-1583）  | 关 ENABLED → 关 CLAIM/宽限 → 取消 `WAITING_APPROVAL` 断言 0 → 清零 PENDING/RUNNING 断言三者 0 → 回滚 Worker → 保留 API 复断言 0；无“滞留清单”       |
| 全量 API 回滚/应急 drop        | M06-9（1585-1607）  | 判定 SQL 任一 >0 拒绝；首次禁用后 `workflow_audit_events` 永远 >0，只能备份恢复；租户/全局模板分段 drop                                             |
| 180 天/M09                     | M06-10（1609-1617） | 到期不可见、M06 不在线删除；物理清理归 M09；标准答复含 30 天删除/≥180 天审计                                                                        |
| 5 MiB/包大小/容量/告警         | M06-11（1619-1652） | 单节点 256 KiB、总 5 MiB、工具 1 MiB；`max_allowed_packet`/JDBC 预检；容量公式；M06-4/M06-5 告警表                                                  |
| 发布/回滚演练                  | M06-12（1654-1672） | 无数据允许（首次禁用前/空栈）→ 有数据拒绝（独立栈/备份夹具）→ 无窗口顺序 → 恢复；记录旧 Worker tag/digest                                           |

数字核对：

- V15 实际 `CREATE TABLE` 9 张、`CREATE TRIGGER` 2 个（审计触发器 2 + workflow 2 = 4）；
- `scripts/ops/restore-drill.mjs` 常量 `EXPECTED_SCHEMA_VERSION=15`、`EXPECTED_TRIGGERS=4`，
  注释明确 58 张表 = 57 业务/基础设施表 + `flyway_schema_history`；
- RUNBOOK:1378-1379、1666 写 V1–V15、58/57、4 触发器、restore 15/4、`MAX(version)=15`，
  与迁移/脚本一致；
- 无窗口回滚顺序、首次 `ENABLED=false` 写 `workflow.feature.changed` 导致“全 0”不可达、
  试发布失败只能恢复 V15 前备份，均与 ADR §10.3/§10.4/§10.5 及 OPS-M06-021/025 一致。

## 测试与独立复跑

在 `git archive 0a57d54` 的干净导出中（无 T06b engine/node），并在 `git archive a99a2ff`
（T06b 已提交后的当前 HEAD 树）上复核同一组 T15 测试：

1. **52 个定向测试 + 默认 context 1**：
   `./mvnw -q -T1C -pl apps/api,apps/worker -am test -Dtest='WorkflowRuntimeLimitsApiTest,WorkflowRuntimeLimitsDisabledApiTest,WorkflowRuntimeLimitsServiceTest,WorkflowRuntimeStartupValidatorTest,WorkflowOperationsMetricsTest,WorkflowRuntimeConfigGuardTest,WorkflowApprovalWaitJdbcIntegrationTest,WorkflowQueueDefaultContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
   → exit 0；surefire 合计 **53 tests**：
   `WorkflowRuntimeLimitsApiTest` 5、`WorkflowRuntimeLimitsDisabledApiTest` 4、
   `WorkflowRuntimeLimitsServiceTest` 5、`WorkflowRuntimeStartupValidatorTest` 25、
   `WorkflowRuntimeConfigGuardTest` 9、`WorkflowApprovalWaitJdbcIntegrationTest` 1、
   `WorkflowOperationsMetricsTest` 3、`WorkflowQueueDefaultContextTest` 1；
   failures/errors/skipped 全为 0。EV-1068 的 7 个类合计正好是 52 个定向测试。
   在 `a99a2ff` 干净导出复跑同一命令同样 exit 0、53 tests、0 failures/errors/skipped，
   说明 T06b 提交后 T15 测试面不回归；本轮不据此评审 T06b 功能。
2. **ArchUnit 12**：
   `./mvnw -q -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false`
   → exit 0；12 tests，0 failures/errors/skipped；`a99a2ff` 同样 12/0/0。
3. **默认 context**：`WorkflowQueueDefaultContextTest` 在干净导出启动 `WorkerApplication`，
   日志 `outbox.lane.scheduler.started lanes=[DEFAULT, HEAVY]`、
   `schema.version.checked expected=15 actual=15`、`RICCON-WORKFLOW-022 ... actual=3 required=14`，
   证明 T15 未破坏 T06a 的 `taskScheduler`/三池/Hikari 观测。
4. **spotless**：API `./mvnw -q -pl apps/api spotless:check` exit 0；Worker 同命令 exit 1，
   见 F-002。

未运行：

- 全量后端 `mvn verify`。任务说明中的 38 errors（connector/AuthFlow 环境问题）本轮未复现/未
  运行，不作为 T15 代码 fail；建议 M06 gate 在干净 canonical 栈重跑全量，并把环境性失败与
  T15 变更面分开归因。
- 全量 E2E/axe/perf/security/coverage：属 T16a/T16b/T17 与模块 gate；本轮按任务要求只跑
  定向测试，不用定向结果冒充模块级通过。

## 证据复核

| EV      | 命令范围                                    | commit  | dirty    | exit | bytes  | SHA-256   | 结论                                  |
| ------- | ------------------------------------------- | ------- | -------- | ---- | ------ | --------- | ------------------------------------- |
| EV-1068 | 7 个定向测试类（52 tests）                  | 0a57d54 | **true** | 0    | 98,055 | b6ee779c… | 日志/hash/bytes 一致；dirty 不可 done |
| EV-1069 | ArchUnit `ModuleBoundaryTest`（12 tests）   | 0a57d54 | **true** | 0    | 447    | e95470ba… | 同上                                  |
| EV-1070 | `WorkflowQueueDefaultContextTest`（1 test） | 0a57d54 | **true** | 0    | 6,791  | fa7585fb… | 同上                                  |

- 三份 JSON 的 `cwd` 与日志第 2 行 `# cwd` 均为主工作树；
- `manual=false`、`exit_code=0`、`output_bytes`/`output_sha256` 与日志逐字节一致；
- 唯一阻断项是 `dirty=true`（EV-*.json:8）。录制时 T06b 未提交；评审期间 `a99a2ff` 已提交，
  当前非 `.riccon` 工作树已干净（TASK-0225 状态仍 `in_progress`，属 T06b 自身的收口问题）。
  按 `docs/CODING-STANDARDS.md:201` 与 `docs/COLLABORATION.md:71`，三条历史 dirty EV 不能
  因之后提交而自动变 clean，必须在当前干净主工作树重录，不能用本轮的 clean 导出复跑替代
  记录。任务卡 `.riccon/tasks/TASK-0236.json:22-26` 的 evidence 也需在重录后更新。

## 范围

- `git show --name-only 0a57d54` 共 21 个文件，全部落在任务卡 6 个 `write_scope` glob 内：
  `apps/api/src/main/java/com/riccon/api/workflow/runtime/**`、
  `apps/worker/src/main/java/com/riccon/worker/workflow/metrics/**`、
  `apps/worker/src/main/java/com/riccon/worker/workflow/config/**`、
  `apps/api/src/test/java/com/riccon/api/workflow/runtime/**`、
  `apps/worker/src/test/java/com/riccon/worker/workflow/metrics/**`、`docs/RUNBOOK.md`。
- 未改 `contracts/**`、`docs/PRODUCT.md`、`AGENTS.md`、V15 迁移、J2/outbox、
  T06a 队列文件；T15 提交本身未触碰 T06b 的 engine/node 文件。评审期间 T06b 以独立提交
  `a99a2ff` 落地，含 engine/node 与自身测试，不属于 T15 范围，也未被本评审当作 T15 实现。
- `a176c8d..0a57d54` 范围内的 67390fe/ec7a5a8 是 T06a 关闭/EV-1067 的记账提交，不属于 T15
  代码面；T15 的实际代码提交是 0a57d54。`a99a2ff` 是评审期间落地的 T06b 提交，列入
  `out_of_scope_commits`。
- T15 不启动 M06b；T16b 的 `scripts/ops/workflow-rollback-drill.mjs` 与 T16a 的 E2E/axe/perf
  仍归后续任务；T08 历史详情/审批聚合、T09 审批决定 503、T14 模板复制 503 也不在本轮。

## 未验证与剩余风险

1. **模块级 AC-17/23 未闭环**：AC-17 的界面可见/限制提示归 E2E-M06-09（T16a）；AC-23 的
   OPS-M06-01 回滚演练、5 MiB 真实插入容量、包大小预检脚本归 T16b。本评审只确认 T15 的
   API/文档面，不声称模块 AC 通过。
2. **T08 历史只读**：关闭态“列表/历史/审批记录/审计只读”由 T08 端点测试补充；本轮只验证
   运行限制可读、创建/重试 503、取消可用。
3. **完整 Worker 执行链**：T06b 串行引擎/节点执行已在 `a99a2ff` 提交，但 TASK-0225 仍
   `in_progress` 且未出本评审的证据/任务收口；本轮只在 `a99a2ff` 复核 T15 测试不回归，
   不复审 T06b 的真实执行、重试、工具调用与审批链。T06b 的质量结论由其自身评审/模块 gate
   另行确认。
4. **全量/性能/安全**：未跑 M06 全量 E2E、axe、50 并发 perf、Trivy；`WorkflowApprovalWaitJdbc`
   是全局 `WHERE status='PENDING'` 聚合，V15 的索引是 `(tenant_id,status,created_at)`，大规模
   审批表上每次抓取可能走全表扫描；本轮以 1s TTL 缓存与低抓取频率视为有界风险，建议 T16a
   容量测试或后续索引评估时观察。
5. **API 池键绑定**：API `application.yml` 未声明 `riccon.db.pool-size`，`WorkflowRuntimeStartupValidator`
   通过 `@Value` 默认 16/环境变量绑定校验；API 实际 Hikari 池不由该键控制。若部署只为 API
   设置较小的 `RICCON_DB_POOL_SIZE`，会出现与 API 实际池无关的启动失败；RUNBOOK 已把该键
   描述为 worker 共用池，部署时应同时核对两侧，必要时在 T01/RUNBOOK 后续收口。
6. **文档小项**：F-003 的指标简称与 F-004 的 M04 schema 旧值不影响代码行为，但应在 T15
   done 前修正。

## 下一步

1. `riccon/builder-b1` 在 T15 写范围内执行 `./mvnw -q -pl apps/worker spotless:apply`
   （或 `./mvnw -q spotless:apply`），确认 `spotless:check` exit 0 后提交格式 diff。
2. T06b 已提交 `a99a2ff`，主工作树非 `.riccon` 文件已干净；现在就在**主工作树**用
   `riccon evidence run` 重录同一组定向测试/ArchUnit/默认 context clean EV（dirty=false），
   更新 `.riccon/tasks/TASK-0236.json` 的 evidence；任务保持 `review`。
3. 修正 F-003/F-004 的 RUNBOOK 指标全名与 schema 版本口径。
4. 以干净 EV 发起 `REV-TASK-0236-002` 复审；复审通过后由 T17 在 canonical 栈跑模块 gate，
   T16a/T16b 承接 AC-17 UI、AC-23 回滚演练与 5 MiB/包大小预检。T06b 的 spotless/证据/任务
   收口由 TASK-0225 自身处理，不混入 T15。
