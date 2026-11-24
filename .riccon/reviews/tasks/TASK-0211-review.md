---
review_id: REV-TASK-0211-001
module_id: M25
task_id: TASK-0211
reviewer:
  agent: riccon/verifier
  session: ses_df489017dffe1k572oAHfA5HVd
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - b7dac1e5f375c02bee98eab25e61fdb17c804f41
  - 993f7fe6739453757b3819608b6bb63ef10357d4
  - 7ac5d3c1cc1158b8ca3a380eb5e8c1a3ff62cbb7
head_commit: 7ac5d3c1cc1158b8ca3a380eb5e8c1a3ff62cbb7
baseline_commit: d7d4aa597f674ceff4a3ade0ba7bd826b0c6b33b
dod:
  acceptance: true
  tests: true
  security: true
  review: true
  docs: true
  evidence: true
dod_not_applicable:
  - build
  - coverage
  - e2e
  - migration
  - perf
  - a11y
dod_note: >-
  acceptance = pass (with residual)：脚本与 RUNBOOK 已交付，30 天置空、审计同事务、幂等/分批/回滚/并发均在
  真实 MySQL 8.4.13 + V12 schema 上独立复验通过。残余是 RUNBOOK 的两处生产可执行性缺口（F-001 误删恢复/
  重新录入指引与现有 API 能力不一致；F-002 未记录维护账号最小权限，缺 CREATE TEMPORARY TABLES 时 apply
  fail-closed），以及证据可重复性（F-003）和 M09 生产决策（F-004）。build 为 Node 脚本无构建；coverage/e2e/
  migration/perf/a11y 由 M25 模块 gate 或 M09 承接，本任务不涉及 Java、迁移、UI 或端到端页面。
reviewed_evidence:
  - EV-0997
evidence:
  - EV-0997
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/ops
    due: M25 gate 前（RUNBOOK 措辞修订）
    title: "RUNBOOK 的误删恢复/重新录入指引与现有连接器 API 能力不一致"
    summary: >-
      docs/RUNBOOK.md:1337-1338 写“若误置空且源端凭据仍在客户密钥库，只能由管理员在管理端重新录入”。
      但被置空的对象必然是已软删除的连接器：ConnectorService#putCredential 使用 lockActive（deleted_at IS NULL，
      ConnectorService.java:278），已删除连接器返回 404；ConnectorRepository#listConnectors 只列 active
      （ConnectorJdbc.java:190-201），管理端列表不可见；createConnector 捕获 DuplicateKeyException
      （ConnectorService.java:192-194），而 kb_connector 的 uk_kb_connector_name 包含软删除行
      （V12__knowledge_depth.sql:190），同名重建会 409；contracts/openapi.yaml 的连接器操作只有
      list/create/update/delete/putCredential/test/sync/items，没有 restore/undelete。RUNBOOK:1262/1270 的
      “30 天供误删恢复/窗口内允许误删恢复”因此没有可执行路径。期望：RUNBOOK 明确 M05b 不提供在线恢复，
      误删恢复只能走 M09/备份恢复审批，或新建不同名连接器并重新同步；不要把“管理端重新录入”写成对同一连接器可用。
    accepted_reason: >-
      O2b 脚本只置空三列、不删除连接器行，也不会自动执行；M25 没有连接器恢复 AC，物理清理与恢复边界归 M09。
      该措辞不改变脚本行为，但在 M09 前会误导故障处理（尤其是“误置空”后直接 SQL 或整库恢复），必须先修订
      RUNBOOK；当前记为 accepted 不影响 O2b 技术验收，责任在 ops 的 RUNBOOK 修订。
    evidence: >-
      docs/RUNBOOK.md:1262,1270,1337-1338；
      apps/api/src/main/java/com/riccon/api/knowledge/connector/service/ConnectorService.java:192-194,278,304-327；
      apps/api/src/main/java/com/riccon/api/knowledge/connector/persistence/ConnectorJdbc.java:190-201,321-335；
      apps/api/src/main/resources/db/migration/V12__knowledge_depth.sql:190；
      contracts/openapi.yaml:1240-1490（connector operationId 清单，无 restore）。
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/ops
    due: M25 gate 前 / M09 维护账号开通前
    title: "RUNBOOK 未记录维护账号最小权限；缺 CREATE TEMPORARY TABLES 时 apply 以 exit 1 失败"
    summary: >-
      scripts/ops/blank-connector-credentials.mjs:479-481 每批创建临时表 tmp_blank_credential_guard 并用
      CHECK 断言“置空行数 = 审计条数”。MySQL 8.4 要求维护账号具备 CREATE TEMPORARY TABLES，另外至少需要
      kb_connector 的 SELECT/UPDATE 与 audit_event 的 INSERT。独立复现：创建只授予
      `SELECT, UPDATE ON <db>.kb_connector` 与 `INSERT ON <db>.audit_event` 的 maintainer 用户运行
      `--apply --via=client`，退出 1，stderr 为 `ERROR 1044 (42000) at line 26: Access denied for user
      'maintainer'@'%' to database '<db>'`（line 26 即 CREATE TEMPORARY TABLE）；行密文未变、审计 0 条；
      追加 `GRANT CREATE TEMPORARY TABLES ON <db>.*` 后同一命令 exit 0，processed=1、auditEvents=1。
      RUNBOOK:1287 只写“需 RICCON_DB_HOST/USER/PASSWORD”，未列最小权限；exit 1 的“权限不足”也没有可执行修复。
      期望：RUNBOOK 给出最小权限清单（SELECT/UPDATE kb_connector、INSERT audit_event、CREATE TEMPORARY TABLES；
      明确不需要且不应授予 audit_event 的 UPDATE/DELETE），并说明 M09 维护账号如何开通/审计。
    accepted_reason: >-
      测试栈 riccon 用户对测试库有 ALL，drill 不受影响；生产维护账号由 M09 开通，当前无生产执行。失败是
      fail-closed（整批回滚、exit 1、无半写），不会造成“已置空无审计”；但若不在 M09 前补文档，生产第一次
      apply 会直接失败并暴露权限缺口。记为 accepted，责任在 ops 的 RUNBOOK 与 M09 账号开通。
    evidence: >-
      scripts/ops/blank-connector-credentials.mjs:479-481；
      docs/RUNBOOK.md:1287,1325-1332；
      独立复现：maintainer 仅 SELECT/UPDATE kb_connector + INSERT audit_event → exit 1（ERROR 1044 line 26）；
      追加 CREATE TEMPORARY TABLES → exit 0，processed=1、auditEvents=1。
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/ops
    due: M25 gate 前或 M09 排期时
    title: "任务证据只覆盖静态自检；真实 DB drill 未记录为 EV，缺少可重复 harness"
    summary: >-
      EV-0997 的命令是 `set -e; node --check ...; node ... --self-test`，只验证语法、UUIDv7/截止时间/SQL 转义
      和批次脚本关键字；`--self-test` 不执行 SQL，无法发现 WHERE 条件、审计插入或事务语义回归。
      RUNBOOK:1345-1352 记录了 builder 的 drill 结论，但仓库没有对应命令日志/EV，也没有可重复的 drill 脚本。
      Verifier 已在隔离 MySQL 8.4.13 + 从测试栈复制的真实 V12 schema（49 表、2 个 append-only 触发器）上独立
      构造初始 7 行（另插入回滚/并发行），完成 dry-run、分批、apply、重复 apply、审计失败回滚、恢复、并发与空表验证（51 项 PASS 断言全过；
      日志见 .riccon/evidence/TASK-0211/verifier-o2b-drill-2026-11-24.log）。当前提交的行为已可验收，但后续
      修改脚本没有 CI/仓库内回归保护。
    accepted_reason: >-
      本次任务已由 verifier 的真实 DB drill 覆盖行为，不以静态 self-test 冒充行为证据；脚本属 ops 维护工具，
      不在应用 CI 的覆盖率/E2E DoD 内。把 drill 固化为仓库内可重复 harness 是 M09 排期或 ops 后续任务，
      不阻塞 O2b 的当前验收；如果 M09 前仍无 harness，应在 M09 gate 重新评估。
    evidence: >-
      .riccon/evidence/TASK-0211/EV-0997.json；
      .riccon/evidence/TASK-0211/verifier-o2b-drill-2026-11-24.log；
      scripts/ops/blank-connector-credentials.mjs:491-561（self-test 仅静态）；
      docs/RUNBOOK.md:1345-1352（无命令日志的 drill 记录）。
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/lead
    due: M09 计划前
    title: "M09 生产首次 apply/调度的用户批准尚未进入决策记录"
    summary: >-
      RUNBOOK:1340-1341 已给出调度建议（每日一次、单实例、与备份窗口错开、首次生产 apply 前 dry-run 并由
      变更单批准），并明确 O2b 不自动调度、M09 负责生产调度与物理清理。但合同 decision_owners 将 production
      与 destructive 都归 user（AGENTS.md:311-317），T4 的生产破坏性操作不能只靠运维变更单；RUNBOOK 是操作
      建议，不是用户决策记录。M09 开始排期前应由 Lead 建决策包，至少覆盖：生产调度频率/单实例、首次
      `--apply` 的批准方式与时间窗、维护账号权限、备份/恢复边界、物理清理与审计保留。
    accepted_reason: >-
      O2b 只交付脚本与 RUNBOOK，不创建定时任务、不依赖生产密钥；当前 HEAD 没有生产执行路径，也没有越过
      M09 gate。该决策属于用户所有权，但可以在 M09 计划阶段完成，不影响 TASK-0211 的技术验收；记 accepted
      防止 M09 直接按 RUNBOOK 建议上线而未形成用户批准。
    evidence: >-
      docs/RUNBOOK.md:1269-1271,1340-1341；
      AGENTS.md:311-317（decision_owners.production/destructive = user）；
      docs/adr/ADR-0023-m05-knowledge-depth.md:595,840-844,1002。
notes: >-
  评审对象为代码提交 b7dac1e（父 d7d4aa5），已 merge 到 993f7fe；当前 HEAD 7ac5d3c 只增加 EV-0997/任务状态
  记录。`git diff --name-only d7d4aa5..b7dac1e` 仅 docs/RUNBOOK.md 与
  scripts/ops/blank-connector-credentials.mjs；`git diff --check` exit 0；无 apps/contracts/deploy/迁移改动。
  EV-0997：commit 993f7fe、dirty=false、exit 0、output_sha256
  df1cbd2dd22b3c73bfae3080f12c09e3cb39ac0e966650a5e362b9be93e0d95d 与日志一致；verifier 重跑 node --check 与 --self-test 均 exit 0。
  独立 drill：测试栈被其他 agent 反复 reset，因此最终采用一次性 mysql:8.4.13 容器 + 从测试栈 riccon 复制的
  schema（49 表、2 触发器、V12），通过 --via=client 跑完整序列；此前 --via=compose 的 apply#1 也实测
  processed=1/audit=1。drill 覆盖：dry-run 计数 3/2/1/1/7 且无密文标记；batch-size=1/max-batches=1 时
  processed=1、truncated=true、余 2；全量 apply processed=2/audit=2；重复 apply 0/0；ACTIVE、未满 30 天、
  already-blank、ciphertext 为空但 key/hint 非空的行均不变；三列置空仅命中到期行；连接器行未物理删除；
  审计 actor_type=SYSTEM/actor_id=system/action/target/trace/schema_version 正确，details 恰为
  connectorId/type/credentialKeyId/maskedHint 四键且值为置空前值，dry-run 与审计均无密文；审计表触发器
  阻断 INSERT 时 apply exit 1 且 UPDATE 整批回滚、无新增审计；CHECK 临时表在 1!=0 时确实报 3819；
  两并发 apply 均 exit 0、无重复审计；空表 apply 0/0。测试库与容器已清理。
  另：独立复现 F-002 的权限缺口（见 finding）。
  gate check --module M25 当前 fail（预期，非本任务判定）：TASK-0209/TASK-0211 仍 in_progress、TASK-0212/
  TASK-0213 planned、无模块 review；且 TASK-0211 的 `changed files outside write_scope` 是把
  baseline_commit..HEAD 中 TASK-0209/其他任务的 apps/web 与 docs/PRODUCT.md 变更算进来了，而
  d7d4aa5..b7dac1e 的代码 diff 只有 2 个本任务文件。Lead 在标 done 前需要把 TASK-0211 状态改为 review/
  done、设置 end_commit（或更新 baseline）并修 write_scope 误报；这些是流程/状态修正，不是本任务代码缺陷。
  未跑全量 Maven/E2E（本任务无 Java/迁移/UI 变更，且按派单要求不重复全量）；未验证生产环境、M09 调度、
  备份恢复演练、OS 级文件权限和 --via=compose 的完整 drill（仅 apply#1；SQL 与 client 模式相同）。
test_impact: >-
  本任务无 Java/迁移/前端行为变更；未跑 Maven 全量、E2E、a11y、coverage。跑了 node --check（exit 0）、
  node --self-test（exit 0）、EV-0997 复验，以及隔离 MySQL 8.4.13 + 真实 V12 schema 的独立 DB drill
  （dry-run/apply/重复/分批/审计失败回滚/恢复/并发/空表，51 项 PASS 断言全过）。未跑项目全量测试，因此不声称
  M25 模块级回归通过；M25 gate 仍由 V1b/V2b 任务承接。
created_at: "2026-11-24T11:18:00+08:00"
---

# REV-TASK-0211：任务 TASK-0211 独立评审

## 结论

**pass（带 residual）**。`scripts/ops/blank-connector-credentials.mjs` 的 30 天置空、同事务审计、幂等/分批/上限、
审计失败回滚和并发行为已在真实 MySQL 8.4.13 + V12 schema 上独立复验通过，未发现 P0/P1。RUNBOOK 的结构、
失败矩阵、复核 SQL 与 M09 边界完整；但有两处生产可执行性缺口（F-001 误删恢复/重新录入指引与现有 API 不一致；
F-002 未记录维护账号最小权限，缺 `CREATE TEMPORARY TABLES` 时 apply 会 fail-closed），以及证据可重复性
（F-003）和 M09 生产决策（F-004），均按 accepted 跟踪，不影响本任务技术验收。

## 验证了什么

- **范围**：`d7d4aa5..b7dac1e` 仅 `docs/RUNBOOK.md` 与 `scripts/ops/blank-connector-credentials.mjs`；无
  apps/contracts/deploy/迁移改动；`git diff --check` 干净；脚本 638 行，中文注释覆盖语义、边界和安全不变量。
- **静态复验**：`node --check` exit 0；`--self-test` exit 0（cutoff-30d、datetime6-utc、uuidv7、sql-escape、
  batch-script-guard、no-ciphertext-read）。
- **EV-0997**：commit `993f7fe`、dirty=false、exit 0、output_sha256 与日志一致；命令包含 `set -e`，不会把
  前半段失败吞掉。
- **真实 DB drill**（独立，隔离 `mysql:8.4.13` + 从测试栈复制的 V12 schema，49 表/2 触发器）：
  - 初始 7 行：到期 45 天/40 天有密文、未满 10 天、ACTIVE、已置空、ciphertext 为空但 key/hint 非空、另一条 40 天到期行；另插入 rollback-2-40d 做审计失败回滚、两条 conc-* 做并发；
  - dry-run 计数 `3/2/1/1/7`，只输出租户/ID 样本与摘要，不输出密文；不写审计；
  - `--batch-size 1 --max-batches 1` → processed=1、audit=1、truncated=true、余 2；全量 apply → 2/2；重复
    apply → 0/0；三列只在到期行置空，其他行零变化；连接器行未删除；
  - 审计每行恰好 1 条，`SYSTEM/system`、`trace_id=connector-credential-blank`、target/type/outcome/schema_version
    正确；details 恰为白名单 4 键、值为置空前 key/hint，dry-run/审计均无密文；
  - 审计 INSERT 被临时触发器阻断 → apply exit 1，UPDATE 整批回滚、无半写；移除故障后重跑 1/1；
  - 两并发 apply 均 exit 0，审计总数只 +2，每 target 恰好 1 条；
  - 空表 apply 0/0；未知参数/互斥参数/client 缺 env 均 exit 2；CHECK 临时表在 `1 != 0` 时确实报错。
  - 另独立复现 F-002：仅 `SELECT/UPDATE kb_connector + INSERT audit_event` 的账号在 line 26（CREATE
    TEMPORARY TABLE）报 `ERROR 1044`、exit 1，行未变/审计 0；追加 `CREATE TEMPORARY TABLES` 后成功。
- **安全**：脚本只 UPDATE 三列、只 INSERT 审计；不 SELECT 密文值、不解密、不打印；审计和置空同一事务；
  没有硬编码生产凭据（compose 仅测试栈默认密码，client 走 `RICCON_DB_*`）；审计 details 与
  `AuditDetailsPolicy` 的 `knowledge.connector.credential.changed` 白名单一致。
- **RUNBOOK**：触发条件、dry-run/apply、复核 SQL、审计/保留、失败处理、幂等/分批、M09 调度/物理清理/备份/
  回滚边界、O2b 不自动调度均已写明；F-001/F-002 是措辞/权限补充，不改变脚本行为。

## AC 对账

| 任务验收项                        | 结论                 | 证据                                             |
| --------------------------------- | -------------------- | ------------------------------------------------ |
| 凭据 30 天置空流程/脚本           | pass                 | 独立 drill；EV-0997；脚本 405-486、564-631       |
| RUNBOOK（含与 M09 物理清理边界）  | pass (with residual) | RUNBOOK 1260-1352；residual F-001/F-002          |
| 明确 O2b 不自动调度、M09 负责生产 | pass                 | RUNBOOK 1262-1271、1340-1341；F-004 跟踪用户决策 |

`dod.acceptance` 记为 true，但按 residual 记录：F-001（恢复指引）和 F-002（最小权限）未修完，不能把 RUNBOOK
写成无残余的“生产完全就绪”。

## 未验证

- 未跑全量 Maven、E2E、a11y、coverage、性能脚本（本任务无 Java/迁移/UI 变更，按派单不重复全量，模块级由
  V1b/V2b 承接）。
- 未在生产/预览环境执行；M09 的调度、维护账号开通、备份恢复演练和物理清理未验证。
- 未验证 `--via=compose` 的完整 drill（测试栈被其他 agent 反复 reset，仅在 reset 前跑通 apply#1 的
  processed=1/audit=1；完整 drill 走 `--via=client`，SQL 与 compose 通道相同）。
- 未验证 OS 级脚本权限、日志落盘权限和主机时钟/NTP 偏差（脚本用主机时钟计算 cutoff 和 occurred_at）。

## Gate

`riccon gate check --module M25` 当前失败，原因是 M25 尚未到 gate：TASK-0209/TASK-0211 仍 in_progress、
TASK-0212/TASK-0213 planned、无模块 review；另有 TASK-0211/TASK-0209 的 `changed files outside write_scope`
误报。后者来自 `baseline_commit..HEAD` 包含其他任务的 apps/web 与 `docs/PRODUCT.md` 变更；`d7d4aa5..b7dac1e`
的本任务代码 diff 只有 2 个文件。Lead 在任务 done 前需更新任务状态、`end_commit`（或 baseline）并修正
write_scope 误报；这不是本任务代码缺陷，也不改变本评审的技术结论。

## 剩余风险与下一步

1. **ops**：修订 RUNBOOK 的恢复指引（F-001）并补最小权限清单（F-002）；建议在 M09 前完成。
2. **ops/M09**：把本次隔离 drill 固化为仓库内可重复 harness（F-003），至少覆盖 WHERE 范围、审计同事务、
   重复 apply 0/0、审计失败回滚和分批上限。
3. **lead**：M09 计划前建生产调度/首次 apply/物理清理/备份恢复/维护账号的用户决策包（F-004）；并修正
   TASK-0211 的状态/end_commit/baseline/write_scope，使模块 gate 能正确评估本任务。
4. **无代码返工**：本任务脚本本身未发现需要修改的 P0/P1/P2 代码缺陷；F-001/F-002 是 RUNBOOK 文档与生产
   账号开通项，F-003/F-004 是证据/决策项。
