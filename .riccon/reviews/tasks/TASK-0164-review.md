---
review_id: REV-TASK-0164-001
module_id: M05
task_id: TASK-0164
reviewer:
  agent: riccon/verifier
  session: ses_dfc7871c6ffeMTzs46vtkfi7mE
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - cd83e24279010d442038e36ad4da6bc3c1610d56
  - e2e36fd2f9713f4c1d6ecff84c1b914b20ca70b8
  - d85dbbdfcd192a438b8de96f72d6bab425d70c05
head_commit: d85dbbdfcd192a438b8de96f72d6bab425d70c05
evidence: [EV-0769, EV-0772, EV-0773]
reviewed_evidence: [EV-0769, EV-0772, EV-0773]
notes: >-
  Phase 1 静态审查 + Phase 2 独立定向验证完成，verdict=pass（任务级）。EV-0772（API：
  V12/V11/V10/V12Vocabulary 47 tests）与 EV-0773（worker：outbox/schema/parse 30 tests）均在
  d85dbbd 干净树 exit 0，0 fail/error/skip；B1 代码在 cd83e24 之后未变，d85dbbd 仅含 M23 测试格式化。
  EV-0769 的 commit/dirty/exit/日志 SHA-256 仍一致。F-008（M24 spotless）在 Phase 2 复现且仍是
  M05 gate 前置；F-001/F-002/F-003/F-004/F-005/F-006/F-007 仍为 M05 gate 前必须关闭或正式接受的
  P2；本评审不替代模块评审、Product 预验收或用户验收。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/lead + riccon/architect"
    due: "M05-B3 实现前 / M05 gate 前"
    title: "ADR-0023 §5.6 的 MEMBER `knowledge.manage` 与 RoleDefinitions/V12/M02 测试不一致，且没有开放项登记"
    summary: >-
      ADR-0023:376 明确把 `knowledge.manage` 授予 `MEMBER`（添加文档；删除/重索引仅限创建人或
      `knowledge.acl.manage`），但 `RoleDefinitions.java:38-52` 的 MEMBER 权限只有 `knowledge.read`，
      `RoleDefinitions.java:64` 才给 TENANT_ADMIN `knowledge.manage`；V12:259-300 只回填三个新权限码，
      没有把 `knowledge.manage` 授予 MEMBER。`KnowledgeApiTest.java:100-111` 反而锁定“普通员工登记文档 403”，
      `V12VocabularyTest.java:54-58` 也只断言新 M05 权限不在 MEMBER。当前 `.riccon` 内未找到任何开放项、
      决策或任务承接这一漂移；builder 注释 `RoleDefinitions.java:27-30` 却写成普通员工仍只有
      `knowledge.read`，与 ADR 原文不一致。预期是在 B3 前由 Lead/Architect 明确：要么修改
      RoleDefinitions + M02 测试 + Product/ADR 口径，要么正式修订 ADR-0023 §5.6 并把 AC-06/15 的
      “创建人”限定为租户管理员；不能静默保留现状。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:376；
      apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:27-30、38-52、64；
      apps/api/src/main/resources/db/migration/V12__knowledge_depth.sql:259-300；
      apps/api/src/test/java/com/riccon/api/KnowledgeApiTest.java:100-111；
      apps/api/src/test/java/com/riccon/api/migration/V12VocabularyTest.java:42-58；
      .riccon/tasks/TASK-0164.json（无相关承接字段）。
    accepted_reason: >-
      TASK-0164 的三条验收不包含“既有 M02 `knowledge.manage` 角色语义变更”，且修改 MEMBER 角色会立即
      破坏 M02 的 `KnowledgeApiTest#createDocument_member_isForbidden`（不在本任务写范围），需要
      Product/ADR 决策和 B3 服务层例外授权一起落地。作为 P2 accepted 不阻断 B1 任务关闭；但 M05-B3
      开工前必须由 Lead 登记为开放项并决定口径，否则 AC-06/15 的“文档创建者”链会在 B3 暴露为 P1。
  - id: F-002
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-core / M05-V1"
    due: "M05 gate 前"
    title: "OutboxLaneScheduler 0% 覆盖，OutboxPoller 的租约丢失/超时分支没有测试"
    summary: >-
      新增的 `OutboxLaneScheduler` 没有任何测试引用；EV-0769 的 JaCoCo 报告显示该类
      line 0/42、branch 0/12。`OutboxPoller` 新增的租约丢失/超时分支（202、205、213、219、222、
      250、253、259 等）也未被 `OutboxPollerTest` 覆盖（该测试使用不含 heartbeat 的旧构造器）。
      `OutboxLeaseHeartbeatTest` 只验证心跳组件本身；没有验证“调度器真的按 heavy 并发启动”、
      “心跳丢失时 poller 放弃结果”、“超时映射为 504 并可重试”这三条集成语义。预期在 M05 gate
      前补 `OutboxLaneScheduler` 启动/关闭/并发测试和 poller+heartbeat 的丢弃/超时测试；当前
      claimer、lane 映射、heartbeat 单元测试和静态代码审查未发现功能错误，故 accepted 而非 fail。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxLaneScheduler.java:28-30、70-97；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxPoller.java:196-306；
      apps/worker/src/test/java/com/riccon/worker/run/service/OutboxPollerTest.java（旧构造器）；
      apps/worker/src/test/java/com/riccon/worker/outbox/OutboxLeaseHeartbeatTest.java；
      apps/worker/target/site/jacoco/jacoco.csv、jacoco.xml（EV-0769 生成）；
      EV-0773（Phase 2 运行 OutboxLane*/Heartbeat，但没有 OutboxLaneScheduler 测试类，poller+heartbeat 分支仍未覆盖）。
    accepted_reason: >-
      claimer 通道过滤、租约续期、单事件心跳、配置边界均有真实测试；scheduler 是薄 wiring，默认
      heavy=2 与 default=1 的静态路径正确；缺口是集成覆盖而非已证实的行为失败，不阻断 B1。
      但 outbox 双通道和“避免双执行”是 B1 的核心验收，M05 gate 前必须补测试或由 V1 独立覆盖。
  - id: F-003
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-core"
    due: "M05 gate 前"
    title: "`RICCON_WORKER_OUTBOX_HEAVY_CONCURRENCY` 允许 1–8，但 worker 调度池默认只有 4，配置 4–8 时实际并发不足"
    summary: >-
      `OutboxLaneProperties.java:30-42、154-156` 允许 heavy-concurrency 为 1–8；`application.yml:6-8`
      把 `spring.task.scheduling.pool.size` 固定为默认 4。`OutboxLaneScheduler.java:70-81` 为
      default 通道提交 1 个任务、heavy 通道提交 `heavyConcurrency` 个任务，因此 heavy=4 时共 5 个
      fixed-delay 任务，超过池大小；heavy=4–8 的实际并发会被池大小截断，且启动日志仍打印配置值。
      预期按 `1 + heavyConcurrency` 推导池大小，或在启动期校验/明确要求同时配置
      `RICCON_WORKER_SCHEDULER_POOL_SIZE`，并在 RUNBOOK 记录。默认 heavy=2 不受影响，故 P2 accepted；
      若不处理，客户调高 heavy 并发时会得到与配置不符的容量。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/run/config/OutboxLaneProperties.java:30-42、154-156；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxLaneScheduler.java:70-97；
      apps/worker/src/main/resources/application.yml:6-8、103-106。
    accepted_reason: >-
      默认部署 heavy=2 + default=1 = 3 个任务，小于 4，功能正常；风险只在非默认 heavy 配置，
      且可通过 `RICCON_WORKER_SCHEDULER_POOL_SIZE` 覆盖。属于配置契约/可运维性缺口，不是默认路径失败；
      必须在 M05 gate 前补校验或文档，避免客户按 1–8 配置得到错误容量。
  - id: F-004
    severity: P2
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "M05b gate 前 / 连接器同步实现前"
    title: "`max-processing=30m` 与 ADR §6.5 的连接器同步总超时 60m 冲突，M05b 可能被看门狗中断"
    summary: >-
      ADR-0023 §7.3:686 规定单事件最大处理时长 `RICCON_WORKER_OUTBOX_MAX_PROCESSING=30m`，B1 按此在
      `OutboxLaneProperties.java:42` 设为默认 30m；但同一 ADR 的 §6.5:583 允许连接器同步总超时
      60 min，并把连接器同步建模为一个 outbox 事件（§7.3:682-688）。若 M05b 的同步按 ADR 运行到
      30–60m，心跳看门狗会在 30m 中断 handler、按可重试失败处理，可能出现反复重试/无法完成。
      预期在 M05b 前决定：按通道配置 max-processing、提高连接器通道默认值、或把同步拆成可恢复的
      批处理并在 30m 内返回；不能把两个 ADR 数字同时保留为默认值。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:583、682-688；
      apps/worker/src/main/java/com/riccon/worker/run/config/OutboxLaneProperties.java:41-42；
      apps/worker/src/main/resources/application.yml:105-106；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxLeaseHeartbeat.java:193-207。
    accepted_reason: >-
      M05b 尚未实现，B1 没有连接器 handler；B1 严格跟随 ADR §7.3 的 30m 默认值，因此不阻断 B1。
      但这是 M05b 的明确语义前置，必须在连接器同步任务开工前由 Architect/Lead 解决。
  - id: F-005
    severity: P2
    status: accepted
    owner: "riccon/builder-kb-core"
    due: "M05 gate 前 / 回滚演练前"
    title: "`legacy-all-lane-scheduling=true` 不会停用 OutboxLaneScheduler，旧/新调度器会同时领取事件"
    summary: >-
      `OutboxScheduler.java:18-20` 只在 `legacy-all-lane-scheduling=true` 时注册，但
      `OutboxLaneScheduler.java:28-30` 只检查 `scheduling-enabled`，没有检查 legacy 开关。
      因此把 legacy 设为 true（注释声称用于回滚窗口）时，新旧调度器同时 active：旧调度器用
      `pollOnce()` 领取全部事件，包括 FILE_PARSE/KNOWLEDGE_INDEX/CONNECTOR_SYNC，破坏通道隔离并
      与新 heavy 线程争抢；若同时把 scheduling-enabled 设为 false，则旧调度器也不会注册，legacy 标志
      无法单独生效。预期两个调度器互斥（lane scheduler 在 legacy=true 时禁用，或把 legacy 标志纳入
      lane scheduler 条件），并在回滚演练中验证。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxScheduler.java:18-20、39-51；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxLaneScheduler.java:28-30、70-81；
      apps/worker/src/main/resources/application.yml:96-104。
    accepted_reason: >-
      默认 legacy=false，生产正常路径不触发；该缺陷只在显式回滚窗口出现，且 `FOR UPDATE SKIP LOCKED`
      阻止同一行被同时领取，当前没有生产数据风险。但回滚是 T4 的必需路径，M05 gate 前必须修正并
      演练，否则不能声称可分通道回退到旧行为。
  - id: F-006
    severity: P2
    status: accepted
    owner: "riccon/architect + riccon/lead"
    due: "M05 gate 前"
    title: "ADR-0023 §7.2/§7.3 与 ARCHITECTURE 仍写 INPLACE、未列 bbox/outbox 回滚，和 V12 实际 COPY+LOCK=SHARED 不一致"
    summary: >-
      ADR-0023 §7.2:641 仍写 `knowledge_document` 的 CHECK 变更用 `ALGORITHM=INPLACE, LOCK=NONE`；
      V12:72-93 实际使用 `ALGORITHM=COPY, LOCK=SHARED`（注释 22-27 说明 MySQL 8.4 拒绝 INPLACE）。
      ADR §7.2 的迁移内容/回滚清单（637-675）没有列 §4.3 的 bbox/ocr_confidence 列，§7.3 的回滚说明
      也没有列 `idx_outbox_lane`；V12 头注释 29-37 和 `V12MigrationTest#rollbackV12Objects` 已包含
      这些对象，但权威 ADR/ARCHITECTURE:432 仍未同步。另 ADR 要求“记录实际锁等待并改维护窗口”，
      当前只有 EV-0769 的测试日志，没有实际锁等待/维护窗口证据。预期 Architect/Lead 在 M05 gate 前
      更新 ADR §7.2/§7.3 与 ARCHITECTURE，记录 COPY 降级、bbox/outbox 回滚对象和锁等待结论。
    evidence: >-
      docs/adr/ADR-0023-m05-knowledge-depth.md:22-27、637-675、682-688；
      docs/ARCHITECTURE.md:432；
      apps/api/src/main/resources/db/migration/V12__knowledge_depth.sql:22-37、72-93、141-155、248-251；
      apps/api/src/test/java/com/riccon/api/migration/V12MigrationTest.java:550-648；
      .riccon/evidence/TASK-0164/EV-0769.json（仅测试全量，无锁等待测量）。
    accepted_reason: >-
      实现、迁移头注释和迁移测试三处一致，V12 在 MySQL 8.4.13 上一次容器测试通过，运行时风险
      已由 COPY+LOCK=SHARED 的维护窗口约束覆盖；缺口是权威文档和实际锁等待证据，不改变 B1 产物。
      M05 gate 前必须更新，否则架构评审与迁移证据对不上。
  - id: F-007
    severity: P2
    status: accepted
    owner: "riccon/ops + riccon/lead"
    due: "M05 gate 前 / 恢复演练与发布前"
    title: "`scripts/ops/restore-drill.mjs` 与 RUNBOOK 仍以 schema 11 为判据，V12 后恢复演练会失败且没有明确 M05 承接"
    summary: >-
      `scripts/ops/restore-drill.mjs:44-45` 硬编码 `EXPECTED_SCHEMA_VERSION = 11`，并在 858、
      964-968 用它判断源库/恢复库；V12 后该脚本会以“源库未初始化 / 版本不一致”失败。
      `docs/RUNBOOK.md:224、259、280、334` 仍写 V1–V11 迁移/演练步骤。TASK-0175 的 write_scope
      含 `docs/RUNBOOK.md` 但验收只覆盖 OCR；TASK-0177 的 write_scope 含 `scripts/ops/**` 但验收
      只写“V12/索引 v2/回滚”，没有明确 restore-drill schema 12。当前没有任务把这些路径与 V12 绑定。
      预期在 M05 gate 前把脚本改成 12（或参数化）并更新 RUNBOOK；否则 T4 的恢复演练证据不可用。
    evidence: >-
      scripts/ops/restore-drill.mjs:7、44-45、858、964-968；
      docs/RUNBOOK.md:224、259、280、334；
      .riccon/tasks/TASK-0175.json（write_scope/acceptance）；
      .riccon/tasks/TASK-0177.json（write_scope/acceptance）。
    accepted_reason: >-
      脚本是运维工具、不在 B1 写范围；V12 为 additive，恢复演练不影响业务代码或生产数据。但它是
      T4 迁移/备份证据的必经路径，M05 gate 前必须由 Ops/Lead 明确承接并更新。
  - id: F-008
    severity: P2
    status: accepted
    owner: "riccon/builder-key（M24/TASK-0158）+ riccon/lead"
    due: "M05 gate 前"
    title: "全仓 `spotless:check` 当前在 M24 的 RunFailureMessageSanitizationTest 失败；EV-0769 未覆盖 lint"
    summary: >-
      Phase 1 为核对 lint 只运行了 `./mvnw -q spotless:check -DskipTests`，在 worker 模块失败：
      `apps/worker/src/test/java/com/riccon/worker/run/service/RunFailureMessageSanitizationTest.java:59-61、192`
      有格式违规；该文件由 M24 的 `02dbc6d` 引入，不属于 B1。Phase 2 再次复现：`spotless:check`
      exit 1，失败文件列表**只有**该文件，没有其他 B1/M05 文件。EV-0769 的命令是
      `./mvnw -q -T1C verify jacoco:report`，不含 spotless；B1 自身文件已格式化，且
      `git diff --check 93b09f7..cd83e24` exit 0。预期 M24 builder/Lead 在 M05 gate 前修复该格式；
      否则合同 `commands.lint` 无法通过，模块 gate 会被外部文件阻断。
    evidence: >-
      apps/worker/src/test/java/com/riccon/worker/run/service/RunFailureMessageSanitizationTest.java:59-61、192；
      git show --stat 02dbc6d；Phase 2 `./mvnw -q spotless:check -DskipTests` exit 1，唯一失败文件为该文件；
      `.riccon/evidence/TASK-0164/F-008-spotless-check.log`（sha256 d1d1b67daf9e18b79e238566060d436cf6c0cd1bab010ff65dbce050511fb605）；
      `git diff --check 93b09f7..cd83e24` exit 0；EV-0769 命令不含 spotless。
    accepted_reason: >-
      违规文件不属于 TASK-0164，B1 产物本身格式干净；这是 M24 已完成任务的 lint 债务，不影响 B1
      功能验收，但必须作为 M05 gate 前置修复。B1 的 EV 未包含 lint 也意味着 B1 不能单独声称全仓
      lint 通过。
  - id: F-009
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-acl（M05-B3）"
    due: "M05-B3 写审计前"
    title: "文档类审计动作只校验白名单形状，不强制 reason/sourceType/visibility 等必填键"
    summary: >-
      `AuditDetailsPolicy.validateRequiredDetails`（432-452）只对 `knowledge.document.admin_read`
      实施必填校验；`knowledge.document.deleted` 的 `reason`、`knowledge.document.added` 的
      `sourceType`/`visibility`、`knowledge.acl.changed` 的 `previousVisibility` 都只在出现时校验
      枚举，缺省仍会通过。ADR-0023 §5.6 和 Product AC-16 要求删除 reason、ACL 前后范围与文档来源
      可查；如果 B3 写入方漏字段，审计会静默不完整。预期 B3 写入路径补必填断言，或在策略层对
      这些动作 require 键（并补 V12VocabularyTest/B3 测试）。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:184-202、283-291、432-452；
      docs/adr/ADR-0023-m05-knowledge-depth.md:380-399；
      apps/api/src/test/java/com/riccon/api/migration/V12VocabularyTest.java:145-232。
    accepted_reason: >-
      当前 B1 只提供白名单/枚举边界，实际写入在 B3；缺省字段不会写入敏感数据，也不会绕过租户隔离，
      风险是审计完整性而非立即泄露。作为 P3 接受，由 B3 在写审计时补必填校验/测试。
  - id: F-010
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-core"
    due: "M05 gate 前"
    title: "`RICCON-WORKER-409` 已定义但没有任何使用点"
    summary: >-
      `WorkerErrorCodes.java:25` 新增 `LEASE_LOST = "RICCON-WORKER-409"`，但全仓未引用；租约丢失只写
      `outbox.lease_lost` 日志（`OutboxPoller.java:316-327`、`OutboxLeaseHeartbeat.java:171-188`），
      没有稳定错误码可被日志/告警/运维检索。`RICCON-WORKER-504` 只在新的超时分支使用，
      `RICCON-WORKER-500` 静态文案未被修改。预期要么在租约丢失日志/last_error 使用 409，要么删除
      未使用常量，避免“定义了却没生效”的假契约。
    evidence: >-
      apps/worker/src/main/java/com/riccon/worker/run/domain/WorkerErrorCodes.java:24-28；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxPoller.java:316-327；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxLeaseHeartbeat.java:171-188；
      `grep -rn "RICCON-WORKER-409" apps platform` 仅命中常量定义。
    accepted_reason: >-
      不影响租约/重试行为或用户结果，500 文案未被改变；属于可观察性/契约卫生问题，M05 gate 前
      顺手修正即可。
  - id: F-011
    severity: P3
    status: accepted
    owner: "riccon/lead"
    due: "TASK-0164 closeout"
    title: "`scope_amendment.reason` 称 `cd83e24` 有 53 个文件，实际为 52 个"
    summary: >-
      `git diff --name-only 93b09f7..cd83e24 | wc -l` 为 52；Lead 的 scope amendment
      （`.riccon/tasks/TASK-0164.json:43`）写“53 个文件逐项核对”。逐项核对结果本身成立：52 个文件
      全部落在当前 write_scope（含补录的测试/SchemaVersionChecker 路径），没有无关生产改动；
      只是数量记录多 1。预期 closeout 时修正文字，避免后续审计把 52/53 当成漏文件。
    evidence: >-
      .riccon/tasks/TASK-0164.json:40-54；
      `git diff --name-only 93b09f7..cd83e24 | wc -l` = 52；
      `git diff --name-only 93b09f7..cd83e24` 与 write_scope 逐项匹配。
    accepted_reason: >-
      纯记录笔误，不改变范围诚实性结论：没有越界或夹带；不影响任务验收。作为 P3 接受，closeout 修正即可。
  - id: F-012
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-core"
    due: "M05 gate 前"
    title: "B1 触及的测试/资源仍写 V1–V11"
    summary: >-
      `ApiApplicationTests.java:14` 的类注释仍写“Flyway V1–V11”，`V11MigrationTest.java:46` 仍写
      “空库 V1–V11 后 RoleDefinitions”，`apps/api/src/test/resources/application-test.yml:2` 仍写
      “V1–V11 建库”；实际测试已断言 12/fresh V1–V12。实现断言正确，只是注释误导排障。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:14、42-45；
      apps/api/src/test/java/com/riccon/api/migration/V11MigrationTest.java:46；
      apps/api/src/test/resources/application-test.yml:2。
    accepted_reason: >-
      仅注释漂移，不影响测试执行、断言或 gate；作为 P3 接受，B1 closeout 或 M05 文档整理时修正。
  - id: F-013
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-core / M05-V1"
    due: "M05 gate 前"
    title: "KnowledgeAclStartupCheck 的 WARN/指标值没有直接测试断言"
    summary: >-
      `KnowledgeAclPropertiesTest.java:17-40` 只断言默认值与常量名；`KnowledgeAclStartupCheck.java:58-67`
      在 API 集成上下文中确实执行（EV-0769 日志可见 `RICCON-KB-001` WARN），但没有任何测试断言
      `enforcementEnabled=true` 时 gauge=0 且不输出 WARN、false 时 gauge=1 且输出固定 WARN。
      预期用 `SimpleMeterRegistry` 直接调用 `run` 或在测试 profile 中断言指标值；当前默认 false
      的 WARN 由日志可见，但开关切换的负向/正向行为未被锁定。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/config/KnowledgeAclStartupCheck.java:58-67；
      apps/api/src/test/java/com/riccon/api/config/KnowledgeAclPropertiesTest.java:17-40；
      `.riccon/evidence/TASK-0164/EV-0769-...log` 中多次出现 `RICCON-KB-001`。
    accepted_reason: >-
      代码路径简单且默认关闭的 WARN 已在集成测试日志中实际触发；没有发现指标名或值错误。作为 P3
      接受，M05 gate 前补断言即可，避免后续把 gauge 语义改坏而无人发现。
  - id: F-014
    severity: P3
    status: accepted
    owner: "riccon/builder-kb-core"
    due: "M05 gate 前"
    title: "`knowledge.document.admin_read.visibility` 被列为 nullable，超出 ADR 明确列出的可空键"
    summary: >-
      `AuditDetailsPolicy.java:283-291` 把 `admin_read.visibility` 加入 nullable 白名单，
      `V12VocabularyTest.java:147-167` 也用 `visibility=null` 通过；ADR-0023 §5.6:387 只列出
      `visibility` 字段，任务 brief 明确提到的可空键是 `admin_read.documentId`、`departmentId`、
      `previousStatus`，未包含 `visibility`。这不会泄露敏感数据，但会让 DETAIL/SEARCH 审计缺少
      可见范围。预期确认 LIST 作用域是否确实需要 null；若需要，在 ADR/白名单注释中写明，否则改为必填。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:283-291、432-452；
      apps/api/src/test/java/com/riccon/api/migration/V12VocabularyTest.java:147-167；
      docs/adr/ADR-0023-m05-knowledge-depth.md:380-399。
    accepted_reason: >-
      只放宽一个非敏感枚举键，不能写入标题、片段或正文；LIST 管理视图没有单一 visibility 是合理解释。
      作为 P3 接受，M05 gate 前在 ADR/注释中确认语义即可。
created_at: "2026-11-22T22:10:39+08:00"
---

# REV-TASK-0164-001：TASK-0164 / M05-B1 独立评审（Phase 1 静态审查 + Phase 2 定向验证）

本评审是任务级独立技术验收，不是 Product 预验收、模块评审或用户产品验收。Phase 1 静态审查与
Phase 2 独立定向测试均已完成；`verdict: pass` 是任务级结论，不表示 M05 模块 gate 已通过。

## 1. 结论

- **未发现 P0/P1。** V12 迁移/回滚、worker schema 12、outbox 双通道/租约续期、审计
  details 白名单、ACL 灰度开关均有可核对的代码与测试；EV-0769 在 `cd83e24` 干净提交上全量
  `verify` exit 0，builder 报告的测试数与覆盖率可由 surefire/JaCoCo 报告复核。
- **Phase 2 独立定向测试通过**：EV-0772（API 47 tests：V12 13、V11 16、V10 11、V12Vocabulary 7）
  与 EV-0773（worker 30 tests：OutboxLaneClaim 3、OutboxLaneProperties 4、OutboxLane 4、
  OutboxLeaseHeartbeat 4、ParseDomainModel 6、SchemaVersionChecker 9）均 exit 0、
  `dirty=false`、0 fail/error/skip；迁移测试使用一次性 Testcontainers MySQL 8.4.13。
- **verdict=`pass`**（P2/P3 均 accepted，不保留 open finding）；但以下 P2 必须在 **M05 gate 前**
  关闭或由 Lead 正式接受：F-001（`knowledge.manage` 漂移）、F-002（outbox scheduler/poller
  测试缺口）、F-003（heavy 并发 vs 调度池）、F-004（30m vs 连接器 60m）、F-005（legacy 双调度）、
  F-006（ADR/ARCH 文档漂移）、F-007（restore-drill/RUNBOOK schema 11）、F-008（M24 spotless
  债务）。其中 F-001/F-004 分别阻塞 B3 与 M05b 的语义前置，不能当普通文档项。F-008 在 Phase 2
  再次复现，失败文件只有 M24 的 `RunFailureMessageSanitizationTest.java`，不是 B1 文件。
- **B1 无用户可见 UI**：本任务不产生页面/文案/入口，E2E、a11y、浏览器验证不适用；这些由 M05-V1/V2
  在 B3–B8 完成后覆盖，不能拿本评审替代模块 gate。

## 2. 评审范围与证据审计

### 2.1 提交与范围

| 项目          | 值                                                                                                                              |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| 任务 baseline | `93b09f7d73c80d8734197b17e2b7bd9ba9566878`（`cd83e24` 的父提交）                                                                |
| 代码提交      | `cd83e24279010d442038e36ad4da6bc3c1610d56`                                                                                      |
| 证据提交      | `e2e36fd2f9713f4c1d6ecff84c1b914b20ca70b8`（EV-0769 + 任务卡）                                                                  |
| 验证提交      | `d85dbbdfcd192a438b8de96f72d6bab425d70c05`（Phase 2 当前 HEAD；`cd83e24..d85dbbd` 仅 `SessionDepartmentApiTest` 的 M23 格式化） |
| 代码 diff     | `git diff 93b09f7..cd83e24` = 52 个文件；`git diff --check` exit 0                                                              |

逐项核对结果：52 个文件全部落在 `TASK-0164.write_scope`（含 Lead 在 `b309311` 补录的
`SchemaVersionChecker`、worker outbox 测试、API config/migration 测试与 `M04V2VerifierBoundaryTest`）；
没有 `contracts/**`、`apps/web/**`、其他模块生产代码或迁移夹带。Lead 的 scope amendment 在
`.riccon/tasks/TASK-0164.json:40-54`，baseline 改为 `93b09f7` 以排除共享工作树中先行的
M23/M24 提交，属于诚实重绑；唯一笔误是 reason 写“53 个文件”（实际 52，见 F-011）。

### 2.2 EV-0769

| 字段      | 值/核对                                                                                     |
| --------- | ------------------------------------------------------------------------------------------- |
| 命令      | `./mvnw -q -T1C verify jacoco:report`                                                       |
| commit    | `cd83e24`（与代码提交一致）                                                                 |
| dirty     | `false`                                                                                     |
| exit_code | `0`                                                                                         |
| duration  | 527274 ms（21:36:46–21:45:33 +08:00）                                                       |
| 输出      | `.riccon/evidence/TASK-0164/EV-0769-...-mvnw-q-t1c-verify-jacoco-report.log`，1659984 bytes |
| SHA-256   | `8676ec8...f666cef8`，与 `EV-0769.json` 一致                                                |
| agent     | `riccon/builder-kb-core`（builder 证据，非独立 verifier 证据）                              |

surefire 报告（EV-0769 生成）核对：API 127 类/689 tests、worker 74 类/401 tests、
platform-common 5 类/87 tests、platform-domain 5 类/22 tests；合计 **1211 tests，0 failures，
0 errors，0 skipped**。与 builder 报告一致。V12MigrationTest 13/13、V11 16/16、V10 11/11；
OutboxLaneClaimIntegrationTest 3/3、OutboxLeaseHeartbeatTest 4/4、OutboxLanePropertiesTest
4/4、OutboxLaneTest 4/4、ParseDomainModelTest 6/6、SchemaVersionCheckerTest 9/9；
V12VocabularyTest 7/7、KnowledgeAclPropertiesTest 2/2。JaCoCo CSV 与 builder 数字一致：
api line 92.5/branch 75.5、worker 87.4/71.0、platform-common 90.8/75.4、platform-domain
95.6（branch 0/0）。EV-0769 日志中出现大量预期负向测试的 ERROR 与两处 `Application run
failed`（启动失败用例），surefire 汇总为 0 failure/0 skip；没有被吞掉的失败或 no-op 证据。

EV-0769 日志末尾有 `[ERROR] Surefire is going to kill self fork JVM`；同一警告在 TASK-0103/
0105/0116/0119/0142/0145/0150/0151/0156 等历史 EV 中已出现，属于既有测试基础设施/线程退出债务，
未导致 skip/timeout，也不是 B1 引入；本评审不把它算作 B1 finding，但保留为 M05 gate 的
测试基础设施残余风险。EV-0769 日志中没有 ChatStream 407 或重试掩盖痕迹；builder 提到的偶发
抖动没有进入最终证据，最终以 EV-0769 的干净全量为准。

### 2.3 静态检查

- `git diff --check 93b09f7..cd83e24`：exit 0，无空白错误。
- Phase 1 为核对 lint 只运行了 `./mvnw -q spotless:check -DskipTests`（只读格式检查）；当前全仓在
  M24 的 `RunFailureMessageSanitizationTest` 失败（F-008），不是 B1 文件。B1 自身文件格式化干净。
- Phase 1 未运行 Maven 测试/构建；Phase 2 的定向测试见 2.4。

### 2.4 Phase 2 verifier 定向测试（EV-0772 / EV-0773）

两条命令都在主工作树（当前 HEAD `d85dbbd`）运行，首次即通过，没有重试；`dirty=false`、
exit 0，测试使用 Testcontainers，未使用热栈、未新建 worktree。

| EV      | 命令/范围                                                                                                                                                                         | 结果                                                                                                                                                                                                                                                                     |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| EV-0772 | `./mvnw -q -T1C -pl apps/api -am test -Dtest='V12MigrationTest,V11MigrationTest,V10MigrationTest,V12VocabularyTest' -Dsurefire.failIfNoSpecifiedTests=false`                      | exit 0；71.5s；**47 tests，0 fail/0 error/0 skip**：V12 13、V11 16、V10 11、V12Vocabulary 7。日志显示 V12 在 `riccon`/`riccon_rollback`/`riccon_fresh` 上分别执行 V1–V11→V12、V11→V12、V1–V12；每个迁移测试类使用一次性 MySQL 8.4.13 容器                                |
| EV-0773 | `./mvnw -q -T1C -pl apps/worker -am test -Dtest='OutboxLane*Test,OutboxLeaseHeartbeatTest,ParseDomainModelTest,SchemaVersionCheckerTest' -Dsurefire.failIfNoSpecifiedTests=false` | exit 0；55.0s；**30 tests，0 fail/0 error/0 skip**：OutboxLaneClaim 3、OutboxLaneProperties 4、OutboxLane 4、OutboxLeaseHeartbeat 4、ParseDomainModel 6、SchemaVersionChecker 9。日志含 `schema.version.checked expected=12 actual=12`，以及心跳超时/续期丢失的预期 WARN |

EV-0772/EV-0773 的 commit 均为 `d85dbbd`；`git diff cd83e24..d85dbbd -- apps platform` 只有
`SessionDepartmentApiTest.java`（M23 格式化），B1 的 52 个文件内容未变。因此定向测试对
`cd83e24` 的 B1 代码有效。Phase 2 没有新增 finding；F-002 的 scheduler/poller 缺口仍未被这两条
命令覆盖，保持 P2 accepted。

## 3. 验收对账（B1 三条）

| 验收                                                              | 静态结论                      | 依据                                                                                                                                                                                                                                     | 残余                                                                                                   |
| ----------------------------------------------------------------- | ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| V12 additive 迁移与回滚 SQL；worker schema 期望 12                | **pass（EV-0772 + EV-0773）** | `V12__knowledge_depth.sql` 全量；`V12MigrationTest` 13 用例覆盖正向、历史行、CHECK/FK、ACL/connector/citation/chunk/outbox/permission、fresh、回滚；`SchemaVersionChecker.EXPECTED_SCHEMA_VERSION=12` 且 `SchemaVersionCheckerTest` 锁定 | F-006 文档/锁等待；F-007 restore-drill                                                                 |
| outbox default/heavy 双通道、租约续期、配置键、ACL 灰度 WARN/指标 | **pass（EV-0772 + EV-0773）** | `OutboxLane` 事件集合/互斥；`OutboxClaimer` lane 过滤/空集合；`OutboxLeaseHeartbeat` 单事件续期/丢失/超时；`OutboxLaneProperties` 边界；`KnowledgeAclProperties`/`KnowledgeAclStartupCheck` 默认 false + WARN + gauge                    | F-002 调度器/poller 集成测试；F-003 并发池；F-004 30m vs 60m；F-005 legacy 双调度；F-013 WARN/指标断言 |
| 审计 details action-specific 数组/可空键/枚举；迁移与单测通过     | **pass（EV-0772 + EV-0773）** | `AuditDetailsPolicy` 新动作；`V12VocabularyTest` 50 UUID 上限、空数组、非法 UUID、admin_read scope/必填、文档/连接器枚举、敏感键 fail-closed；`AuditEvent` 白名单校验                                                                    | F-009 必填键；F-014 visibility nullable；F-010 409 未使用                                              |

B1 没有用户可见的 UI/AC；Product §11 的 M05 AC-01..09/15/16/21 等由 B3–B8 实现，本评审不提前判 pass。

## 4. 按风险排序的发现

完整字段（severity/status/owner/due/accepted_reason/evidence）见 front matter；本节按风险给结论。

| ID    | 级别        | 发现                                                                                      | 为什么现在不 fail                                                                                      |
| ----- | ----------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| F-001 | P2 accepted | ADR §5.6 的 MEMBER `knowledge.manage` 与 RoleDefinitions/V12/M02 测试不一致，未登记开放项 | 属既有 M02 语义，B1 验收不含；改角色会破坏 M02 测试，需 Lead/Architect + B3 一起决定；但 B3 前必须登记 |
| F-002 | P2 accepted | OutboxLaneScheduler 0% 覆盖；poller 租约丢失/超时分支未测                                 | claimer/lane/heartbeat 单测与静态路径正确，缺口是集成覆盖；核心验收须在 gate 前补                      |
| F-003 | P2 accepted | heavy-concurrency 1–8 vs 调度池默认 4                                                     | 默认 2 正常；只影响非默认配置，需校验/文档                                                             |
| F-004 | P2 accepted | max-processing 30m vs 连接器同步 60m                                                      | M05b 未实现；B1 跟随 ADR §7.3；M05b 前必须决策                                                         |
| F-005 | P2 accepted | legacy-all-lane-scheduling 不能替代 lane scheduler，旧/新同时领取                         | 默认 false；只影响回滚路径；回滚演练前必须修                                                           |
| F-006 | P2 accepted | ADR/ARCH 仍写 INPLACE、未列 bbox/outbox 回滚；无实际锁等待                                | 实现/迁移头/测试一致；权威文档需 gate 前更新                                                           |
| F-007 | P2 accepted | restore-drill/RUNBOOK 仍 schema 11                                                        | 不在 B1 写范围；T4 迁移/恢复证据前必须承接                                                             |
| F-008 | P2 accepted | 全仓 spotless 在 M24 文件失败；B1 EV 无 lint                                              | 不是 B1 文件；M05 gate 前 M24/Lead 修复                                                                |
| F-009 | P3 accepted | 文档类审计动作缺 reason/sourceType/visibility 必填校验                                    | B3 写入方负责；P3                                                                                      |
| F-010 | P3 accepted | `RICCON-WORKER-409` 定义未使用                                                            | 不影响行为；P3                                                                                         |
| F-011 | P3 accepted | scope amendment 53 vs 实际 52                                                             | 纯笔误，无越界；P3                                                                                     |
| F-012 | P3 accepted | B1 触及测试仍写 V1–V11                                                                    | 注释漂移；P3                                                                                           |
| F-013 | P3 accepted | ACL 启动 WARN/指标无直接断言                                                              | 代码简单且默认 WARN 在日志可见；P3                                                                     |
| F-014 | P3 accepted | admin_read.visibility nullable 超出 ADR 明列键                                            | 非敏感枚举；LIST 语义合理解释；P3                                                                      |

## 5. Phase 2 独立验证结果（已执行）

Lead 确认测试窗口后，在**主工作树**（不新建 worktree、不修改生产/测试/迁移）执行了两条定向命令，
首次即通过，没有重试：

```bash
# EV-0772
./mvnw -q -T1C -pl apps/api -am test \
  -Dtest='V12MigrationTest,V11MigrationTest,V10MigrationTest,V12VocabularyTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# EV-0773
./mvnw -q -T1C -pl apps/worker -am test \
  -Dtest='OutboxLane*Test,OutboxLeaseHeartbeatTest,ParseDomainModelTest,SchemaVersionCheckerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

证据：

| EV      | commit    | dirty | exit | 结果                                                                                                                                                            |
| ------- | --------- | ----- | ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EV-0772 | `d85dbbd` | false | 0    | API 47 tests：V12 13、V11 16、V10 11、V12Vocabulary 7；0 fail/error/skip                                                                                        |
| EV-0773 | `d85dbbd` | false | 0    | worker 30 tests：OutboxLaneClaim 3、OutboxLaneProperties 4、OutboxLane 4、OutboxLeaseHeartbeat 4、ParseDomainModel 6、SchemaVersionChecker 9；0 fail/error/skip |

迁移测试全部使用一次性 Testcontainers MySQL 8.4.13；日志显示 V12 在 forward/rollback/fresh 三个
schema 上完成 V1–V11→V12、V11→V12、V1–V12，worker 日志显示
`schema.version.checked expected=12 actual=12`。EV-0772/EV-0773 的 commit 为当前 HEAD `d85dbbd`；
`git diff cd83e24..d85dbbd -- apps platform` 仅 `SessionDepartmentApiTest.java`（M23 格式化），
B1 代码未变，因此定向结果对 `cd83e24` 有效。

**F-008 复现**：Phase 2 只读运行 `./mvnw -q spotless:check -DskipTests`，exit 1；失败文件列表
**只有** `apps/worker/src/test/java/com/riccon/worker/run/service/RunFailureMessageSanitizationTest.java`
（M24/TASK-0158 提交 `02dbc6d` 引入，非 B1）。没有修改任何文件；该债务保持 F-008 P2 accepted，
仍是 M05 gate 的前置修复项。

**EV-0769 复核**：commit `cd83e24`、`dirty=false`、exit 0 未变；日志 SHA-256 仍为
`8676ec812240845aa9d16c29f9690019433453469924caa39a1b1063f666cef8`，与 JSON/文件大小一致。

Phase 2 没有新增 finding；F-002 的 scheduler/poller 缺口仍未被这两条命令覆盖，保持 P2 accepted。

## 6. 未验证 / 没有做什么

- **未重跑 EV-0769 的全量 `verify`**：按 L3 分层，任务级用定向测试；模块 gate 再由 verifier 在最终
  提交上跑合同全量 test/coverage/E2E。
- **未验证真实生产锁等待/维护窗口**：V12 的 `COPY+LOCK=SHARED` 只在 MySQL 8.4.13 一次性容器验证；
  ADR 要求的实际锁等待/客户数据量结论尚无证据（F-006）。
- **未验证 M05b 连接器同步**：30m vs 60m 是设计冲突（F-004），M05b 尚未实现。
- **未验证 UI/E2E/a11y/浏览器**：B1 无 UI；M05-V1/V2 覆盖。
- **未验证 ACL 实际强制**：B1 只有开关/指标；B3 才实现谓词，本评审不把“默认 false + WARN”当成 ACL
  已生效。
- **未跑 `riccon gate check --module M05`**：M05 仍 `building`，本任务仍 `review`；gate 应在任务
  done、模块证据齐全后再由 Lead/Verifier 执行。当前 gate 不应通过。

## 7. 剩余风险与下一步

1. **Phase 2 已完成**：EV-0772/EV-0773 通过，final verdict=pass；不需要重试或补跑。
2. **M05-B3 前**：Lead 必须对 F-001 给出决定并登记（RoleDefinitions/ADR/Product/M02 测试四者之一），
   否则 AC-06/15 的创建人链会失败；B3 写审计时处理 F-009。
3. **M05b 前**：Architect/Lead 解决 F-004（30m/60m），并确认 F-003 的池大小配置。
4. **M05 gate 前**：修复 F-002（outbox 集成测试）、F-005（legacy 互斥）、F-006（ADR/ARCH 文档与锁等待）、
   F-007（restore-drill/RUNBOOK schema 12）、F-008（M24 spotless）；F-010/F-012/F-013/F-014 可顺手处理。
5. **模块 gate 不得以本任务评审替代**：M05 仍有 B2–B11/O1a/O1b/V1/V2 与 Product 预验收；本 review
   只是 B1 的技术验收。

---

**评审结论：Phase 1 静态审查 + Phase 2 独立定向测试完成，verdict=pass（任务级）。未发现 P0/P1；
EV-0772/EV-0773 均 exit 0、dirty=false、0 skip；F-008 复现且失败文件仅为 M24 文件；P2/P3 accepted，
M05 gate 前按上文关闭。**
