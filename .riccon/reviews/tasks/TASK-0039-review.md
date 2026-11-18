---
review_id: REV-TASK-0039-001
module_id: M03
task_id: TASK-0039
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M03_TASK-0040_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [e9057d1]
head_commit: 5ac5abf
evidence:
  - EV-0314
findings:
  - id: F-001
    title: "平台管理员部门成员列表端点未被 DepartmentAdminApiTest 覆盖；停用账号在 HTTP 成员列表与 memberCount 的语义也未被断言"
    severity: P2
    status: accepted
  - id: F-002
    title: "移出成员的跨表 CUSTOM 清理没有失败注入测试证明整体回滚；当前只由 @Transactional 与代码路径保证"
    severity: P2
    status: accepted
  - id: F-003
    title: "并发重复 disable/enable/负责人移交请求可能重复写审计或记录过期的 previousManagerUserId"
    severity: P3
    status: accepted
  - id: F-004
    title: "OrganizationDirectory.listActiveMembers 无分页/上限，且不过滤部门状态；B3 必须用 isActiveMember 做授权谓词"
    severity: P3
    status: accepted
  - id: F-005
    title: "surefire 报告目录残留 07:51 的 AgentStreamTimingProbeTest 报告，naive 汇总得到 550；EV-0314 实际 549"
    severity: P3
    status: accepted
  - id: F-006
    title: "managerDisplayName 通过租户端 GET /departments 返回给普通成员，ADR 7.2 标注“仅管理界面”，需 B5 确认展示范围"
    severity: P3
    status: accepted
created_at: 2026-11-18T18:15:14+08:00
---

# TASK-0039 独立评审：M03-B2 部门后端与 OrganizationDirectory

## 结论

**verdict: pass**（无开放 P0/P1）。B2 的最终代码提交是 `e9057d1`，证据/状态提交是 `efc8d97`；`TASK-0039` 仍为 `review`，`end_commit` 为空，符合“Builder 只到 review”的约定。平台管理员跨租户部门端点、租户只读端点、成员/负责人规则、`OrganizationDirectory` 实现、审计和错误语义均与 ADR-0018 §1.3/§1.4/§2.3/§5/§7.3 及 OpenAPI 对齐；定向测试 11/11 通过，EV-0314 的 549 tests/0 skipped 与 API 覆盖率 90.47%/71.92% 已核对。

**B2 可以 done（由 Lead 按流程标记），B3 可以在 B2 done 后启动。** 两个 P2 与四个 P3 都是回归守卫、失败注入或使用约束，不是当前实现的功能/安全失败；承接位置见文末。**`listActiveMembers` 语义成立**：它只返回启用账号，和 HTTP 成员列表不是同一方法，后者按 OpenAPI `DepartmentMember.status` 包含停用账号、`memberCount` 只计 ACTIVE；两者不冲突。B3 不能用 `listActiveMembers` 当可见性/管理能力授权谓词，必须用 `isActiveMember` + `findDepartment`。

## 独立验证（干净树，HEAD `5ac5abf`）

| 命令/检查                                                                                                    | 退出码 | 结果                                                                                                                                                                         |
| ------------------------------------------------------------------------------------------------------------ | ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git status --short`                                                                                         | 0      | 空；HEAD `5ac5abf`，工作区干净                                                                                                                                               |
| `git show --stat e9057d1 efc8d97`                                                                            | 0      | `e9057d1` 22 个代码/测试文件，3098 insertions；`efc8d97` 仅 EV-0314 日志、EV JSON、state/task                                                                                |
| `git diff --name-status e602c93..e9057d1`                                                                    | 0      | 22 个新增文件，全部在 `admin/department/**`、`department/**` 与两个测试文件内；无 V9、workspace/chat/file/research/run、`config/**`、前端、PRODUCT/AGENTS/contracts/威胁模型 |
| `./mvnw -q -T1C -pl apps/api -am -Dtest=DepartmentAdminApiTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0      | **11 tests / 0 failures / 0 errors / 0 skipped**，55.75 s（surefire 报告 18:09:20）                                                                                          |
| `./mvnw -q spotless:check`                                                                                   | 0      | 通过                                                                                                                                                                         |
| `riccon contract validate --strict --project .`                                                              | 0      | 0 errors, 0 warnings                                                                                                                                                         |
| `riccon state validate --project .`                                                                          | 0      | 0 errors, 0 warnings                                                                                                                                                         |
| `git merge-base --is-ancestor e9057d1 HEAD` / `efc8d97 HEAD`                                                 | 0      | 均为 HEAD 祖先；`TASK-0039.status=review`，`end_commit=null`                                                                                                                 |

**EV-0314 审计**：`commit=e9057d1`、`dirty=false`、`exit_code=0`；日志 SHA-256 `e3eee280...a0962` 与 JSON 一致。该次运行窗口内 fresh surefire 汇总为 **549 tests / 0 failures / 0 errors / 0 skipped**：API 238、worker 262、platform-common 33、platform-domain 4、ArchUnit 12。另有 07:51 的 `AgentStreamTimingProbeTest` 报告 1 个，源码与 git 都不存在，属残留报告，不属于 EV-0314；naive 汇总会得到 550（见 F-005）。覆盖率：API 行 **90.47%** / 分支 **71.92%**，worker **87.89% / 70.77%**，platform-common **98.55% / 98.15%**，platform-domain **94.44%**（无分支）；均高于合同 80%/70%。`apps/api/pom.xml:150-181` 的 `jacoco:check` 在 `verify` 阶段执行，exit 0 即门槛通过。

## 边界、状态与编码规范

- **写入范围**：`e602c93..e9057d1` 只新增 4 个 `admin/department/**` 文件、16 个 `department/**` 文件、`DepartmentAdminApiTest.java` 与 `DepartmentTestSupport.java`；未改 V9、迁移、`workspace/**`、`chat/file/research/run`、`config/**`、前端、PRODUCT/AGENTS/contracts、威胁模型。`efc8d97`/`5ac5abf` 只改 `.riccon` 状态与证据。
- **状态**：`TASK-0039` 仍是 `review`，未设置 `end_commit`；EV-0314 绑定 `e9057d1` 且是 HEAD 祖先。未发现“Builder 自标 done”或提前启动 B3 的状态。
- **编码规范**：新增公开类/方法/字段均有中文 Javadoc；复杂 SQL、租户/审计边界有中文说明；没有生产 mock、TODO/FIXME、注释掉的代码或 `@Disabled`；“成员列表包含停用账号”与“memberCount 只计 ACTIVE”都有行级注释。Spotless 通过。

## 平台管理员跨租户边界

- **路径即租户**：`DepartmentAdminController` 的 `@RequestMapping("/api/v1/admin/tenants/{tenantId}/departments")`（`DepartmentAdminController.java:46-48`）和 `targetTenant` 只从路径读取目标租户（`:267-269`）；所有请求 DTO 只有 `name`/`userId`，没有 tenant 字段，不能由请求体覆盖。HTTP 端点为 ADR §7.1 的 7 组：列表/创建、改名、停用/启用、成员列表/加入、移除成员、负责人移交（`:74-259`）。
- **404/409/403**：`requireActiveTenant` 对不存在租户抛 `NotFoundException`、对非 ACTIVE 租户抛 `ConflictException`（`DepartmentAdminService.java:381-388`）；`requireDepartment`/`requireUser` 都按目标租户过滤（`:398-414`），跨租户部门/账号统一 404，不区分“不存在”与“不匹配”。控制器每个方法都带 `@RequirePermission(PermissionCodes.PLATFORM_DEPARTMENT_MANAGE)`；租户管理员/普通用户没有该权限，实测 403 `RICCON-AUTH-403`。
- **不泄露业务内容/账号列表**：`DepartmentAdminJdbc` 只查询 `department`、`app_user`、`workspace_member`/`workspace`（成员行清理），不读取会话、文件、报告、产物或知识库（`DepartmentAdminJdbc.java:24-30`）。部门响应用 `DepartmentResponse`（id/name/status/managerUserId/managerDisplayName/memberCount/时间），成员响应用 `DepartmentMemberResponse`（id/username/displayName/status），没有 email、角色、最近登录或业务内容；测试 `DepartmentAdminApiTest.java:369-377` 断言成员响应不含 `@`。
- **租户端无部门写端点**：`DepartmentController` 只有两个 GET（`DepartmentController.java:50-78`）；没有创建/改名/停用/成员/负责人写方法。测试 `DepartmentAdminApiTest.java:392-408` 对租户管理员和普通成员的 POST 均断言 403。
- **未发现跨租户 IDOR**：`FIND_DEPARTMENT`、`LIST_DEPARTMENTS`、`LIST_MEMBERS`、`FIND_USER`、`ASSIGN_USER_DEPARTMENT`、`CLEAR_USER_DEPARTMENT`、`DELETE_CUSTOM_MEMBERS` 全部带 `tenant_id = :tenantId`；`PlatformJdbc.updateTenant/queryTenant` 在 SQL 层强制租户条件。跨租户部门改名/移除成员测试均得到 404（`DepartmentAdminApiTest.java:425-435`），跨租户账号加入/移交得到 404（`:216-226`、`:339-343`）。

## 成员规则

- **单主部门**：`addMember` 先读 `app_user.department_id`，已在本部门/其他部门都返回 `DepartmentConflictException`（409 `RICCON-DEPT-409`），再用 `UPDATE ... WHERE department_id IS NULL` 条件更新兜底并发（`DepartmentAdminService.java:273-299`；`DepartmentAdminJdbc.java:131-135`）。测试覆盖“加入—重复加入—加入另一部门”三次状态（`DepartmentAdminApiTest.java:180-211`），并断言部门归属未变。
- **移出当前负责人 409**：`removeMember` 在任何写入前比较 `department.managerUserId`（`DepartmentAdminService.java:317-320`）；测试 `DepartmentAdminApiTest.java:231-253` 先移交再移出，断言 409 且归属保留。
- **负责人只能本部门启用成员**：`transferManager` 要求目标账号 `ACTIVE` 且 `user.departmentId == departmentId`（`DepartmentAdminService.java:353-357`）；同一人重复提交 200 且不写重复审计（`:358-360`）；测试覆盖首次任命、同人幂等、移交、停用账号 409、跨租户账号 404（`DepartmentAdminApiTest.java:292-344`）。一次移交只写一条 `department.manager.changed`，details 含新/旧负责人 UUID，不含 username。
- **CUSTOM 行同事务清理**：`removeMember` 在同一个 `@Transactional` 方法内先 `clearDepartment` 再 `deleteCustomWorkspaceMembers`，后者只删本租户、本部门 `visibility='CUSTOM'` 空间里该用户的 `workspace_member` 行（`DepartmentAdminService.java:313-334`；`DepartmentAdminJdbc.java:143-159`）。测试种了销售部与市场部两个 CUSTOM 空间及两条成员行，移出后销售部 0 行、市场部 1 行（`DepartmentAdminApiTest.java:258-287`），证明没有跨部门清理。**失败是否整体回滚**没有失败注入测试（见 F-002），但代码没有 catch、审计服务也不吞异常，事务边界正确。
- **不得跨租户清理**：`DELETE_CUSTOM_MEMBERS` 外层 `workspace_member.tenant_id = :tenantId`，子查询 `workspace.tenant_id = :tenantId`，`app_user` 清理也带租户条件；`removeMember` 对跨租户账号是 204 幂等无副作用，不泄露账号存在性。测试只覆盖同租户其他部门，跨租户清理是代码审查结论（F-003/F-002 的测试承接）。

## OrganizationDirectory 与 `listActiveMembers`

- **显式 TenantId、无业务内容依赖**：`OrganizationDirectory` 四个方法都接收 `TenantId`（`OrganizationDirectory.java:17-57`）；`DepartmentRepository` 实现只依赖 `DepartmentJdbc`（`DepartmentRepository.java:108-117`）；`DepartmentJdbc` 不 import workspace/chat/file/research/run，只查 `app_user`/`department`。
- **`findDepartmentOfUser` 语义**：SQL `u.status = 'ACTIVE'` + `JOIN department`，账号不存在、停用或未分配部门时为空；不因部门停用而隐藏（快照带 `status`，供 B3 判断）（`DepartmentJdbc.java:60-64`、`156-162`）。测试覆盖停用账号为空、移除后为空（`DepartmentAdminApiTest.java:490-528`）。
- **`listActiveMembers` 语义成立**：实现过滤 `u.status = 'ACTIVE'`，按 displayName、id 升序，返回 `DepartmentMemberSnapshot`（id/username/displayName），停用账号保留归属但不返回（`DepartmentJdbc.java:82-88`、`199-208`）。测试在有 3 名成员、1 名停用时断言列表恰为 2 名启用成员（`DepartmentAdminApiTest.java:509-517`），覆盖了 B1a Javadoc 的“只列当前启用成员”。
- **与 HTTP 成员列表不冲突**：租户端 `DepartmentQueryService.listMembers` 与管理端 `DepartmentAdminService.listMembers` 用的是 `DepartmentRepository.listMembers` / `DepartmentAdminRepository.listMembers`（`DepartmentJdbc.java:66-79`、`DepartmentAdminJdbc.java:103-116`），SQL 明确不按 `u.status` 过滤，注释写明“包含停用账号以便管理员清理归属”；OpenAPI `DepartmentMember` 的 `status` 是显式字段（`contracts/openapi.yaml:2774-2785`），ADR §1.3 只要求 `memberCount` 只计 ACTIVE（`DepartmentAdminJdbc.java:35-41`、`118-122`），ADR §7.2 要求成员列表只含 id/username/displayName/status。因此 **builder 回执里的“成员列表包含停用账号、memberCount 只计 ACTIVE”与 OpenAPI/ADR 一致，和 `listActiveMembers` 不冲突**，也没有 email/角色/最近登录泄露。**没有发现 P1/P2 冲突**；只有“两个列表语义相近、需要在 B3/B5 明确区分”的 P3 文档/测试缺口（F-001、F-004）。
- **授权谓词**：`isActiveMember` 同时要求账号 `ACTIVE`、主部门匹配、部门 `ACTIVE`（`DepartmentJdbc.java:91-97`、`218-226`）；测试在停用账号、移除成员、停用部门后均为 false（`DepartmentAdminApiTest.java:497-552`）。`listActiveMembers` 只过滤账号状态，不判断部门状态；B3 不能用它当可见性/管理能力的唯一谓词，否则停用部门仍可能被当成有成员。这是 B1a F-002 的延续，已记为 F-004，承接 B3 任务卡。

## 审计

- **动作覆盖**：`DepartmentAdminService` 写 `department.created`、`department.renamed`、`department.disabled`、`department.enabled`、`department.member.added`、`department.member.removed`、`department.manager.changed`（`DepartmentAdminService.java:133`、`180`、`208`、`236`、`297`、`333`、`369`），与 ADR-0018 §5 的七个部门动作一致（ADR 使用 `renamed`，不是 `updated`；builder 实际实现遵循 ADR）。
- **平台事件租户/actor/target**：`appendAudit` 把事件 `tenant_id` 写成 `actor.tenantId()`（平台管理员为 `platform`），`actor_type=USER`，`actor_id=平台管理员 UUID`，`target_type=DEPARTMENT`，`target_id=部门 ID`，目标租户只进入 `details.targetTenantId`，与 M02 `TenantUserAdminService` 完全同构（`DepartmentAdminService.java:436-454`；对照 `TenantUserAdminService.java:391-414`）。测试断言 `department.created`/`member.added`/`member.removed`/`manager.changed` 的 `tenant_id=platform`、actor、target 和 `targetTenantId`（`DepartmentAdminApiTest.java:61-74`、`202-210`、`278-286`、`320-325`）。
- **details 白名单**：`AuditDetailsPolicy` 对七个部门动作分别放行 `targetTenantId/departmentId/name/previousName/status/memberCount/userId/previousDepartmentId/removedWorkspaceMembers/managerUserId/previousManagerUserId`（`AuditDetailsPolicy.java:43-59`）；`AuditEvent.create` 在写入前调用 `AuditDetailsPolicy.validate`（`AuditEvent.java:102`），未知键与 PII 键仍 fail-closed。`department.member.added` 未写 `previousDepartmentId`，该字段是 ADR 允许但非必填（用户必然未分配部门），不构成遗漏。
- **无 PII/成员名单**：审计 details 只有 UUID、枚举、部门名、计数；测试断言 `department.member.added` 不含 username、创建事件不含 `@`/`password`、移除事件不含 username（`DepartmentAdminApiTest.java:73-74`、`209-210`、`286`）。`managerDisplayName` 不进审计。
- **审计失败使业务失败**：`AuditService.append` 不捕获持久化异常，`DepartmentAdminService` 不 catch，审计与业务在同一个 `@Transactional` 内（`AuditService.java:30-38`；`DepartmentAdminService.java:112-135` 等）。全局 `FileAuditFailClosedTest` 已验证 API 审计失败会回滚业务；B2 的部门路径没有单独的失败注入测试（F-002）。

## 错误语义

- **409 `RICCON-DEPT-409`**：`DepartmentConflictException` 固定 `HttpStatus.CONFLICT` + `ErrorCodes.RICCON_DEPT_409`（`DepartmentConflictException.java:21-22`；`ErrorCodes.java:36`）。测试覆盖重名、重复加入、加入其他部门、停用部门改名、移除负责人、负责人非启用成员（`DepartmentAdminApiTest.java:83-84`、`153-154`、`193-199`、`244-245`、`336-337`）。
- **404 `RICCON-TENANT-404`**：不存在租户、跨租户部门、跨租户账号加入/移交、普通成员访问其他部门均使用统一 `NotFoundException`（`DepartmentAdminService.java:381-414`；`DepartmentQueryService.java:89-101`）；测试断言 404 code（`DepartmentAdminApiTest.java:422-431`、`223-224`、`342-343`、`383-384`）。`removeMember` 对不存在成员/跨租户账号是 204 幂等，不泄露存在性（`:248-252`、`:433-435`）。
- **403 `RICCON-AUTH-403`**：租户管理员/普通用户调用平台端点由 `@RequirePermission` 拦截（`DepartmentAdminController.java:75` 等），测试断言 403 code（`DepartmentAdminApiTest.java:398-407`）。
- **停用租户 409 `RICCON-CONFLICT-409`**：`requireActiveTenant` 抛 `ConflictException`（`DepartmentAdminService.java:384-386`），测试创建/列表均 409 且 code 正确（`DepartmentAdminApiTest.java:450-455`）。
- **不泄露存在性/名单**：404 detail 统一为 `tenant/department/user not found`；409 detail 只给稳定原因，如“member already belongs to another department”“department manager must be transferred before removal”，不回显成员名单、邮箱或名字；测试断言跨租户账号响应不含对方 username（`DepartmentAdminApiTest.java:225`）。

## 测试与覆盖对账

- **新增 11 个测试**全部来自 `DepartmentAdminApiTest`（`git diff --stat e602c93..e9057d1`：554 行测试 + 306 行 support），测试类 extends 真实 MySQL/HTTP 基座，没有 mock 持久化（`DepartmentAdminApiTest.java:18-25`）。测试方法覆盖：创建/审计、改名幂等、停用/启用幂等、单主部门、跨租户账号 404、移除负责人 409/不存在 204、CUSTOM 清理、负责人移交、租户端角色/部门隔离、目标租户 404/409、OrganizationDirectory。**无 `@Disabled`、`assumeTrue`、skip 标记，无删测/改弱断言**；定向测试与 EV-0314 均 0 skipped。
- **未覆盖的 B2 HTTP 端点**：`GET /api/v1/admin/tenants/{tenantId}/departments/{departmentId}/members` 在 `DepartmentAdminController.java:179-187` 实现，但 `DepartmentAdminApiTest` 只直接调用过租户端 `/api/v1/departments/{id}/members`（`:369-390`）；管理端成员列表没有 200/403/404/409 或响应字段断言。`memberCount` 的 ACTIVE-only 规则也没有在“停用账号仍在部门”的夹具上直接断言（仅在两个启用成员时断言 `memberCount=2`，`:309`）。代码审查显示实现正确（`LIST_MEMBERS` 含停用、`COUNT_ACTIVE_MEMBERS`/`DEPARTMENT_COLUMNS` 只计 ACTIVE），但缺回归护栏，见 F-001。
- **覆盖面**：EV-0314 的 fresh 549 tests 与 0 skip 已核对；API 行/分支 90.47/71.92 高于合同门槛。`verify` 的 Jacoco check 覆盖 API bundle；worker/platform 覆盖率也高于门槛（见上）。没有用任务级定向 EV 替代模块级全量 EV 的问题；本轮未重复 5–10 分钟 full verify，只跑定向测试，符合“不要重复 full verify”的 brief。

## Findings 与承接

### F-001（P2，accepted）：管理员成员列表与停用账号语义缺少测试

- **位置**：`apps/api/src/main/java/com/riccon/api/admin/department/controller/DepartmentAdminController.java:179-187`；`apps/api/src/test/java/com/riccon/api/DepartmentAdminApiTest.java:369-390`（只有租户端 GET）；`DepartmentAdminJdbc.java:103-122`（成员列表含停用、计数只 ACTIVE）。
- **观察**：管理端 `GET /admin/tenants/{tenantId}/departments/{departmentId}/members` 没有测试请求；停用账号仍在部门时，HTTP 成员列表的 `status=DISABLED` 是否出现、`Department.memberCount` 是否仍只计 ACTIVE，都没有断言。实现本身符合 OpenAPI/ADR（代码审查已核对），但这是 B2 唯一未被测试触达的 HTTP 端点，也是 ADR §1.3/§7.2 的显式边界。
- **期望**：在 B3/M03-V 增加：管理端成员列表 200（含启用+停用账号，响应无 email/role/lastLogin）、404 跨租户部门、409 停用租户、403 租户管理员；以及停用账号存在时 `memberCount` 仍只计 ACTIVE。承接：**B3 或 M03-V**。

### F-002（P2，accepted）：跨表 CUSTOM 清理缺少失败注入回滚测试

- **位置**：`apps/api/src/main/java/com/riccon/api/admin/department/service/DepartmentAdminService.java:313-334`；`DepartmentAdminJdbc.java:138-159`；`AuditService.java:30-38`。
- **观察**：`removeMember` 把 `clearDepartment`、`deleteCustomWorkspaceMembers` 和 `department.member.removed` 审计放在同一个 `@Transactional` 方法内；成功路径测试证明两张表同时变化（`DepartmentAdminApiTest.java:258-287`），但没有让清理 SQL 或审计失败并断言 `app_user.department_id` 与 `workspace_member` 同时回滚的测试。全局 `FileAuditFailClosedTest` 只覆盖文件上传审计失败，不覆盖部门路径。当前代码没有 catch，事务边界正确；属于失败注入证据缺口。
- **期望**：M03-V 参考 `FileAuditFailClosedTest` 用 `@MockitoSpyBean` 让 `department.member.removed` 审计失败，或让清理 SQL 失败，断言成员归属与 CUSTOM 行均未变化。承接：**M03-V（B3 可补集成测试）**。

### F-003（P3，accepted）：并发幂等/审计一致性未覆盖

- **位置**：`DepartmentAdminService.java:193-238`（disable/enable）、`:349-371`（manager）；`DepartmentAdminJdbc.java:92-101`。
- **观察**：disable/enable 与负责人移交都是“先读状态、后无条件 UPDATE”。两个并发重复请求可以都通过服务层判断：disable/enable 可能写两条审计；负责人移交的第二个请求可能用过期的 `previousManagerUserId` 写第二条审计。当前串行测试的幂等断言通过，但没有并发测试；不构成当前验收失败。
- **期望**：M03-V 加并发/唯一性用例，或把 UPDATE 改成带 `status <> :status` 的条件更新并在 0 行时返回现有状态；至少登记为已知限制。承接：**M03-V**。

### F-004（P3，accepted）：`listActiveMembers` 无分页且不判断部门状态

- **位置**：`apps/api/src/main/java/com/riccon/api/organization/OrganizationDirectory.java:37-47`；`apps/api/src/main/java/com/riccon/api/department/persistence/DepartmentJdbc.java:82-88`、`:199-208`。
- **观察**：实现按 B1a 接口只过滤 `u.status='ACTIVE'`，不限制返回数量，也不要求 `department.status='ACTIVE'`；`isActiveMember` 才同时要求两者（`DepartmentJdbc.java:91-97`）。B3 的可见性/管理能力热路径若用 `listActiveMembers` 做授权，停用部门仍会被视为“有启用成员”。B1a F-002 已提示此风险，B2 实现了接口但没有加 limit。
- **期望**：B3 任务卡明确：授权用 `isActiveMember`/`findDepartment`；`listActiveMembers` 只用于成员选择或分页端点，如使用则加上限。承接：**B3**。

### F-005（P3，accepted）：surefire 残留报告导致全量计数口径偏差

- **位置**：`apps/worker/target/surefire-reports/com.riccon.worker.agent.AgentStreamTimingProbeTest.txt`（mtime 2026-11-18T07:51:13）；`EV-0314`。
- **观察**：该报告 1 个测试，但源码与 git 均无此类，是历史手工 probe 的残留；fresh 报告为 549，naive 汇总为 550。按同一口径回算，B1a 的 539 很可能也包含这 1 个残留（fresh 538 + B2 11 = 549）。这不是跳测/假绿，但会让“全量测试数”不可复现。
- **期望**：M03 模块 gate 前清理各模块 `target/surefire-reports` 或在证据脚本中按本次运行窗口统计；EV-0314 的权威值按 fresh 549 记录。承接：**Lead / M03 gate**。

### F-006（P3，accepted）：`managerDisplayName` 对普通成员的展示范围

- **位置**：`apps/api/src/main/java/com/riccon/api/department/dto/DepartmentResponse.java`；`DepartmentController.java:50-58`；ADR-0018 §7.2（`managerDisplayName` “仅管理界面展示”）；`contracts/openapi.yaml:2706`。
- **观察**：租户端 `GET /api/v1/departments` 对普通成员也返回共享 `Department` schema，其中含 `managerDisplayName`。该字段是组织元数据、普通成员在成员列表也能看到负责人展示名，不构成跨租户/业务内容泄露；但 ADR 的“仅管理界面”与 API 实际返回不一致，且没有按角色裁剪或 OpenAPI 说明。
- **期望**：B5 确认普通成员页面不展示该字段，或在 B3/B5 对普通成员响应裁剪；若保留，在 OpenAPI/ADR 注明由 UI 控制。承接：**B5 / M03 评审包**。

## 剩余风险与未验证项

- **未重复 full verify**：按 brief 只跑 `DepartmentAdminApiTest` 定向测试，并审计 EV-0314；未在本轮重新跑 5–10 分钟全量。
- **未跑**：E2E、axe/a11y、trivy/security、k6/perf、前端测试、V9 迁移/回滚（B2 未改迁移）；这些属于 B5/M03-V/模块 gate。
- **未跑 M03 gate**：M03 仍 `building`，B3/B4/B1b/B5/M03-V 未完成；不能以 B2 任务级通过替代模块 gate。
- **未独立验证**：跨租户 CUSTOM 清理（同租户其他部门已测，跨租户只按 SQL 条件审查）；审计失败/清理失败的整体回滚（F-002）；并发幂等（F-003）；管理端成员列表端点（F-001）。
- **隐私提示**：HTTP 成员列表（含管理端与租户端）按 OpenAPI 返回停用账号的 `username/displayName/status`，普通成员也能看到本部门停用账号；这是合同允许的组织字段，但 B5 应避免把它展示成 HR/账号状态面板。

## 是否可进入 B3

- **B2 可以 done**：无 P0/P1；验收 6 项均由代码+测试支撑，EV-0314 的 549 tests/0 skipped 与覆盖率 90.47/71.92 已核对。`TASK-0039` 保持 `review`，由 Lead 标记 done。
- **B3 可以启动**：依赖 B2 的实现已就绪；B3 消费 `OrganizationDirectory` 时必须用 `isActiveMember`/`findDepartment` 做可见性/管理能力判定，不要用 `listActiveMembers` 当授权谓词（F-004）；B3 可顺带补 F-001/F-002 的测试。
- **`listActiveMembers` 语义成立**：接口实现只列启用账号；HTTP 成员列表是另一个方法，按 OpenAPI `DepartmentMember.status` 包含停用账号；两者不冲突，`memberCount` 只计 ACTIVE 的规则由 `DEPARTMENT_COLUMNS`/`COUNT_ACTIVE_MEMBERS` 实现。**没有 P1/P2 冲突**；F-001/F-004 是测试与使用约束。
- **P2/P3 承接**：F-001 → B3/M03-V；F-002 → M03-V（B3 可补）；F-003 → M03-V；F-004 → B3；F-005 → Lead/M03 gate；F-006 → B5/M03 评审包。
