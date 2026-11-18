---
review_id: REV-TASK-0037-001
module_id: M03
task_id: TASK-0037
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M03_TASK-0038_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [8b561f3, 1505ddb]
head_commit: 99b948c
evidence:
  - EV-0312
findings:
  - id: F-001
    title: "M03 契约字面值未被测试锁定：权限码、12 个审计动作和两个新枚举只测唯一性/可用性，未断言稳定字符串"
    severity: P2
    status: accepted
  - id: F-002
    title: "OrganizationDirectory.listActiveMembers 无分页/上限，B3 可见性热路径必须约束为 isActiveMember，避免整部门列表查询"
    severity: P3
    status: accepted
  - id: F-003
    title: "TASK-0037 evidence 同时保留中间 EV-0311（8b561f3）与最终 EV-0312（1505ddb），任务 done 时只能绑定最终 EV"
    severity: P3
    status: accepted
created_at: 2026-11-18T16:40:00+08:00
---

# TASK-0037 独立评审：M03-B1a 领域词汇与共享接口

## 结论

**verdict: pass**（无开放 P0/P1）。B1a 的最终代码提交是 `1505ddb`；`8b561f3` 的领域词汇和 `1505ddb` 的精简快照都在写范围内，`9dfebab`/`99b948c` 只追加证据与状态。权限矩阵、审计动作/白名单、稳定错误码、只读组织接口、测试与边界均满足 B1a 验收；**B1a 可以 done，B2/B3 可以启动**。

三个 P2/P3 均为回归守卫或后续使用约束，不是当前实现的正确性失败；承接位置见文末。模块 gate 现在**不应**通过：M03 仍缺 B2–B5、V9 收紧（B1b）和 M03-V，本评审只关闭 B1a 任务级验收。

## 独立验证（干净树，HEAD `99b948c`）

| 命令                                                                                                                                                  | 退出码 | 结果                                                                                                                  |
| ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------ | --------------------------------------------------------------------------------------------------------------------- |
| `git status --short`                                                                                                                                  | 0      | 空；HEAD `99b948c`，工作区干净                                                                                        |
| `git show --stat 8b561f3 1505ddb 9dfebab`                                                                                                             | 0      | `8b561f3` 领域词汇；`1505ddb` 仅两个快照精简；`9dfebab` 仅 EV-0312 与状态                                             |
| `git diff --name-status d2eb7bd..1505ddb`                                                                                                             | 0      | 仅 20 个文件（15 个代码/测试 + 5 个 `.riccon` 状态/证据）；无 V9、controller、前端、PRODUCT/AGENTS/contracts/威胁模型 |
| `git diff --name-status 1505ddb..HEAD`                                                                                                                | 0      | 仅 `.riccon` 证据/状态文件；代码与文档零变化                                                                          |
| `./mvnw -q -T1C -pl apps/api -am -Dtest=DomainVocabularyTest,AuditDetailsPolicyTest,RoleDefinitionsTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0      | 定向 19 tests（11+4+4），0 failures / 0 errors / 0 skipped                                                            |
| `./mvnw -q spotless:check`                                                                                                                            | 0      | 通过                                                                                                                  |
| `riccon contract validate --strict --project .`                                                                                                       | 0      | 0 errors, 0 warnings                                                                                                  |
| `riccon state validate --project .`                                                                                                                   | 0      | 0 errors, 0 warnings                                                                                                  |
| EV-0312 `./mvnw -q -T1C verify`                                                                                                                       | 0      | commit `1505ddb`，dirty=false；539 tests / 0 skipped；见下                                                            |

**EV-0312 审计**：`EV-0312.json` 的 `commit=1505ddbe...`、`dirty=false`、`exit_code=0`，输出日志 SHA-256 `772e3d...e47a` 与文件一致。该次运行窗口内 surefire 报告汇总为 **539 tests / 0 failures / 0 errors / 0 skipped**（API 227、worker 263、platform-domain 4、platform-common 33、ArchUnit 12）；jacoco CSV 为 API 行 **90.31%** / 分支 **71.16%**，worker 行 **87.89%** / 分支 **70.77%**，platform-common 行 **98.55%** / 分支 **98.15%**，platform-domain 行 **94.44%**（无分支）。均不低于合同 80%/70% 门槛；`verify` 内含 `jacoco:check`，exit 0 即门槛通过。EV-0311 是 `8b561f3` 的中间全量，已在 `EV-0312` 后失效但按 brief 保留，不能作为 done 的权威证据。

## 权限矩阵

| 角色             | `department.read` | `platform.department.manage` | `department.manage` | 结论 |
| ---------------- | ----------------- | ---------------------------- | ------------------- | ---- |
| `PLATFORM_ADMIN` | 否                | 是                           | 否                  | 通过 |
| `TENANT_ADMIN`   | 是                | 否                           | 否                  | 通过 |
| `MEMBER`         | 是                | 否                           | 否                  | 通过 |

- `PermissionCodes.DEPARTMENT_READ = "department.read"`、`PLATFORM_DEPARTMENT_MANAGE = "platform.department.manage"`（`PermissionCodes.java:55-61`）；没有 `department.manage` 常量。既有权限码仅注释/新增，码值语义未变。
- `RoleDefinitions`（`RoleDefinitions.java:20-62`）：`MEMBER` 和 `TENANT_ADMIN` 只新增 `DEPARTMENT_READ`；`PLATFORM_ADMIN` 只新增 `PLATFORM_DEPARTMENT_MANAGE`，且不持有 `DEPARTMENT_READ`。测试 `RoleDefinitionsTest.departmentPermissionsFollowPlatformAndTenantBoundary` 明确断言两侧边界和 `"department.manage"` 不存在。
- V9 权限字典与回填正确：`department.read` 回填给既有 `TENANT_ADMIN`/`MEMBER`，`platform.department.manage` 只回填给 `platform` 租户的 `PLATFORM_ADMIN`（`V9__organization_and_workspace_scope.sql:107-127`）。新租户开通走 `TenantAdminService.createTenant` 的 `RoleDefinitions.tenantSystemRoles()`（`TenantAdminService.java:122-124`）；空库引导走 `BootstrapAdminRunner` 的 `RoleDefinitions.platformAdminRole()`（`BootstrapAdminRunner.java:113-116`）。两条路径都读取同一份角色定义，没有第二份映射。

## 审计动作与 details 白名单

`AuditActions` 新增的 12 个 M03 动作与 ADR-0018 §5 逐项一致（`AuditActions.java:53-77`）：`department.created/renamed/disabled/enabled/member.added/member.removed/manager.changed` 与 `workspace.created/updated/visibility.changed/member.added/member.removed`。`AuditTargetType.DEPARTMENT` 已加入（`AuditTargetType.java:14`）；既有枚举值和含义未改。

`AuditDetailsPolicy` 的 12 条白名单（`AuditDetailsPolicy.java:42-67`）与 ADR §5 的允许字段逐项核对一致，包括 `targetTenantId`、`departmentId`、`name/previousName`、`status/memberCount`、`previousDepartmentId`、`removedWorkspaceMembers`、`managerUserId/previousManagerUserId`、`workspaceId/visibility/previousVisibility`。未知键仍拒绝（`AuditDetailsPolicy.java:102-104`），敏感键（password/token/content/email/phone 等）在 allowed 检查前拒绝（`:97-101`），值只允许字符串/数字/布尔且 ≤200 字符（`:115-129`）。既有 M02 白名单条目未改。

`AuditDetailsPolicyTest` 不是 `assertNotNull` 型测试：正向用例逐字段提交 Map，负向用例断言具体异常消息（`not allowed`/`forbidden`/`type`/`too long`），并覆盖部门成员名单/邮箱/空间成员名单/内容拒绝（`AuditDetailsPolicyTest.java:120-237`）。11 个测试全部通过。

## 错误码

`ErrorCodes.RICCON_DEPT_409` 精确为 `"RICCON-DEPT-409"`（`ErrorCodes.java:36`），与 ADR-0018 §7.3 一致；`RoleDefinitionsTest.departmentConflictCodeUsesStableDeptDomain` 断言稳定值。既有错误码只增不改，码值与语义未变。

## OrganizationDirectory 接口边界

`OrganizationDirectory`（`OrganizationDirectory.java:17-57`）只有四个只读方法：`findDepartment`、`findDepartmentOfUser`、`listActiveMembers`、`isActiveMember`。全部方法显式接收 `TenantId`；没有 workspace/chat/file/report/knowledge 类型依赖，也没有实现类（`grep` 仅找到接口本身），B2 实现、B3 消费的依赖方向与 ADR §8 一致。

`DepartmentSnapshot`（`DepartmentSnapshot.java:17`）和 `DepartmentMemberSnapshot`（`DepartmentMemberSnapshot.java:15`）都是 record，字段只有 UUID、String、枚举，无集合/可变对象，构造后不可变；不包含成员数、邮箱、角色、最近登录时间或业务内容，符合 ADR §1.4/§7.2。`DepartmentSnapshot` 不含 tenantId 是显式 TenantId 参数下的最小化设计，不构成跨租户绕过：实现必须按传入 TenantId 查询。

## 边界、注释与状态

- 代码边界：`d2eb7bd..1505ddb` 只改 `platform-domain` 主源码、`RoleDefinitions`、`AuditDetailsPolicy`、`ErrorCodes`、`organization/**` 和两个授权测试；**未碰** V9、controller、admin/controller、前端、`docs/PRODUCT.md`、`AGENTS.md`、`contracts`、威胁模型。
- 注释：新增/变更的 public 类型、接口方法和枚举字段均为中文 Javadoc；审计/权限/租户边界有中文说明；没有生产 mock、TODO 占位或注释掉的代码。
- 测试没有删除、跳过或降低断言：diff 只新增 147 行并替换 1 行旧断言（旧 `doesNotContain` 被扩展），无 `@Disabled`、`assumeTrue` 或 skip 标记；定向测试与 EV-0312 均 0 skipped。
- 状态：`TASK-0037` 仍为 `review`，未设置 `end_commit`；EV-0312 的 `1505ddb` 是 HEAD `99b948c` 的祖先；`EV-0311` 保留但已被 EV-0312 取代。`TASK-0038` 的本评审证据在 review 文件提交后由 `evidence run` 记录。

## Findings 与承接

### F-001（P2，accepted）：M03 契约字面值缺少测试锁定

- **位置**：`platform/platform-domain/src/test/java/com/riccon/platform/domain/DomainVocabularyTest.java:52-80、93-103、106-112`；`apps/api/src/test/java/com/riccon/api/identity/domain/RoleDefinitionsTest.java:41-58`；`apps/api/src/test/java/com/riccon/api/audit/domain/AuditDetailsPolicyTest.java:120-237`。
- **观察**：`DomainVocabularyTest` 只做枚举非空/去重与少数既有状态值的字面断言；新权限码、12 个审计动作名、`WorkspaceVisibility`/`DepartmentStatus` 的值没有字面值测试。`RoleDefinitionsTest`/`AuditDetailsPolicyTest` 都通过常量引用，若常量值被误改，测试仍会通过。**当前源代码值与 ADR 一致**（见上），因此不是当前 acceptance 失败。
- **期望**：在 B2/B3 或一个小测试任务中补字面值断言，例如 `department.read`、`platform.department.manage`、12 个动作名、`PRIVATE|TENANT|DEPT|CUSTOM`、`ACTIVE|DISABLED`；需 Lead 把 `platform/platform-domain/src/test/**` 加入相应任务写范围（B1a 当前写范围只含主源码）。
- **理由**：这些字符串是权限、审计查询、迁移 CHECK 和 OpenAPI 的稳定契约，属于高风险词汇；当前值正确，但没有回归护栏。

### F-002（P3，accepted）：`listActiveMembers` 的使用约束

- **位置**：`apps/api/src/main/java/com/riccon/api/organization/OrganizationDirectory.java:37-47`。
- **观察**：`listActiveMembers` 返回整部门成员列表，无分页/上限；接口 Javadoc 说 B3 通过它读取成员关系。如果 B3 的 `requireAccessible`/`requireManageable` 每次请求调用它，会造成整部门列表加载和大部门性能风险。
- **期望**：B3 任务卡明确：可见性/管理能力判定使用 `findDepartmentOfUser` + `findDepartment` + `isActiveMember`；`listActiveMembers` 只用于 B2 的成员选择或分页端点，且实现应加上限。当前 B1a 只有接口，没有实现，不构成本任务失败。

### F-003（P3，accepted）：EV-0311 与 EV-0312 的权威性

- **位置**：`.riccon/tasks/TASK-0037.json:25-28`；`.riccon/evidence/TASK-0037/EV-0311.json`；`EV-0312.json`。
- **观察**：task evidence 同时列出 EV-0311（中间 `8b561f3`）与 EV-0312（最终 `1505ddb`）。brief 要求保留 EV-0311，但若 done/后续评审误绑 EV-0311，会把旧快照代码当作最终验证对象。
- **期望**：B1a done 和 M03 任务级记录只引用 EV-0312；EV-0311 标注 superseded/中间证据。模块级 tests/coverage 仍必须用 M03-V 的全量 EV，不能用本任务级 EV 替代。

## 剩余风险与未验证项

- **未重复 full verify**：按 brief 要求只审计 EV-0312 的 539 tests/覆盖率和本任务的定向测试；未在本轮重新跑 5–10 分钟全量。EV-0312 的日志哈希、commit、dirty=false 与 surefire/jacoco 报告已核对。
- **未跑 E2E/a11y/security**：B1a 没有 UI、端点或迁移变更，这些属于 B2/B5/M03-V；本轮不把它们当作 B1a 证据。
- **未跑模块 gate**：M03 仍处于 in_progress，B2–B5/V 未完成；现在运行 `gate check --module M03` 应失败，不应以 B1a 任务级通过替代模块 gate。
- **新租户/空库落库的集成断言**：代码路径已确认（`TenantAdminService`/`BootstrapAdminRunner` 均读 `RoleDefinitions`），但没有专门的集成测试断言 `role_permission` 实际写入；建议 B2/B5 的租户/部门集成测试顺带覆盖，不影响 B1a done。

## 是否可进入 B2/B3

**可以。** B1a 已提供 B2 所需的角色/权限、审计动作、details 白名单、`RICCON-DEPT-409` 与 `OrganizationDirectory` 接口；B2 可以开始部门后端，B3 可以按 `OrganizationDirectory` 实现空间可见性。B2 任务卡需按 REV-TASK-0033-002 的 F-016 合并验收到 ≤6，并把 F-001 的字面值测试和 F-002 的 `isActiveMember` 使用约束写入任务卡；B3/B4 仍需遵守 ADR-0018 §8 的依赖顺序，B1b 在 B4 后收紧 V9。

**B1a 可 done**（由 Lead/Builder 按流程将 `TASK-0037` 置 done 并设置 `end_commit=1505ddb`）；本 verifier 不代为修改任务状态或标记 done。
