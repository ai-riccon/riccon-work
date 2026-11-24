---
review_id: REV-M06-OPS-001
module_id: M06
task_id: TASK-0218
reviewer:
  agent: riccon/ops
  session: ses_df17bdd54ffeus9tUQ2Rw3wUPe
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 751d38428384fa63a909717f6d353c7c906a33ca
  - 01b5c57fd767f9e954a15218837997fedabd88a5
  - a69497962c03226acaa39717162cbbabc899c18d
baseline_commit: 751d38428384fa63a909717f6d353c7c906a33ca
head_commit: a69497962c03226acaa39717162cbbabc899c18d
review_scope: >-
  设计阶段只读评审 ADR-0024（proposed）、ARCHITECTURE M06 章节、THREAT-MODEL M06 条、
  contracts/openapi.yaml 1.6.0-m06 与当前 outbox/schema/CI/deploy 实现；评审期间 DEC-0047/0048
  由用户答复为 A，AC-23 已同步（01b5c57）。未运行任何构建或测试，未修改被评审文件。
findings:
  - id: OPS-M06-001
    severity: P2
    status: open
    title: "AC-23 口径已按 DEC-0047=A 修订；缺可执行判定 SQL 与演练断言"
    summary: >-
      评审开始时 AC-23 要求“应用回滚到 M06 前版本且历史仍可读”，与旧 API 无 workflow 端点、
      审计白名单 fail-closed 冲突。评审期间用户答复 DEC-0047=A，AC-23 已同步为
      “关闭开关 + 回滚 Worker + 保留/前向修复 API；有 workflow 数据/审计行时不支持全量 API 回滚”，
      ADR §10.4/§2 也标 A，Ops 认可该口径（唯一可演练、不削弱审计 fail-closed）。
      剩余缺口是可执行性：“全量 API 回滚仅限无任何 workflow 数据与审计行”目前仍是文字，
      没有判定 SQL（workflow_run/workflow_approval/audit_event 中 workflow.* 计数）与
      “不满足即拒绝回滚”的演练断言；应急 drop 的全局表/租户表也没分脚本。
      必须把判定查询、拒绝路径、在途 WAITING_APPROVAL 必须先取消的顺序写进 RUNBOOK 与
      OPS-M06-01 演练脚本；否则 AC-23 的文字无法被 verifier 复现。
    owner: "riccon/lead（AC/演练口径）；T-M06-16（脚本）；T-M06-15（RUNBOOK）"
    due: "M06 gate 前（OPS-M06-01 取证时）"
    evidence: >-
      .riccon/decisions/DEC-0047.md（answered=A）；docs/PRODUCT.md M06-AC-23（01b5c57）；
      docs/adr/ADR-0024 §10.4 L669-680、§2 回滚行
  - id: OPS-M06-002
    severity: P1
    status: open
    title: "运行队列领取/租约/并发参数未闭合：批量领取会烧租约，调度池与心跳池无校验"
    summary: >-
      ADR §4.3 每月 worker 槽位独立轮询，但 §10.1 同时给出 RICCON_WORKFLOW_CLAIM_BATCH=10 与
      WORKER_CONCURRENCY=10；若每个槽位按 batch=10 领取，单实例一次可领最多 100 个 run，
      只有 10 个立即执行，其余 run 的 90s 租约从领取时刻开始计时且没有心跳，90s 后会被其他实例重领，
      形成重复领取/重复执行窗口（当前设计只用节点级条件更新兜底，未把该语义写成不变量）。
      另外：worker 现有 `spring.task.scheduling.pool.size` 默认 4（application.yml），而 M06 要提交
      10 个固定延迟任务；参照 M05 F-003 的教训（OutboxSchedulingPoolValidator），不校验就会静默只有 4 个并发。
      心跳沿用 `OutboxLeaseHeartbeat` 的 2 线程池，10 个并发 run × 每次续期/看门狗都落在这 2 个线程，
      DB 抖动时续期排队会导致租约丢失。要求：claim 批量必须 ≤ 空闲槽位且领取后立即启动心跳
      （或明确 batch=1/槽位）；启动期校验调度池 ≥ worker 并发；明确心跳池容量与续期间隔（≤ lease/3）。
    owner: "riccon/architect（ADR 修订）；T-M06-06 / T-M06-15"
    due: "T-M06-06 开工前完成 ADR 修订；T15 落地启动校验"
    evidence: >-
      docs/adr/ADR-0024 §4.3 L308-316、§10.1 L638-654、
      apps/worker/src/main/resources/application.yml（pool.size 默认 4、batch-size 10、lease-duration 90s）、
      apps/worker/.../OutboxLeaseHeartbeat.java:50（2 线程池）、
      apps/worker/.../OutboxSchedulingPoolValidator.java（M05 F-003 同类校验）
  - id: OPS-M06-003
    severity: P1
    status: open
    title: "队列可观测性不足：缺积压深度/最老 PENDING/租约丢失/领取耗尽指标与告警阈值"
    summary: >-
      ADR §10.2 只有 runs_total{status}、run/node duration、runs_active、approvals_pending、
      admission_rejected_total{scope}、result_oversize_total。把 workflow_run 当持久队列后，
      最关键的两个信号缺失：队列深度与最老 PENDING 年龄（对照 ADR-0016 的
      riccon_agent_queue_depth / oldest_age_seconds）。没有它们，Worker 全挂或全部槽位被
      WAITING_APPROVAL 占满时，用户只看到 429，运维无法在告警前定位。还缺
      lease_lost_total、claim_attempts_exhausted_total、claim_failures_total，
      否则“租约丢失后放弃结果”和 WF_INTERNAL 只能在日志里翻。§10.2 把告警阈值全部推到 M09，
      但 M06 是 v1 交付物，至少要在 RUNBOOK 给出 backlog/oldest-age/失败率的建议阈值与处理步骤。
    owner: "riccon/architect（指标清单）；T-M06-15"
    due: "T-M06-15 完成前"
    evidence: >-
      docs/adr/ADR-0024 §10.2 L656-660、§10.5 L682-691；
      docs/adr/ADR-0016 §3（outbox 指标基线）
  - id: OPS-M06-004
    severity: P1
    status: open
    title: "V15 联动断言无任务写范围：CI、ApiApplicationTests、SchemaVersionCheckerTest、restore-drill 会直接变红"
    summary: >-
      V15 新增 9 张表与 workflow_version 的 2 个不可变触发器后，以下写死的断言会失败：
      `.github/workflows/ci.yml:247` `test "${triggers}" -eq 2`（V15 后应为 4）；
      `ApiApplicationTests:44-45` tables=49/triggers=2（应为 58/4，经 Flyway 口径）；
      `SchemaVersionCheckerTest:64 expectedSchemaVersion_isLockedAtV14`（应为 15）；
      `scripts/ops/restore-drill.mjs:48/51` EXPECTED_SCHEMA_VERSION=14、EXPECTED_TRIGGERS=2
      （含 self-test 夹具）；`docs/RUNBOOK.md` 迁移演练的 V1–V14/48 表/2 触发器与 49 表口径。
      ADR §13 中 T01 的 write_scope 只覆盖 V15、两个 application.yml、AuditDetailsPolicy、
      RoleDefinitions 和 SchemaVersionChecker.java（不含其测试）；T16 覆盖 scripts/** 但不含
      `.github/**` 与上述两个测试类。结果是 V15 一合入，首个绿灯构建与迁移冒烟必然失败，
      且没有任务/owner 负责修。CI 与演练断言应尽量改为从迁移文件推导（表数已动态推导，触发器未做）。
    owner: "riccon/lead（分配）；T-M06-01 + T-M06-16"
    due: "V15 合入前"
    evidence: >-
      .github/workflows/ci.yml:247；apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:44-45；
      apps/worker/src/test/java/com/riccon/worker/persistence/SchemaVersionCheckerTest.java:64；
      scripts/ops/restore-drill.mjs:48,51；docs/RUNBOOK.md:226,268,289,300,351-352；
      docs/adr/ADR-0024 §13.1 L728（T01 write_scope）
  - id: OPS-M06-005
    severity: P1
    status: open
    title: "V15 单迁移缺少失败处置与前置检查：TRIGGER/binlog、MDL、max_allowed_packet、DML 幂等、repair 流程"
    summary: >-
      V15 在一个迁移里做 9 张表 + model_invocation 加列/索引 + 权限/角色回填 + 模板种子 +
      触发器。MySQL DDL 非事务；任一后续语句失败（例如触发器因权限/1419 失败、权限回填唯一键冲突）
      会留下“已建 8 张表 + 1 个失败 history 行”的中间态，重新执行 V15 会重复建表。
      ADR §15.2 只写“迁移账号需要 TRIGGER 权限，否则 V15 失败 → 写 RUNBOOK”，但没有：
      1) 生产预检清单（`SHOW GRANTS`、binlog 开启时 `log_bin_trust_function_creators` 或 SUPER、
      数据库/表 MDL 与长事务、磁盘、`max_allowed_packet`）；
      2) 失败处置（停写 → 备份 → 手工 drop 已建对象 → 删除 success=0 的 history 行 → flyway repair → 重跑，
      或以备份恢复为唯一路径）；
      3) 所有种子/回填 DML 必须可重复执行（INSERT ... ON DUPLICATE KEY UPDATE 或 WHERE NOT EXISTS），
      并在迁移测试中断言二次执行幂等。
      测试栈靠 compose 的 `--log-bin-trust-function-creators=1` 掩盖了生产差异，不能作为生产预检证据。
    owner: "riccon/architect（ADR 修订）；T-M06-01"
    due: "T-M06-01 编码前"
    evidence: >-
      docs/adr/ADR-0024 §3.5 L258-275、§15.2 L812-817；
      deploy/compose/docker-compose.test.yml（log-bin-trust-function-creators 仅测试栈）；
      docs/RUNBOOK.md 迁移演练章节（V1–V14 口径）
  - id: OPS-M06-006
    severity: P1
    status: open
    title: "RUNBOOK M06 章节无任务/写范围，AC-23 与 TM-82 要求的回滚步骤无处落地"
    summary: >-
      AC-23 明确“回滚步骤与限制写入运行手册”，TM-82 也要求“回滚限制进 RUNBOOK”；
      ADR §10.5 只给“设计输入，不写最终 runbook”，§13 的 17 个任务没有任何一个的 write_scope
      包含 `docs/RUNBOOK.md`（T15 只写 workflow/runtime 代码与配置；T17 只写 .riccon/reviews）。
      当前 RUNBOOK 全文无 M06/workflow 字样。至少缺：开关与全部 RICCON_WORKFLOW_* 配置表、
      关闭/开启步骤与审计核对、队列积压/租约/看门狗排障、审批等待与执行并发区别、
      失败节点重试与写工具重审批、未知副作用处置、V15 迁移预检与失败处置、回滚步骤与限制、
      保留边界与“未物理删除”标准答复、指标与容量（5 MiB、max_allowed_packet、备份体积）。
    owner: "riccon/lead（补写范围）；建议 T-M06-15 或新增 ops 任务"
    due: "M06 任务建卡时；M06 gate 前"
    evidence: >-
      docs/PRODUCT.md M06-AC-23 L840；docs/security/THREAT-MODEL.md TM-82；
      docs/adr/ADR-0024 §10.5 L682-691、§13.1 L728-744；
      docs/RUNBOOK.md（grep M06/workflow 无运行章节）
  - id: OPS-M06-007
    severity: P2
    status: accepted
    title: "审批等待占用租户准入名额且无过期，可能长期占满 10 个槽位"
    summary: >-
      运行准入把 WAITING_APPROVAL 计入租户并发，审批又“无自动过期、不自动放行”
      （AC-06/U-12 已批准）。极端情况下 10 条等待审批的运行会让租户所有新运行持续 429，
      且 AC 不要求把等待中的运行踢出。恢复路径存在（发起人/租户管理员取消），但需要可发现：
      riccon_workflow_approvals_pending 需带最老等待时长告警，RUNBOOK 要有“租户被等待审批卡死”
      的解除步骤，UI 空态/429 文案要能指向审批人。
    owner: "riccon/architect（告警与手册）；T-M06-15 / T-M06-09"
    due: "M06 gate 前写入 RUNBOOK"
    accepted_reason: >-
      U-12 已由 DEC-0046=A 批准“计入准入、释放执行槽位”；取消路径与审批待办列表已存在，
      不改变产品语义，仅补可观测性与操作步骤，属残余风险而非设计阻塞。
  - id: OPS-M06-008
    severity: P2
    status: open
    title: "WF_INTERNAL / claim_attempts 耗尽后缺少可恢复终态定义"
    summary: >-
      §4.3 规定租约过期重领超过 5 次 → 运行失败 WF_INTERNAL。但未规定此时当前节点
      workflow_node_run 的状态：若停留在 RUNNING，`POST /retry` 的前置（运行 FAILED 且节点 FAILED）
      永远不满足，运行成为不可重试的死记录；也没有“运维重新入队”的受支持操作。
      要求：WF_INTERNAL 时把当前节点置 FAILED（或 CANCELLED）并写审计，保证 retry 可用；
      若节点本就不存在（崩溃在节点边界），明确从 current_node_key 重建 PENDING 尝试。
    owner: "T-M06-06 / T-M06-08"
    due: "T-M06-06 完成前"
    evidence: docs/adr/ADR-0024 §4.3 L314、§4.6 L349
  - id: OPS-M06-009
    severity: P2
    status: open
    title: "kill switch 语义与审计（U-04）需收口：多实例重复、非变更记录、缺 gauge、worker 侧无停止领取开关"
    summary: >-
      §4.8 的启动审计会在每次重启、每个 API 实例写一条 workflow.feature.changed，
      它不是“变更日志”，只是“启动时观测到关闭”。建议：动作语义改为观测/或仅在 enabled=false 时
      带 instanceId/observedAt 写一条（多实例重复仍可接受），同时暴露
      riccon_workflow_feature_enabled gauge，供告警发现“API 已关但 Worker 仍在领”。
      另外，kill switch 只约束 API；Worker 没有“停止领取新 run、让在途 run 跑到终态”的开关，
      滚动更新/紧急停止只能缩容到 0，会把在途 run 变成租约过期 + WF_SIDE_EFFECT_UNKNOWN。
      建议增加 Worker 侧 claim 开关与优雅停机语义（停止领取 → 等待 ≤N 秒 → 退出，在途按租约恢复）。
      Ops 对 U-04 的立场：保留部署配置（比 DB 开关更适合私有化 + 无平台 UI），
      但必须补 gauge、实例标识与 worker 侧开关；无需升为用户决策。
    owner: "riccon/architect（ADR）；T-M06-15"
    due: "T-M06-15 完成前"
    evidence: docs/adr/ADR-0024 §4.8 L374-375、§10.1 L640、§16 U-04 L862
  - id: OPS-M06-010
    severity: P2
    status: open
    title: "轮询契约缺 updatedAt，且 5 MiB 结果会随每次轮询重复传输"
    summary: >-
      ADR §7.4 写“默认 3s，updatedAt 变化才重绘”，但 `WorkflowRun` schema
      （contracts/openapi.yaml:8377-8441）没有 updatedAt 字段，实现者只能自行加字段或退回
      全量比对。`WorkflowRunDetail` 还内联返回最多 5 MiB 的 result；终态前轮询没有代价，
      但多标签页查看同一条已完成运行、客户端未正确停轮询时会持续拉 5 MiB。
      要求：契约补 `updatedAt`；文档明确“终态即停止轮询，result 只在终态读取一次”；
      条件请求（ETag/304）或 `includeResult=false` 作为可选优化记录，不阻塞 M06。
    owner: "T-M06-01（契约）；T-M06-08 / T-M06-12（行为）"
    due: "T-M06-08 契约冻结前"
    evidence: >-
      docs/adr/ADR-0024 §7.4 L525；contracts/openapi.yaml:8377-8441,8485-8505
  - id: OPS-M06-011
    severity: P2
    status: open
    title: "5 MiB 结果的容量/协议/备份影响未进入预检：连接器 maxAllowedPacket 默认 65535"
    summary: >-
      结果只存 MySQL（U-07 默认），单行最大 5 MiB。项目未配置 `maxAllowedPacket`；
      Connector/J 9.7.0 的属性默认值是 65535（本机 javap PropertyDefinitions 核对），
      若未显式配置且驱动未按服务器值自动放宽，单条 5 MiB JSON 写入会在协议层失败。
      发布前必须：断言服务器 `max_allowed_packet` 与 JDBC `maxAllowedPacket` ≥8 MiB；
      集成测试真实插入接近 5 MiB 的 result_json；给出容量公式
      （runs/day × 平均 result_size × 保留天数，读 result_size_bytes 分位数）与磁盘/备份告警；
      说明 binlog/备份体积影响与 RTO 4h 的余量。另外 `workflow_node_run.output_json` ≤256 KiB × 50 节点
      与 5 MiB 总上限的关系要写清（总上限优先，节点写入须先累计校验）。
    owner: "T-M06-15（预检/指标）；T-M06-16（容量与备份演练）"
    due: "T15/T16 完成前"
    evidence: >-
      docs/adr/ADR-0024 §3.4 L251-256、§10.5 L690、§16 U-07 L865；
      本机 `javap` mysql-connector-j 9.7.0 PropertyDefinitions（maxAllowedPacket 默认 65535）
  - id: OPS-M06-012
    severity: P2
    status: accepted
    title: "180 天只做到期不可见，物理清理归 M09，表会单调增长"
    summary: >-
      M06 无清理任务、无在线删除；v1 私有化交付后表与备份持续增长，客户若在 M09 前运行超过
      180 天，“保留期”只是可见性而非删除。必须在 RUNBOOK 写标准答复与容量告警，
      并给出经用户批准的人工归档/清理边界（带审计、绝不走在线删除）。
    owner: "riccon/lead（M09 排期）；T-M06-08 只做到期不可见"
    due: "M09 release blocker；RUNBOOK 在 M06 gate 前"
    accepted_reason: >-
      与 M05 R-26 同口径，合同已把物理清理列为 M09 release blocker（R-35）；
      M06 不新增清理路径是刻意避免在线删除与跨租户批量删除风险。
    evidence: docs/adr/ADR-0024 §9.5 R-35 L628、§10.5 L689；docs/PRODUCT.md M06-AC-14
  - id: OPS-M06-013
    severity: P2
    status: open
    title: "T15/T17 运维工时与证据面偏紧，回滚演练没有独立脚本落点"
    summary: >-
      T15（3h）要同时交付 runtime-limits 端点、kill switch 与启动审计、启动校验、7 个以上指标
      与测试，明显偏紧；T17（8h）同时承担全部任务评审、全量回归、模块 gate、回滚/威胁模型核对，
      真正可执行的 M06 回滚演练（M06 部署 → 造运行/审批/审计 → 关开关 → 回滚 Worker →
      核对历史可读与 J1/J2 → schema 15 人工核对 → 恢复）放不进去。建议在 120h 内调剂：
      从画布增强（T11，-2~3h，ADR 已列候选）挪到 T15 +1h、T16 +2h，
      由 T16 产出 `scripts/ops/workflow-rollback-drill.mjs` 与可重复报告，T17 只复核。
      超过 20% 才需要回到用户，本次属预算内调剂，由 Lead 决定并记录。
    owner: "riccon/lead"
    due: "M06 任务建卡时"
    evidence: docs/adr/ADR-0024 §13.1 L728-749
  - id: OPS-M06-014
    severity: P2
    status: open
    title: "M06b/M26 部署边界：无迁移可滚动，但新增审计动作后同样不能把 API 回滚到 M06"
    summary: >-
      M06b 只加 API/Web 与 1.7.0 契约，无迁移、无 Worker 变更，部署是普通滚动，回滚 = 回退
      API/Web 镜像，风险低。但 M06b 新增 workflow.template.saved/shared/unshared 审计动作后，
      一旦写入，旧（M06）API 的 AuditDetailsReader 仍会 fail-closed，即“M06b → M06 API 回滚”
      同样不可用。M06 §12 应加一句约束，M06b 设计必须继承 U-01/DEC-0047 的回滚口径并明确
      “回滚到 M06 只允许在无 M06b 审计行时”，避免到 M06b gate 才发现。
    owner: "riccon/architect"
    due: "M06b 设计启动时"
    evidence: docs/adr/ADR-0024 §8.1 L564、§12 L711-718、§10.4 L678；docs/PRODUCT.md M06-AC-11/25
  - id: OPS-M06-015
    severity: P2
    status: open
    title: "TM-85 跨租户领取可接受，但缺列集合静态断言与拒绝审计"
    summary: >-
      领取索引 `(status, lease_until, id)` 不以 tenant 开头、领取只读 id/tenant_id/status/lease_until，
      与 ADR-0016 同类例外，Ops 可接受。但与 outbox 不同，M06 领取没有 HMAC 三方校验，
      workflow_run 又是含 inputs/result 的业务表，未来加列/改 mapper 很容易把业务内容带进
      跨租户 SELECT。要求：用测试或 ArchUnit 断言 WorkflowRunClaimer 的 SELECT 列集合恰为 4 列；
      非法 tenant_id / 领取失败写 denied 审计或计数指标；`ORDER BY id` 在两段 status 范围上
      可能 filesort，记录为可接受并观察 claim 延迟。`IT-WF-103`/`TEN-M06-01` 需覆盖。
    owner: "T-M06-06（实现与测试）；riccon/verifier（复核）"
    due: "T-M06-06 完成前"
    evidence: docs/adr/ADR-0024 §4.3 L311-312、§9.2 TM-85 L602；docs/adr/ADR-0016 §1-§4
  - id: OPS-M06-016
    severity: P3
    status: open
    title: "runtime-limits 的 disabledReason 没有部署配置来源"
    summary: >-
      契约 `WorkflowRuntimeLimits.disabledReason`（≤200，可空）用于“编排关闭”的可读原因，
      但 §10.1 只有布尔开关，没有对应配置键。建议增加可选
      `RICCON_WORKFLOW_DISABLED_REASON`（默认空，API 回退固定文案），并在 RUNBOOK 配置表登记；
      否则实现会硬编码一句文案，客户无法说明关闭原因。
    owner: "T-M06-01 / T-M06-15"
    due: "T-M06-15 完成前"
    evidence: contracts/openapi.yaml:8671-8733；docs/adr/ADR-0024 §10.1 L638-654
notes: >-
  本次为设计阶段只读评审，未产生 EV；所有结论以文件/行号与本机只读命令为依据。
  评审期间 DEC-0047/0048 由用户答复 A（.riccon/decisions），AC-23 已在 01b5c57 同步；
  ADR-0024 与 ARCHITECTURE 的工作区改动由 Architect 并发进行，本评审以 751d384 设计文本为准，
  未把未提交改动当作已修复证据。
  未运行构建/测试/迁移；deploy_preview / deploy_prod 指向的 M09 产物尚不存在，
  M06 证据应按 M04 先例标 not-applicable，用一次性 MySQL 8.4.13 + compose 栈取证。
created_at: "2026-11-25T01:12:17+08:00"
---

# REV-M06-OPS-001：M06 运维/回滚设计独立评审

## 结论

**不通过（fail）**。ADR-0024 的执行模型（`workflow_run` 作持久队列、独立租约、kill switch、
不做 down migration）方向正确，J1/J2 隔离也成立；AC-23 已按 DEC-0047=A 修订为可演练口径，
U-01 不再阻塞。但作为 T4 可运维设计，仍有 5 条 P1 未闭合：

1. 运行队列的领取批量、调度池、心跳池参数互相冲突，存在烧租约/重复领取与静默降并发；
2. 队列可观测性缺积压深度/最老 PENDING/租约丢失，告警全部推给 M09；
3. V15 的联动断言（CI、两个测试类、restore-drill、RUNBOOK 数字）没有任何 M06 任务负责；
4. V15 单迁移没有部分失败处置与生产预检（TRIGGER/binlog/MDL/包大小/DML 幂等）；
5. RUNBOOK 的 M06 章节没有写范围，AC-23/TM-82 要求的回滚步骤无处落地。

这些都能在 M06 实现前以 ADR 修订 + 任务写范围 + 一条预检清单修掉，不需要改产品范围；
但当前状态不满足“可部署、可回滚、可观测”的 Ops 门禁。

## 评审范围与方法

只读核对，未修改任何被评审文件：

- 设计：`docs/adr/ADR-0024`（§3.5、§4.3/4.6/4.8、§9、§10、§13、§16）、`docs/ARCHITECTURE.md` M06 章节与评审状态表、`docs/security/THREAT-MODEL.md`（TM-79/81/82/85、B-29）、`contracts/openapi.yaml` 1.6.0-m06 与 `contracts/README.md`。
- 现状：`OutboxClaimer` / `OutboxPoller` / `OutboxLeaseHeartbeat` / `OutboxLaneScheduler` / `OutboxSchedulingPoolValidator` / `SchemaVersionChecker`、API/Worker `application.yml`、`deploy/compose/docker-compose.test.yml`、`scripts/sandbox.sh`、`.github/workflows/ci.yml`、`scripts/ops/restore-drill.mjs`、`docs/RUNBOOK.md`、ADR-0016/0021。
- 决策：`DEC-0047=A`（AC-23 回滚口径，评审期间 answered）、`DEC-0048=A`（配额口径，answered）。

### 证据命令与输出摘要

```text
$ git rev-parse 751d384
751d38428384fa63a909717f6d353c7c906a33ca
$ git rev-parse HEAD
a69497962c03226acaa39717162cbbabc899c18d   # 评审期间依次经过 e414d5b → 69bf886 → 01b5c57 → a694979

$ grep -n 'test "${triggers}" -eq 2' .github/workflows/ci.yml
247:          test "${triggers}" -eq 2
$ grep -n 'isEqualTo(49L)\|isEqualTo(2L)' apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java
44:        assertThat(tables).isEqualTo(49L);
45:        assertThat(triggers).isEqualTo(2L);
$ grep -n 'EXPECTED_SCHEMA_VERSION = 14\|EXPECTED_TRIGGERS = 2' scripts/ops/restore-drill.mjs
48:const EXPECTED_SCHEMA_VERSION = 14;
51:const EXPECTED_TRIGGERS = 2;
$ grep -n isLockedAtV14 apps/worker/src/test/java/com/riccon/worker/persistence/SchemaVersionCheckerTest.java
64:    void expectedSchemaVersion_isLockedAtV14()

$ grep -n 'newScheduledThreadPool(2' apps/worker/.../OutboxLeaseHeartbeat.java
50:        this.scheduler = Executors.newScheduledThreadPool(2, ...)
$ grep -n 'pool:\|size:\|batch-size\|lease-duration' apps/worker/src/main/resources/application.yml
pool.size: 4；outbox.batch-size: 10；lease-duration: 90s

# Connector/J maxAllowedPacket 默认值（本机 jar 9.7.0）
$ javap -p -c com/mysql/cj/conf/PropertyDefinitions.class | grep -A4 maxAllowedPacket
  1705: getstatic PropertyKey.maxAllowedPacket
  1708: ldc int 65535
```

## A. 运行队列与租约

### 结论

把 `workflow_run` 当持久队列、不复用 J2 outbox 的决策是正确取舍：J2 的 `agent_run_outbox`
`run_id NOT NULL` + `OutboxPoller` 绑定 `agent_run.kind`，复用会直接扩大 J2 回归面；
新增 `workflow_outbox` 又要复制 claimer/看门狗/清理。单写、状态即队列、租约 + 条件更新足以支撑
M06 的 10 并发。**但参数层面还没有闭合**（OPS-M06-002）：

- 领取批量 10 × 槽位 10：如果按 outbox 的“领一批、串行处理”实现，队列中的 run 在未开始执行前
  没有心跳，90s 租约从领取时刻开始烧；一个 30 分钟的 run 会挤掉同批 9 个 run 的租约。
  必须明确“每个执行槽位只领取 1 个 run 并立即启动心跳”，或把领取写成
  `LIMIT min(claimBatch, freeSlots)` 且领取后立刻为该 run 启动心跳；`claim_batch` 的默认值
  不应大于 worker 并发。
- 调度池：worker 当前 `spring.task.scheduling.pool.size` 默认 4。M06 若要提交 10 个固定延迟
  轮询任务，会静默只跑 4 个——这正是 M05 F-003 修过的坑（`OutboxSchedulingPoolValidator`）。
  要么使用独立 `TaskScheduler`（自有池 = worker 并发），要么加启动校验并在错误信息里给出
  `RICCON_WORKFLOW_WORKER_CONCURRENCY` 与 `RICCON_WORKER_SCHEDULER_POOL_SIZE` 两个键。
- 心跳池：现有 `OutboxLeaseHeartbeat` 固定 2 线程，续期间隔 = lease/3（30s）。10 个并发 run
  的续期 + 超时看门狗共用 2 线程；DB 抖动或长 GC 时续期排队会直接造成租约丢失。
  需要按并发配置池大小（≥并发数），并记录续期耗时/失败指标。
- 租约丢失后的结果丢弃语义、`FOR UPDATE SKIP LOCKED` + 条件更新、`claim_attempts` 5 次上限
  本身是合理的；缺的是 `WF_INTERNAL` 的终态定义（OPS-M06-008）和可观测性（OPS-M06-003）。

### 与 outbox 的运维面差异

- 不新增表/事件是净收益；代价是“队列即业务表”，`workflow_run` 同时承载 inputs/result/租约。
  这让 claim 索引必须扫全租户，且清理/归档只能按业务保留策略走（M09），不像 outbox 可以按
  DONE 状态直接清。可接受，但需把容量与清理边界写进 RUNBOOK（OPS-M06-011/012）。
- 没有死信队列是合理简化：失败即 `FAILED` + 稳定错误码，重试是人工 API。但必须保证
  “失败运行可重试、不可重试时也能取消/归档”的闭环（OPS-M06-008）。
- 跨租户领取（TM-85）可接受，但 M06 没有 outbox 的 HMAC 三方校验，业务表又含敏感内容；
  必须用“领取 SQL 列集合断言 + 拒绝审计/指标”补上结构性防护（OPS-M06-015）。

## B. 迁移与回滚

### V15 结构

顺序（9 表按 FK → model_invocation 可空列 + 索引 → 权限/角色回填 → 模板种子 → 触发器）正确，
`model_invocation` 只加可空列、不加 CHECK、不改 `purpose`，旧 API 显式列清单读写兼容；
`workflow_template` 作为全局表扩展 ADR-0012 例外清单也已登记。PL 层面没有发现破坏性变更。

问题在**执行安全**（OPS-M06-005）：

- MySQL DDL 非事务，9 表 + ALTER + 触发器 + DML 放在一个 V15 里，任一语句失败会留下半迁移；
  重新执行会撞“表已存在”。必须写明失败处置：停写 → 备份 → 手工清理已建对象 →
  删除 `success=0` 的 history 行 → `flyway repair` → 重跑；生产默认走备份恢复。
- 触发器要求迁移账号有 `TRIGGER` 权限；测试栈靠 `--log-bin-trust-function-creators=1`
  绕开 1419，生产必须预检该变量或 SUPER，否则 V15 在最后一步失败。
- 权限/角色/模板种子必须是幂等 DML，并在迁移测试中断言二次执行不产生重复行
  （ADR 只对 WORKFLOW_EDITOR 的确定性 ID 提了幂等断言，未覆盖权限映射与模板种子）。
- 建议在预检里加入：`SHOW GRANTS`、长事务/MDL、磁盘余量、`max_allowed_packet` ≥8 MiB、
  当前 `MAX(version)=14`、备份可恢复性；全部通过才允许执行 V15。

### 回滚

- 受支持回滚（关开关 → 回滚 Worker → 保留/前向修复 API）**可执行**且已由 DEC-0047=A 确认为 AC-23 口径：
  关开关后 API 只读、在途 run 继续；旧 Worker（expected=14）在 15 库上必须
  `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动并输出 `RICCON-WORKER-002` 固定 WARN，
  与 ADR-0021 的流程一致。需要在 RUNBOOK 写清“回滚窗口只允许 M05 最后一个 worker 版本、
  人工核对 `MAX(version)=15`、登记工单”。
- 一个容易漏的顺序问题：在途运行包含 `WAITING_APPROVAL`，而审批不过期；回滚 Worker 前必须
  先用**保留的 M06 API** 取消这些运行，否则它们永远不进入终态，旧 Worker 也不会接管。
  §10.4 第 2 步“先等待/取消在途运行”应展开为“等待终态不可行时必须取消”，并给出判定查询。
- “全量 API 回滚仅限无 workflow 数据/审计行”已写进 AC-23，但必须变成可执行判定：例如
  `SELECT COUNT(*) FROM workflow_run`（含未到期）、`workflow_approval`、
  `audit_event WHERE action LIKE 'workflow.%'` 全为 0，且用户书面同意，才允许应急 drop；
  否则拒绝。当前只有文字（OPS-M06-001，P2）。
- 应急 drop 的顺序（触发器 → 子表 → 父表 → 权限/角色回填 → model_invocation 索引/列 → history 15）
  正确；但 drop 全局 `workflow_template` 与租户表混在一个脚本里，建议拆成“全局/租户”两段，
  避免租户数据未清时误删内置模板。

### 保留与 5 MiB

- 180 天只做到期不可见、物理清理 M09 是已接受的 R-35；但 v1 交付后表与备份单调增长，
  RUNBOOK 必须有容量公式、`result_size_bytes` 分位数监控和“未物理删除”的标准答复（OPS-M06-011/012）。
- 5 MiB 结果：MySQL 单行 JSON 本身可行（JSON 存 off-page），真正的坑在协议与备份：
  项目没有配置 `maxAllowedPacket`，Connector/J 9.7.0 默认 65535，5 MiB 单语句可能直接失败。
  需在预检与集成测试中真实插入接近上限的 result，并记录 binlog/备份体积影响。
  另外单节点 256 KiB × 50 与总 5 MiB 的关系要写成“总上限优先，写入前累计校验”。

## C. 配置、开关与指标

### 配置

- §10.1 的键齐全（节点/步数/超时/并发/结果大小/worker 并发/领取批量/保留），启动校验只写了
  “必须为正、worker 并发 ≥1”，缺 claim_batch ≤ worker 并发、调度池 ≥ worker 并发、
  心跳池 ≥ worker 并发（OPS-M06-002）。
- 租户只读、平台管理员默认不改（OQ-M06-16）与 AC-17 一致；`GET /workflows/runtime-limits`
  作为字面路径优先于 `{workflowId}` 的约定清楚，契约 `additionalProperties: false` 也合理。
- `disabledReason` 缺部署配置来源（OPS-M06-016）。

### kill switch

- 关闭后“新运行/重试/审批决定/复制模板 503，历史只读，J1/J2 正常，在途继续”是正确语义；
  取消没有被列入 503 是必要的（否则无法清空在途），建议把这一点写进 IT-WF-110 的断言。
- 启动审计在多实例/重启下会重复（U-04）；它不是变更日志，只是观测证据。Ops 立场：
  保留部署配置 + 补 gauge + 实例标识；不升为用户决策（OPS-M06-009）。
- Worker 侧没有“停止领取”开关，滚动更新/紧急停止只能缩容，需补优雅停机语义（OPS-M06-009）。

### 指标与告警

§10.2 的指标覆盖了运行/节点耗时、活跃数、待审批、准入拒绝、超限，但缺队列与租约信号
（OPS-M06-003）。对“DB 即队列”的架构，建议最小补充：

| 指标                                               | 类型    | 用途 / 建议阈值                   |
| -------------------------------------------------- | ------- | --------------------------------- |
| `riccon_workflow_queue_depth{status}`              | Gauge   | PENDING 数；> 50 持续 10min 告警  |
| `riccon_workflow_queue_oldest_pending_age_seconds` | Gauge   | > 300s 告警（Worker 停摆/池不足） |
| `riccon_workflow_lease_lost_total`                 | Counter | 任何增长都告警（重复执行风险）    |
| `riccon_workflow_claim_exhausted_total`            | Counter | > 0 告警（WF_INTERNAL 死记录）    |
| `riccon_workflow_approvals_pending` + 最老等待时长 | Gauge   | 最长等待 > 24h 提示租户管理员     |
| `riccon_workflow_feature_enabled`                  | Gauge   | API/Worker 不一致告警             |

`/actuator/prometheus` 仍按 M04 的 CIDR 限制，不需要新暴露面。

### 预览/测试环境与 CI

- Compose 不需要新服务；但 V15 的 TRIGGER 依赖测试栈已有的 `log-bin-trust-function-creators=1`，
  不能把该参数当作生产配置。
- CI 迁移冒烟的表数已动态推导，触发器数硬编码 2；V15 后必红（OPS-M06-004）。
- `real-stack/run.mjs` 与 e2e 需要能设置/切换 `RICCON_WORKFLOW_ENABLED`（关闭态 E2E-M06-10
  与 IT-WF-110 依赖），建议在 T16 的 env 白名单中显式登记 M06 键。
- `commands.deploy_preview` / `deploy_prod` 指向的 compose/helm 产物尚不存在（M09），
  M06 预览与发布证据应按 M04 先例标 `not-applicable`，用一次性栈取证（见“未验证”）。

## D. 任务拆分与预算

- 覆盖面：T01 迁移/契约/权限回填、T06 队列/引擎、T07 审批/工具、T15 限制/开关/指标、
  T16 harness、T17 gate 的设计是合理的，AC-23 也绑定到 T15/T17。
- 缺口：
  1. `.github/workflows/ci.yml`、`ApiApplicationTests`、`SchemaVersionCheckerTest`、
     `scripts/ops/restore-drill.mjs`、`docs/RUNBOOK.md` 都不在任何 M06 任务的 write_scope 内
     （OPS-M06-004/006）；这是“任务拆分没覆盖运维工作量”的具体表现，不只是文档问题。
  2. T15 3h 同时做 runtime-limits、开关审计、启动校验、7+ 指标与测试，偏紧；
     T17 8h 同时做全部任务评审 + 全量回归 + gate + 回滚/威胁模型核对，回滚演练没有独立脚本落点（OPS-M06-013）。
  3. 回滚演练（OPS-M06-01）需要可重复脚本与报告，建议放 T16（`scripts/**` 已在写范围），
     T17 只复核，避免“评审者自己造证据”。
- 120h 现实性：关键路径 73h、2–3 builder 75–90h 的说法可信；但把上述运维证据补进去后，
  净增约 3–4h，从画布增强（ADR 已列 -3h 候选）调剂即可，不需要回到用户改预算。

## E. 已知未决项（Ops 立场，不替用户决定）

| 项                         | Ops 评估                                                                                 | 建议                                                                                               |
| -------------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| U-01 / AC-23（DEC-0047=A） | 已由用户答复 A，AC-23 已同步；A 是唯一可演练且不削弱审计 fail-closed 的口径              | 认可 A；剩余工作是把“无 workflow 数据/审计行”落成判定 SQL、拒绝路径与演练断言（P2）                |
| U-04 kill switch 审计      | 部署配置适合私有化；重复启动审计可接受，但不能当变更日志                                 | 保留部署配置 + gauge + instanceId；补 Worker 侧停止领取开关（P2，不需用户决策）                    |
| U-07 结果存 MySQL          | 5 MiB 在 MySQL 内可行，但协议上限/备份/RTO 必须在预检里证明；对象存储是 M09 后的独立决策 | 维持 MySQL；补 maxAllowedPacket 预检、容量公式与分位数监控                                         |
| U-12 审批等待计入并发      | 计入是对的（防无限等待占满队列），但需最老等待告警与解除步骤                             | 维持默认；补告警/RUNBOOK（P2 accepted）                                                            |
| U-13 跨租户领取            | 与 ADR-0016 同类例外，可接受；M06 缺三方令牌校验，需列集合断言 + 拒绝审计                | 接受，补结构性与可观测防护（P2）                                                                   |
| U-02 / DEC-0048 配额       | 按运行 1 单位对 Worker 侵入最小；逐次扣减会把配额准入下沉到 Worker，增加跨服务一致性负担 | 认可 A；建议在文档中把 OQ-M06-09 的“模型调用仍受 M04 配额”解释为成本聚合而非逐次准入，避免验收歧义 |

## F. 任务级结论（TASK-0218 验收）

- 部署配置限制、kill switch、不做 down migration、保留/清理边界、指标/RUNBOOK 最小章节、
  J1/J2 不受影响：**已覆盖但有缺口**（P1 ×5，P2 ×10，P3 ×1）。
- M06b/M26 部署边界：**可部署（纯 API/Web 滚动、无迁移）**，但回滚约束需在 M06b 设计继承（P2）。
- 未修改生产源码/测试/迁移，未改 `docs/PRODUCT.md`、`AGENTS.md`、ADR/威胁模型/契约。

## 未验证 / 证据限制

- 未运行构建、测试、迁移或回滚演练：这是设计阶段评审，ADR 明确不含实现；V15 的可执行性、
  触发器权限、5 MiB 写入与 CI 断言“会失败”的结论来自静态核对，需 Builder 的迁移测试与 CI 实证。
- 未验证 `deploy_preview`/`deploy_prod`：对应 M09 产物不存在；M06 预览证据只能用 compose 一次性栈。
- 未验证真实 MySQL 8.4 的 `max_allowed_packet` 与 Connector/J 自动取值行为；已核对驱动属性默认值
  为 65535，预检必须实测而不是假设。

## 建议的最小修复顺序

1. **AC-23 收尾**（Lead）：DEC-0047=A 已答复、AC-23 已同步；补“无数据才允许全量 API 回滚”的判定 SQL、
   拒绝路径与演练断言，并在 RUNBOOK 记录。
2. **ADR-0024 修订一次**（Architect）：补领取/心跳/调度池不变量、WF_INTERNAL 终态、队列与租约指标、
   kill switch 观测语义、5 MiB 容量与协议上限、M06b 回滚约束。
3. **任务建卡时补写范围**（Lead）：CI、两个断言测试类、restore-drill、RUNBOOK、回滚演练脚本；
   T15 +1h、T16 +2~3h，从画布调剂。
4. **T01 开工前**：V15 预检清单 + 失败处置 + DML 幂等 + 触发器权限/binlog 检查，并在测试栈之外验证一次。
5. **T06 开工前**：领取批量/租约起点/心跳池的实现约束写进任务验收，IT-WF-103 覆盖重复领取与租约丢失。

修完 1–5 后可重开 Ops 评审；AC-23 已由 DEC-0047=A 解决，M06 实现不再因回滚口径阻塞，但 P1 未修前不适合进入实现。
