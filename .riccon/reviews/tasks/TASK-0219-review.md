---
review_id: REV-TASK-0219-001
module_id: M06
task_id: TASK-0219
reviewer:
  agent: riccon/verifier
  session: ses_df1084040ffe3EPc00RRm2GBLn
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 7f8cedc68fc4e3d1a6a7ce00d45ee67ea2c9edb1
  - 15049563681272467e7d6506662cfe5d952d2a49
  - 96f6033942a93a08dc986ded46fc9abe586e3250
head_commit: 15049563681272467e7d6506662cfe5d952d2a49
baseline_commit: 7f8cedc68fc4e3d1a6a7ce00d45ee67ea2c9edb1
review_scope: >-
  M06 T01（TASK-0219）实现独立任务评审：V15 additive 迁移、workflow 词汇/权限/角色、审计写入与读取白名单、
  平台租户 404 守卫、关闭态启动观测、schema 15/Hikari/RICCON_WORKFLOW_* 联动、契约测试与 J1/J2 回归。
  不替代 M06 模块 gate、Product 预验收或用户产品验收。
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: riccon/builder-b1
    due: "M06 gate 前（TASK-0229/T09 实现前）"
    title: "审批审计 decisionComment 被 200 字符上限拒绝，与契约/ADR 的 500 字符不一致"
    summary: >-
      `apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:331` 已允许
      `workflow.approval.decided.decisionComment`，但全局 `MAX_VALUE_LENGTH = 200`（同文件 `:491`，
      `:713-717` 拒绝超长字符串）。契约 `WorkflowApprovalDecisionRequest.comment`
      （`contracts/openapi.yaml:8897-8899`、条件必填 `:8911-8914`）与
      `WorkflowApproval.decisionComment`（`:8646-8648`）均允许 500；ADR-0024 §8.1（第 591 行）和
      V15 `workflow_approval.decision_comment VARCHAR(500)`（`V15__workflow_studio.sql:230`）也允许 500。
      观察：201–500 字符的合法拒绝理由会在 `AuditEvent.create` 的 details 校验中抛
      `IllegalArgumentException("audit detail value is too long: decisionComment")`。若 T09 按 ADR 同事务写审计，
      审批会因审计写失败整体回滚。期望：该键使用动作级 500 上限，或明确“审批记录保留全文、审计按已声明策略截断”，
      并补 201/500 字符用例。T01 当前没有审批写入端点，风险尚未在生产路径暴露。
    accepted_reason: >-
      T01 的白名单已预留该键，且审批写入由 T09 实现；本任务迁移、404 守卫、启动审计、权限回填、契约与回归
      测试均不受影响，故作为有界 residual 接受，不阻塞本任务 done。T09 完成前必须修复或明确截断策略并补测试；
      若 U-05 最终改为 `commentSha256`，需同步白名单与 AC-16 文案，不能静默沿用当前 200 字符行为。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:331,491,713-717；
      contracts/openapi.yaml:8897-8914,8646-8648；docs/adr/ADR-0024-m06-workflow-orchestration.md:591；
      apps/api/src/main/resources/db/migration/V15__workflow_studio.sql:230
  - id: F-002
    severity: P3
    status: accepted
    owner: riccon/builder-b1
    due: "M06 gate 前"
    title: "四个兼容契约测试 Javadoc 仍写 1.5.0-m05b，与已更新的 1.6.0-m06 断言不一致"
    summary: >-
      `ConnectorContractM05bTest.java:11,13`、`KnowledgeContractM05Test.java:11,13`、
      `ModelContract108aTest.java:13`、`ModelContract108bTest.java:13` 的类注释仍写
      `1.5.0-m05b`，而同一文件断言已改为 `1.6.0-m06`。仅文档一致性，不影响测试结果或生产行为。
    accepted_reason: >-
      注释滞后不改断言、契约或运行行为；这些文件均在 T01 追认写范围内，可在 M06 gate 前随文档清理一次修正。
    evidence: >-
      apps/api/src/test/java/com/riccon/api/contract/ConnectorContractM05bTest.java:11,13；
      apps/api/src/test/java/com/riccon/api/contract/KnowledgeContractM05Test.java:11,13；
      apps/api/src/test/java/com/riccon/api/contract/ModelContract108aTest.java:13；
      apps/api/src/test/java/com/riccon/api/contract/ModelContract108bTest.java:13
reviewed_evidence:
  - EV-1018
  - EV-1019
evidence:
  - EV-1018
  - EV-1019
created_at: "2026-11-25T03:24:00+08:00"
---

# REV-TASK-0219-001：M06 T01 契约/迁移/审计/平台 404 独立评审

## 结论

**Pass（带 residual）**。任务卡 6 条验收与设计依据逐项核对通过：V15 additive 迁移在 MySQL 8.4.13
Testcontainers 上正向/触发器拒绝/DML 二次执行/应急 drop/ fresh install 全部通过；workflow 权限/角色回填、
内置模板种子、审计 API/worker 白名单、平台租户 404 守卫、关闭态启动观测、schema 15/Hikari 16/restore-drill
联动均落地；独立重跑受影响测试全部 0 failures/0 errors/0 skipped，J2 相关 API 回归与既有契约测试通过；
J1 全量 E2E 未跑（见“未验证”）。
无 open P0/P1；保留 1 个 accepted P2（F-001：审批审计理由 500 字符上限）和 1 个 accepted P3
（F-002：兼容测试 Javadoc 版本号滞后），均绑定 owner/due。按任务要求未运行 `gate check --module M06`，
未修改生产源码/测试/迁移/契约/PRODUCT/AGENTS/.riccon 状态，也未把任务标 done。

## 评审范围与提交

| 项        | 值                                                                                                                                                                                     |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| baseline  | `7f8cedc68fc4e3d1a6a7ce00d45ee67ea2c9edb1`（M06 设计评审关闭）                                                                                                                         |
| 代码/取证 | `db38f09`（T01 feat，58 文件）→ `86f631b`（Lead 追认 9 个兼容测试 + worker 白名单/Reader scope）→ `1504956`（worker 审计白名单修复，5 文件）→ `96f6033`（EV-1019 取证与任务转 review） |
| 任务卡    | `.riccon/tasks/TASK-0219.json`：32 条 write_scope，status=review，end_commit=1504956                                                                                                   |
| 设计依据  | ADR-0024 §3/§5/§6/§7/§8/§10、PRODUCT §12、ARCHITECTURE M06、REV-M06-ARCH-003、REV-M06-OPS-004                                                                                          |
| 范围结论  | `git diff --name-only 7f8cedc..1504956` 无 `contracts/`、`docs/PRODUCT.md`、`AGENTS.md`、ADR/ARCHITECTURE 改动；生产/测试/CI/restore 改动均命中任务卡 scope；无其他模块改动            |

## 迁移 V15（MySQL 8.4.13）

- **9 张表齐全**：`workflow_definition`、`workflow_version`、`workflow_run`、`workflow_node_run`、
  `workflow_approval`、`workflow_approval_assignee`、`workflow_tool_invocation`、`workflow_template`、
  `workflow_user_template`。FK 覆盖 tenant/app_user/department/workflow_definition/workflow_version/
  workflow_run/workflow_approval/mcp_tool；父子索引与租户前缀符合 ADR-0012/0024；`workflow_user_template`
  为 M06b 预留。
- **不可变版本**：`workflow_version` 的 `BEFORE UPDATE`/`BEFORE DELETE` 触发器最后创建，`SIGNAL 45000`
  拒绝修改；应用层无更新/删除路径。无 down migration。
- **model_invocation**：两个可空 `workflow_run_id`/`workflow_node_run_id` 以 INSTANT 追加；新增
  `idx_model_invocation_workflow (tenant_id, workflow_run_id, started_at)` INPLACE/LOCK=NONE；未改
  `purpose` CHECK、未加 FK/CHECK。
- **权限/角色回填**：6 个 `workflow.*` 权限；存量 `TENANT_ADMIN` 6 个、`MEMBER` read/run/approve；
  存量租户创建确定性 `WORKFLOW_EDITOR`（`SHA-256('role:WORKFLOW_EDITOR:'+tenant_id)` 前 16 字节）并回填 5 个，
  `platform` 排除；新租户由 `RoleDefinitions.tenantSystemRoles()` 创建。`manager_user_id` 未被任何授权代码使用。
- **内置模板**：`market-research` 固定 UUID `01990000-0000-7000-8000-000000000601`、`BUILT_IN`、
  version=1、7 节点/6 边、JSON 合法。模板工具节点 `toolId=null` 是已知限制（T03/T14），不作为 T01 失败。
- **幂等/应急 drop**：所有 DML 使用 `NOT EXISTS`/确定性 ID；`V15MigrationTest` 二次执行 INSERT 后计数不变；
  无数据时按 FK 逆序 drop 成功并回到 schema 14，有 definition/version 数据时判定 SQL 拒绝且数据保留。
- **测试**：`V15MigrationTest` 7/7 通过（0 failures/0 errors/0 skipped，23.7s），覆盖正向、触发器拒绝、
  DML 幂等、无数据 drop、有数据拒绝、fresh install 与 RoleDefinitions 对齐。未覆盖“仅有 workflow 审计行、
  无业务数据”的应急 drop 拒绝路径；由 T16b 演练补（见剩余风险）。

## 审计

- **API 白名单**：16 个 `workflow.*` 动作全部登记；details 只含 ID/枚举/计数/脱敏摘要；`workflow.created`
  来源、`published` 范围/部门、`run.*`、`admin_read`、`approval.requested/decided`、`feature.changed`
  均有封闭枚举或 nullable 规则；未知动作非空 details fail-closed。
- **worker 白名单**：登记本任务要求的 `workflow.run.started/completed/failed/cancelled` 与
  `workflow.approval.requested`；与 API 共享动作的允许键、status/previousStatus/actionKind 枚举、
  failedNodeId/reason/targetToolName nullable 语义对称；API-only 动作在 worker fail-closed；
  `mcp.tool.invoked` 两端追加可选 `workflowRunId`/`workflowNodeId` 且允许显式 null，J2 调用不受影响。
- **workflow.feature.changed**：`instanceId`/`observedAt` 强制；`source=DEPLOYMENT_CONFIG`、`enabled`
  为布尔；`reason` 可选；`AuditDetailsReader` 与写入端同一策略，round-trip 测试通过。关闭态启动写
  platform/SYSTEM 审计、失败只记 `RICCON-WORKFLOW-001` 日志、不阻断启动（单元 + 2 个集成用例通过）。
- **测试**：worker `AuditDetailsPolicyTest` 17/17、API `AuditDetailsPolicyTest` 12/12、
  `WorkflowFeatureStartupAuditorTest` 6/6、`WorkflowFeatureDisabledStartupTest` 2/2 通过。
- **F-001**：API `decisionComment` 仍受全局 200 字符限制，而契约/ADR/V15 允许 500；当前无端点，T09 前必须
  修复或明确截断。详见 finding。

## 平台租户 404

- `WorkflowPlatformTenantFilter` 位于 Spring Security 链、`TenantContextFilter` 之后；
  `SecurityConfig` 用 `addFilterAfter(workflowPlatformTenantFilter, TenantContextFilter.class)`，在 MVC
  `PermissionInterceptor` 之前短路。4 个前缀 `/api/v1/workflows`、`/workflow-runs`、`/workflow-approvals`、
  `/workflow-templates` 对 `tenantId=platform` 返回 404 `RICCON-TENANT-404`；未认证主体放行给安全链；
  非 platform 主体继续走 RBAC；路径边界/context path 已覆盖。
- 单元测试 5/5 通过（四前缀、非 platform、未认证、前缀边界、context path）；`WorkflowFeatureDisabledStartupTest`
  通过真实安全链证明平台管理员得到 404 而不是 403，验证了过滤器顺序。
- 未认证工作流端点未在 T01 做 HTTP 级 401 断言（单元只断 pass-through）；由 T05/T16a 端点/安全套件补。

## 权限与角色

- `PermissionCodes` 新增 `workflow.read/edit/publish/run/approve/manage`；`RoleCodes.WORKFLOW_EDITOR`；
  `RoleDefinitions` 新租户三角色：`TENANT_ADMIN` 6、`MEMBER` 3、`WORKFLOW_EDITOR` 5（不含 manage）；
  `PLATFORM_ADMIN` 不持有任何 workflow 权限。
- V15 存量回填与确定性角色 ID 通过迁移测试；`platform` 不创建 WORKFLOW_EDITOR、平台管理员无 workflow 权限。
  `RoleDefinitionsTest` 5/5、`TenantIsolationTest` 12/12、`AuditVocabularyTest` 6/6、`RoleCodesTest` 4/4 通过；
  `manager_user_id` 只作为 DEPT 发布范围约束，不授予编辑/发布/管理能力。

## 配置、schema 与联动断言

- API/worker `application.yml` 均写入 ADR §10.1 的 23 个 `RICCON_WORKFLOW_*` 键（含 enabled/disabled-reason、
  上限/超时/并发/结果大小/claim/心跳/租约/宽限/保留）以及 `RICCON_DB_MAX_ALLOWED_PACKET`；worker Hikari
  默认 `RICCON_DB_POOL_SIZE:16`；`SchemaVersionChecker.EXPECTED_SCHEMA_VERSION=15`。
- 独立核对数字：迁移文件 15 个、V15 新增 9 张表、手工重放建表 57 张、Flyway 口径 58 张（含
  `flyway_schema_history`）、触发器 4 个（V6 审计 2 + V15 workflow_version 2）。`ApiApplicationTests` 9/9
  通过；`SchemaVersionCheckerTest` 9/9 通过；`V10/V11/V12` 迁移测试分别 11/16/13 通过；
  `M04V2VerifierBoundaryTest` 4/4 通过（schema=15）。
- `restore-drill.mjs` 自检通过（source/target fixtures 58/4/15、broken 57/3/11）；`ci.yml` 的
  `CREATE TABLE`/`CREATE TRIGGER` 推导值与硬编码 4 哨兵一致。`docs/RUNBOOK.md` 仍是 V14/49/2，按 ADR §3.5
  由 T15 在 M06 gate 前更新，不是 T01 失败。

## 契约测试

- `WorkflowContractM06Test` 6/6 通过：`1.6.0-m06`、117 paths、23 个 M06 operationId/permission/CSRF/
  Idempotency-Key；`WorkflowEdge.branch` 为字符串枚举 `["TRUE","FALSE",null]`；`actionKind`/`targetNodeKey`、
  `dataScopeSummary`、`updatedAt`/counts、`inputSummary`、REJECT 条件必填 comment（minLength=1/maxLength=500）、
  M06b deferred 只读字段均存在。
- 兼容测试版本断言已更新为 `1.6.0-m06`：`ConnectorContractM05bTest` 11/11、`KnowledgeContractM05Test`
  10/10、`ModelContract108aTest` 1/1、`ModelContract108bTest` 1/1 通过；F-002 仅记录四个测试 Javadoc
  仍写 1.5.0-m05b。

## 回归与范围

- `ResearchTaskApiTest` 7/7、`ApprovalApiTest` 4/4 通过，J2 阶段/审批/产物入口未变；本次没有修改
  J1/J2 生产代码，J1 全量 E2E 未跑（由模块 gate/T16a 承担）；M04V2 边界通过。`mcp.tool.invoked` 新增键只放宽白名单并允许 null，没有改变 J2 写入/读取路径。
- 范围审计：生产/测试/CI/restore 改动均命中 32 条 write_scope；无 `contracts/`、`docs/PRODUCT.md`、
  `AGENTS.md`、ADR/ARCHITECTURE 或 M06b 改动；未发现其他模块源码变更。

## 证据复核与独立重跑

- `EV-1018`：commit `db38f09a4a1d84dd3bf8cb914319de5f0cd739bc`，`manual=false`、`dirty=false`、exit 0，
  output 164,943 bytes，sha256 `144e79dd…cd3`（与 JSON 一致）；命令覆盖迁移/契约/守卫/启动审计/回归。
- `EV-1019`：commit `15049563681272467e7d6506662cfe5d952d2a49`，`manual=false`、`dirty=false`、exit 0，
  output 164,966 bytes，sha256 `bceeb501…4f4f`（与 JSON 一致）；在 EV-1018 基础上加入
  `AuditDetailsPolicyTest`，覆盖 worker 白名单修复。
- 独立重跑（工作树干净、HEAD 96f6033，代码与 1504956 一致）：
  ```text
  ./mvnw -q -l target/verify-task-0219.log -pl apps/api,apps/worker -am -Dtest='AuditDetailsPolicyTest,V10MigrationTest,V11MigrationTest,V12MigrationTest,V15MigrationTest,WorkflowContractM06Test,ModelContract108aTest,ModelContract108bTest,KnowledgeContractM05Test,ConnectorContractM05bTest,WorkflowPlatformTenantFilterTest,WorkflowFeatureStartupAuditorTest,WorkflowFeatureDisabledStartupTest,ApiApplicationTests,RoleDefinitionsTest,TenantIsolationTest,ResearchTaskApiTest,ApprovalApiTest,M04V2VerifierBoundaryTest,SchemaVersionCheckerTest' -Dsurefire.failIfNoSpecifiedTests=false test
  # exit 0；surefire XML：0 failures / 0 errors / 0 skipped
  ./mvnw -q -pl platform/platform-domain -Dtest='AuditVocabularyTest,RoleCodesTest' test
  # exit 0；6/6、4/4
  ./mvnw -q spotless:check -pl apps/api,apps/worker,platform/platform-domain -am
  # exit 0
  node scripts/ops/restore-drill.mjs --self-test
  # status=passed，6 项检查通过
  ```
- 具体通过数：`V15MigrationTest` 7、`WorkflowContractM06Test` 6、`WorkflowPlatformTenantFilterTest` 5、
  `WorkflowFeatureStartupAuditorTest` 6、`WorkflowFeatureDisabledStartupTest` 2、API/worker
  `AuditDetailsPolicyTest` 12/17、`ApiApplicationTests` 9、`SchemaVersionCheckerTest` 9、`V10/V11/V12`
  11/16/13、`TenantIsolationTest` 12、`RoleDefinitionsTest` 5、`ResearchTaskApiTest` 7、
  `ApprovalApiTest` 4、`M04V2VerifierBoundaryTest` 4。
- `git diff --check 7f8cedc..1504956` 仅报 EV-1018 证据日志中的 Testcontainers 输出尾随空格/EOF 空行，
  不是生产源码问题；`git status` 干净。

## 未验证 / 已知限制（不作为 T01 失败）

- 未运行全量 E2E、浏览器/axe、perf/k6、Trivy/security、coverage 与模块 gate；这些由 M06 后续任务和模块
  gate 承担。T01 无 UI，E2E/a11y 不适用于本任务。
- API 侧 `workflow.run.*`/`workflow.approval.*` 的写入细节尚未直接单测（API `AuditDetailsPolicyTest`
  未覆盖 workflow 动作；worker 侧已覆盖本任务写入动作）；T05/T08/T09 的端点集成测试必须覆盖写入+读取。
- 未认证工作流端点的 HTTP 级 401 未在 T01 断言；T05/T16a 补。
- `V15MigrationTest` 的应急 drop 只验证有 definition/version 数据时拒绝，未单独构造“仅有 workflow 审计行”
  的拒绝；T16b 回滚演练按 §10.4 判定 SQL 覆盖。
- worker 白名单对 `workflow.run.completed.durationMs/nodeCount/resultBytes` 与
  `workflow.approval.requested.assigneeCount` 未设为必填；T06b/T07a 写入时必须带齐，建议其测试断言。
- 本机 Connector Testcontainers `::1` 失败不在 T01；内置模板 `toolId=null` 由 T03/T14 处理；worker 实际
  运行/审批写入在 T06a/T07a。均不构成 T01 失败。
- `docs/RUNBOOK.md` 仍为 V14/49/2；按 ADR §3.5 由 T15 在 M06 gate 前更新。

## 剩余风险

1. **F-001**：审批理由 201–500 字符会在审计 details 校验失败；T09 写入前必须扩展动作级上限或明确截断并补测试。
2. **审计测试面**：API 侧 workflow 动作尚无直接 policy 测试；T05/T08/T09 若只走正常短字段，可能漏掉超长/
   缺失 details。建议至少补一条 API policy 单测与一条 500 字符审批理由集成用例。
3. **未认证 401 与四前缀集成**：过滤器单元已覆盖，端点级只测了 `/api/v1/workflows`；T05/T16a 补。
4. **应急 drop 判定**：仅有 `workflow.*` 审计行时也应拒绝；T16b 演练必须使用独立空栈/备份夹具验证。
5. **`api/config/**` 范围较宽**：T01 实际只改 `AppConfig`/`SecurityConfig` 与 `Workflow*` 配置，未发现越界；
   后续任务不得再把该 glob 当作共享写入区。
6. **RUNBOOK/真实迁移演练**：T15/T16b 前 RUNBOOK 仍 V14；不得把 T01 的 V15MigrationTest 当作生产回滚演练。

## 是否可 done

可 done（**pass**）。任务卡的独立 Verifier 评审条件已满足：V15 迁移在真实 MySQL 8.4.13 上正向/拒绝/
幂等/应急 drop/fresh install 全部通过，平台 404、关闭态启动审计、权限回填、契约与 J2 相关回归均通过；
J1 全量 E2E 由模块 gate/T16a 承担；
无 open P0/P1。F-001（P2）与 F-002（P3）均 accepted 且有 owner/due，F-001 必须在 T09/M06 gate 前闭环。
本评审不标任务 done、不运行 `gate check --module M06`，也不替代 M06 模块 gate、Product 预验收或用户验收。
