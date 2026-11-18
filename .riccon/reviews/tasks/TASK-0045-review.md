---
review_id: REV-TASK-0045-001
module_id: M03
task_id: TASK-0045
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M03_TASK-0046_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [4648bc3]
head_commit: 4250c62
findings:
  - id: F-001
    title: "V9 收紧后，WorkspaceRecord/WorkspaceJdbc/ApiIntegrationTestSupport 仍保留 V9 前兼容期注释"
    severity: P3
    status: accepted
  - id: F-002
    title: "V9MigrationTest 的 CHECK 断言是 token 级，且未覆盖 PERSONAL 搭配非 PRIVATE 可见性的非法组合"
    severity: P3
    status: accepted
  - id: F-003
    title: "EV-0320 的 589 tests 含 EV 窗口外 07:51 的残留报告，窗口内 fresh 为 588"
    severity: P3
    status: accepted
created_at: 2026-11-18T20:32:43+08:00
---

# TASK-0045 独立评审：M03-B1b V9 收紧与迁移测试

## 结论

**verdict: pass**（无 P0/P1）。B1b 的代码提交是 `4648bc3`，证据/状态提交是 `eb1f98d`/`7079bcf`，写范围追认是 `f598a81`，评审时 HEAD 为 `4250c62`。V9 在同一文件内按 ADR-0018 §6.2 完成 expand→contract：先回填 `visibility IS NULL`（PERSONAL→PRIVATE、TEAM→TENANT），再 `MODIFY ... NOT NULL`，最后加三段严格 CHECK；CHECK 与 ADR 文本逐字一致，没有默认值绕过，没有新增 V10，worker 版本仍为 9。迁移测试真实地从 V1–V8、seed M02 数据、再应用 V9，覆盖回填、权限/角色回填、复合外键、CHECK、唯一键与显式 NULL 拒绝；回滚在同容器第二个 schema 上执行 ADR §6.3 全 SQL，验证 V9 对象消失、权限回填消失、M02 数据可读、Flyway current=8/pending=9，且未使用 `flyway repair`。追认的两处夹具改动是必要的 expand 期适配，没有删断言、放宽权限或跳过测试。

**B1b 可以由 Lead 标记 done**（本评审不修改任务状态）。**B5 可以启动**：B2/B3/B4 已完成，B1b 未改 API/前端/OpenAPI，B5 依赖不受影响；**M03-V 应在 B5 完成后启动**，并按合同跑一次全量 test/coverage/E2E/a11y/security，不能拿 EV-0320 代替模块 gate 证据。三个 P3 分别承接 B1b 注释清理、M03-V 迁移测试补强和模块评审包证据口径，均不阻塞。

## 独立验证（干净树，HEAD `4250c62`）

| 命令/检查                                                                                                                                    | 退出码 | 结果                                                                                                                                            |
| -------------------------------------------------------------------------------------------------------------------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `git status --short`                                                                                                                         | 0      | 空；HEAD `4250c62`                                                                                                                              |
| `git show --stat 4648bc3`                                                                                                                    | 0      | 5 个文件、913 insertions / 36 deletions：V9、`V9MigrationTest`（新）、`ApiApplicationTests`、`WorkspaceScopeTest`、worker `TestDataFactory`     |
| `git show --stat eb1f98d 7079bcf`                                                                                                            | 0      | 仅 `.riccon` 的 EV 日志/JSON、state、task；无源码                                                                                               |
| `git diff --name-status 6ab90bd..4648bc3`                                                                                                    | 0      | 代码仅上述 5 个文件；另 `.riccon/state.json` 与新建 `TASK-0045.json`（`f60ed71` 启动任务）                                                      |
| `./mvnw -q -T1C -pl apps/api -am -Dtest=V9MigrationTest,ApiApplicationTests,WorkspaceScopeTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0      | **36 tests / 0 failures / 0 errors / 0 skipped**：`V9MigrationTest` 11、`ApiApplicationTests` 9、`WorkspaceScopeTest` 16（独立跑两轮均 exit 0） |
| `./mvnw -q spotless:check`                                                                                                                   | 0      | 通过                                                                                                                                            |
| `riccon contract validate --strict --project .`                                                                                              | 0      | 0 errors, 0 warnings                                                                                                                            |
| `riccon state validate --project .`                                                                                                          | 0      | 0 errors, 0 warnings                                                                                                                            |
| `git merge-base --is-ancestor 4648bc3 HEAD` / `eb1f98d HEAD`                                                                                 | 0 / 0  | 代码与证据提交均为 HEAD 祖先                                                                                                                    |
| `git diff --check 6ab90bd..4648bc3`                                                                                                          | 0      | 无空白错误                                                                                                                                      |
| `sha256(EV-0320 log)`                                                                                                                        | —      | `6bd719605153a1362d3d3385707267c1ebcdf75d1e41bc985493e08a8e27d340`，与 EV JSON 和 469325 bytes 一致                                             |

**EV-0320 审计**：`commit=4648bc39...`、`dirty=false`、`exit_code=0`，命令 `./mvnw -q -T1C verify`，运行窗口 20:14:20–20:22:56，提交时间 20:14:16（干净树）。窗口内 fresh surefire 汇总为 **588 tests / 0 failures / 0 errors / 0 skipped**：API 277、worker 262、platform-common 33、platform-domain 4、ArchUnit 12；当前 `target` 另有 07:51 的 `AgentStreamTimingProbeTest` 残留报告 1 个，588 + 1 = 589 与任务 brief 的数字一致（见 F-003）。API 覆盖率 **行 90.77%（报告写 90.8 为四舍五入）/ 分支 73.10%**；worker 87.89/70.77；platform-common 98.55/98.15；platform-domain 94.44（无分支）；均高于合同 80/70。

**未重复 full verify**：按 brief 只跑受影响的 3 个测试类并审计 EV-0320。EV-0320 是后端 `verify`，不包含合同 `commands.test` 的 `pnpm -w test`；B1b 无前端改动，任务级证据可接受，但 **M03 模块评审必须使用覆盖合同 test/coverage/E2E 的全量 EV，不能复用 EV-0320**。

## V9 SQL：expand→contract 与 ADR 对照

- **顺序正确**：`ALTER TABLE workspace ADD COLUMN department_id ... / visibility ... NULL`（第 68–73 行）→ 回填 `PERSONAL→PRIVATE`（第 98–101 行）、`TEAM→TENANT`（第 103–106 行）→ `MODIFY visibility ... NOT NULL`（第 110–111 行）→ `ADD CONSTRAINT chk_workspace_scope`（第 114–119 行）。与 ADR §6.2 的 1/2/3 步一致。
- **无默认值绕过**：`visibility` 新增和 `MODIFY` 都没有 `DEFAULT`；若回填漏行，`MODIFY NOT NULL` fail-closed，不会把未知数据静默变成某个可见性。MySQL 非严格模式下空串也会被 CHECK 拒绝。
- **CHECK 严格一致**：V9 第 116–118 行与 ADR-0018 §6.2 的三段谓词逐字一致（PERSONAL+PRIVATE+无部门；TEAM+TENANT+无部门；TEAM+DEPT/CUSTOM+有部门），没有保留 `visibility IS NULL` 分支。
- **未触碰 M02 数据和其它表**：V9 只新增 `department`、`workspace_member`、`app_user.department_id`/索引/FK、`workspace.department_id`/`visibility`/索引/FK/CHECK，以及权限字典和角色回填；没有改 M02 列类型、删除列或重建表，也没有修改 `department`/`workspace_member` 之外的 M03 对象。
- **权限回填**：`department.read` 写入全局 `permission` 并回填给 `TENANT_ADMIN`/`MEMBER` 角色；`platform.department.manage` 只回填 `platform` 租户的 `PLATFORM_ADMIN`；不创建 `department.manage`。ADR §4 表格要求 `PLATFORM_ADMIN` 不持有 `department.read`，`RoleDefinitions.PLATFORM_ADMIN_PERMISSIONS` 也只含平台级权限且不含 `department.read`，迁移与开通逻辑一致。
- **无 V10、worker 版本 9**：迁移目录只有 V1–V9；`SchemaVersionChecker.EXPECTED_SCHEMA_VERSION = 9`，`SchemaVersionCheckerTest` 以 9 为期望值并通过。
- **注释**：文件头记录 V9 未发布、已应用旧版 V9 的环境必须 V10、禁用 `flyway repair`，以及 ADR §6.3 的停写/排空/备份前提；中文注释符合规范。

## 迁移语义测试：不是空库直跑

- **真实 V8 起点**：`V9MigrationTest.prepareSchemas` 先 `migrate(..., "8")`，再 `seedM02Data`（platform/A/B 租户、6 个账号、5 个角色、3 条基线 role_permission、4 个 M02 空间），然后 `migrate(..., "9")`，最后 `seedM03Data`。测试 Javadoc 明确“空库直接应用 V9 通过不算完成”。
- **回填断言**：四个 M02 空间的 visibility 分别为 PRIVATE/TENANT/PRIVATE/TENANT；全表 `visibility IS NULL` 计数为 0；`IS_NULLABLE='NO'`；列注释含“TENANT/历史”。这些值只能来自 V9 回填，证明不是 seed 直接写入。
- **严格 CHECK 断言**：`CHECK_CLAUSE` 含 PRIVATE/TENANT/DEPT/CUSTOM 且不含 `visibility is null`；实际插入测试拒绝 `TEAM+TENANT+部门`、`TEAM+PRIVATE`、`TEAM+DEPT+无部门`、`TEAM+CUSTOM+无部门`、`PERSONAL+PRIVATE+部门`、`TEAM+PUBLIC` 六种非法组合。
- **显式 NULL 拒绝**：`insertTenantAWorkspace(..., visibility=null, ...)` 断言 `DataIntegrityViolationException`，消息同时含 `visibility` 与 `cannot be null`，不是 catch 吞掉或跳过。
- **权限/角色回填**：`department.read` 与 `platform.department.manage` 各 1 条；A/B 的 TENANT_ADMIN/MEMBER 各有 `department.read`；platform 的 PLATFORM_ADMIN 没有 `department.read`；platform 的 PLATFORM_ADMIN 有 `platform.department.manage`，A 的 TENANT_ADMIN 与 B 的 MEMBER 没有；`department.manage` 计数 0。
- **FK/CHECK/唯一键**：跨租户 `app_user.department_id`、`workspace.department_id`、`workspace_member` 的 workspace/user/granted_by 外键均按约束名断言拒绝；`department` 的 ACTIVE/DISABLED 与 `disabled_at` 成对约束；跨租户同名部门允许、同租户大小写不敏感重名拒绝；`workspace_member` 复合主键重复拒绝。
- **M02 数据可读**：租户 3、账号 6、角色 5、4 个空间与团队空间名称 `Acme Team` 均保持；合法组合（历史 TENANT、DEPT、CUSTOM、PERSONAL）在 V9 后仍可写。
- **断言质量**：非法路径统一用 `assertRejected(constraintName, ...)` 断言 `DataAccessException` 且错误消息包含具体约束名，避免被其他约束误判为通过；没有 `@Disabled`/`assumeTrue`/空断言。

## 回滚演练

- **同容器第二 schema**：`riccon_rollback` 由 root 创建，独立 Flyway 以同样 V1–V8 → seed M02 → V9 构造，随后执行 `dropV9Objects()`；第二个 schema 是迁移状态的逻辑副本，测试 Javadoc 明确说明这不是物理备份，避免在一次性容器里做备份恢复。
- **SQL 与 ADR §6.3 一致**：依次 `DROP TABLE workspace_member`、`workspace` 的 FK/索引/CHECK/两列、`app_user` 的 FK/索引/列、`DROP TABLE department`、删除两条 `role_permission`、删除两条 `permission`，顺序与 ADR 文本一致。
- **断言完整**：`department`/`workspace_member` 表不存在；`workspace.department_id`/`visibility` 与 `app_user.department_id` 列不存在；`chk_workspace_scope`、两个 FK、两个索引不存在；两条新权限与对应 `role_permission` 清空；基线 `workspace.read` 仍在，`role_permission` 总数为 3（种子的三条 M02 基线）；A/B 4 个空间、6 个账号、`Acme Team` 名称仍可读。
- **Flyway 状态**：维护账号语义删除 V9 历史行后，`MAX(CAST(version AS UNSIGNED))=8`；`Flyway.info().current()` 为 `8`，`pending()` 含 `9`。测试注释写明非生产库重置、停 API/Worker 写入、排空旧实例、checksum 处理，以及不使用 `flyway repair`；代码中没有任何 `repair` 调用。
- **未覆盖**：回滚 schema 未 seed V9 之后的 M03 数据（部门/空间/DEPT/CUSTOM 行），M03 数据回滚时被整表删除的语义未单独演练；生产回滚演练仍按 ADR 归 M09（B-13）。这是剩余风险，不是 B1b 验收缺口。

## 追认夹具

- **`WorkspaceScopeTest`**：原 `nullVisibilityWorkspace_isNotOverExposed` 被替换为 `nullVisibilityWorkspace_isRejectedAfterTightening`，对 TEAM 与 PERSONAL 两种 NULL 种子分别断言 `DataAccessException` 且消息含 `visibility`；这是 V9 收紧后的正确 contract 期负向断言，不是弱化。其余 15 个用例保留，仍覆盖历史 TENANT 全租户可见且不可转换、PERSONAL 仅 owner、DEPT/CUSTOM 成员、部门停用/移出、403/404 边界；测试数保持 16。未删除其他测试或断言。
- **worker `TestDataFactory`**：只把个人空间 INSERT 改为显式写 `department_id=NULL, visibility='PRIVATE'`，与 B3 生产写入方一致；只改 `src/test`，没有生产代码、跳过测试或放宽断言。worker 其他 workspace 种子只此一处。
- **写范围**：`f598a81` 已把 `WorkspaceScopeTest` 与 worker `TestDataFactory` 扩入 TASK-0045 `write_scope`；`git show --stat 4648bc3` 的五个文件全部落在当前 write_scope（`migration/**` 覆盖新测试类）。`4648bc3` 未改生产服务/前端/config/contracts/威胁模型，未新增 V10。

## 边界、状态与规范

- **改动边界**：`git diff --name-status 6ab90bd..4648bc3` 的代码面只有 V9 与 4 个测试/夹具文件；`.riccon` 变更来自任务启动与证据提交。未触碰 OpenAPI、ARCHITECTURE、PRODUCT、AGENTS、威胁模型、部署文件。
- **任务状态**：`TASK-0045.status=review`、`end_commit=4648bc3`、`evidence=[EV-0320]`；builder 未自行标 done，符合“Builder 只移到 review”。EV-0320 的 commit 是 HEAD 祖先；工作区干净；`state validate` 0 errors/0 warnings。当前 `state.json` 的 gate 失败原因是旧快照（TASK-0045 未 done、无模块 review、旧 write_scope 告警），属于任务评审前的预期状态，不构成本任务 finding；Lead 标记 done 并进入 M03-V 后应重新跑 gate。
- **规范**：新增/修改代码中文注释齐全，复杂迁移步骤和回滚前提有段落说明；`spotless:check` 通过；无 TODO/FIXME、无 `@Disabled`、无测试跳过。
- **未验证**：未跑前端 `pnpm -w test`、E2E、a11y、trivy、k6；B1b 无前端/部署改动，且任务级评审不承担模块 gate，这些属于 M03-V/M03 模块评审。

## Findings 与承接

### F-001（P3，accepted）：V9 收紧后仍保留兼容期注释

- **位置**：`apps/api/src/main/java/com/riccon/api/workspace/domain/WorkspaceRecord.java:15-16`（“visibility 允许为 null，只用于兼容 M02 旧代码…B1b 收紧 V9 前…”）、`:25`（“M02 旧代码写入的行可能为空”）、`:57`（“V9 收紧前仍可能写入 visibility = NULL”）；`apps/api/src/main/java/com/riccon/api/workspace/persistence/WorkspaceJdbc.java:32`（“visibility = NULL 是 M02 旧代码在滚动发布窗口写入的兼容状态”）、`:58`（“visibility = NULL 的团队空间不匹配任何分支”）；`apps/api/src/test/java/com/riccon/api/support/ApiIntegrationTestSupport.java:22`（“Flyway 在上下文启动时按 V1–V8 建库”）。
- **观察**：V9 已收紧为 NOT NULL，这些注释描述的状态不再存在；`effectiveVisibility()` 与谓词中的 NULL 分支只是防御代码。`ApiIntegrationTestSupport` 的 V1–V8 也与实际 V1–V9 不符。行为无害，但会误导后续维护和对迁移状态的判断。
- **期望**：改为“V9 起数据库不允许 NULL；以下为防御性降级/历史说明”，并把 V1–V8 改为 V1–V9。
- **承接**：B1b 注释清理或 M03-V 文档收尾；不阻塞。

### F-002（P3，accepted）：CHECK 断言是 token 级，非法组合未覆盖 PERSONAL 非 PRIVATE

- **位置**：`apps/api/src/test/java/com/riccon/api/migration/V9MigrationTest.java:227-232`（只断言 clause 含四个 token 且不含 `visibility is null`）；`:310-328`（六种非法组合，未覆盖 `PERSONAL+TENANT`、`PERSONAL+DEPT/CUSTOM`）。
- **观察**：实际行为已由六种插入拒绝和显式 NULL 拒绝覆盖，但 CHECK 表达式本身没有逐段断言；若未来有人把 CHECK 改成更宽的表达式而保留这些 token，当前测试不一定发现。PERSONAL 搭配非 PRIVATE 的非法组合由同一 CHECK 分支拒绝，属于测试补强项。
- **期望**：M03-V 增加完整三段谓词断言，并补 `PERSONAL+TENANT`（或 `PERSONAL+CUSTOM`）负向用例。
- **承接**：M03-V 迁移测试补强；不阻塞。

### F-003（P3，accepted）：EV-0320 的 589 含窗口外残留报告

- **位置**：`.riccon/evidence/TASK-0045/EV-0320.json` 与 EV 日志；`apps/worker/target/surefire-reports/TEST-com.riccon.worker.agent.AgentStreamTimingProbeTest.xml`（mtime 07:51:13，1 test）。
- **观察**：EV 窗口 20:14:20–20:22:56 内 fresh 报告为 588 tests / 0 skipped；当前 target 另有 07:51 的 1 个残留报告，合计 589。任务 brief 的 589 与之一致，但模块评审若直接写“EV-0320 589 全部 fresh”会不准确。
- **期望**：M03 模块评审按 **588 fresh + 1 已知残留** 记录，或在 clean target 后重录全量 EV；不要把残留报告算作本次运行的覆盖。
- **承接**：M03 评审包/证据口径；不阻塞。

## 剩余风险与下一步

1. **回滚演练是逻辑副本**：第二个 schema 由相同迁移与 seed 构造，不是物理备份恢复，且未 seed V9 后的 M03 数据；ADR 要求的生产回滚演练在 M09（B-13），M03 评审包应保留该限制说明。
2. **旧 V9 持久环境**：若某持久开发/演示库已应用宽松版 V9，修改后的文件会触发 checksum 失败；按 ADR §6.2 F-009 必须新增 V10 前向迁移并保留旧校验和，不能 `flyway repair`。本任务默认路径未创建 V10，文档已明示，M03 发布前需确认不存在此类环境。
3. **模块 gate 证据**：EV-0320 只覆盖后端 `verify`，不含合同 `pnpm -w test`、E2E、a11y、security、perf；M03-V 必须在最终提交上跑完整合同命令并录独立 EV，不能让任务级 EV 替代模块 `dod_evidence`。
4. **P3 承接**：F-001 注释清理、F-002 迁移测试补强、F-003 证据口径，均可带入 M03-V/M03 评审包，不阻塞 B1b done 与 B5 启动。

**结论重申**：B1b 可以 done（由 Lead 标记）；B5 可以启动；M03-V 在 B5 完成后启动，并承担全量合同验证与部门越权套件。V9 的回填、NOT NULL、严格 CHECK、权限回填、复合外键与回滚 SQL 均有真实 seed 路径的执行证据，不是空库直跑或自报；追认夹具没有削弱测试。
