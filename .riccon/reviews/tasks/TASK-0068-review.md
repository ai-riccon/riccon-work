---
review_id: REV-TASK-0068-001
module_id: M10
task_id: TASK-0068
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0072_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [e07f35b]
head_commit: c50e3fd
reviewed_evidence: [EV-0409]
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: "REV-TASK-0065-002 F-001：RoleDefinitions 两条导出审批权限已由 B3a 收口"
    summary: "TENANT_ADMIN 仅新增 audit.export.approve，PLATFORM_ADMIN 仅新增 platform.audit.export.approve；MEMBER 无审计权限。V10 字典/回填覆盖既有角色，RoleDefinitions 覆盖新租户与空库引导；F-001 可记为 fixed。"
    evidence: "RoleDefinitions.java:62-78；RoleDefinitionsTest.java:54-72；V10__audit_query_export.sql:84-135；V10MigrationTest.java:498-532、582-611；EV-0409"
  - id: F-002
    severity: P3
    status: open
    title: "EV-0409 的 685 tests 含 1 条 2026-11-18 陈旧报告；EV 窗口内实际 684 tests/0 skip"
    summary: "按 EV-0409 起止时间统计 surefire 报告：109 个报告、684 tests、0 failures/errors/skipped。685 是把 apps/worker/target/surefire-reports/com.riccon.worker.agent.AgentStreamTimingProbeTest.txt（2026-11-18 07:51，源码已不在仓库）计入。与 REV-TASK-0065-002 F-004 同类；不改代码，但证据叙述需纠正。"
    evidence: "EV-0409 JSON/日志；apps/worker/target/surefire-reports/com.riccon.worker.agent.AgentStreamTimingProbeTest.txt"
  - id: F-003
    severity: P3
    status: open
    title: "SchemaVersionCheckerTest 不锁定 EXPECTED_SCHEMA_VERSION=10，常量回退不会被测试发现"
    summary: "现有 5 个测试全部以 SchemaVersionChecker.EXPECTED_SCHEMA_VERSION 为基准构造/断言，没有任何断言把常量固定为 10，也没有与迁移目录最大版本交叉校验。代码当前正确（=10），但若未来常量漂移，worker 套件仍会全绿，只有部署时才暴露 RICCON-WORKER-001。建议 V1 或下一个迁移任务加一条“常量=最高 V 号”的契约测试。"
    evidence: "SchemaVersionChecker.java:35；SchemaVersionCheckerTest.java:58-96；WorkerIntegrationTestBase.java:77、86"
  - id: F-004
    severity: P3
    status: open
    title: "V10MigrationTest 未逐列断言类型/可空性，类型漂移只能靠 SQL 评审"
    summary: "测试断言了列名、索引列序、复合外键、CHECK 名称/子句与状态机，但未查询 information_schema.COLUMNS 的 DATA_TYPE/IS_NULLABLE；例如 BINARY(16)→VARBINARY、DATETIME(6)→DATETIME 这类漂移不会被测试发现。本评审已按 ADR-0020 §5.1 逐列对账，当前 V10 类型/可空性正确；此 finding 只针对测试护栏。"
    evidence: "V10MigrationTest.java:248-311；V10__audit_query_export.sql:39-79；ADR-0020 §5.1"
  - id: F-005
    severity: P3
    status: open
    title: "权限字典 audit.read 描述仍写“M03 启用”，M10 实际启用后文案漂移"
    summary: "V2 写入 ('audit.read','审计查询（M03 启用）')，但 M03 只保留未启用，M10 才开放查询。ADR-0020 §6.1 只要求语义从“保留”变为“启用”，V10 未刷新 description。不影响权限码/授权与验收，但管理端若展示描述会误导；建议后续迁移或 B3b/architect 收口，不要修改已应用的 V2。"
    evidence: "V2__identity_and_workspace.sql:121；ADR-0020 §6.1；V10__audit_query_export.sql:84-90"
evidence: []
created_at: "2026-11-19T10:12:00+08:00"
---

# REV-TASK-0068-001：TASK-0068（M10-B3a V10/权限/RoleDefinitions/worker schema）独立评审

## 1. 结论

**verdict: pass（任务级）。** B3a 的代码、迁移、权限/角色回填、worker schema 与迁移测试均可独立复验；F-001 可记为 fixed。本任务范围内 **开放 P0/P1 = 0**；P3 4 条（见 Findings）。

三点边界必须先说清：

1. **这不是 M10 模块 gate。** EV-0409 是 `./mvnw -q -T1C verify`（后端全量），不含合同的 `pnpm -w test`、E2E、a11y、security、完整 coverage 命令；模块 gate 由 M10-V1 在最终提交上跑合同命令。
2. **OPS-001 仍是 open P1，但不属于 TASK-0068 的交付缺陷：** V10 合入 main 后 `.github/workflows/ci.yml:64` 的 `tables -eq 36` 断言会因新增 `audit_export_request` 变红，`docs/RUNBOOK.md:176/213` 也仍写 36 表/V1–V7。OPS-001 owner 是 ops/lead，写范围不在 B3a（B3a 只允许 V10/RoleDefinitions/worker/测试）；**Lead 必须在合入窗口同步关闭 OPS-001，否则 main CI 必红、M10 gate 不过**。
3. **V10 生产执行不是本评审的批准：** 仍需 OPS-004/005/006/007 的运行手册与 V1 演练、OPS-001 关闭，以及用户按合同 `decision_owners.production` 的 T4 批准。

## 2. 评审范围与独立复验

评审起点：`git status --short` 空；HEAD `c50e3fd`；TASK-0068 `status=review`、`end_commit=e07f35b`。被评审提交 `e07f35b` 仅 6 个文件（1487+/12-）：

| 文件                                                                   | 变更                          |
| ---------------------------------------------------------------------- | ----------------------------- |
| `apps/api/src/main/resources/db/migration/V10__audit_query_export.sql` | 新增 169 行                   |
| `apps/api/src/main/java/.../identity/domain/RoleDefinitions.java`      | 两条审批权限 + Javadoc        |
| `apps/worker/.../persistence/SchemaVersionChecker.java`                | 期望版本 9→10 + 滚动/回滚注释 |
| `apps/api/src/test/java/.../migration/V10MigrationTest.java`           | 新增 1272 行                  |
| `apps/api/src/test/java/.../identity/domain/RoleDefinitionsTest.java`  | 新增 1 个测试                 |
| `apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java`       | 迁移/表计数 9→10、39→40       |

| 检查/命令                                                                                                                                      | 退出码 | 结果                                                                                                       |
| ---------------------------------------------------------------------------------------------------------------------------------------------- | ------ | ---------------------------------------------------------------------------------------------------------- |
| `git status --short`；`git rev-parse --short HEAD`                                                                                             | 0      | 空；`c50e3fd`                                                                                              |
| `git show --stat e07f35b`；`git show --check e07f35b`                                                                                          | 0      | 仅上述 6 文件；无空白错误                                                                                  |
| `./mvnw -q -T1C -pl apps/api -am -Dtest=V10MigrationTest,ApiApplicationTests,RoleDefinitionsTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0      | V10MigrationTest 11/0 skip；ApiApplicationTests 9/0 skip；RoleDefinitionsTest 5/0 skip                     |
| `./mvnw -q -T1C -pl apps/worker -am -Dtest=SchemaVersionCheckerTest -Dsurefire.failIfNoSpecifiedTests=false test`                              | 0      | 5/0 skip；日志 `schema.version.checked expected=10 actual=10`                                              |
| `./mvnw -q spotless:check`                                                                                                                     | 0      | 通过                                                                                                       |
| `riccon contract validate --strict --project .`                                                                                                | 0      | 0 errors / 0 warnings                                                                                      |
| `riccon state validate --project .`                                                                                                            | 0      | 0 errors；3 warnings（M11 legacy + M14 两条 legacy，均非本任务引入）                                       |
| `git diff --check`                                                                                                                             | 0      | 通过                                                                                                       |
| EV-0409 复核                                                                                                                                   | 0      | `manual=false`、`dirty=false`、`commit=e07f35b`、exit 0、992410ms；输出 646226 bytes，SHA-256 与 JSON 一致 |

**未重复 16 分钟全量 verify**：EV-0409 已覆盖 `./mvnw -q -T1C verify`；本次只跑定向测试与治理检查。EV-0409 的覆盖率：`apps/api/target/site/jacoco/jacoco.csv` line **90.6%** / branch **71.8%**，worker line **87.9%** / branch **70.8%**，均高于合同 80/70；`apps/web/coverage` 是 M02/M03 的旧产物，本轮未重跑，B3a 未改 Web。

## 3. V10 迁移审计

### 3.1 `audit_export_request` 结构与契约

V10:39-79 与 ADR-0020 §5.1 逐列一致：

- **租户键**：`PRIMARY KEY (tenant_id, id)`；`tenant_id VARCHAR(64) ascii_bin` 在首位；复合外键 `(tenant_id, requested_by) → app_user (tenant_id, id)`（V2:35-48 的 `app_user` 主键匹配）。跨租户申请人被数据库拒绝（V10MigrationTest:428-431 断言 `fk_audit_export_requester`）。
- **列/类型**：`id/requested_by/approved_by BINARY(16)`、`filters JSON NOT NULL`、`filters_hash CHAR(64) ascii_bin`、`estimated_row_count INT NOT NULL`、`approved_by_tenant_id VARCHAR(64)`、`decided_at/expires_at/created_at/updated_at DATETIME(6)`、`decision_comment VARCHAR(500)`；`created_at/updated_at` 默认 `CURRENT_TIMESTAMP(6)` 且 `updated_at ON UPDATE`。与 ADR §5.1 一致；DATETIME(6) UTC 口径符合 ADR-0008/0012。
- **FK 边界**：`approved_by` 不建外键（平台审批人属于 `platform`，无法满足目标租户复合 FK），与 ADR §5.1/§3 一致；权威操作者仍在 `audit_event.actor_id`。
- **CHECK 一致性**：`chk_audit_export_tenant`、`chk_audit_export_status`（三态）、`chk_audit_export_rows`（≥0）、`chk_audit_export_self_approval`（申请人不得自批）、`chk_audit_export_decision`（PENDING 无审批字段/APPROVED 必须有 decided_at+expires_at/REJECTED 有 decided_at 无 expires_at）。V10MigrationTest:366-435 对非法状态、负行数、自批、状态字段不一致、跨租户申请人、重复主键逐一用约束名断言拒绝；`migration_supportsExportRequestStateMachine`（:315-363）验证申请、条件审批（第二次影响 0 行）、拒绝路径。
- `status` 不存 `EXPIRED`（派生过滤），与 ADR §2/§7 一致。
- 未存导出文件、对象 key、预签名 URL；`filters` 4 KiB 上限由服务层（B3b）负责，不在 DB 强制——与 ADR §5.1 一致。

### 3.2 索引与 DDL

- V10:142-146 为**单条** `ALTER TABLE audit_event ADD KEY idx_audit_tenant_action_time (...), ADD KEY idx_audit_tenant_actor_time (...), ALGORITHM = INPLACE, LOCK = NONE;`，两条索引同语句原子成败（OPS-005）。
- V10MigrationTest:439-471 断言两个索引的列序、`audit_event` 触发器仍为 2、分区仍为 15，并用 `EXPLAIN possible_keys` 证明索引可用（不锁死优化器选择）。
- V10MigrationTest:474-493 读取真实迁移文件（非副本），去注释后断言只有一个 `ALTER TABLE audit_event`、单语句内同时含两条索引与 `ALGORITHM = INPLACE`/`LOCK = NONE`。
- 生产执行仍按 OPS-005 走维护窗口停写；online DDL 只作演练后回退方案（V10 头部注释已写明）。

### 3.3 权限字典与角色回填幂等

- V10:84-90 插入两条审批权限，`NOT EXISTS` 守卫用于失败后 repair 重跑；V10:80-82 注释明确“不表示可容忍预先占用”，生产前仍按 ADR-0020 §5.4 预检两个 code 不存在（OPS 前置）。
- V10:94-135 回填四组映射：所有 `TENANT_ADMIN` 的 `audit.read`/`audit.export.approve`、`platform` 租户 `PLATFORM_ADMIN` 的 `audit.read`/`platform.audit.export.approve`。全部带 `NOT EXISTS`，覆盖 B1 之后新建租户已由 RoleDefinitions 写入 `audit.read` 的场景，避免 `role_permission` 主键冲突。
- V10MigrationTest:496-532 断言字典各 1 行、acme/globex 管理员各得查询+同租户审批、平台管理员得查询+平台审批、acme/globex 管理员无平台审批、平台管理员无租户审批、MEMBER 全无、B1 租户 `initech` 回填幂等。
- V10MigrationTest:679-728 的失败注入覆盖“权限 INSERT 已提交、索引 ALTER 失败”后的 repair/重跑：`NOT EXISTS` 使权限重跑不冲突，最终两条索引与权限均正确。

### 3.4 纯 additive、向前兼容、回滚与失败恢复

- **纯 additive**：只新增表、两索引、权限行、角色映射；不删列/不改类型/不重建 M02/M03 表。V10MigrationTest:534-577 断言 M02/M03 数据可读、审计 append-only（UPDATE 被触发器拒绝）、触发器仍 2。
- **向前兼容**：旧代码可忽略新表/索引/权限；旧 worker 在 DB=10 时不能重启，必须按 OPS-004 协调切换（V10:17-23、SchemaVersionChecker Javadoc:23-28 已写明）。
- **回滚**：V10:150-165 的注释包含 ADR-0020 §5.4 的完整 SQL（`DROP TABLE`、`ALTER TABLE ... DROP INDEX`、删角色映射/权限），并补 OPS-007 的 `DELETE FROM flyway_schema_history WHERE version='10' AND success=1` 与 `MAX(version)=9` 校验；同时写明 F-022 前提（`audit.read` 发布后若手工授予需先记录）。V10MigrationTest:613-673 在非生产 Testcontainers 副本执行回滚，断言表/索引/两条权限消失、M02/M03 数据与审计可读、剩余 9 条 M02/V9 映射保留、删 history 后 `current=9` 且 V10 pending。
- **失败恢复矩阵**：V10:24-31 按语句顺序列出 1) CREATE TABLE 失败 2) 权限 INSERT 失败 3) 角色回填失败 4) 索引 ALTER 失败四类残留与 `flyway repair`/重跑路径；V10MigrationTest:679-728 用重名索引真实注入失败并恢复成功。满足 OPS-006 的 B3a 交付部分。

**V10 结论：与 ADR-0020 §5/§5.1/§5.2/§5.3/§5.4 一致，未发现 P0/P1/P2 缺陷。** 一个已知架构取舍：`audit_export_request` 仍无 `created_at` 清理索引（OPS-008 P2，M09 按租户循环或后续迁移处理），B3a 验收未要求，不阻塞本任务。

## 4. RoleDefinitions 与权限边界（F-001）

- **只新增两条**：`RoleDefinitions.java:62-67` 给 TENANT_ADMIN 加 `audit.export.approve`；`:73-78` 给 PLATFORM_ADMIN 加 `platform.audit.export.approve`。没有改其他角色/权限；MEMBER 仍是原集合（无审计权限）。
- **新租户/空库引导**：`tenantSystemRoles()` 与 `platformAdminRole()` 都被 `TenantAdminService`/`BootstrapAdminRunner` 使用；V10 的 `INSERT ... SELECT FROM role` 只覆盖迁移前既有角色，RoleDefinitions 覆盖 fresh install、空库引导和 V10 后新建租户。V10MigrationTest:579-611 在空库 V1–V10 后按 RoleDefinitions 创建角色与全部映射，任何缺失权限都会因 `role_permission` 外键失败——该测试通过。
- **真实引导路径**：`ApiApplicationTests.bootstrapAdminCreationIsAudited` 与 `bootstrapAdminBelongsToPlatformTenant` 在干净 V1–V10 容器上启动应用并断言平台管理员/权限变更审计；若 RoleDefinitions 引用 V10 未写入的权限，应用启动即失败。EV-0409 中该测试通过，补足了“模拟 role 创建”之外的启动路径。
- **B1 共享词汇边界**：`PermissionCodes` 的两条常量由 B1 提供（`platform/.../PermissionCodes.java:67-71`）；B3a 只引用。`AuditActions`/`AdditionalAuditActions`/`AuditDetailsPolicy`/`ErrorCodes` 未被 e07f35b 修改（`git show --stat` 可证）。Javadoc 明确记录 §8.1 窄例外与“B3b 及后续不得再改本文件”。
- **F-001 判定：fixed。** REV-TASK-0065-002 的 P1 是“RoleDefinitions 导出审批权限无 owner”；B3a 已在干净提交 `e07f35b` + EV-0409 下完成两条权限及测试，满足 ADR §8.1 的 fixed 条件。Lead 可在 TASK-0065/TASK-0068 的 closeout 与模块评审中把 F-001 记为 fixed；本评审即作为该结论的独立依据。

## 5. Worker schema（OPS-002/004）

- `SchemaVersionChecker.java:35` `EXPECTED_SCHEMA_VERSION = 10`；Javadoc 写明 V1..V10、严格校验、`RICCON-WORKER-001`、协调切换与回滚顺序。
- `apps/worker/src/main/resources/application.yml:15-17` 仍 `spring.flyway.enabled=false`；worker 不执行迁移，只读 `flyway_schema_history` 最大成功版本。
- `grep` 确认 Worker 代码不引用 `audit_export_request` / `audit_export`；V10 新表/索引不影响 worker 查询。
- `SchemaVersionCheckerTest` 5/5 通过，日志 `expected=10 actual=10`；`WorkerIntegrationTestBase` 用常量创建版本记录，因此测试与代码同步（但见 F-003 的锁定缺口）。
- OPS-004 的发布顺序已在 V10:17-23 与 SchemaVersionChecker Javadoc:23-28 落地为代码内说明；运行手册的一键切换/一次性 Job 细节仍属 ops。

## 6. 迁移测试与证据核对

- **11 个 V10 测试**覆盖：V9 种子→V10（:234-243）、表/键/索引/FK/CHECK（:246-312）、状态机/条件审批（:315-363）、约束拒绝（:366-435）、分区索引/触发器（:439-471）、单语句 INPLACE/LOCK=NONE（:474-493）、权限回填含 B1 租户（:496-532）、M02/M03 可读与 append-only（:534-577）、空库 fresh install（:579-611）、回滚 V9（:613-673）、索引失败注入/repair 重跑（:679-728）。**不是空库通过冒充种子升级**：主路径明确 `V1–V8 → seedM02 → V9 → seedM03/audit → V10`。
- **无删测/跳测/弱断言**：e07f35b 只新增 V10MigrationTest 与 RoleDefinitionsTest 的一个测试；`V9MigrationTest` 未改；所有 surefire 报告 0 skipped。`ApiApplicationTests` 仅迁移数 9→10、表 39→40（`:42-45`），未删除/弱化原有 8 个测试；`V10MigrationTest` 的断言均带约束名/列序/状态值。
- **EV-0409 计数纠正**：EV 窗口内 surefire 报告 109 个、**684 tests / 0 failures / 0 errors / 0 skipped**；build/worker 的 685 是把 2026-11-18 的 `AgentStreamTimingProbeTest` 陈旧报告（源码已不在仓库）计入。全量仍绿，但证据叙述需按 684 修正（F-002 P3）。
- **覆盖率**：API line 90.6/branch 71.8、worker line 87.9/branch 70.8，均高于合同 80/70；这些报表在 EV-0409 时间窗内生成。Web 覆盖率未由本轮刷新，B3a 无 Web 变更，模块 gate 需重跑 `pnpm -w test -- --coverage`。

## 7. 边界、状态与工作区

- `e07f35b` 无 `contracts/**`、`apps/web/**`、`AGENTS.md`、`docs/PRODUCT.md`、ADR 修改；ADR-0020 仍 `Status: proposed`，符合“B3a 只改 V10/RoleDefinitions/worker/测试”的边界。
- TASK-0068 `write_scope` 与实际改动逐一匹配；`ApiApplicationTests.java` 在 scope 内且仅计数更新，未借机弱化。
- 状态：TASK-0068 `review`、`end_commit=e07f35b`；EV-0409 `manual=false`/`dirty=false`/`commit=e07f35b`/exit 0；JSON 的 SHA-256/字节数与日志一致。HEAD `c50e3fd`，评审开始时工作区干净。
- `state validate` 0 errors / 3 warnings：M11 legacy（既有）+ M14 的 product pass while building / user accepted before done（legacy，非本任务引入）。建议 Lead 在 M10 gate 前顺手归一，避免与新状态混淆。

## 8. Findings

### F-001（P1，fixed）：RoleDefinitions 两条导出审批权限已收口

见 §4。REV-TASK-0065-002 的 F-001 从 accepted/assigned 转 fixed；证据为 `e07f35b`、EV-0409、本次定向 `RoleDefinitionsTest` 5/5 与 `V10MigrationTest` 11/11。

### F-002（P3，open）：EV-0409 的 685 含 1 条陈旧报告

按 EV 起止时间统计：109 个报告、684 tests、0 skipped；685 多出的是 `AgentStreamTimingProbeTest.txt`（2026-11-18 07:51，`src/test` 已无该类）。不影响 0 skip/0 failure 结论，但会误导“测试数增长”判断。建议 builder/Lead 在 handoff 与模块评审写 684，或下次 EV 前清理 `target/surefire-reports` 陈旧文件。

### F-003（P3，open）：worker 期望版本未被测试锁定为 10

`SchemaVersionCheckerTest` 的 5 个用例全部相对 `EXPECTED_SCHEMA_VERSION` 构造；若常量回退/漂移到 9，测试仍绿。建议 V1 增加“常量 = `db/migration` 最大 V 号”的契约断言，或在 `ApiApplicationTests` 中交叉校验。

### F-004（P3，open）：V10MigrationTest 未逐列断言类型/可空性

列名、索引列序、FK、CHECK、状态机均有断言，但 `information_schema.COLUMNS` 的 DATA_TYPE/IS_NULLABLE 未断言。当前 V10 与 ADR §5.1 逐列一致（本评审人工对账），但类型漂移缺少测试护栏；建议 V1 或下一次迁移测试补。

### F-005（P3，open）：`audit.read` 描述仍写“M03 启用”

V2:121 的描述在 M10 启用后已过时；ADR §6.1 只要求语义变化，V10 未刷新 description。不影响权限码/授权/AC，但管理端若展示描述会误导。建议 architect/B3b 用后续迁移或文档口径收口；不要修改已应用的 V2。

## 9. OPS 承接与 V10 生产前置

| ID              | 状态（本评审时）        | B3a 已交付                                 | 仍需                                                                                                                          |
| --------------- | ----------------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| OPS-001         | **open P1**（ops/lead） | 无（不在 B3a 写范围）                      | `.github/workflows/ci.yml:64` 表数 36→37（或改结构断言）、`docs/RUNBOOK.md:176/213` 更新；**合入 main / M10 gate 前必须关闭** |
| OPS-002         | fixed（B3a）            | 期望版本 10 + 注释                         | 无（模块 gate 复核）                                                                                                          |
| OPS-004         | B3a 部分完成            | 版本号 + V10/worker 协调切换说明           | ops 运行手册的一次性迁移 Job/维护窗口步骤                                                                                     |
| OPS-005         | B3a 部分完成            | 单语句 INPLACE/LOCK=NONE + 原子性/失败测试 | ops 生产维护窗口停写；V1 演练记录                                                                                             |
| OPS-006         | B3a 部分完成            | V10 失败恢复矩阵 + repair 重跑测试         | ops 运行手册 + V1 gate 前演练                                                                                                 |
| OPS-007         | B3a 部分完成            | 回滚 SQL + history 行处理 + 回滚测试       | ops 运行手册 + V1 gate 前演练                                                                                                 |
| OPS-008         | open P2（M09）          | 未加 `idx_audit_export_created`            | M09 逐租户清理或后续迁移；不阻塞 B3a                                                                                          |
| OPS-009/013/014 | open P2                 | 不在 B3a 范围                              | B3b/V1/M09 承接                                                                                                               |

**V10 生产执行前置（汇总）**：OPS-001 关闭 + OPS-004/005/006/007 运行手册/演练完成 + OPS-M10 评审结论记录 + 用户 T4 生产批准；迁移采用“停 worker → 停 API 写 → 一次性迁移 Job → 起新 API → 起新 worker → 单管理员/审计 smoke”的协调切换，不得纯滚动。本评审不代替 production owner 的批准。

## 10. B2/B3b 启动条件

- **B2（TASK-0069）可启动，且与 B3a 无依赖冲突。** ADR-0020 §8 明确 B3a 可与 B2 并行；B2 无迁移、无 worker 变更，写范围（admin identity/controller/service、identity dto、auth repository/persistence）与 B3a 不重叠。TASK-0066 starter 已 done，B2 无 blocker。B2 仍须遵守“不碰 V10/RoleDefinitions/worker/审计 API”的边界，并覆盖 platform 租户拒绝（其 acceptance 已写）。
- **B3b（TASK-0071）可在 Lead 标记 TASK-0068 done 后启动**（若 Lead 允许，也可现在以 `e07f35b` 为 baseline 启动）。V10 已在 V9 种子和空库上可应用，权限/角色回填与 worker 10 已就绪；B3b 只消费，不得改 `V10__*.sql`、`RoleDefinitions.java`、`SchemaVersionChecker.java`、`audit/domain/**`、`contracts/**`（ADR §8.1）。其 baseline 必须包含 `e07f35b`。
- **合入顺序建议**：先由 ops/lead 关闭 OPS-001（同一变更窗口更新 ci.yml/RUNBOOK），再把 B3a 合入 main；否则 main CI 的迁移冒烟会红。B3b 分支应基于含 OPS-001 修复的 main。

## 11. 未验证与剩余风险

- **未执行**：V10 真实生产 DDL（本评审只在 Testcontainers MySQL 8.4.13 上验证）；生产备份/恢复、迁移账号权限、维护窗口时长、在线 DDL 回退方案均需 V1/M09 演练。
- **未执行**：V10 回滚后**重新前滚**的完整链路（测试只验证删 history 后 `current=9`/`pending=10`；失败注入测试覆盖了 repair 后重跑，但没有在回滚 schema 上再跑一次 V10）。
- **未执行**：真实并发审批线程交错（测试用两次串行条件更新证明“第二次 0 行”；DB 的 `WHERE status='PENDING_APPROVAL'` 语义正确，API 409 由 B3b 覆盖）。
- **未执行**：`pnpm -w test`、E2E、a11y、security、完整 `commands.coverage`；这些属于 M10-V1 的模块 gate。
- **剩余风险**：V10 与 M10 代码同批上线，回滚窗口内导出功能不可用（新功能，可接受）；`audit_export_request` 无 `created_at` 清理索引（OPS-008）使 M09 跨租户清理需逐租户；`NOT EXISTS` 守卫依赖 §5.4 预检，若生产预检缺失，预占权限码会被静默接受；worker 版本常量缺测试锁定（F-003）。
- **状态遗留**：M11/M14 legacy warnings 与本任务无关，但 M10 gate 前建议归一。

## 12. 回执摘要

- **B3a 是否可 done**：可以（任务级）。6 项 acceptance 均有代码/测试/EV 支撑，开放 P0/P1=0；但 **合入 main 前必须处理 OPS-001**（CI/RUNBOOK 的 36 表断言），且 V10 生产仍待 OPS 运行手册/V1 演练/T4 批准。
- **F-001**：fixed（REV-TASK-0065-002 的 P1 由 e07f35b + EV-0409 收口）。
- **B2/B3b**：B2 可独立启动；B3b 可在 B3a done 后（或现在以 e07f35b 为 baseline）启动，只消费不改 B3a 文件。
- **OPS-002**：fixed；**OPS-004/005/006/007**：B3a 部分完成，ops 运行手册 + V1 演练仍待；**OPS-001**：open P1，阻塞 main CI/M10 gate；**OPS-008/009/013/014**：按原 owner/M09/B3b/V1 承接。
- **剩余 P2/P3**：P2 继承 OPS-008/009/013/014；P3 F-002（684 vs 685）、F-003（worker 版本未锁定）、F-004（类型断言缺口）、F-005（audit.read 描述漂移）。
