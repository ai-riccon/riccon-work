---
review_id: REV-TASK-0041-001
module_id: M03
task_id: TASK-0041
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M03_TASK-0042_20261118
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [2f90d0e]
head_commit: b45734c
evidence:
  - EV-0316
findings:
  - id: F-001
    title: "addMember 与 B2 部门移出清理存在 check-then-act 竞态，可留下 stale CUSTOM 成员行，重新加入部门后恢复旧共享"
    severity: P2
    status: accepted
  - id: F-002
    title: "addMember 在授权后仍调用 listActiveMembers 全量扫描部门成员构造响应，B2 F-004 的无分页/上限约束仍在写路径"
    severity: P3
    status: accepted
  - id: F-003
    title: "OpenAPI 错误响应与实际端点不一致：POST /workspaces 的 404 与部门空间 GET 的停用部门 400 未入契约"
    severity: P3
    status: accepted
  - id: F-004
    title: "TASK-0041 已恢复 review，但 blocker 字段仍保留旧的 write_scope 阻塞文本"
    severity: P3
    status: accepted
  - id: F-005
    title: "若干 ADR 明示边界缺少回归测试：TENANT 改名、description-only PATCH、停用/跨租户成员加入、个人空间成员端点"
    severity: P3
    status: accepted
  - id: F-006
    title: "workspace 包外仍有 M02 旧可见性谓词（FileJdbc.WORKSPACE_VISIBLE、ConversationJdbc owner-only），B3 未新增但 B4 必须删除"
    severity: P3
    status: accepted
created_at: 2026-11-18T18:58:49+08:00
---

# TASK-0041 独立评审：M03-B3 空间可见性与访问判定

## 结论

**verdict: pass**（无开放 P0/P1）。B3 的代码提交是 `2f90d0e`，证据/状态提交是 `715ad66`，评审时 HEAD 为 `b45734c`。可见性谓词、`requireAccessible`/`requireManageable`、部门空间与 CUSTOM 成员端点、`visibility NULL` 兼容路径、追认的三处测试夹具和审计均满足 TASK-0041 验收与 ADR-0018 §1.4/§2/§3.1/§3.2/§7.1/§7.3。

**B3 可以由 Lead 标记 done**（本评审不修改任务状态、不代替 Lead 标记）。**B4 可以在 B3 done 后启动**：`requireAccessible` 签名/语义稳定，B4 只需把所有内容入口收敛到它并删除旧谓词；F-001 是 B3 成员加入路径与 B2 清理的并发硬化项，承接 M03-V，不阻塞 B4。若 Lead 不接受 P2 的书面接受，则 B3 不应 done；按 Riccon 的 P2 接受规则，本评审给 pass。

**单一谓词成立**：`WorkspaceJdbc` 只有一处 `VISIBILITY_PREDICATE`，详情/列表/计数共用 `ACCESSIBLE_SOURCE`；workspace 包内没有第二份可见性 SQL。**`insert` 写路径成立**：B3 起同时写 `visibility` 与 `department_id`，个人空间 `PRIVATE`+空部门，团队空间必须启用部门且仅 `DEPT|CUSTOM`；`TENANT` 不可新建/转换。**`visibility NULL` 处理成立**：团队空间 fail-closed 不可见，个人空间仍仅 owner 可见；响应侧 `effectiveVisibility()` 只做展示降级，不放宽查询。**`requireManageable` 成立**：仅 `TENANT_ADMIN` 或本部门 ACTIVE 负责人，经 `OrganizationDirectory` 读取，不可见 404、可见无管理 403，无跨请求缓存。**追认夹具安全**：三处改动只增加部门/成员夹具并传 `departmentId`，没有删断言、放宽 404/403 或跳测；`ConversationApiTest.listIsPagedAndOwnerScoped` 的 owner-only 断言仍在，B4 才更新。

## 独立验证（干净树，HEAD `b45734c`）

| 命令/检查                                                                                                                 | 退出码 | 结果                                                                                                                                          |
| ------------------------------------------------------------------------------------------------------------------------- | ------ | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `git status --short`                                                                                                      | 0      | 空；HEAD `b45734c`                                                                                                                            |
| `git show --stat 2f90d0e 715ad66`                                                                                         | 0      | `2f90d0e` 19 个代码/测试文件，2573 insertions；`715ad66` 仅 EV-0316 日志、EV JSON、state/task                                                 |
| `git diff --name-status ebe5175..2f90d0e`                                                                                 | 0      | 仅 `workspace/**` 生产代码、`WorkspaceApiTest`/`WorkspaceScopeTest`/三个追认夹具；无 V9、前端、`config/**`、PRODUCT/AGENTS/contracts/威胁模型 |
| `git diff --name-status 2f90d0e..HEAD`                                                                                    | 0      | 仅 `.riccon` 证据/状态/任务文件；EV 之后无代码变化                                                                                            |
| `./mvnw -q -T1C -pl apps/api -am -Dtest=WorkspaceApiTest,WorkspaceScopeTest -Dsurefire.failIfNoSpecifiedTests=false test` | 0      | **24 tests / 0 failures / 0 errors / 0 skipped**（`WorkspaceApiTest` 8、`WorkspaceScopeTest` 16），surefire 18:53–18:54                       |
| `./mvnw -q spotless:check`                                                                                                | 0      | 通过                                                                                                                                          |
| `riccon contract validate --strict --project .`                                                                           | 0      | 0 errors, 0 warnings                                                                                                                          |
| `riccon state validate --project .`                                                                                       | 0      | 0 errors, 0 warnings                                                                                                                          |
| `git merge-base --is-ancestor 2f90d0e HEAD`                                                                               | 0      | 2f90d0e 是 HEAD 祖先                                                                                                                          |

**EV-0316 审计**：`EV-0316.json` 的 `commit=2f90d0e9...`、`dirty=false`、`exit_code=0`，日志 SHA-256 `03214d6f39ee1d08cffa8fb3d61b48287e5c84bcc95f4895137e08fae40873e4` 与文件一致；运行窗口 `18:40:35–18:47:26`。该窗口内 fresh surefire 汇总为 **568 tests / 0 failures / 0 errors / 0 skipped**：API 257、worker 262、platform-common 33、platform-domain 4、ArchUnit 12。API 257 = B2 的 238 + `WorkspaceScopeTest` 16 + `WorkspaceApiTest` 新增 3，计数自洽；EV 日志中 `WorkspaceScopeTest` 于 18:40、`WorkspaceApiTest` 于 18:45 启动。另有 07:51 的 `AgentStreamTimingProbeTest` 残留报告 1 个，不在 EV 窗口内，是 B2 F-005 的已知现象，不计入 568。覆盖率：API 行 **90.62%** / 分支 **72.63%**，worker **87.89% / 70.77%**，platform-common **98.55% / 98.15%**，platform-domain **94.44%**（无分支）；均高于合同 80%/70%，`verify` 内 `jacoco:check` exit 0。

**未重复 full verify**：按 brief 只审计 EV-0316 并跑定向测试。EV-0316 的命令是后端 `./mvnw -q -T1C verify`，不包含合同 `commands.test` 的 `pnpm -w test`；B3 没有前端改动，任务级定向证据可接受，但 M03 模块评审必须用完整合同命令的全量 EV，不能用本任务 EV 替代。

## 单一可见性谓词对账

- **唯一实现点**：`WorkspaceJdbc.java:60-77` 定义 `VISIBILITY_PREDICATE`，`:80-94` 定义唯一 `ACCESSIBLE_SOURCE`；`:105` 的 `FIND_ACCESSIBLE`、`:108` 的 `COUNT_ACCESSIBLE`、`:111` 的 `LIST_ACCESSIBLE` 都复用同一个片段，详情/计数/列表没有各写一套。注释明确禁止在 controller/其他仓储复制（`WorkspaceJdbc.java:24-30`）。
- **PERSONAL**：`w.kind='PERSONAL' AND w.owner_user_id = actor.id`，只认 owner；`actor.status='ACTIVE'` 由 `ACCESSIBLE_SOURCE` 的 join 强制。
- **DEPT**：`w.kind='TEAM' AND w.visibility='DEPT'`，要求 `actor.department_id IS NOT NULL`、`w.department_id = actor.department_id`、`actor_department.status='ACTIVE'`。
- **CUSTOM**：在 DEPT 条件上再要求 `EXISTS workspace_member`，且成员行带 `wm.tenant_id = w.tenant_id`、`wm.workspace_id = w.id`、`wm.user_id = actor.id`。
- **TENANT**：`w.kind='TEAM' AND w.visibility='TENANT'`，保留 M02 历史可见面；不要求部门。
- **`visibility NULL`**：`VISIBILITY_PREDICATE` 对团队空间不匹配任何分支，团队 NULL 空间对所有人都不可见；个人空间仍走 PERSONAL owner 分支。`WorkspaceRecord.effectiveVisibility()`（`WorkspaceRecord.java:63-68`）只在响应/业务快照侧把 NULL 团队降级为 `TENANT`、个人降级为 `PRIVATE`，查询侧不放宽。测试 `nullVisibilityWorkspace_isNotOverExposed`（`WorkspaceScopeTest.java:188-205`）覆盖。
- **例外查询不是第二套可见性**：`FIND_PERSONAL`（`:96-98`）只用于“查找本人个人空间”，`FIND_BY_ID`（`:100-102`）明确标注“不做可见性过滤，只供管理能力判定与更新后重读”；内容读取没有使用 `FIND_BY_ID` 的路径。`FIND_ACCESSIBLE` 是唯一内容入口 `WorkspaceService.requireAccessible` 的实现。
- **status 差异**：`COUNT_ACCESSIBLE`/`LIST_ACCESSIBLE` 额外要求 `w.status='ACTIVE'`，`FIND_ACCESSIBLE` 不加；这是 M02 既有语义“归档空间仍可按 ID 读取”（`git show 2f90d0e^:...WorkspaceJdbc.java` 的类注释），不是 B3 新引入的谓词分叉。B3 的验收“单一谓词覆盖 list/count/detail”按可见性谓词理解成立。
- **package 外**：`FileJdbc.java:48-50` 仍有 M02 的 `WORKSPACE_VISIBLE`（`w.kind='TEAM' OR PERSONAL owner`），`ConversationJdbc.java:25-38、59-104` 仍是 owner-only；B3 的 diff 没有新增这两处。它们由 B4 删除（TASK-0033 F-007），见 F-006；当前 B3 的 `requireAccessible` 服务层前置拦截，旧查询不能单独绕过空间可见性。

## 写路径对账

- **`insert` 写两列**：`WorkspaceJdbc.INSERT`（`:124-133`）包含 `department_id`、`visibility`；`insert`（`:228-248`）绑定 `workspace.departmentId()`/`workspace.visibility()`，并继续兼容 `null`（B1b 收紧前不破坏旧代码）。B3 新建路径从不写 null：个人空间 `PRIVATE` + 空部门（`WorkspaceService.java:600-611`），团队空间 `DEPT/CUSTOM` + 非空部门（`:502-521`）。
- **新建团队空间**：`createTeam` 先要求 `departmentId` 非空（`WorkspaceService.java:141-142`），加载当前用户并检查 `TENANT_ADMIN`（`:143-146`），`OrganizationDirectory.findDepartment` 后 `requireActiveDepartment`（`:148-151`）；`createTeamInDepartment` 从路径取部门，`requireDepartmentManagement` 判定租户管理员/本部门负责人（`:168-177`）。`createWorkspace` 只接受 `DEPT|CUSTOM`，null 默认 `DEPT`（`:502-505`）。`WorkspaceApiTest` 覆盖缺部门 400、`TENANT`/`PRIVATE` 400、停用部门 400（`WorkspaceApiTest.java:118-186`）。
- **`TENANT` 不可新建/转换**：`createWorkspace` 拒绝 TENANT/PRIVATE；`applyVisibilityChange` 对 `current == TENANT`、`current == PRIVATE` 或 `kind == PERSONAL` 直接 400（`WorkspaceService.java:459-467`），对 `requested == TENANT/PRIVATE` 也 400；`WorkspaceScopeTest.legacyTenantWorkspace_isTenantWideAndImmutable`（`:166-186`）和 `visibilityChange_clearsMembersAndRejectsTenant`（`:332-365`）覆盖。TENANT 改名（不改变可见性）在代码上走 `updateMetadata`，允许但缺测试（F-005）。
- **DEPT/CUSTOM 互转**：`applyVisibilityChange` 先 `deleteAllMembers` 再 `updateVisibility`（`:471-473`），然后写 `workspace.visibility.changed` 审计（details 含 `visibility/previousVisibility/departmentId`）。测试断言成员行清零（`:344-348`）。这符合 ADR §2.2“避免重新加入部门后恢复旧共享”的设计意图，但与 B2 移出清理的并发交错有 F-001 的残留竞态。
- **`visibility NULL` 旧路径**：`insert` 仍允许 null，服务的新建路径不写 null；`requireAccessible` 对 NULL 团队不匹配任何分支；`effectiveVisibility` 只做展示降级。`WorkspaceScopeTest` 用真实迁移直接种 NULL 团队/NULL 个人空间验证，未修改 V9。
- **审计**：创建/改名/可见性/成员增删分别使用 `workspace.created`、`workspace.updated`、`workspace.visibility.changed`、`workspace.member.added/removed`（`WorkspaceService.java:274、381、411、479、526`）；`AuditDetailsPolicy` 的五条白名单（`:61-67`）与 ADR §5 一致；审计与业务同 `@Transactional`，审计失败 fail-closed。测试 `workspaceLifecycle_writesAudit`（`WorkspaceScopeTest.java:383-426`）断言目标、租户、details 不含 `@`。

## 授权与端点对账

- **`requireAccessible` 未变**：签名 `(TenantId, UUID, UUID)` 与父提交逐字一致（父提交 `WorkspaceService.java:131-135`，现文件 `:204-209`），只做 `findAccessible` + 404；会话/文件/报告/产物/运行详情继续复用（`ConversationService.java:117/146`、`FileService.java:150/237`、`ResearchTaskService.java:174/263/286`、`ArtifactService.java:126/304`、`RunDetailService.java:125`）。B4 可依赖该入口。
- **`requireManageable`**（`WorkspaceService.java:300-320`）：先按租户 `findById`，不存在/跨租户 404；`TEAM` + `TENANT_ADMIN` 直接通过；`TEAM` + 非空部门 + `isActiveDepartmentManager` 通过；否则若 `findAccessible` 可见抛 403，不可见抛 404。`isActiveDepartmentManager`（`:582-588`）通过 `OrganizationDirectory.findDepartment` 读取当前负责人并要求部门 ACTIVE，没有复制部门 SQL；`loadCurrentUser`（`:305`）保证账号 ACTIVE。个人空间：owner 403、其他人 404（`:306-319`，测试 `WorkspaceScopeTest.java:428-440`）。
- **部门管理判定**（`:539-560`）：`findDepartment` 先按租户取部门；租户管理员要求部门 ACTIVE；负责人要求 `actor.userId == managerUserId` 且部门 ACTIVE；同部门普通成员 403，其他部门/无部门 404。路径部门来自 URL，请求体不携带 `departmentId`（`DepartmentWorkspaceCreateRequest`），不能由负责人伪造归属。
- **CUSTOM 成员**：`addMember` 先 `requireCustomWorkspace`（`:421-440`），再用 `OrganizationDirectory.isActiveMember` 校验目标账号为空间所属部门的 ACTIVE 成员（`:365`）；跨部门/未分配/停用部门统一 404，不泄露目标部门；`memberExists` 重复 409 `RICCON-DEPT-409`（`:368-370`），并发重复由复合主键兜底（`:372-377`）。`removeMember` 对不存在成员返回 204 且不写审计（`:400-412`）。`listMembers` 非 CUSTOM 返回空页，个人空间 owner 403/其他人 404。测试覆盖跨部门/未分配 404、重复 409、不存在 204、DEPT/TENANT 空页、个人空间 400/404（`WorkspaceScopeTest.java:105-164`）。
- **端点权限**：`WorkspaceController` 的 `POST /workspaces` 用 `workspace.write`（`:86-94`），其余读写用 `workspace.read`（`:67-76、103-129、140-192`）；`DepartmentWorkspaceController` 的部门列表/创建用 `workspace.read`（`:59-90`），具体资源能力由服务层判定。`WorkspaceScopeTest.managementAuthorization_distinguishesForbiddenAndNotFound`（`:276-307`）覆盖 403/404/租户管理员例外，`createWorkspace_authorizationMatrix`（`:309-330`）覆盖部门路径创建矩阵。
- **无缓存**：workspace 包内没有 `@Cacheable`/`@CacheEvict`/Caffeine 等；`requireManageable`/`requireAccessible` 每请求读库，`OrganizationDirectory` 也每次查询；符合 ADR §3.3。
- **跨租户**：所有新 SQL 走 `TenantJdbc`，显式 `TenantId`；`findById`/`findAccessible`/`count/list`/成员操作均带 `tenant_id`。`WorkspaceApiTest.getWorkspace_crossTenant_returnsNotFound`（`:91-110`）和 `WorkspaceScopeTest` 多部门/跨租户夹具覆盖 404。
- **列表不泄露**：`list` 只返回可见空间；`WorkspaceScopeTest.list_doesNotLeakOtherDepartmentMetadata`（`:367-381`）断言其他部门名和空间名不出现，`total == items.size`；`deptWorkspace`/`listAndCount` 同时验证 DEPT/CUSTOM 不泄漏。

## 追认夹具对账

- **`ConversationApiTest`**：diff 只把测试 `listIsPagedAndOwnerScoped` 的 `createTeamWorkspace` 调用改为先创建部门、把 admin/member 加入部门、再带 `departmentId` 创建空间（`:78-86`）；新增 `createDepartment`/`addDepartmentMember`/`userId`/`randomSuffix` 辅助。**没有删除或放宽断言**：`:89-111` 仍断言 admin 的 3 条会话、member 只看自己 1 条、admin 看不到 member 的会话；owner-only 断言完整保留，B4 才应更新。
- **`FileLifecycleApiTest`**：只把 `upload_conversationFromOtherWorkspace_returnsNotFound` 的夹具从无部门团队空间改为 `createDepartmentTeam`（`:301-312`）；新 helper 创建部门、加入 admin/member、带 `departmentId` 创建 DEPT 空间（`:313-346`）。测试仍断言 404，且两个账号都在部门内，404 只能来自“会话不属于该工作空间”，没有被空间不可见掩盖。
- **`FileApiTestSupport`**：`createTeam` 从 `(session, name)` 改为 `(session, departmentId, name)` 并在请求体带 `departmentId`（`:49-70`）；没有断言变化。`grep -rn "createTeam(" apps/api/src/test/java` 只剩该定义，调用点都由编译收敛，没有漏改。
- **测试结果**：上述三文件包含在定向测试运行中（`ConversationApiTest`/`FileLifecycleApiTest` 属于全量 568，定向 24 不含它们；EV-0316 的 568 tests 与 0 skipped 已核对），未见 `@Disabled`、`assumeTrue`、`@Skip`、TODO/FIXME 或注释掉的用例。

## 测试与覆盖率对账

- **定向**：本次独立运行 `WorkspaceApiTest,WorkspaceScopeTest` 共 **24 tests / 0 failures / 0 errors / 0 skipped**，`exit 0`；surefire 报告时间 18:53:48/18:54:03，报告文件在 `apps/api/target/surefire-reports`。
- **EV-0316**：fresh 568 tests（API 257、worker 262、platform-common 33、platform-domain 4、ArchUnit 12），0 failures/errors/skipped；API 257 的增量与 B3 新增测试数吻合（16 + 3）。EV 日志中无 `BUILD FAILURE`/`Tests run: ... Failures: [1-9]`/`[ERROR]` 测试失败标记；日志中的 `RICCON-TENANT-404`/`RICCON-AUTH-403`/`RICCON-DEPT-409`/`RICCON-REQUEST-400` 都是负向用例的预期拒绝。
- **覆盖率**：API 行 90.62% / 分支 72.63%，worker 87.89/70.77，platform-common 98.55/98.15，platform-domain 94.44/无分支；均高于合同 80/70。`verify` 内 jacoco check exit 0。
- **未发现假绿**：没有零测试命令、没有跳过测试、没有 retry 掩盖、没有只做快照的断言；`WorkspaceScopeTest` 使用真实 MySQL/HTTP（`DepartmentTestSupport`/`ApiIntegrationTestSupport`），没有 mock 持久化；`WorkspaceApiTest` 同样走真实 HTTP。

## 边界、状态与编码规范

- **写入范围**：`git diff --name-status ebe5175..2f90d0e` 只有 19 个文件：`apps/api/src/main/java/com/riccon/api/workspace/**` 12 个、`WorkspaceApiTest`/`WorkspaceScopeTest`、`ConversationApiTest`、`FileLifecycleApiTest`、`FileApiTestSupport`。没有 V9/迁移、chat/file/research/run 内容入口、前端、`config/**`、PRODUCT/AGENTS/contracts/威胁模型。`715ad66` 只加 EV-0316 日志/JSON 与 state/task；`b45734c` 只改 module/state/tasks。
- **状态**：`TASK-0041.status=review`、`end_commit=2f90d0e`，`TASK-0042.status=in_progress`；EV-0316 的 commit 是 HEAD 祖先；`riccon state validate` 0 errors/0 warnings。未发现 builder 自标 done。**旧 blocker 字段仍在**（F-004）。
- **规范**：新/改 public 类、方法、DTO 字段均有中文 Javadoc；复杂 SQL、租户边界、`visibility NULL`、成员清理有中文说明；没有生产 mock、注释掉的代码、TODO/FIXME；`spotless:check` 通过。
- **安全基线**：新端点均有 authn/permission + 服务层资源判定；输入经 `Validation`/枚举约束；审计 details 白名单；无密钥/PII 写入。未跑 trivy/trivy 依赖扫描（B3 未改依赖，模块 gate 负责）。

## Findings 与承接

### F-001（P2，accepted）：并发加入成员与部门移出清理的 stale CUSTOM 行

- **位置**：`WorkspaceService.java:361-387`（`isActiveMember` 365、`memberExists` 368、`insertMember` 373）；`DepartmentAdminService.java:313-334`（`clearDepartment` 322、`deleteCustomWorkspaceMembers` 323）；`DepartmentAdminJdbc.java:138-159`。
- **观察**：`addMember` 是 check-then-act：先普通 SELECT 校验目标为部门 ACTIVE 成员，再普通 SELECT 查重复，最后普通 INSERT 成员行；B2 的移出成员在另一个事务里先 `UPDATE app_user SET department_id = NULL`，再 `DELETE workspace_member`，没有对 `app_user` 行加锁。可交错为：T1 `addMember` 读到 active=true；T2 `removeMember` 清空部门并删除“当时不存在”的成员行；T1 在 T2 的删除之后插入成员行并提交；之后平台管理员把该用户重新加入部门，B2 的清理不会再执行，旧的显式成员行重新授予 CUSTOM 访问。当前在“移出后、重新加入前”谓词仍要求部门成员，所以不是当前越权；但违反 ADR-0018 §2.2“移出部门时清理，避免重新加入后恢复旧共享”。
- **期望**：把 `INSERT_MEMBER` 改为条件插入（`INSERT ... SELECT ... WHERE EXISTS (SELECT 1 FROM app_user ... status='ACTIVE' AND department_id=:departmentId AND d.status='ACTIVE')`），0 行时返回 404/409；或在 `addMember`/`removeMember` 两路径对 `app_user` 行 `SELECT ... FOR UPDATE`，并加一个并发用例。
- **承接**：**M03-V**（并发/失败注入）。B3 可按 P2 书面接受，不阻塞 B4；B4 不修改成员端点。

### F-002（P3，accepted）：`addMember` 仍用 `listActiveMembers` 全量扫描

- **位置**：`WorkspaceService.java:382-385`；`OrganizationDirectory.java:37-47`；`DepartmentJdbc.java:82-88`、`:199-208`。
- **观察**：授权已经按 B2 F-004 的要求使用 `isActiveMember`，但构造响应时仍调用 `listActiveMembers` 加载整个部门的启用成员再 `filter(userId)`；`listActiveMembers` 没有分页/上限，大部门下会增加写路径的延迟与内存。这是 B2 F-004 的残留，不是可见性绕过。
- **期望**：增加 `findActiveMember(tenantId, departmentId, userId)` 的 Optional 快照接口，或让成员查询带 `userId`/`LIMIT`；`listActiveMembers` 只保留给成员选择/分页端点。
- **承接**：**B5 / M03-V** 性能与接口收敛。

### F-003（P3，accepted）：OpenAPI 错误响应与实际端点不一致

- **位置**：`contracts/openapi.yaml:221-249`（`POST /api/v1/workspaces` 只列 201/400/401/403/500）；`WorkspaceService.java:148-150`（部门不存在/跨租户抛 404）；`contracts/openapi.yaml:1441-1500`（`GET /departments/{departmentId}/workspaces` 未列 400）；`WorkspaceService.java:539-550`、`:568-572`（停用部门抛 400）。
- **观察**：`POST /workspaces` 使用不存在或跨租户的 `departmentId` 会返回 404 `RICCON-TENANT-404`，但 OpenAPI 没有 404 响应；部门空间 GET 在部门停用时返回 400（`WorkspaceScopeTest.disabledDepartment_hidesWorkspacesUntilEnabled` 断言 400），OpenAPI 未列 400。生成客户端/契约测试可能遗漏这些响应。
- **期望**：B5/架构收口补 404/400 响应，或把停用部门 GET 的错误统一成 403/404 并同步 OpenAPI；B3 写范围不含 `contracts`，本任务不改。
- **承接**：**B5 / Architect / M03 评审包**。

### F-004（P3，accepted）：TASK-0041 的旧 blocker 未清空

- **位置**：`.riccon/tasks/TASK-0041.json:7`（status=review）、`:28`（blocker）、`:32`（end_commit=2f90d0e）。
- **观察**：Lead 已追认三处夹具写范围并恢复 review，但 blocker 仍保留“这三个文件不在 write_scope，内核拒绝进入 review”的旧文本；`riccon task show TASK-0041` 仍显示 blocker，后续 agent/gate 可能误判为 blocked。
- **期望**：Lead 在 B3 done/closeout 时清空 blocker 或改为已解决说明；verifier 不修改任务状态。
- **承接**：**Lead / B3 closeout**。

### F-005（P3，accepted）：若干 ADR 明示边界缺少回归测试

- **位置**：`WorkspaceScopeTest.java:166-186`（TENANT 只测转换 400，未测改名 200）；`:332-365`（可见性切换，未测 description-only PATCH）；`:106-137`（CUSTOM 成员未测停用账号/跨租户账号）；`:428-440`（个人空间未测 owner 的成员列表 403 / 非 owner 加成员 404）；`WorkspaceApiTest.java:118-143`（跨租户只测 GET，未测 PATCH）。
- **观察**：代码路径正确（`requireManageable`/`requireCustomWorkspace`/`isActiveMember` 已覆盖），但上述显式边界没有断言。TENANT 改名是 ADR §2.1/§7.1 允许的行为（`updateMetadata` 不改变 visibility），当前只有转换 400 的测试；停用/跨租户 target 是成员规则的核心边界。
- **期望**：M03-V 补：TENANT PATCH name 200 且 visibility 400；description-only PATCH 200 + `workspace.updated` 审计；停用/跨租户 target `addMember` 404；个人空间成员端点 403/404；跨租户 PATCH 404。
- **承接**：**M03-V / B5**。

### F-006（P3，accepted）：workspace 包外仍有旧可见性谓词，B4 必须删除

- **位置**：`FileJdbc.java:48-50` 的 `WORKSPACE_VISIBLE`；`ConversationJdbc.java:25-38、59-104` 的 `created_by = :userId` owner-only；`RunDetailService.java:110-125` 的 owner 条件。
- **观察**：B3 没有新增第二份 workspace 可见性 SQL；但仓库仍保留 M02 的 `FileJdbc.WORKSPACE_VISIBLE`（TEAM 全可见、PERSONAL owner）与 `ConversationJdbc` owner-only。当前 B3 的 `requireAccessible` 在服务层先拦截，旧查询不能单独绕过；但 B4 若不删除，文件列表/下载、会话列表仍会各用一套规则，违背 ADR §3.1“唯一访问判定”。TASK-0033 F-007 已把这些列入 B4。
- **期望**：B4 删除 `FileJdbc.WORKSPACE_VISIBLE`、`ConversationJdbc` owner-only、`RunDetailService` owner 条件，统一调用 `requireAccessible`；更新 `ConversationApiTest` 的 owner-only 断言；在模块 gate 用 grep/ArchUnit 确认没有第二份 `visibility` SQL。
- **承接**：**B4 / M03 评审包**。

## 剩余风险与未验证项

- **未重复 full verify**：按 brief 只审计 EV-0316 并跑定向 24 tests；未在本轮重跑 5–10 分钟全量。
- **未跑**：E2E、axe/a11y、trivy/security、k6/perf、前端测试、V9 迁移/回滚（B3 未改迁移）；这些属于 B4/B5/M03-V/模块 gate。B3 没有 UI，因此没有浏览器 E2E 证据。
- **未独立验证**：F-001 的并发交错只做了代码路径分析，没有多线程失败注入；F-002 的大部门性能没有压测；B2 的跨表清理失败回滚仍沿用 B2 F-002；`listActiveMembers` 的停用账号/无分页语义沿用 B2 F-004。
- **EV 口径**：EV-0316 是后端 `verify`，不覆盖合同 `test` 中的 `pnpm -w test`；B3 无前端改动，任务级可接受，模块评审必须重跑完整合同命令。
- **surefire 残留**：07:51 的 `AgentStreamTimingProbeTest` 报告仍存在（B2 F-005），本轮按 EV 窗口统计，未把它算入 568；模块 gate 前建议清理各模块 `target/surefire-reports` 或按运行窗口统计。
- **模块 gate 未跑**：M03 仍为 `building`，B4/B5/B1b/M03-V 未完成；本评审只是 B3 任务级通过，不能替代模块 gate。现在运行 `riccon gate check --module M03` 应失败。
- **文档影响**：B3 行为与 ADR-0018/OpenAPI 已描述的内容一致，未新增需要同步的架构章节；OpenAPI 错误响应缺口见 F-003。B3 未改 `docs/**`，没有额外 no-impact 声明需求以外的文档债务。

## 是否可进入 B4

- **B3 可以 done**：TASK-0041 的 6 项验收均由代码 + 测试支撑；定向 24 tests、EV-0316 568 tests/0 skipped、API 覆盖率 90.62/72.63 已核对；无 P0/P1。Lead 按流程标记 done 即可，本评审不修改任务状态。
- **B4 可以启动**（B3 done 后）：`WorkspaceService.requireAccessible` 签名/语义稳定，B4 可以直接消费；B4 必须完成 F-006（删除 `FileJdbc.WORKSPACE_VISIBLE`、`ConversationJdbc` owner-only、`RunDetailService` owner 条件）并更新 `ConversationApiTest` 的 owner-only 断言。F-001 与 B4 写范围不重叠，承接 M03-V。
- **P2/P3 承接**：F-001 → M03-V（并发/失败注入）；F-002 → B5/M03-V（性能/接口收敛）；F-003 → B5/Architect/M03 评审包；F-004 → Lead/B3 closeout；F-005 → M03-V/B5；F-006 → B4/M03 评审包。
