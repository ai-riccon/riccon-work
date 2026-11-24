---
review_id: REV-M06-OPS-002
module_id: M06
task_id: TASK-0218
previous_review: REV-M06-OPS-001
reviewer:
  agent: riccon/ops
  session: ses_df15e35e9ffedd4qfjpIYijTWG
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits:
  - 9d30ac167b55d951dd3c87b0465bbe2e2bb10ffa
  - 4e575a00dc3fc838049b53f7880c5c45069919fb
baseline_commit: 4e575a00dc3fc838049b53f7880c5c45069919fb
head_commit: 4e575a00dc3fc838049b53f7880c5c45069919fb
review_scope: >-
  OPS 第二轮：只读复核 ADR-0024 修订（§3.5、§4.3/4.6/4.8、§9.2/9.4/9.5、§10.1–§10.5、§12、
  §13.1、§14、§15.5、§17）、ARCHITECTURE M06 章节与评审状态表、THREAT-MODEL TM-81/82/85 与
  R-06-1、contracts/openapi.yaml 1.6.0-m06、contracts/README.md，并与仓库现状（V1–V14 迁移、
  CI 迁移冒烟、ApiApplicationTests、SchemaVersionCheckerTest、restore-drill.mjs、worker
  application.yml、OutboxLeaseHeartbeat、OutboxSchedulingPoolValidator、RUNBOOK、real-stack
  run.mjs）逐项对账。未修改任何被评审文件；未运行构建/测试/迁移；未运行 gate check。
findings:
  - id: OPS-M06-001
    severity: P2
    status: fixed
    title: "AC-23 判定 SQL、拒绝路径、WAITING_APPROVAL 清空顺序与演练断言已落成"
    summary: >-
      §10.4 给出 8 段判定 SQL（definition/version/run/node_run/approval/tool_invocation/
      user_template + audit_event action LIKE 'workflow.%'）、"任一 >0 即非 0 退出并拒绝"的路径、
      全局 workflow_template 与租户表分段 drop；§10.4 第 2 步把"等待或取消"改为"回滚 Worker 前
      必须清空 WAITING_APPROVAL 并断言 =0"；§10.5 第 7/8/11 章与 T16b 的
      `scripts/ops/workflow-*.mjs`（断言"有数据拒绝、无数据允许"+ 报告）形成闭环，与
      AC-23 文本（存在任何 workflow 数据/审计行 → 不支持全量 API 回滚）和 DEC-0047=A 一致。
      判定 SQL 不含全局 workflow_template（内置种子恒 >0）是正确的，否则会永久拒绝；未列
      workflow_approval_assignee 不构成漏检（其存在即伴随 approval 行）。
    evidence: >-
      docs/adr/ADR-0024 §10.4 L741-760、§10.5 L762-774、§13.1 L830；docs/PRODUCT.md M06-AC-23 L840；
      .riccon/decisions/DEC-0047.md
  - id: OPS-M06-002
    severity: P1
    status: fixed
    title: "每槽位 batch=1、先心跳、独立调度池、心跳池≥并发、interval≤lease/3、启动校验与重复领取验收已闭合"
    summary: >-
      §4.3 明确：独立 ThreadPoolTaskScheduler（不复用 spring.task.scheduling.pool.size，池=worker
      并发，启动校验池≥并发）；每槽位空闲只领 1 个 run（LIMIT 1，CLAIM_BATCH v1 固定 1，>1 启动失败）；
      租约从领取起算、领取成功后先起心跳再加载图；独立 WorkflowLeaseHeartbeat（池≥并发，默认 10，
      间隔 30s 且必须 ≤ lease/3=30s，领取后立即首续）。§10.1 的启动校验逐条给出可操作键；
      IT-WF-103 验收覆盖批量/并发领同一行、租约过期重领、心跳丢失、重复执行防护；TM-81 同步。
      与现状可落地：worker 现有 `spring.task.scheduling.pool.size=${RICCON_WORKER_SCHEDULER_POOL_SIZE:4}`
      与 OutboxLeaseHeartbeat 2 线程都是既有事实，ADR 用独立池规避，并在误用共享池时要求把 outbox
      任务计入池大小校验（与 M05 F-003 的 OutboxSchedulingPoolValidator 同模式）。
      残余（连接池与轮询周期）另立 OPS-M06-022。
    evidence: >-
      docs/adr/ADR-0024 §4.3 L327-335、§10.1 L682-697、§9.2 TM-81 L629；
      apps/worker/src/main/resources/application.yml:8，run/service/OutboxLeaseHeartbeat.java:50；
      run/config/OutboxSchedulingPoolValidator.java
  - id: OPS-M06-003
    severity: P1
    status: fixed
    title: "队列/租约/审批/开关指标与阈值落到 §10.2，告警处置归 M06"
    summary: >-
      §10.2 补齐 queue_depth{status}、oldest_pending_age_seconds、lease_lost_total、
      claim_exhausted_total、claim_failures_total{reason}、approvals_pending +
      approval_oldest_wait_seconds、heartbeat_renew_failures_total、feature_enabled，并给出阈值
      （PENDING>50/10min、oldest>300s 报警 >900s 升级、lease_lost/claim_exhausted/claim_failures>0、
      审批等待>24h）。告警阈值与处置写入 RUNBOOK、由 M06 负责，M09 只做路由与长期容量，未再全推 M09；
      T15 描述含"队列/租约/审批等待指标与告警"且写范围覆盖 worker metrics 与 RUNBOOK。
      平台级计数查询与 ADR-0016 的 queue_depth 设计同源，不构成新例外。
    evidence: >-
      docs/adr/ADR-0024 §10.2 L700-722、§10.5 L762-774、§13.1 T15 L832；
      docs/adr/ADR-0016 L53
  - id: OPS-M06-004
    severity: P1
    status: fixed
    title: "V15 联动断言与写范围已补齐，实际数字核对一致（48/2 → 57/58/4）"
    summary: >-
      T01 写范围已含 `.github/workflows/ci.yml`、`ApiApplicationTests`、`SchemaVersionCheckerTest`、
      `scripts/ops/restore-drill.mjs`；`.github` 与 restore-drill 不再无主。ADR §3.5 item 7 的数字
      与仓库实测一致：当前迁移文件 48 个 CREATE TABLE（手工重放口径）、ApiApplicationTests 49 表
      （48+flyway_schema_history）、14 条 success=1、2 个触发器；V15 增 9 表 + workflow_version 2 个
      触发器后为 57/58/15/4，RUNBOOK 的 48/49→57/58、V1–V14→V15 亦正确。CI 表数已由
      CREATE TABLE 动态推导，仅触发器硬编码 2 需改 4（ADR 给出"建议从 CREATE TRIGGER 推导"）。
      新发现的迁移测试目录缺口另立 OPS-M06-017；RUNBOOK 更新时序见 OPS-M06-020。
    evidence: >-
      docs/adr/ADR-0024 §3.5 L274、§13.1 T01 L811；.github/workflows/ci.yml:247；
      apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java:42,44,45；
      apps/worker/src/test/java/com/riccon/worker/persistence/SchemaVersionCheckerTest.java:64；
      scripts/ops/restore-drill.mjs:48,51,779-809；docs/RUNBOOK.md:300,352,692
  - id: OPS-M06-005
    severity: P1
    status: fixed
    title: "V15 生产预检、DDL 非事务失败矩阵、DML 幂等与 repair/备份恢复已写清"
    summary: >-
      §3.5 预检含 MAX(version)=14、备份可恢复、SHOW GRANTS 含 TRIGGER、binlog 下
      log_bin_trust_function_creators/SUPER、无长事务/MDL、磁盘余量、@@max_allowed_packet 与 JDBC
      ≥8 MiB，并明确测试栈 --log-bin-trust-function-creators=1 不能作为生产预检证据；失败矩阵按
      "建表/索引/列中断、DML 冲突、触发器 1419/权限、包大小/超时"四类给出残留与处置（停写 → 逆序
      drop 半成品、全局/租户分段 → 删 success=0 history → flyway repair → 重跑；无法定位走备份恢复），
      且要求所有种子/回填 DML 幂等并在迁移测试断言二次执行无重复行；不修改已应用迁移。
      T01 描述含 V15 与迁移测试（但迁移测试文件位置见 OPS-M06-017）。
    evidence: >-
      docs/adr/ADR-0024 §3.5 L264-292、§15.2 L899-904、§13.1 T01 L811；
      deploy/compose/docker-compose.test.yml（测试栈专用参数）
  - id: OPS-M06-006
    severity: P1
    status: fixed
    title: "RUNBOOK 进入 T15 写范围，M06 章节与回滚演练脚本落点齐全"
    summary: >-
      T15 写范围已含 docs/RUNBOOK.md；§10.5 的 11 章覆盖配置表、开关/Worker 停止领取与优雅停机、
      队列积压/租约/claim_exhausted/心跳失败排障、审批等待占满槽位的发现与解除、失败重试/重审批/
      WF_SIDE_EFFECT_UNKNOWN/WF_INTERNAL、V15 预检与失败处置、受支持回滚、全量回滚/应急 drop 判定与
      拒绝、保留与"未物理删除"答复、5 MiB/包大小/容量/指标告警、发布/回滚演练。回滚演练脚本在
      T16b（`scripts/ops/workflow-*.mjs`），T17 只复核，避免评审者自造演练证据。
      章节与 AC-23、TM-82 的要求逐项对应，无遗漏项。
    evidence: >-
      docs/adr/ADR-0024 §10.5 L762-774、§13.1 T15 L832 / T16b L830、§9.2 TM-82 L630；
      docs/PRODUCT.md M06-AC-23 L840
  - id: OPS-M06-007
    severity: P2
    status: accepted
    title: "审批等待占用租户准入名额且无过期（U-12/DEC-0046=A）"
    summary: >-
      §10.2 增加 approvals_pending 与 approval_oldest_wait_seconds（>24h 提示租户管理员），
      §10.5 第 4 章给出"审批等待占满 10 槽"的发现与解除步骤（取消/审批人配置），并与执行并发区分。
      语义仍按 U-12 计入准入，不自动放行，不踢出等待中的运行。
    owner: "T15（RUNBOOK/指标）；T09（待办可见性）"
    due: "M06 gate 前"
    accepted_reason: >-
      U-12 已由 DEC-0046=A 批准"计入准入、释放执行槽位"；恢复路径（发起人/管理员取消）与待办列表
      已存在，本次只补可观测与操作步骤，不改变产品语义。
    evidence: docs/adr/ADR-0024 §10.2 L713、§10.5 L767、§16 U-12 L954
  - id: OPS-M06-008
    severity: P2
    status: fixed
    title: "WF_INTERNAL/claim 耗尽的终态定义已补，retry 可用"
    summary: >-
      §4.3 与 §4.6 规定 claim_attempts>5 或不可恢复内部错误时：当前节点 RUNNING→FAILED（无节点行则按
      current_node_key 创建一次失败尝试）、运行 FAILED（WF_INTERNAL）、写审计；POST /retry 可重试，
      运行不再永久停在 RUNNING。T06a/T08 分别承接实现与重试 API。
    evidence: docs/adr/ADR-0024 §4.3 L333、§4.6 L374、§13.1 T06a L816 / T08 L821
  - id: OPS-M06-009
    severity: P2
    status: fixed
    title: "kill switch 观测语义、feature gauge、Worker 停止领取/优雅停机已收口"
    summary: >-
      §4.8 把启动审计改为"启动观测"（source=DEPLOYMENT_CONFIG、instanceId、observedAt，多实例/重启
      重复可接受），暴露 riccon_workflow_feature_enabled gauge，并新增 Worker 侧
      RICCON_WORKFLOW_CLAIM_ENABLED + SHUTDOWN_GRACE_SECONDS 的停止领取/优雅停机，明确不得只靠
      缩容到 0；§10.5 第 2 章给出操作与审计证据。保留部署配置、不升为用户决策。
      残余：§8.1 的 details 白名单未同步 instanceId/observedAt（OPS-M06-019）。
    evidence: >-
      docs/adr/ADR-0024 §4.8 L405、§10.1 L688-689、§10.5 L765、§13.1 T15 L832、§16 U-04 L951
  - id: OPS-M06-010
    severity: P2
    status: fixed
    title: "WorkflowRun.updatedAt 已入契约，轮询/结果读取语义写清"
    summary: >-
      contracts/openapi.yaml 的 WorkflowRun required 增加 updatedAt（date-time，描述"变化才重绘；
      终态后停止轮询，result 只在终态读取一次"）；§7.4 同步"终态即停止轮询、列表不返回 result、
      ETag/includeResult 作为后续优化"，与 §3.4（5 MiB、OVER_LIMIT）和 §10.1（resultMaxBytes 预检）
      一致。多标签页重复拉取 5 MiB 的风险被"只在终态读一次 + 列表不含 result"约束到可接受范围。
    evidence: >-
      contracts/openapi.yaml WorkflowRun required/updatedAt、WorkflowResultStatus L8025、
      WorkflowRunDetail L8514-8534；docs/adr/ADR-0024 §7.4 L556
  - id: OPS-M06-011
    severity: P2
    status: fixed
    title: "5 MiB 结果的协议/容量/备份影响已进入配置与预检"
    summary: >-
      §10.1 新增 RICCON_DB_MAX_ALLOWED_PACKET=16 MiB，启动校验要求服务器 max_allowed_packet 与
      JDBC maxAllowedPacket ≥8 MiB 且 ≥结果上限 + JSON/协议余量；§3.5 预检再次要求；§10.5 第 10 章
      给出容量公式（runs/day × 结果分位 × 保留天数）、binlog/备份体积与 RTO 余量；§10.2 增加
      result_size_bytes 直方图与 oversize 计数；§3.4 明确"单节点 256 KiB、总 5 MiB 总上限优先"。
      残余：近 5 MiB 实插集成测试的落点未点名（OPS-M06-023e）。
    evidence: >-
      docs/adr/ADR-0024 §3.4 L260、§3.5 L279、§10.1 L691-696、§10.2 L715、§10.5 L773；
      contracts/openapi.yaml WorkflowRuntimeLimits.resultMaxBytes
  - id: OPS-M06-012
    severity: P2
    status: accepted
    title: "180 天只做到期不可见，物理清理归 M09（R-35）"
    summary: >-
      §9.5 R-35 与 §10.5 第 9 章保留"到期不可见、无在线删除、未物理删除标准答复与容量告警"；
      M06 不新增清理路径。
    owner: "riccon/lead（M09 排期）；T15（RUNBOOK）"
    due: "M09 release blocker；RUNBOOK 在 M06 gate 前"
    accepted_reason: >-
      与 M05 R-26 同口径，合同已把物理清理列为 M09 release blocker；M06 新增在线删除会引入跨租户
      批量删除风险，刻意不做。
    evidence: docs/adr/ADR-0024 §9.5 R-35 L659、§10.5 L772
  - id: OPS-M06-013
    severity: P2
    status: fixed
    title: "工时已按建议调剂，回滚演练有独立任务与脚本"
    summary: >-
      §13.1 明确 T11 5→2h（-3）、T15 3→4h（+1）、T16 8→10h（+2），总额仍 120h；T16 拆为 T16a（6h
      harness）/T16b（4h 回滚演练/5 MiB 容量/包大小预检），演练脚本与报告归 T16b，T17 8h 只复核。
      与 OPS-M06-013 的建议完全一致，未超 20% 预算阈值。
    evidence: docs/adr/ADR-0024 §13.1 L831、L830；docs/ARCHITECTURE.md 交付与预算
  - id: OPS-M06-014
    severity: P2
    status: fixed
    title: "M06b 回滚约束已写入 §12，继承 DEC-0047=A 口径"
    summary: >-
      §12 增加"回滚继承"：M06b 无迁移、纯 API/Web 滚动，但新增 template.saved/shared/unshared 审计
      动作后旧 API 的 AuditDetailsReader 仍 fail-closed，"M06b → M06 API 回滚"只允许在无 M06b 审计行时
      执行；M06b 设计必须继承 AC-23 判定 SQL，不得声称可任意回退。
    evidence: docs/adr/ADR-0024 §12 L800
  - id: OPS-M06-015
    severity: P2
    status: fixed
    title: "TM-85 跨租户领取的列集合断言与拒绝审计/计数已补"
    summary: >-
      §4.3 规定 claim SELECT 恰好 4 列并有 ArchUnit/字符串静态守卫、非法/空 tenant fail-closed
      （不开 TenantContext、不读业务列、不执行）、记 claim_failures_total{reason} 并写 denied 审计；
      IT-WF-103 与 TEN-M06-01 覆盖非法 tenant/跨租户行/列集合；索引不以 tenant 开头登记为例外，
      ORDER BY id 的 filesort 作为可观察项。TM-85 同步。
    evidence: docs/adr/ADR-0024 §4.3 L329-331、§9.2 TM-85 L633、§13.1 T06a L816
  - id: OPS-M06-016
    severity: P3
    status: fixed
    title: "disabledReason 已有部署配置来源"
    summary: >-
      §10.1 新增可选 RICCON_WORKFLOW_DISABLED_REASON（默认空，API 回退固定文案），契约
      WorkflowRuntimeLimits.disabledReason 注明来源，§10.5 第 1 章配置表覆盖。
    evidence: docs/adr/ADR-0024 §10.1 L672；contracts/openapi.yaml WorkflowRuntimeLimits.disabledReason
  - id: OPS-M06-017
    severity: P1
    status: open
    title: "T01 write_scope 缺 migration 测试目录，任务证据'迁移测试'无法在写范围内产出"
    summary: >-
      T01 的证据列要求"迁移测试"（V15 forward、触发器拒绝、种子/DML 二次执行幂等、无数据可 drop/
      有数据保留），但 §13.1 T01 的 write_scope 只有
      `apps/api/src/test/java/com/riccon/api/contract/WorkflowContractM06Test.java`、
      `.../api/workflow/web/**`、`.../api/ApiApplicationTests.java`、
      `.../worker/persistence/SchemaVersionCheckerTest.java`，没有
      `apps/api/src/test/java/com/riccon/api/migration/**`。仓库先例 V9–V14MigrationTest 全部位于该包；
      write_scope 按文件 glob 匹配，新建 `V15MigrationTest` 会被判越界。结果只能是二选一：T01 违反
      写范围，或迁移证据缺失（直接影响模块 DoD 的 tests/coverage 与 T4 迁移验证）。
      同源问题：§15.2 要求在迁移测试中验证应急 drop 的"无数据可 drop、有数据保留"，若 drop SQL 作为
      独立文件落 `scripts/ops/*.sql`，T16b 的 `scripts/ops/workflow-*.mjs` 与 T01 的 V15 迁移文件都不覆盖；
      建议明确"drop SQL 内嵌在 T16b 的 .mjs 或由 T01 在迁移测试内联"，避免第三次发现同类缺口。
    owner: "riccon/lead（写范围）；riccon/architect（ADR §13.1 文案）"
    due: "T01 建卡前"
    evidence: >-
      docs/adr/ADR-0024 §13.1 T01 L811、§15.2 L903；apps/api/src/test/java/com/riccon/api/migration/
      （V9–V14MigrationTest 先例）
  - id: OPS-M06-018
    severity: P2
    status: open
    title: "T16a 未要求登记 M06 环境键注入与证据快照，E2E-M06-10 与 T16b 回滚演练无法切换开关"
    summary: >-
      scripts/real-stack/run.mjs 用显式 env map 启动 API/Worker（不是 process.env 透传），M06 的
      RICCON_WORKFLOW_ENABLED、RICCON_WORKFLOW_CLAIM_ENABLED、RICCON_WORKFLOW_RESULT_MAX_BYTES、
      心跳/租约/并发等键都不在注入列表，也不进 real-stack-env.json 快照。E2E-M06-10（关闭态）与
      OPS-M06-01（关开关 → 回滚 Worker → 核对历史/J1/J2 → 恢复）都要求按场景切换；T16b 的写范围只有
      `scripts/ops/workflow-*.mjs`，不能改 run.mjs。T16a 描述（E2E/axe/perf harness、写范围含
      scripts/real-stack/**）没有点名 M06 键，实现时容易漏，导致关闭态用例被迫跳过或改造成
      不可复现的手工步骤。建议在 T16a 验收写明"env 注入与证据快照登记 M06 键，含默认值与来源"。
    owner: "riccon/lead（T16a 验收）；T16a"
    due: "T16a 开工前"
    evidence: >-
      scripts/real-stack/run.mjs:900-1052（显式 env map）、:117（ENV_SNAPSHOT_FILE）；
      docs/adr/ADR-0024 §13.1 T16a L829 / T16b L830
  - id: OPS-M06-019
    severity: P2
    status: open
    title: "§8.1 的 workflow.feature.changed 白名单缺 instanceId/observedAt，与 §4.8 冲突"
    summary: >-
      §4.8 要求关闭态启动审计 details 带 source=DEPLOYMENT_CONFIG、instanceId、observedAt；§8.1 的
      details 列仍是 enabled、source、reason?。AuditDetailsPolicy.validate 对白名单外的键直接抛
      IllegalArgumentException（写入路径 fail-closed）。若实现按 §8.1 写白名单，则 instanceId/observedAt
      写不进去，OPS-M06-009 想要的多实例可辨识性落空；若按 §4.8 写入未登记键，则关闭态启动审计抛错，
      可能阻断 API 启动——而关闭态恰好是回滚/紧急停止场景，失败模式最差。T01 需同时改 §8.1 文案、
      AuditDetailsPolicy 白名单（enabled/source/reason/instanceId/observedAt）并加启动审计断言。
    owner: "riccon/architect（§8.1）；T01（白名单与测试）"
    due: "T01 完成前"
    evidence: >-
      docs/adr/ADR-0024 §4.8 L405、§8.1 L591；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:393-423、
      apps/api/src/main/java/com/riccon/api/audit/service/AuditDetailsReader.java:42-46
  - id: OPS-M06-020
    severity: P2
    status: open
    title: "§3.5 '联动断言必须在同一提交更新' 与 RUNBOOK 归 T15 的写范围冲突，V15 合入后 RUNBOOK 数字过期"
    summary: >-
      §3.5 item 7 把 `docs/RUNBOOK.md`（V1–V14→V15、48/49→57/58、2→4）与 CI/测试类/restore-drill
      并列为"必须在同一提交更新"，但 RUNBOOK 只在 T15 写范围，T15 依赖 T05/T06a，必然晚于 T01。
      写范围规则下 T01 无法更新 RUNBOOK，两处要求互斥。实际影响：可执行断言（CI 表数动态推导、
      触发器 4、ApiApplicationTests 58/4、SchemaVersionCheckerTest 15、restore-drill 15/4）由 T01
      同提交改到正确，不会合入即红；但 RUNBOOK 226/268/289/300/351-352/692 行在 V15 合入到 T15 之间
      仍写 V1–V14、48/49 表、2 触发器、MAX(version)=14，按手册手工演练会对不上。
      建议 §3.5 改为"可执行断言与 V15 同提交；RUNBOOK 由 T15 在 M06 gate 前更新"，并在 T15 验收里
      列明需改的数字与章节（迁移演练、restore-drill 判据）。
    owner: "riccon/architect（§3.5）；T15"
    due: "M06 建卡时"
    evidence: >-
      docs/adr/ADR-0024 §3.5 L274、§13.1 T01 L811 / T15 L832；
      docs/RUNBOOK.md:226,268,289,300,351-352,692
  - id: OPS-M06-021
    severity: P2
    status: open
    title: "回滚顺序 §10.5 与 §10.4 不一致；回滚 Worker 前只断言 WAITING_APPROVAL=0"
    summary: >-
      §10.4 顺序为"关开关 → 清 WAITING_APPROVAL → 回滚 Worker"，§10.5 第 7 章写成"取消
      WAITING_APPROVAL → 关开关 → 回滚 Worker"。先取消后关开关会与仍在推进的运行竞争，可能又生成
      新的 WAITING_APPROVAL，与"回滚前必须为 0"矛盾。另外两处都只断言 WAITING_APPROVAL 计数：回滚
      Worker 后旧 Worker 不认 workflow_run，PENDING/RUNNING 会滞留（租约过期也无人领取），只能靠
      保留的 M06 API 取消或等下一次 M06 部署恢复。建议统一为：
      (1) 关开关（取消仍可用）→ (2) 取消 WAITING_APPROVAL 并断言 =0 → (3) 断言/逐条取消 PENDING、
      RUNNING，或明确写出滞留运行的恢复路径与责任人 → (4) 回滚 Worker"，T16b 演练脚本按此顺序断言。
    owner: "T15（RUNBOOK）；T16b（演练断言）"
    due: "T15 完成前"
    evidence: docs/adr/ADR-0024 §10.4 L735-739、§10.5 L770
  - id: OPS-M06-022
    severity: P2
    status: open
    title: "心跳/执行槽位与 outbox 共用 Hikari 池（默认 10），且 claim 轮询周期未定义"
    summary: >-
      worker 现有 `hikari.maximum-pool-size=${RICCON_DB_POOL_SIZE:10}`，outbox default+heavy 通道共用；
      M06 在此之上增加 10 个执行槽位与 10 个心跳线程，每次续期是一条 UPDATE。DB 抖动、节点写事务或
      outbox 重任务排队时，心跳拿不到连接 → 续期超过 30s 节奏 → 租约丢失/重复领取，正是 OPS-M06-002
      想消除的失败模式。§10.1 只有线程池校验（调度/心跳），没有连接池容量键、启动校验，也没有
      hikaricp pending/timeout 告警。另：§4.3 未定义空闲槽位的 claim 轮询周期或空队列退避，10 个槽位
      空转时的 DB 查询频率不可调、claim 延迟不可预期，也无法在 RUNBOOK 里给"claim 变慢"的判断依据。
      建议补 `RICCON_WORKFLOW_CLAIM_POLL_INTERVAL_MS`（或固定延迟 + 空队列退避），在 §10.1/§10.5
      登记，并加一条连接池容量规则（≥ worker 并发 + 心跳余量 + outbox 通道，或心跳使用独立小池）
      与 hikaricp 等待/超时指标。
    owner: "T06a（队列节奏）；T15（配置/校验/指标/RUNBOOK）"
    due: "T06a 开工前"
    evidence: >-
      apps/worker/src/main/resources/application.yml:19（Hikari 10）、:101-113（outbox lanes）；
      apps/worker/src/main/java/com/riccon/worker/run/service/OutboxLeaseHeartbeat.java:50；
      docs/adr/ADR-0024 §4.3 L327-328、§10.1 L693-697
  - id: OPS-M06-023
    severity: P3
    status: accepted
    title: "文档/映射/验收细化小项：AC-23 映射缺 T16b、restore-drill fixture、发布默认值、T15 工时、5 MiB 实插、池键命名"
    summary: >-
      (a) §14 AC-23 映射只写 T15,T17，而 OPS-M06-01 由 T16b 产出（AC-21 已列 T16b，AC-23 漏）；
      (b) §3.5 说 restore-drill 含 self-test fixtures 49/48 表，未点出 fixtures 的 triggers 2/1 也要
      变为 4/3（restore-drill.mjs:801-809）；(c) §10.3 第 2 步仍留"开关默认 false 或 true？"；
      (d) T15 4h 需覆盖 runtime-limits/开关/启动校验/13 项指标/11 章 RUNBOOK，仍偏紧，但不在 75h
      关键路径、模块尚有 28–43h 余量；(e) OPS-M06-011 要求的"真实插入接近 5 MiB 的 result_json"
      未点名测试落点（建议并入 T16b 容量报告或 T01 迁移/契约测试）；(f)
      `RICCON_WORKFLOW_SCHEDULER_POOL_SIZE` 与既有 `RICCON_WORKER_SCHEDULER_POOL_SIZE` 名称接近，
      RUNBOOK 配置表需说明是不同池（独立调度池 vs Spring 共享池）。
    owner: "riccon/lead（建卡/映射）；riccon/architect（§3.5/§10.3 文案）；T15/T16b"
    due: "M06 建卡时（2026-11-25）"
    accepted_reason: >-
      均为文案、映射与验收细化，不改变队列、回滚或审计语义，也不影响 CI 变绿；T15 不在关键路径且
      有模块余量；(e) 可在 T16b 容量报告中补，不阻塞 T01 开工。
    evidence: >-
      docs/adr/ADR-0024 §10.3 L727、§13.1 L830-832、§14 L879、§3.5 L274；
      scripts/ops/restore-drill.mjs:779-809
notes: >-
  设计阶段只读复核，未产生 EV，也未被要求产生 EV（manual 证据不能建立 done）。所有结论以文件行号、
  grep/只读命令与 codegraph 读取的现有代码为依据。未运行构建/测试/迁移/演练；未运行
  `riccon gate check --module M06`（按 Lead 要求避免污染 state）。ADR-0024 §17 自述"只记录设计响应，
  不声称 fixed"，本评审即对其逐条复验；P1 已全部闭合，剩余 1 个新 P1 为写范围缺口，不是架构问题。
created_at: "2026-11-25T01:44:53+08:00"
---

# REV-M06-OPS-002：M06 运维/回滚设计修订独立复评

## 结论

**不通过（fail）**，但性质与上一轮不同：5 条 P1（OPS-M06-002..006）全部实质闭合，AC-23 判定 SQL、
队列参数不变量、指标告警、V15 预检/失败矩阵、RUNBOOK 章节与演练脚本都已落成，V15 后的数字
（57/58 表、15 迁移、4 触发器）与实际仓库核对一致，任务/工时拆分（21 个、120h、关键路径 75h）
自洽。剩余问题是**写范围与文案的内部矛盾**，其中 1 条 P1 会直接卡住 M06 的第一个任务：

1. **OPS-M06-017（P1，新）**：T01 证据要求"迁移测试"，但写范围没有
   `apps/api/src/test/java/com/riccon/api/migration/**`（V9–V14MigrationTest 的既有位置），
   T01 要么越界写文件、要么缺迁移证据。
2. OPS-M06-018..022（P2，新）：T16a 未要求登记 M06 env 键（关闭态 E2E 与回滚演练无法切换开关）；
   §8.1 审计 details 白名单缺 instanceId/observedAt；§3.5"同一提交"与 RUNBOOK 归 T15 互斥；
   §10.5 回滚顺序与 §10.4 不一致且未覆盖 PENDING/RUNNING；心跳/槽位与 outbox 共用 Hikari 10 连接、
   claim 轮询周期未定义。

这些都是 ADR/任务表上的小改动（多数是 1–3 行），不需要改架构、产品范围或预算；改完可再开一轮
Ops 复评。P2/P3 中 OPS-M06-007/012（上一轮 accepted）与 OPS-M06-023（本轮文案小项）为 accepted，
其余均为 open。

## 评审范围与方法

只读核对，未修改任何被评审文件、源码/测试/迁移、`docs/PRODUCT.md`、`AGENTS.md` 或 `.riccon` 状态。

- 设计/契约：`docs/adr/ADR-0024`（重点 §3.5、§4.3/4.6/4.8、§8.1、§9.2/9.4/9.5、§10.1–§10.5、
  §12、§13.1、§14、§15.2/15.5、§17）、`docs/ARCHITECTURE.md` M06 章节与评审状态表、
  `docs/security/THREAT-MODEL.md`（TM-81/82/85、R-06-1）、`contracts/openapi.yaml`（WorkflowRun/
  WorkflowRunDetail/WorkflowRuntimeLimits/WorkflowResult）、`contracts/README.md`。
- 现状对账：`apps/api/src/main/resources/db/migration/V*.sql`（表/触发器计数）、
  `.github/workflows/ci.yml` 迁移冒烟、`ApiApplicationTests`、`SchemaVersionCheckerTest`、
  `scripts/ops/restore-drill.mjs`、`docs/RUNBOOK.md`、`apps/worker/.../application.yml`、
  `OutboxLeaseHeartbeat`、`OutboxSchedulingPoolValidator`、`scripts/real-stack/run.mjs`、
  `AuditDetailsPolicy`/`AuditDetailsReader`。
- 决策：`DEC-0047=A`（AC-23 回滚口径）、`DEC-0046=A`（U-12 等默认值）、`DEC-0048=A`（配额）。

工具：整体结构用 codegraph 读 `AuditDetailsPolicy.validate` 的白名单 fail-closed 行为；文档与配置
用 grep/read（非代码文件，不存在符号级操作）；本任务不写代码、不做符号编辑，因此未启用 Serena 的
符号编辑能力（无 rename/replace_symbol_body 需求）。未运行任何会产生 EV 的命令。

### 证据命令与输出摘要

```text
$ git rev-parse HEAD
4e575a00dc3fc838049b53f7880c5c45069919fb
$ git show --stat 4e575a0
 contracts/README.md | 4 +-   contracts/openapi.yaml | 72 ++-
 docs/ARCHITECTURE.md | 9 +-  docs/adr/ADR-0024... | 369 +++--
 docs/security/THREAT-MODEL.md | 6 +-

$ grep -hE '^[[:space:]]*CREATE TABLE' apps/api/src/main/resources/db/migration/V*.sql | wc -l
48
$ grep -hiE '^[[:space:]]*CREATE TRIGGER' apps/api/src/main/resources/db/migration/V*.sql | wc -l
2
$ grep -n 'test "${triggers}" -eq 2' .github/workflows/ci.yml
247:          test "${triggers}" -eq 2
$ sed -n '42,45p' apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java
assertThat(migrations).isEqualTo(14L);  // tables 49 = 48 + flyway_schema_history; triggers 2
$ grep -n 'EXPECTED_SCHEMA_VERSION = \|EXPECTED_TRIGGERS = ' scripts/ops/restore-drill.mjs
48:const EXPECTED_SCHEMA_VERSION = 14;   51:const EXPECTED_TRIGGERS = 2;
$ grep -n 'void expectedSchemaVersion_isLockedAtV14' .../SchemaVersionCheckerTest.java
64:    void expectedSchemaVersion_isLockedAtV14()   // 65: isEqualTo(14)
$ grep -n 'maximum-pool-size' apps/worker/src/main/resources/application.yml
19:      maximum-pool-size: ${RICCON_DB_POOL_SIZE:10}
$ grep -n 'newScheduledThreadPool' apps/worker/.../OutboxLeaseHeartbeat.java
50:        this.scheduler = Executors.newScheduledThreadPool(2, ...)
$ grep -n 'size: ${RICCON_WORKER_SCHEDULER_POOL_SIZE' apps/worker/src/main/resources/application.yml
8:        size: ${RICCON_WORKER_SCHEDULER_POOL_SIZE:4}
```

## A. 逐条状态（OPS-M06-001..016）

| ID          | 上轮 | 本轮     | 依据（ADR/仓库）                                                                         |
| ----------- | ---- | -------- | ---------------------------------------------------------------------------------------- |
| OPS-M06-001 | P2   | fixed    | §10.4 判定 SQL/拒绝路径/WAITING_APPROVAL 顺序；§10.5 7/8/11；T16b 脚本；AC-23 文本       |
| OPS-M06-002 | P1   | fixed    | §4.3 每槽位 batch=1、先心跳、独立调度/心跳池、≤lease/3；§10.1 启动校验；TM-81；IT-WF-103 |
| OPS-M06-003 | P1   | fixed    | §10.2 8 类新增指标+阈值；M06 负责处置；T15 写范围含 metrics+RUNBOOK                      |
| OPS-M06-004 | P1   | fixed    | 数字 48/2→57/58/4 核对一致；T01 写范围含 ci.yml/两个测试类/restore-drill                 |
| OPS-M06-005 | P1   | fixed    | §3.5 预检+四类失败矩阵+幂等 DML+repair/备份恢复；T01 实施                                |
| OPS-M06-006 | P1   | fixed    | RUNBOOK 入 T15；§10.5 十一章；T16b 演练脚本                                              |
| OPS-M06-007 | P2   | accepted | §10.2 审批等待告警；§10.5 第 4 章；U-12/DEC-0046=A                                       |
| OPS-M06-008 | P2   | fixed    | §4.3/§4.6 WF_INTERNAL 终态 + retry 可用；T06a/T08                                        |
| OPS-M06-009 | P2   | fixed    | §4.8 观测审计/instanceId/gauge/Worker CLAIM_ENABLED/优雅停机（残余见 019）               |
| OPS-M06-010 | P2   | fixed    | 契约 updatedAt required；§7.4 终态停轮询、result 只读一次                                |
| OPS-M06-011 | P2   | fixed    | §10.1 包大小配置/校验；§3.5 预检；§10.5 容量公式；result_size_bytes（残余见 023e）       |
| OPS-M06-012 | P2   | accepted | R-35；§10.5 第 9 章；M09 release blocker                                                 |
| OPS-M06-013 | P2   | fixed    | T11 5→2、T15 3→4、T16 8→10（6+4），总额 120h；演练归 T16b                                |
| OPS-M06-014 | P2   | fixed    | §12 回滚继承，M06b 只在无 M06b 审计行时回退 M06 API                                      |
| OPS-M06-015 | P2   | fixed    | §4.3 4 列静态断言、非法 tenant fail-closed、claim_failures/denied；TM-85                 |
| OPS-M06-016 | P3   | fixed    | §10.1 RICCON_WORKFLOW_DISABLED_REASON；契约描述；RUNBOOK 配置表                          |

上一轮 5 个 P1 的修复不是"改一句话"：§4.3 的队列不变量与 §10.1 的启动校验互相引用、可被
`IT-WF-103`/`OPS-M06-01` 验证；§3.5 的失败矩阵按失败点给出残留与处置；§10.2 的阈值与 §10.5 的
处置步骤一一对应。这是可以交给 Builder 执行、交给 Verifier 复核的形态。

## B. V15 数字核对（实测 vs 应有）

| 断言点                         | 当前实测                                | V15 后应为                              | 归属                                     |
| ------------------------------ | --------------------------------------- | --------------------------------------- | ---------------------------------------- |
| 迁移文件 `CREATE TABLE`        | 48                                      | 57（+9）                                | CI 已动态推导，无需改数字                |
| `ApiApplicationTests` 表数     | 49（48 + `flyway_schema_history`）      | 58                                      | T01（写范围已含）                        |
| `flyway_schema_history` 成功行 | 14                                      | 15                                      | T01                                      |
| 触发器                         | 2（`audit_event` BEFORE UPDATE/DELETE） | 4（+ `workflow_version` UPDATE/DELETE） | T01 改 `ci.yml:247` 的 `-eq 2` → 4       |
| `SchemaVersionCheckerTest`     | `isLockedAtV14`、期望 14                | 15（`isLockedAtV15`）                   | T01（写范围已含）                        |
| `restore-drill.mjs`            | 14/2；fixtures 49/2 与 48/1             | 15/4；fixtures 58/4 与 57/3             | T01（写范围已含；fixture 触发器见 023b） |
| `docs/RUNBOOK.md`              | V1–V14、48/49、2、MAX=14                | V1–V15、57/58、4、MAX=15                | T15（写范围已含；时序见 OPS-M06-020）    |

ADR §3.5 写的"49→58、2→4、48/49→57/58"与实测一致，没有算错表或触发器；`workflow_version` 的
不可变由 BEFORE UPDATE + BEFORE DELETE 两个触发器实现，与 +2 的断言自洽。CI 的表数是从
`CREATE TABLE` 推导的，V15 合入后自动变 57，不需要改；触发器仍是硬编码，ADR 只写"建议从
`CREATE TRIGGER` 推导"——本次可接受，但建议 T01 顺手改成推导，避免 M06b/M07 再踩。

## C. 任务拆分与工时

- **21 个任务、120h**：逐行求和 = 120h（T01 9、T02 6、T03 6、T04 6、T05 6、T06a 6、T06b 8、
  T07a 5、T07b 5、T08 6、T09 5、T10 10、T11 2、T12a 5、T12b 5、T13 4、T14 4、T15 4、T16a 6、
  T16b 4、T17 8）。拆分没有偷偷增删工作量：T06 14=6+8、T07 10=5+5、T12 10=5+5、T16 10=6+4，
  T11 -3、T15 +1，总额不变。
- **关键路径 75h 成立**：T01→T02→T03→T04→T05→T06a→T06b→T07a→T07b = 57h，+T16a 6 +T16b 4
  +T17 8 = 75h；前端链 41h 并行；T15（4h）不在关键路径，故 023d 的"偏紧"按 P3 处理。
- **写范围互斥**：按包前缀检查，T02 definition/domain/persistence、T03 validation、T04 version/
  publish、T05 run、T06a queue/heartbeat、T06b engine/node、T07a approval、T07b tool+mcp、
  T08 history、T09 approval API、T10–T13 web 各页、T14 template、T15 runtime/metrics/config、
  T16a e2e+real-stack、T16b `scripts/ops/workflow-*.mjs`、T17 reviews，互不重叠；共享文件
  （`AuditDetailsPolicy`、`SchemaVersionChecker`、`contracts/**`、`docs/RUNBOOK.md`、
  `.github/workflows/ci.yml`、`scripts/ops/restore-drill.mjs`）由 T01/T15 单写，符合"一个文件
  一个 writer"。
- **缺口**：迁移测试目录（017）、T16a 的 env 键登记（018）、T15 需要的 `application.yml` 键仍归
  T01（建议 T01 建卡时明确"§10.1 全部键一次性写入两个 application.yml"，否则 T06a/T15 会被迫用
  纯 env 默认值或越界改 yml；此项并入 023 的建卡注意事项）。
- **演练归属**：T16b 写在 `scripts/ops/workflow-*.mjs`，与 T16a 的 `scripts/real-stack/**` 分离，
  避免"证据脚本与 harness 同一作者同一文件"；T17 只复核。Ops 认可，但 T16b 的脚本需要 T16a 能
  注入 M06 env（018），二者是硬依赖，不能各写各的。

## D. 契约、README 与运维口径一致性

- `WorkflowRun.updatedAt` 已 required，描述与 §7.4 的"变化才重绘、终态停轮询、result 只在终态读一次"
  一致；列表 schema 不含 `result`，`WorkflowRunDetail` 才带，符合"列表轻、详情重"的运维预期。
- `WorkflowResultStatus.OVER_LIMIT` 描述写明 5 MiB 与 `WF_RESULT_LIMIT`；`WorkflowResult.text`
  描述"超过 5 MiB 不保存"；`WorkflowRuntimeLimits.resultMaxBytes` 描述引用
  `max_allowed_packet`/JDBC ≥8 MiB 预检——三处与 §3.4/§10.1/§10.5 口径一致。
- `WorkflowRuntimeLimits` 不暴露 worker 内部参数（claim batch/心跳/租约/连接池），`contracts/README.md`
  明确"队列执行不变量属实现/运维约束，不在 REST 契约暴露"——租户只读端点不该暴露内部拓扑，认可。
  `disabledReason` 的来源键已写入契约描述与 §10.1。
- 部署面：M06 不新增服务/中间件，`deploy/**` 无 M06 改动；告警规则本体（Prometheus/Helm）归 M09，
  M06 只交阈值与 RUNBOOK 处置——可接受，但 release 记录要写明"告警规则不在 M06 交付"，避免 gate
  时误判。预览/测试环境沿用一次性 compose + real-stack，`deploy_preview`/`deploy_prod` 仍指向
  M09 产物，M06 证据按 M04 先例标 not-applicable。

## E. 新发现与建议的最小修复

| 顺序 | 动作                                                                                                             | Owner                   | 截止        |
| ---- | ---------------------------------------------------------------------------------------------------------------- | ----------------------- | ----------- |
| 1    | §13.1 T01 write_scope 增加 `apps/api/src/test/java/com/riccon/api/migration/**`，并明确应急 drop SQL 落点（017） | Lead/Architect          | T01 建卡前  |
| 2    | T16a 验收写明登记 M06 env 注入键与 `real-stack-env.json`（018）                                                  | Lead/T16a               | T16a 开工前 |
| 3    | §8.1 `workflow.feature.changed` details 增加 instanceId/observedAt；T01 白名单+启动审计测试（019）               | Architect/T01           | T01 完成前  |
| 4    | §3.5 改为"可执行断言同提交、RUNBOOK 由 T15 gate 前更新"（020）                                                   | Architect               | 建卡时      |
| 5    | §10.5 第 7 章回滚顺序与 §10.4 对齐，补 PENDING/RUNNING 清零或滞留恢复步骤；T16b 断言（021）                      | T15/T16b                | T15 完成前  |
| 6    | 补 claim 轮询周期配置、Hikari 容量规则与 hikaricp 等待/超时告警（022）                                           | T06a/T15                | T06a 开工前 |
| 7    | 023 的文案/映射小项在建卡时一并处理（AC-23 映射加 T16b、发布默认 false、fixture 触发器、5 MiB 实插）             | Lead/Architect/T15/T16b | 建卡时      |

1–6 都是设计文本与任务表的小改动，不改变队列、回滚或审计语义，也不改变 120h 预算；改完 Ops
可在下一轮只审 delta。

## F. 未验证 / 证据限制

- 未运行构建、测试、迁移或回滚演练：本任务是设计阶段评审，ADR 不含实现；V15 的可执行性、
  触发器权限、5 MiB 写入、CI 断言"会失败/会变绿"均为静态核对，需 T01 的迁移测试与 CI 实证。
- 未验证真实 MySQL 8.4 的 `max_allowed_packet` 与 Connector/J 实际取值；仍以"预检必须实测"
  为准，不得假设驱动会自动放宽。
- 未验证 `deploy_preview`/`deploy_prod`：对应 M09 产物不存在；M06 预览与演练只能用一次性栈。
- 未运行 `riccon gate check --module M06`（按 Lead 要求，避免污染 state）；本评审不构成模块 gate。

## G. 剩余风险

- 回滚后滞留的 PENDING/RUNNING（021）在真实故障中可能被误认为"已回滚完成"；RUNBOOK 必须给出
  可见查询与恢复责任。
- 心跳依赖 Hikari 连接（022）在 DB 抖动时会放大为租约丢失；好在重领 + 节点幂等能兜底，代价是
  重复模型成本与运行延迟，需在 perf/容量报告里记录。
- T15 4h 若超支，最可能被压缩的是 RUNBOOK 的回滚/预检章节——这是 gate 证据，不得裁剪（023d）。
- 180 天保留只做到期不可见（OPS-M06-012/R-35），M09 前客户数据与备份会单调增长，交付时需在
  一页评审包里明确表述，不得声称"已删除"。

## H. 给 Lead 的建卡提示

- T01 卡片的 write_scope 直接把 017 的目录加进去，并把"§10.1 全部 `RICCON_WORKFLOW_*` 键写入
  两个 application.yml"写进验收，避免 T06a/T15 再开口子。
- T06a 验收必须包含：每槽位 batch=1、领取后先心跳、启动校验（池/间隔/批量）、IT-WF-103 的重复
  领取与租约丢失用例、claim 轮询周期与退避（022）。
- T15 验收写清 RUNBOOK 必须覆盖 §10.5 全部 11 章，并按 020 修正 V15 数字；4h 不够时从模块余量
  补，不得删回滚/预检章节。
- T16a/T16b 是硬依赖：T16a 先登记 M06 env 与证据快照，T16b 再实现"有数据拒绝、无数据允许"的
  判定脚本与回滚演练报告。
- 建卡后让 Ops 只审 delta（默认值、写范围、RUNBOOK 章节、脚本），不需要重读全部 ADR。
