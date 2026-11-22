---
review_id: REV-TASK-0142-001
module_id: M20
task_id: TASK-0142
reviewer:
  agent: riccon/verifier
  session: ses_dfe0e5a9bffe7QltYWJtSnb7NA
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - 22e1be9f5a504a9a231609e96ccfd12083f19d3a
  - 9eeb58fe6d3b3277069d993affaa58a2ef0b4ef0
  - b41466c3839c8236de616efd075652fd19a5020f
head_commit: b41466c3839c8236de616efd075652fd19a5020f
evidence:
  - EV-0757
  - EV-0758
  - EV-0759
reviewed_evidence:
  - EV-0757
  - EV-0758
  - EV-0759
findings:
  - id: F-001
    severity: P2
    status: accepted
    owner: "riccon/builder-core + riccon/architect"
    due: "M20 模块 gate 前复核；任何新增消费 tenantId 查询参数的端点前必须修复"
    title: "X-Tenant-Id 与 tenantId 同时出现且请求头匹配主体时，伪造的查询参数不再被过滤器拒绝/审计"
    summary: >-
      TenantContextFilter 用 firstPresent(header, query) 只检查第一个非空值
      （TenantContextFilter.java:127-129、304-309）。租户管理员 A 发送
      `GET /api/v1/costs?...&tenantId=B` 并携带 `X-Tenant-Id: A` 时，请求头命中主体，
      filter 跳过不一致判断，既不返回 403，也不写 tenant.access.denied；/api/v1/costs
      只使用主体租户（TenantCostController.java:53-65），实际仍只返回 A 的数据，因此没有
      跨租户数据泄漏。平台非管理员在 platform 租户下用 `X-Tenant-Id: platform` 掩盖
      `tenantId=B` 时，过滤器同样跳过内层权限校验，最终由 PermissionInterceptor 返回 403
      （无数据，但也没有过滤器拒绝审计）。这与 ADR-0022 §2.4 “查询参数或请求头与主体不一致
      即 403 + 审计”的字面口径不符，也是 REV-TASK-0108 F-006 “filter 在角色校验前放行伪造
      tenantId”的合并参数残余。PlatformCostTenantFilterTest 只分别测试 query 与 header，
      未覆盖“匹配主体的 header + 不匹配的 query”。
    accepted_reason: >-
      当前唯一消费 tenantId 查询参数的端点是 /api/v1/admin/costs，且 controller 的
      @RequirePermission(platform.cost.read) 与 PermissionInterceptor 仍会拦截平台非管理员；
      /api/v1/costs 与 /api/v1/costs/invocations 完全不读取 tenantId。残余因此只影响拒绝/
      审计一致性，不产生跨租户数据、提权或旧权限复用。修复需分别检查 header 与 query
      （或对 query 单独触发例外判断），属安全过滤器变更，由 builder/architect 在 M20 gate
      前决定；在新增消费 tenantId 查询参数的端点前必须完成，避免未来端点继承该检测缺口。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:127-140、161-193、304-309；
      apps/api/src/main/java/com/riccon/api/cost/controller/TenantCostController.java:53-65；
      apps/api/src/main/java/com/riccon/api/admin/cost/controller/PlatformCostController.java:46-56；
      apps/api/src/main/java/com/riccon/api/auth/controller/PermissionInterceptor.java:46-65；
      apps/api/src/test/java/com/riccon/api/admin/cost/PlatformCostTenantFilterTest.java:94-118、129-152；
      docs/adr/ADR-0022-m04-quota-and-cost.md:244-246。
  - id: F-002
    severity: P3
    status: accepted
    owner: "riccon/builder-core"
    due: "M20 模块 gate 前"
    title: "TenantContextFilter 类级 javadoc 仍写“任何不一致立即拒绝”，未提平台成本窄例外"
    summary: >-
      TenantContextFilter.java:36-42 的类级 javadoc 写“请求头 X-Tenant-Id 与查询参数 tenantId
      若与主体不一致，立即拒绝并写审计；一致时也忽略”，未提及 platform 租户 + GET
      /api/v1/admin/costs + platform.cost.read 的窄例外；方法级 javadoc（:149-193）与
      ADR-0009/ADR-0022 已完整记录例外。运行行为正确，但类级注释会误导维护者把平台成本
      过滤当成“非法伪造”。TASK-0142 的写范围包含该文件，可在 M20 gate 前顺手修正。
    accepted_reason: >-
      仅文档漂移，不改变运行时行为、AC、错误码或安全边界；方法级注释和两份 ADR 已给出
      完整口径。作为 P3 接受，由 builder-core 在 M20 gate 前更新类级 javadoc，避免后续
      修改过滤器时误删例外分支。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:36-42、130-139、149-193；
      docs/adr/ADR-0009-tenant-isolation-enforcement.md:172-184；
      docs/adr/ADR-0022-m04-quota-and-cost.md:243-244。
  - id: F-003
    severity: P3
    status: accepted
    owner: "riccon/architect"
    due: "M20 模块 gate 前"
    title: "CODING-STANDARDS §10.4 的 security 映射未列 PlatformCostTenantFilterTest"
    summary: >-
      docs/CODING-STANDARDS.md:242 的“当前映射（M19 P4a）”security 清单列出了
      DepartmentIsolationTest、TenantIsolationSecurityTest、M04Verifier* 等类，但没有新增的
      PlatformCostTenantFilterTest；该类已在 :30 标注 @Tag("security")，且继承
      ApiIntegrationTestSupport 的 integration 标签，因此 -Dgroups=security 仍会选中它，
      默认全量也不受影响。问题只是文档清单不完备，属测试分层可维护性问题。
    accepted_reason: >-
      标签本身已正确，选择执行与默认执行均未被破坏；CODING-STANDARDS 不在 TASK-0142 写范围，
      故作为 P3 接受，由 architect 在 M20 gate 前把新类补进 §10.4 的 security 映射。
    evidence: >-
      docs/CODING-STANDARDS.md:228-247；
      apps/api/src/test/java/com/riccon/api/admin/cost/PlatformCostTenantFilterTest.java:30-31；
      apps/api/src/test/java/com/riccon/api/support/ApiIntegrationTestSupport.java:36-44。
  - id: F-004
    severity: P3
    status: accepted
    owner: "riccon/builder-core"
    due: "M20 模块 gate 前"
    title: "平台成本例外候选主体的会话失效被归类为 403 missing_permission 而非 401"
    summary: >-
      filter 内权限校验调用 AuthService.requirePermission；loadCurrentUser 在租户停用、
      用户不存在或用户禁用时抛 UnauthenticatedException，而 hasPlatformCostPermission 的
      RuntimeException catch 会把它与权限读取异常一并转为 false，最终走
      denyMismatchedTenant 返回 403 RICCON-AUTH-403 + reason=missing_permission
      （TenantContextFilter.java:177-193）。常规路径由 PermissionInterceptor 抛
      UnauthenticatedException → 401。当前没有数据泄漏，且 fail-closed，但“会话失效”与
      “缺少权限”的状态码和审计原因不准确。
    accepted_reason: >-
      安全方向正确（拒绝且无数据），残余是错误语义和审计原因精度，不影响本任务“平台非管理员
      403 且无数据”的 AC。作为 P3 接受，由 builder-core 在 M20 gate 前决定是否把
      UnauthenticatedException 拆成 401 或改用 session_invalid 等更准确的 reason。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:177-193；
      apps/api/src/main/java/com/riccon/api/auth/service/AuthService.java:309-328、360-365；
      apps/api/src/main/java/com/riccon/api/auth/controller/PermissionInterceptor.java:46-65。
notes: "任务级独立安全评审；TASK-0108 F-006 的主修复与 TASK-0141 F-002 已在本任务落地，剩余 1 条 P2 accepted 与 3 条 P3 accepted 均为有界残余。不参与 M20 模块 gate；模块级全量合同命令、E2E、coverage 与 gate 仍由 M20 模块 review 承担。"
created_at: "2026-11-22T14:37:31+08:00"
---

# REV-TASK-0142-001：TASK-0142 平台成本租户过滤角色校验与负向测试独立评审

本文件是任务级独立安全评审，不参与 M20 模块 gate；模块级 AC、全量合同命令、E2E、覆盖率与安全/性能仍由 M20 模块 review 承担。

## 结论

**pass（任务级，带残余）**。提交 `22e1be9` 在 `TASK-0142.write_scope` 内完成了：

- `TenantContextFilter` 在平台成本窄例外内调用 `AuthService.requirePermission(platform.cost.read)`，缺少权限和权限读取异常都 fail-closed；
- 平台非管理员、租户端点伪造查询参数/请求头、其他路径/方法/trailing slash 的负向测试；
- ADR-0009 新增平台成本例外小节，ADR-0022 §2.4 更新 `X-Tenant-Id` 不参与过滤与空白 `tenantId` → 400 的现状。

未发现 P0/P1。存在 1 条 P2 accepted（请求头匹配主体时掩盖伪造查询参数的检测/审计缺口）和 3 条 P3 accepted（类级 javadoc 漂移、CODING-STANDARDS security 清单漏项、会话失效返回 403 的语义）。P2 残余不产生跨租户数据或提权，但应在 M20 gate 前由 builder/architect 决定修复；本任务验收结论不受其影响。

## 评审对象与范围

- 基线 `81b4101`，代码提交 `22e1be9`，证据/状态提交 `9eeb58f`、`b41466c`；`end_commit=22e1be9`，评审 HEAD `b41466c`。
- `git diff --name-status 81b4101..22e1be9` 共 6 个文件：
  - `.riccon/state.json`（M）
  - `.riccon/tasks/TASK-0142.json`（M）
  - `apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java`（M）
  - `apps/api/src/test/java/com/riccon/api/admin/cost/PlatformCostTenantFilterTest.java`（A）
  - `docs/adr/ADR-0009-tenant-isolation-enforcement.md`（M）
  - `docs/adr/ADR-0022-m04-quota-and-cost.md`（M）
- 全部匹配 `write_scope`（`TenantContextFilter.java`、`apps/api/src/test/java/com/riccon/api/admin/cost/**`、ADR-0009、ADR-0022、`.riccon/**`）。**未改** `PermissionInterceptor`、`AuthService`、`RoleDefinitions`、cost service/Jdbc、迁移、`contracts/`、`docs/PRODUCT.md`、`docs/ACCEPTANCE.md` 或历史 evidence/review；符合任务边界。
- `git diff --name-status 22e1be9..b41466c` 仅 8 个文件：EV-0757/EV-0758/EV-0759 的日志与 JSON、`.riccon/state.json`、`.riccon/tasks/TASK-0142.json`；未夹带生产源码、测试或 ADR。
- `git show --check 22e1be9` 与 `git diff --check 81b4101..22e1be9` 无空白错误。

## 证据核查

| EV      | 命令                                                                                                                                                                                           | 提交      | exit | manual | dirty | 观察值                                                                                        | 哈希/大小                        |
| ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ---- | ------ | ----- | --------------------------------------------------------------------------------------------- | -------------------------------- |
| EV-0757 | `./mvnw -T1C -pl apps/api -am test -Dtest='PlatformCostTenantFilterTest,CostAdminApiTest,CostApiTest,TenantIsolationTest,TenantIsolationSecurityTest' -Dsurefire.failIfNoSpecifiedTests=false` | `22e1be9` | 0    | false  | false | `PlatformCostTenantFilterTest` 4 tests；合计 58 tests，0 failures/errors/skips；BUILD SUCCESS | `3ffe713d…410a96`，82228 bytes   |
| EV-0758 | `./mvnw -T1C -pl apps/api -am test`                                                                                                                                                            | `22e1be9` | 0    | false  | false | 653 tests，0 failures/errors/skips；BUILD SUCCESS                                             | `6d711463…3b0d1f`，1408862 bytes |
| EV-0759 | `./mvnw -T1C -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false`                                                              | `9eeb58f` | 0    | false  | false | 12 tests，0 failures/errors/skips；BUILD SUCCESS                                              | `87ae2ec4…90247`，12846 bytes    |

- 三份 JSON 的 `exit_code=0`、`manual=false`、`dirty=false`，提交分别为 `22e1be9`/`22e1be9`/`9eeb58f`；日志头部 `# command` 与 JSON `command` 完全一致，`# exit_code` 与 JSON 一致。
- 独立复算 `shasum -a 256` 与 `wc -c`：EV-0757 `3ffe713d332493289285580a78b30f8f1c1c9119eb9b8c9725de68931e410a96`/82228；EV-0758 `6d71146388c11c34a145d7300bc33abce85a1f34e27b27311537c2e71a3b0d1f`/1408862；EV-0759 `87ae2ec4a88ebacbadfde0a380396d08cf01ab714b00c3433df4537d0fd90247`/12846；均与 JSON 一致。
- EV-0757 日志实际包含 `PlatformCostTenantFilterTest` 的 `Tests run: 4` 和汇总 `Tests run: 58`；EV-0758 含 `Tests run: 653` 与 `BUILD SUCCESS`；EV-0759 含 `Tests run: 12` 与 `BUILD SUCCESS`。

## 独立复跑与检查

- 在 HEAD `b41466c`（生产/测试文件与 `22e1be9` 相同，仅多证据/状态提交）独立重跑：

  ```bash
  ./mvnw -T1C -pl apps/api -am test \
    -Dtest='PlatformCostTenantFilterTest,CostAdminApiTest,CostApiTest,TenantIsolationTest,TenantIsolationSecurityTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
  ```

  结果：exit 0；`TenantIsolationTest` 12、`TenantIsolationSecurityTest` 31、`CostApiTest` 5、`PlatformCostTenantFilterTest` 4、`CostAdminApiTest` 6；合计 `Tests run: 58, Failures: 0, Errors: 0, Skipped: 0`；BUILD SUCCESS。日志保存在 `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/task0142-verify.XXXXXX.log`（外部临时目录，不提交）。

- `./mvnw -q -T1C spotless:check` exit 0；`git diff --check 81b4101..22e1be9` exit 0。
- 按要求**未**重跑全量 API（EV-0758 已录 653 tests）、未跑 E2E/coverage/`gate check`；这些属于 M20 模块 gate，不在本任务级评审重复。

## 逐项安全核查

### 1. 例外命中条件

`isPlatformCostFilter`（`TenantContextFilter.java:161-165`）仍严格匹配：

- `"GET".equals(request.getMethod())`；
- `"/api/v1/admin/costs".equals(request.getRequestURI())`（精确，无 trailing slash、无子路径）；
- `PlatformTenant.ID.equals(principal.tenantId())`（`platform` 保留租户）。

不属于例外的伪造值走 `denyMismatchedTenant(...TENANT_MISMATCH_REASON)`。因此 POST/PUT/HEAD、`/api/v1/admin/costs/`、`/api/v1/users?tenantId=...` 均不会继承例外。

### 2. 权限校验与 fail-closed

命中例外后、放行前，`doFilterInternal` 调用 `hasPlatformCostPermission`（`:136-139`）；后者调用 `AuthService.requirePermission(principal, PermissionCodes.PLATFORM_COST_READ)`（`:177-193`），与 `PlatformCostController` 的 `@RequirePermission` 使用同一权限常量。逐条核对：

- 缺少权限：`ForbiddenException` → `false` → `denyMismatchedTenant(...MISSING_PERMISSION_REASON)` → 403；
- 权限读取异常（DB/租户/账号异常）：`RuntimeException` → 记录 ERROR 日志 → `false` → 403；异常不会逃逸出过滤器形成 500 放行；
- 会话失效（租户停用、用户缺失/禁用）会被 `RuntimeException` 分支一并转成 403 `missing_permission`，见 F-004；
- `AuthService.requirePermission` → `loadCurrentUser` 每次从数据库读取租户、账号、角色与权限，不信任会话缓存；过滤器与 `PermissionInterceptor` 各查一次，见剩余风险中的双重查询成本；
- 过滤器 `TenantContext.clear()` 在入口和 `finally` 都执行；`denyMismatchedTenant` 内用 `TenantContext.runWithTenant(principal tenant)` 写审计，作用域自动恢复，未发现 ThreadLocal 泄漏。

### 3. 拒绝审计与 403 响应

`denyMismatchedTenant`（`:206-254`）：

- 审计 `tenant_id=principal.tenantId()`、`actor_type=USER`、`actor_id=principal.userId()`、`action=tenant.access.denied`、`target_type=TENANT`、`target_id=forgedTenant`（≤64 字符，否则 null）、`outcome=DENIED`、`details={attemptedTenant(≤200 字符), reason}`；
- 平台非管理员命中例外 → `reason=missing_permission`；其他路径/方法/主体的不一致值 → `reason=tenant_mismatch`；
- 审计写入失败（`RuntimeException`）→ 记录日志并返回 500 `RICCON-COMMON-500`，不返回 403、不返回数据，保持“无审计不拒绝”的 fail-closed；
- 403 响应由 `ProblemWriter` 写 `application/problem+json`，detail 为 “tenant does not match authenticated principal”，不含租户数据、金额或权限码；`PlatformCostTenantFilterTest` 的 `assertForbiddenWithoutCostData` 断言无 `items`、无 `totals`、不含 `0.003000/0.006000/0.009000` 与两个租户代码。

### 4. 平台非管理员路径

测试用户为 `platform` 租户、真实登录、空角色（`seedAndLoginLocalUser(fixture.platform(), "platform", ..., List.of())`），权限集合为空：

- 不带过滤参数：filter 放行，`TenantContext` 打开 `platform`，`PermissionInterceptor` 对 `@RequirePermission(platform.cost.read)` 返回 403，无数据；
- 带 `tenantId=other`：filter 命中例外、权限校验失败、`missing_permission` 403 + 审计；
- 带 `X-Tenant-Id: other`：同上；
- 对照：平台管理员真实登录（`PLATFORM_ADMIN` 角色，权限含 `platform.cost.read`）在 `tenantId=other` 时返回过滤后的 1 个桶、金额 `0.006000`；只带 `X-Tenant-Id: other` 时仍返回全租户 2 个桶、金额 `0.009000`，证明头不参与过滤；同时带 `tenantId=other` 与不匹配的头时以查询参数为准。

### 5. 租户端点与其他路径/方法

- 租户管理员在 `/api/v1/costs?...&tenantId=other` 与 `X-Tenant-Id: other` 两种情况下均 403 + `tenant_mismatch` 审计；`/api/v1/costs` controller 不读 `tenantId`，因此没有“伪造查询参数改变本租户结果”的路径；
- `/api/v1/users?tenantId=other`（租户管理员）→ 403 + 审计；
- 平台管理员 `POST /api/v1/admin/costs?tenantId=other` → 403 + 审计；
- 平台管理员 `GET /api/v1/admin/costs/?tenantId=other`（trailing slash）→ 403 + 审计；
- 上述拒绝均发生在路由/CSRF 之前（`TenantContextFilter` 注册在 `SecurityContextHolderFilter` 之后、`CsrfFilter` 之前），响应不含数据。

### 6. 绕过尝试矩阵

| 尝试                                          | 观察                                                                                                                                       | 结论                             |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------- |
| 空/纯空白 header 或 query                     | `firstPresent` 跳过空白；空白 `tenantId` 进入 `normalizeTenantFilter` → 400（TASK-0141 已落地）                                            | 无越权                           |
| query 参数名/值编码（如 `tenant%49d`、`%2F`） | `queryParameter` 用 `URLDecoder` 解码；参数名可命中，值不匹配则 403；非法编码当空串，不阻断                                                | 无越权                           |
| 重复 `tenantId`                               | filter 与 controller 均取第一个值；首个不匹配 → 403，首个匹配 → 本租户结果                                                                 | 无跨租户                         |
| 大小写（`TenantId`/`TENANTID`）               | 查询参数名精确匹配；Spring 绑定同名才生效                                                                                                  | 无越权                           |
| 路径编码/大小写/trailing slash/子路径         | `getRequestURI` 精确匹配失败 → 403（安全方向失败关闭）                                                                                     | 无越权                           |
| POST/PUT/HEAD                                 | `"GET".equals(method)` 为 false → 403                                                                                                      | 无越权                           |
| 平台租户代码伪造                              | `platform` 为保留租户，租户开通接口禁止创建；`RoleDefinitions` 只给平台管理员 `platform.cost.read`                                         | 无越权                           |
| 权限缓存/旧会话                               | `requirePermission` 每次 `loadCurrentUser` 从 DB 读取；filter 与 interceptor 双检；检查间撤权 → interceptor 拒绝，检查间授权 → filter 拒绝 | 无旧权限复用                     |
| 匹配 header + 不匹配 query                    | 见 F-001：filter 只取第一个非空值（header），跳过 query 不一致判断；当前受影响端点不消费 query，无数据泄漏，但拒绝/审计缺失                | **P2 accepted**                  |
| 审计写入失败                                  | `denyMismatchedTenant` catch `RuntimeException` → 500，无数据；本任务未注入审计失败做动态用例                                              | 代码审查 fail-closed，动态未验证 |
| 并发权限变更窗口                              | 双检均实时读 DB；撤权后第二次检查拒绝；授权后第一次检查拒绝                                                                                | 无越权                           |

### 7. 启动与依赖

`TenantContextFilter` 新增 `AuthService` 构造参数；`SecurityConfig` 通过 `@Qualifier("tenantContextFilter")` 注册过滤器并禁用 Servlet 容器重复注册。EV-0758 的 653 个 API 测试全部启动 Spring 上下文并通过，未发现循环依赖或启动回归。Worker 不受影响（过滤器仅在 API）。

### 8. 测试质量

`PlatformCostTenantFilterTest`：

- 继承 `CostApiTestSupport` → `ResearchApiTestSupport` → `ApiIntegrationTestSupport`（`@SpringBootTest(RANDOM_PORT)` + Testcontainers MySQL 8.4.13）；`ApiTestClient` 走真实 HTTP 端口，租户/账号/登录走公开接口或真实登录（种子账号仍真实 `GET /auth/csrf` + `POST /auth/login`），不是 mock；
- 平台非管理员用空角色种子，确实没有 `platform.cost.read`；
- 断言覆盖 403 `RICCON-AUTH-403`、无 `items`/`totals`/金额/租户代码，以及审计的 actor、tenant、`target_type=TENANT`、`target_id`、`outcome=DENIED`、`reason`；
- 保留窗口 `2027-04-07`（`2027-04-06T16:00Z..2027-04-07T16:00Z`）在仓库其他测试中未被使用；`@BeforeEach` 精确删除该日调用行，`@AfterEach` 按本类创建的 model id 删除，未发现与 `CostAdminApiTest` 等冲突；
- 4 个用例均无 `@Disabled`、`assumeTrue`、空断言或依赖顺序；每个用例创建唯一租户/用户/模型，审计查询按唯一 actor 过滤；
- `@Tag("security")` 与继承的 `@Tag("integration")` 共存，`-Dgroups=security` 可选中（CODING-STANDARDS 映射漏项见 F-003，不影响选择）；
- 未覆盖的组合：匹配文件头 + 不匹配查询参数（F-001）；审计写入失败（未验证）；`hasPlatformCostPermission` 的通用 `RuntimeException` 分支（未验证）。

### 9. 文档核查

- ADR-0009:172-184 新增“平台成本端点例外”小节，路径/方法、主体、权限强制点、头处理、拒绝行为、负向测试、残余均与代码一致；
- ADR-0022 §2.4:243-244 明确 `isPlatformCostFilter` 的命中条件、filter 内 `requirePermission`、`PermissionInterceptor` 双保险，以及 `X-Tenant-Id` 不参与过滤；:249 已把空白 `tenantId` 改为“400、TASK-0141 已落地”，TASK-0141 F-002 的过时句已删除；
- `TenantContextFilter` 类级 javadoc 仍写“任何不一致立即拒绝”，见 F-002；
- ADR-0022:16 的 2026-11-22 TASK-0143 修订历史注记仍保留“空白 tenantId 的 400 校验待 M20/TASK-0141 落地”的历史措辞；它是修订记录而非 §2.4 正文，当前正文已更新，不作为本任务 finding，但 M20 文档整理时可一并清理；
- CODING-STANDARDS §10.4 security 映射未列 `PlatformCostTenantFilterTest`，见 F-003。

## 验收对账

| TASK-0142 验收项                                                                | 结果                  | 依据                                                                                                       |
| ------------------------------------------------------------------------------- | --------------------- | ---------------------------------------------------------------------------------------------------------- |
| filter 内校验 `platform.cost.read`，或 ADR-0009 记录例外并证明 defense-in-depth | pass                  | `TenantContextFilter.java:136-193`；ADR-0009:172-184；EV-0757 的 4 个用例                                  |
| 负向测试覆盖平台非管理员、`X-Tenant-Id` 伪造、其他路径/方法                     | pass                  | `PlatformCostTenantFilterTest` 4 个用例；EV-0757/EV-0758                                                   |
| 独立 Verifier 高风险安全评审并绑定证据                                          | pass                  | 本 review；EV-0757/EV-0758/EV-0759                                                                         |
| ADR-0022 记录 `X-Tenant-Id` 不参与过滤及安全边界                                | pass                  | ADR-0022:244                                                                                               |
| 清理 §2.4 “normalizeTenantFilter 仍把空白归一为未提供”过时句                    | pass                  | ADR-0022:249（正文已改为 400、TASK-0141 已落地）；历史注记 :16 见剩余风险                                  |
| 平台非管理员在 filter/interceptor 任一路径 403 且无数据；头不改变过滤结果       | pass（带 F-001 残余） | 单参数 query/header 均 403 + 审计；无参数由 interceptor 403；匹配 header + 不匹配 query 的合并场景见 F-001 |

## 未验证

- 未重跑全量 API（EV-0758 已记录 653 tests，提交 `22e1be9`）；未跑 E2E、coverage、`gate check`（按任务级评审范围）。
- 未注入审计写入失败来动态验证 500；仅按 `denyMismatchedTenant` 的 `catch (RuntimeException)` 代码路径审查为 fail-closed。
- 未动态覆盖 `hasPlatformCostPermission` 的通用 `RuntimeException` 分支（权限读取异常）；代码路径明确转为 403。
- 未验证真实浏览器/UI（本任务无 UI 变更）。
- Worker 与迁移未运行；本任务未改 Worker、迁移或契约。

## 剩余风险

1. **F-001（P2 accepted）**：匹配主体的 `X-Tenant-Id` 会掩盖不匹配的 `tenantId`，使过滤器跳过拒绝/审计；当前唯一消费 `tenantId` 的平台成本端点仍有 `PermissionInterceptor` 兜底，且租户端点不读取该参数，因此无跨租户数据；新增消费 `tenantId` 查询参数的端点前必须修复。
2. **双重权限查询**：filter 与 interceptor 各调用一次 `requirePermission`（每次 `loadCurrentUser` 读租户/账号/角色/权限）；ADR-0009:184 已记录为可接受成本。该路径只影响平台管理员带 `tenantId` 的请求，不影响所有请求。
3. **例外信任 `platform` 租户代码与精确 URI**：新增平台成本路径或修改路由必须同步过滤器、ADR-0022 §2.4 与本测试；ADR-0009:184 已记录。
4. **会话失效返回 403**：见 F-004；fail-closed，但状态码与 `missing_permission` 审计原因对“会话失效”不精确。
5. **历史注记**：ADR-0022:16 的修订历史仍保留“待 TASK-0141 落地”的旧措辞；当前 §2.4 正文已更新，不构成规范性口径冲突。

## 下一步

1. Lead 在 M20 gate 前决定 F-001 的修复方式（分别检查 header 与 query，或对 query 单独触发例外判断）；若接受，应把 due 与 owner 带入 M20 模块 review。
2. builder-core 在后续触碰 `TenantContextFilter` 时修正类级 javadoc（F-002），并评估会话失效 401/审计原因（F-004）。
3. architect 更新 `docs/CODING-STANDARDS.md` §10.4 的 security 映射（F-003）。
4. M20 模块 review 仍需在最终提交上跑全量合同 build/test/coverage/E2E/a11y/security 并绑定模块 `dod_evidence`。
