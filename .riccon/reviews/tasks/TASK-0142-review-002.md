---
review_id: REV-TASK-0142-002
module_id: M20
task_id: TASK-0142
reviewer:
  agent: riccon/verifier
  session: ses_dfdefca04ffebcXlb9SH0WXeNN
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits:
  - dd22cfda85b8b65a0074f3eb477970b94c2279ac
  - 0bb81b210758d8221ed91803af4c237943aa9f82
  - 1edb537a4f64b4e558b6277eb691bab7a50b9b88
  - b41466c3839c8236de616efd075652fd19a5020f
  - 22e1be9f5a504a9a231609e96ccfd12083f19d3a
  - 9eeb58fe6d3b3277069d993affaa58a2ef0b4ef0
head_commit: 0bb81b210758d8221ed91803af4c237943aa9f82
previous_review: REV-TASK-0142-001
findings:
  - id: F-001
    severity: P2
    status: fixed
    owner: riccon/builder-core
    title: "X-Tenant-Id 与 tenantId 同时出现时只检查第一个值的检测/审计缺口"
    summary: >-
      dd22cfd 把请求头与查询参数分别归一化后保存为两个独立变量，并在 `isPlatformCostFilter`
      之前检查冲突：两者同时存在且不相等时直接 403 `RICCON-AUTH-403` 并写
      `tenant.access.denied`（`reason=tenant_mismatch`），平台管理员也不能用与主体匹配的头掩盖
      伪造的查询参数。冲突时审计 `attemptedTenant` 记录为
      `header=<头值>;query=<查询值>`（仍使用已批准的白名单键，≤200 字符），
      `targetId` 取查询参数值（≤64 字符）。租户管理员、平台非管理员、平台管理员三条路径均有
      真实 HTTP + 审计断言；相同值、query-only、header-only 行为保持不变。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:135-156、247-301、314-363；
      apps/api/src/test/java/com/riccon/api/admin/cost/PlatformCostTenantFilterTest.java:107-133、154-175、210-223；
      apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java:47、210；
      .riccon/evidence/TASK-0142/EV-0760.json、EV-0761.json
  - id: F-002
    severity: P3
    status: fixed
    owner: riccon/builder-core
    title: "TenantContextFilter 类级 javadoc 未描述平台成本窄例外"
    summary: >-
      类级 javadoc 已更新为：租户只从会话主体派生，请求头/查询参数都不是租户来源；不一致默认拒绝
      并审计；两者同时出现且不一致一律拒绝；唯一窄例外是 `platform` 租户 + `GET
      /api/v1/admin/costs` + `platform.cost.read`，过滤器先校验权限、拦截器二次强制；例外下头不参与
      过滤；其他路径/方法/主体继续默认拒绝；ThreadLocal 在请求结束时清理。描述与实现一致。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:37-50、97-163、165-233
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/architect
    due: "M20 模块 gate 前 / M20 文档整理任务"
    title: "CODING-STANDARDS §10.4 的 security 映射仍未列 PlatformCostTenantFilterTest"
    summary: >-
      docs/CODING-STANDARDS.md:242 的 security 清单仍没有新增的
      PlatformCostTenantFilterTest；该类已在 :31 标注 @Tag("security")，并继承
      ApiIntegrationTestSupport 的 @Tag("integration")，-Dgroups=security 与默认全量都能选中，
      只是文档映射不完备。docs/CODING-STANDARDS.md 不在 TASK-0142 write_scope，本轮未修改。
    accepted_reason: >-
      标签本身正确，测试选择与默认执行均未被破坏；缺项只影响测试分层文档的可维护性，不影响
      AC、运行时行为或安全边界。作为 P3 接受，由 architect 在 M20 文档整理时把该类补进
      §10.4 的 security 映射。
    evidence: >-
      docs/CODING-STANDARDS.md:240-247；
      apps/api/src/test/java/com/riccon/api/admin/cost/PlatformCostTenantFilterTest.java:31；
      apps/api/src/test/java/com/riccon/api/support/ApiIntegrationTestSupport.java:36-44
  - id: F-004
    severity: P3
    status: fixed
    owner: riccon/builder-core
    title: "平台成本例外内会话失效被归类为 403 missing_permission 而非 401"
    summary: >-
      hasPlatformCostPermission 现在先捕获 UnauthenticatedException，写 401
      `RICCON-AUTH-401`（title=Authentication required，detail=session is missing or
      expired），不写 tenant.access.denied、不进入 filter chain、响应无 items/totals/金额/租户；
      ForbiddenException 与权限读取 RuntimeException 仍 403 + 审计。UnauthenticatedException
      分支位于 ForbiddenException 之前（二者是兄弟异常，顺序不影响语义，但先捕获未认证分支符合意图），
      其他路径和 PermissionInterceptor 未改动。测试通过禁用真实登录账号验证了 401 与无
      tenant.access.denied。
    evidence: >-
      apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java:199-233；
      apps/api/src/test/java/com/riccon/api/admin/cost/PlatformCostTenantFilterTest.java:229-260；
      apps/api/src/main/java/com/riccon/api/error/ErrorCodes.java:15；
      apps/api/src/main/java/com/riccon/api/error/UnauthenticatedException.java:10-14；
      apps/api/src/main/java/com/riccon/api/web/GlobalExceptionHandler.java:297-301；
      apps/api/src/main/java/com/riccon/api/web/JsonAuthenticationEntryPoint.java:44-54
evidence:
  - EV-0757
  - EV-0758
  - EV-0759
  - EV-0760
  - EV-0761
reviewed_evidence:
  - EV-0757
  - EV-0758
  - EV-0759
  - EV-0760
  - EV-0761
notes: "二次独立安全评审（任务级）：F-001/F-002/F-004 已 fixed，F-003 作为 P3 accepted；无 P0/P1、无 open finding。不参与 M20 模块 gate；全量合同 build/test/coverage/E2E/a11y/security 与 gate 仍由 M20 模块 review 承担。"
created_at: "2026-11-22T15:09:48+08:00"
---

# REV-TASK-0142-002：TASK-0142 平台成本租户过滤修复的二次独立安全评审

本文件是任务级独立安全评审的第二轮，聚焦 REV-TASK-0142-001 的 F-001/F-002/F-004 修复和修复引入的新风险；不参与 M20 模块 gate。模块级全量合同命令、E2E、覆盖率、可访问性、安全扫描与 gate 仍由 M20 模块 review 承担。

## 结论

**pass（任务级，F-003 为 accepted 文档残余）**。提交 `dd22cfd` 在 `TASK-0142.write_scope` 内完成了：

- **F-001 fixed**：请求头与查询参数分别解析；两者同时存在且不相等时，在平台成本例外判断之前直接 403 + `tenant.access.denied`（`reason=tenant_mismatch`），租户管理员、平台非管理员和平台管理员一律拒绝；
- **F-002 fixed**：类级 javadoc 准确描述默认拒绝、平台成本窄例外、header/query 冲突拒绝、header 不参与过滤以及 ThreadLocal 清理；
- **F-004 fixed**：例外内会话失效/账号禁用返回 401 `RICCON-AUTH-401`，不写 `tenant.access.denied`、不返回数据；缺少权限和权限读取失败仍 403 + 审计。

未发现 P0/P1，也未发现可绕过的新路径。F-003（CODING-STANDARDS §10.4 security 映射漏列 `PlatformCostTenantFilterTest`）仍是 P3 accepted，owner 为 architect，due 为 M20 模块 gate 前/M20 文档整理任务。任务验收结论为 pass。

## 评审对象与范围

- 基线 `81b4101`；首轮实现 `22e1be9`、证据 `9eeb58f`、首轮评审 `1edb537`、首轮评审时 HEAD `b41466c`；本次修复 `dd22cfd`；证据/状态 `0bb81b2`（HEAD）。
- `git show --stat dd22cfd` 共 4 个文件，全部在写范围内：
  - `apps/api/src/main/java/com/riccon/api/auth/controller/TenantContextFilter.java`（M）
  - `apps/api/src/test/java/com/riccon/api/admin/cost/PlatformCostTenantFilterTest.java`（M）
  - `docs/adr/ADR-0009-tenant-isolation-enforcement.md`（M）
  - `docs/adr/ADR-0022-m04-quota-and-cost.md`（M）
- `git diff --stat b41466c..dd22cfd` 另含 `.riccon/reviews/tasks/TASK-0142-review.md`（A），它是首轮评审 `1edb537` 的产物，不是 builder 的生产变更；除此之外无其他文件。
- `0bb81b2` 仅新增 `EV-0760`/`EV-0761` 的 JSON 与日志，并更新 `.riccon/state.json`、`.riccon/tasks/TASK-0142.json`；未夹带源码、测试、迁移或 ADR。
- `git show --check dd22cfd` 与 `git diff --check b41466c..dd22cfd` 均 exit 0；`./mvnw -q -T1C spotless:check` exit 0；评审开始时工作树干净。
- TASK-0142 仍为 `status=review`，`end_commit=dd22cfd`，evidence 列表包含 EV-0757/0758/0759/0760/0761；本评审未改任务状态。

## 证据核查

| EV      | 命令                                                                                                                                                                                           | 提交      | exit | manual/dirty | 观察值                                                                                        | 哈希/大小                       |
| ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ---- | ------------ | --------------------------------------------------------------------------------------------- | ------------------------------- |
| EV-0760 | `./mvnw -T1C -pl apps/api -am test -Dtest='PlatformCostTenantFilterTest,CostAdminApiTest,CostApiTest,TenantIsolationTest,TenantIsolationSecurityTest' -Dsurefire.failIfNoSpecifiedTests=false` | `dd22cfd` | 0    | false/false  | `PlatformCostTenantFilterTest` 5 tests；合计 59 tests，0 failures/errors/skips；BUILD SUCCESS | `47a3536a…12e4118`，81218 B     |
| EV-0761 | `./mvnw -T1C -pl apps/api -am test`                                                                                                                                                            | `dd22cfd` | 0    | false/false  | 654 tests，0 failures/errors/skips；BUILD SUCCESS                                             | `87533432…349e70dc2`，1408607 B |
| EV-0757 | 与 EV-0760 相同的定向命令                                                                                                                                                                      | `22e1be9` | 0    | false/false  | 首轮 58 tests；`PlatformCostTenantFilterTest` 4 tests；BUILD SUCCESS（已被 EV-0760 覆盖）     | `3ffe713d…e410a96`，82228 B     |
| EV-0758 | `./mvnw -T1C -pl apps/api -am test`                                                                                                                                                            | `22e1be9` | 0    | false/false  | 首轮 653 tests；0 failures/errors/skips；BUILD SUCCESS（已被 EV-0761 覆盖）                   | `6d711463…a3b0d1f`，1408862 B   |
| EV-0759 | `./mvnw -T1C -pl platform/platform-architecture-tests -am test -Dtest=ModuleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false`                                                              | `9eeb58f` | 0    | false/false  | 12 tests；BUILD SUCCESS（架构边界自检；本次修复未改变模块依赖，未重跑）                       | `87ae2ec4…fd90247`，12846 B     |

- 五份 JSON 的 `exit_code=0`、`manual=false`、`dirty=false`；EV-0760/0761 的 `commit=dd22cfd`，EV-0757/0758 为 `22e1be9`，EV-0759 为 `9eeb58f`。
- 独立复算 `shasum -a 256` 与 `wc -c`：EV-0760 `47a3536a411ab7d49907436b12a092ee0c4e01d78061304cf86d40d1c12e4118`/81218；EV-0761 `87533432d90450a333edf76bc518c136952e75a69b3e3663da3dcc1349e70dc2`/1408607；EV-0757/0758/0759 的哈希与大小也与 JSON 一致。
- 日志头部 `# command`/`# exit_code` 与 JSON 一致；EV-0760 日志含 `Tests run: 59`、`PlatformCostTenantFilterTest` 的 `Tests run: 5` 与 `BUILD SUCCESS`；EV-0761 日志含 `Tests run: 654` 与 `BUILD SUCCESS`。
- TASK-0142.evidence 列表为 `EV-0757..EV-0761`，`end_commit=dd22cfda85b8b65a0074f3eb477970b94c2279ac`，与 `0bb81b2` 的更新一致。EV-0760/0761 是最终提交 `dd22cfd` 的权威证据；EV-0757/0758/0759 是首轮历史证据，仍保留但不用于最终提交判定。

## 独立复跑与检查

在 HEAD `0bb81b2`（生产/测试文件与 `dd22cfd` 相同，仅多证据/状态提交）独立重跑：

```bash
./mvnw -T1C -pl apps/api -am test \
  -Dtest='PlatformCostTenantFilterTest,CostAdminApiTest,CostApiTest,TenantIsolationTest,TenantIsolationSecurityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

结果：**exit 0**；`TenantIsolationTest` 12、`TenantIsolationSecurityTest` 31、`CostApiTest` 5、`PlatformCostTenantFilterTest` 5、`CostAdminApiTest` 6；合计 **`Tests run: 59, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS**。日志保存在 `/private/var/folders/jz/47nmh5g113sb1674f5q_ttqm0000gn/T/opencode/task0142-r2.log`（外部临时目录，不提交、不作为 EV）。

辅助检查：`./mvnw -q -T1C spotless:check` **exit 0**；`git show --check dd22cfd`、`git diff --check b41466c..dd22cfd` 均 exit 0。按要求**未**重跑全量 API（EV-0761 已录 654 tests）、未跑 E2E/coverage/`gate check`；这些属于 M20 模块 gate。

## 逐项核查

### 1. F-001：header/query 冲突是否真正修复

**代码路径**（`TenantContextFilter.java:135-156`）：

1. `headerTenant = nonBlank(request.getHeader("X-Tenant-Id"))`，`queryTenant = nonBlank(queryParameter(request, "tenantId"))`；空白值按“未提供”处理（`:350-352`）。
2. **冲突检查在平台成本例外之前**（`:138-142` 早于 `:147` 的 `isPlatformCostFilter`）：两者同时非空且不相等时，`denyMismatchedTenant(..., queryTenant, headerTenant, "tenant_mismatch")` 后直接 `return`。因此平台管理员、平台非管理员、租户管理员都走同一拒绝路径，不进入权限例外或 controller。
3. 两者相等或只出现一个时，才计算 `forgedTenant`（`:144`）并进入原例外/拒绝逻辑；只出现一个时行为与修复前一致。

**动态验证**（`PlatformCostTenantFilterTest.java`，均为真实 HTTP + Testcontainers MySQL）：

- **租户管理员**：`/api/v1/costs?...&tenantId=other` + `X-Tenant-Id: <自身租户>` → 403 `RICCON-AUTH-403`，无 items/totals/金额/租户；审计 3 条（query、header、冲突），冲突行 `reason=tenant_mismatch`，`targetId=other`，details 同时含 `other` 与自身租户（`:154-175`）。
- **平台非管理员**：`platform` 租户空角色用户，`tenantId=other` + `X-Tenant-Id: platform` → 403 且审计；3 条审计中 2 条 `missing_permission`（两个单参数用例）、1 条 `tenant_mismatch`（冲突），冲突行 details 同时含 `other`、`platform`（`:107-133`）。
- **平台管理员**：`tenantId=other` + `X-Tenant-Id: <其他租户>` → 403 且 `tenant_mismatch` 审计（`:210-223`）；即使主体有 `platform.cost.read` 也不能放行冲突请求。
- **相同值/单值**：平台管理员 `tenantId=other` 单独过滤返回 1 个桶、`0.006000`；`tenantId=other` + `X-Tenant-Id: other` 相同值同样过滤；只带 `X-Tenant-Id: other` 时仍返回 2 个桶、`0.009000`，证明头不参与过滤（`:188-208`）。
- 平台非管理员单参数 query/header 均 403 且 `missing_permission`（`:99-106`）；租户管理员单参数 query/header 均 403（`:146-153`）。

**审计与响应**：

- 冲突 details 使用已批准的白名单键 `attemptedTenant`（`AuditDetailsPolicy.java:47`），值为 `header=<头值>;query=<查询值>`；`AuditDetailsPolicy` 单值上限 200（`:210`、`:260-271`），过滤器用 `truncate(..., 200)` 保证不超限（`:256-262`、`:361-363`），未新增字段。
- `targetId` 取查询参数值（`:261`）；正常租户代码 ≤64，测试断言 `targetId=other`（`:125`、`:172`、`:221`）。
- 403 由 `ProblemWriter` 写 `application/problem+json`，detail 固定为 “tenant does not match authenticated principal”（`:294-300`），不含租户、金额、明细或权限码；测试 `assertForbiddenWithoutCostData` 断言无 items/totals 和金额/租户值（`:390-400`）。
- 审计写入失败仍返回 500（`:279-293`），不返回 403 或数据；本次未注入审计失败做动态用例，代码路径保持 fail-closed。

**结论**：F-001 在代码、测试和审计三个层面均已修复；首轮“匹配头的 header 掩盖伪造 query”的绕过不再成立。

### 2. F-002：类级 javadoc 是否准确

`TenantContextFilter.java:37-50` 现在明确：

- ADR-0009 的租户只从会话主体派生，请求头/查询参数都不是租户来源；与主体不一致默认拒绝并审计；
- 两者同时出现且不一致时一律拒绝，任何一方不能掩盖另一方（`:41-42`）；
- 唯一窄例外：`platform` 租户 + `GET /api/v1/admin/costs` + `platform.cost.read`；过滤器先校验权限，controller 仍由 `PermissionInterceptor` 二次强制；例外下 `X-Tenant-Id` 不参与过滤，过滤值只来自查询参数；其他路径/方法/主体继续默认拒绝（`:44-47`）；
- 每个请求结束时清理 ThreadLocal（`:49`）。

与方法级 javadoc（`:165-233`）、ADR-0009（`:172-184`）和 ADR-0022（`:243-244`）一致；未发现该类级 javadoc 的新漂移。**F-002 fixed**。

### 3. F-004：会话失效/账号禁用是否返回 401

**代码**（`TenantContextFilter.java:199-233`）：

- `hasPlatformCostPermission` 先 `try { authService.requirePermission(...) }`；`requirePermission` 每次 `loadCurrentUser` 从数据库读租户、账号、角色、权限（`AuthService.java:309-328`、`:360-365`）。
- `catch (UnauthenticatedException)`（`:209-218`）：写 401 `RICCON-AUTH-401`，title “Authentication required”，detail “session is missing or expired”，然后 `return false`。不写 `tenant.access.denied`。
- `catch (ForbiddenException)`（`:219-222`）：`denyMismatchedTenant(...missing_permission)` 写 403 + 审计后 `return false`。
- `catch (RuntimeException)`（`:223-232`）：记录 ERROR，按 403 `missing_permission` 拒绝后 `return false`；异常不会逃逸出过滤器。
- 调用方在 `hasPlatformCostPermission` 返回 false 时直接 `return`（`:153-155`），不继续 filter chain，也不会重复写响应。

`UnauthenticatedException` 与 `ForbiddenException` 都继承 `RicconException`（`UnauthenticatedException.java:6`、`ForbiddenException.java:6`），是兄弟异常；`UnauthenticatedException` 分支在 `ForbiddenException` 之前，顺序不影响语义，但先捕获未认证分支符合意图。`PermissionInterceptor` 和其他路径未改动（`PermissionInterceptor.java:46-65`）。

**动态验证**（`:229-260`）：真实登录平台用户 → SQL 将 `app_user.status` 置为 `DISABLED` → 带 `tenantId=other` 请求 `/api/v1/admin/costs`，断言 401、`code=RICCON-AUTH-401`、无 items/totals、响应不含 other 租户或金额、该 actor 无 `tenant.access.denied`。与常规 `PermissionInterceptor` 401 语义一致。

**文案/错误码**：`ErrorCodes.AUTH_UNAUTHENTICATED = "RICCON-AUTH-401"`（`ErrorCodes.java:15`）是既有错误码；`UnauthenticatedException` 的 title/detail 也是既有文案（`:10-14`）。filter 的 401 detail 与 `GlobalExceptionHandler` 的 “Authentication is required.”、`JsonAuthenticationEntryPoint` 的 “Authentication is required to access this resource.” 略有措辞差异，但状态码、title、code 一致，且 filter 内既有的“session tenant is invalid”也采用同类脱敏 detail，不构成泄露或语义错误。**F-004 fixed**。

### 4. 新风险与绕过检查

| 检查项                                                    | 观察                                                                                                                                                                                                                                                                          | 结论                                                                                         |
| --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| header/query 编码、空值、重复参数、大小写、trailing slash | `queryParameter` 手工按 `&` 拆分并 `URLDecoder` 解码（`:314-342`）；空白归一为未提供（`:350-352`）；冲突比较区分大小写/空白；重复参数 filter 与 Spring 都取第一个；编码参数名可命中，非法编码当空串；trailing slash 不命中例外（`isPlatformCostFilter` 精确 URI，`:177-181`） | 无越权；不一致时 fail-closed                                                                 |
| POST/HEAD/其他路径                                        | 例外只匹配 `"GET".equals(method)`；POST `/api/v1/admin/costs`、`/api/v1/admin/costs/`、`/api/v1/users` 均由测试覆盖 403 + 审计（`PlatformCostTenantFilterTest.java:265-316`）                                                                                                 | 无越权                                                                                       |
| 冲突时 `targetId` 取 query 值                             | 正常场景（query 为伪造租户）`targetId` 有意义；反向冲突（header=other、query=自身）时 `targetId` 可能指向自身/良性值，但 details 同时记录 `header=...;query=...` 与 reason，审计事实仍完整                                                                                    | 低风险；建议后续评估 targetId 置空或以 details 为准                                          |
| 审计 details 复合格式注入/超长                            | 值经 Jackson 序列化进 JSON，不是可执行文本；仅使用 `attemptedTenant`/`reason` 两个白名单键，长度 ≤200；外部值可含 `;`/`=`/换行，但复合串不被机器解析                                                                                                                          | 无策略绕过；长 header 可能截断 query 部分（见剩余风险）                                      |
| `truncate` null/多字节                                    | 当前所有调用点均保证 `forgedTenant`/`conflictingHeader` 非空；`truncate` 按 Java UTF-16 长度截断，与 `AuditDetailsPolicy` 的 `String.length()` 口径一致；目标列 details 为 JSON、target_id 为 VARCHAR(64)                                                                     | 当前无 NPE；代理对边界可能截断半个码元，最坏审计写入失败返回 500，仍 fail-closed，不构成越权 |
| `hasPlatformCostPermission` 403/401 分支重复写/继续执行   | 401 分支写响应后 `return false`；403 分支由 `denyMismatchedTenant` 写响应后 `return false`；调用方 `return`（`:153-155`）；未发现二次写入或继续 filter chain                                                                                                                  | 无重复响应/无继续链                                                                          |
| `AuthService` 注入/双重查询                               | 过滤器构造注入 `AuthService`（`:89-95`）；例外请求 filter + `PermissionInterceptor` 各查一次权限；ADR-0009:184 已记录为可接受成本；EV-0761 全量启动上下文通过                                                                                                                 | 可接受；无循环依赖                                                                           |
| `ProblemWriter` 写入后行为                                | `write` 设置 status/content-type/encoding 并写 ProblemDetail（`ProblemWriter.java:44-52`）；所有失败分支写后返回，未继续业务链                                                                                                                                                | 无数据泄露/无继续执行                                                                        |
| 会话失效 401 与常规路径一致性                             | filter 401 与 `PermissionInterceptor` 的 `UnauthenticatedException` 均由既有 `RICCON-AUTH-401` 映射；未改变 token/CSRF/其他端点行为                                                                                                                                           | 一致                                                                                         |

未发现新的 P0/P1/P2 绕过。需要说明的是：新逻辑对“header 与 query 同时提供但值不同”的客户端一律 403；这是有意的安全收紧，调用方必须保证两值一致或不发送头，ADR-0009/ADR-0022 已记录。

### 5. 证据

- EV-0760（`dd22cfd`，exit 0，dirty=false）：定向 59 tests，`PlatformCostTenantFilterTest` 5/5，覆盖 F-001 三条冲突路径和 F-004 401；EV-0761（`dd22cfd`，exit 0）：全量 API 654 tests，BUILD SUCCESS。两者的 hash/size 与 JSON 一致，日志含对应 `Tests run` 和 `BUILD SUCCESS`。
- EV-0757/0758（`22e1be9`）、EV-0759（`9eeb58f`）仍保留且可复算；它们是首轮证据，最终提交判定以 EV-0760/0761 为准。TASK-0142 的 `end_commit=dd22cfd`，evidence 列表未丢历史证据。
- 本评审独立重跑 59 tests（exit 0，5/5）与 `spotless:check`（exit 0）；未将复跑写成新 EV，避免改动任务证据状态。

### 6. F-003

`docs/CODING-STANDARDS.md:242` 的 security 映射仍只列 `DepartmentIsolationTest`、`TenantIsolationSecurityTest`、`TenantIsolationTest`、`ChatScopeApiTest`、`ContentScopeApiTest`、`M04Verifier*`、`ModelCredentialSecurityTest`、`ModelRoutingAuditSecurityTest`，没有 `PlatformCostTenantFilterTest`。该类 `@Tag("security")`（测试文件 `:31`），并继承 `ApiIntegrationTestSupport` 的 `@Tag("integration")`（`ApiIntegrationTestSupport.java:41`），因此 `-Dgroups=security` 仍能选中，默认全量不受影响。`docs/CODING-STANDARDS.md` 不在本任务写范围，本轮不改；作为 **P3 accepted**，owner `riccon/architect`，due M20 模块 gate 前/M20 文档整理任务。

## 验收对账

| TASK-0142 验收项                                                                | 结果                 | 依据                                                                                                       |
| ------------------------------------------------------------------------------- | -------------------- | ---------------------------------------------------------------------------------------------------------- |
| filter 内校验 `platform.cost.read`，或 ADR-0009 记录例外并证明 defense-in-depth | pass                 | `TenantContextFilter.java:199-233`；ADR-0009:172-184；EV-0760 的 5 个用例                                  |
| 负向测试覆盖平台非管理员、`X-Tenant-Id` 伪造、其他路径/方法                     | pass                 | `PlatformCostTenantFilterTest.java:82-316`；EV-0760/0761                                                   |
| 独立 Verifier 高风险安全评审并绑定证据                                          | pass                 | 本 review；EV-0760/0761                                                                                    |
| ADR-0022 记录 `X-Tenant-Id` 不参与过滤及安全边界                                | pass                 | ADR-0022:243-244；ADR-0009:181-183                                                                         |
| 清理 §2.4 “normalizeTenantFilter 仍把空白归一为未提供”过时句                    | pass                 | ADR-0022:249（正文已为 400、TASK-0141 已落地）；首轮评审已核对，本次未回退                                 |
| 平台非管理员在 filter/interceptor 任一路径 403 且无数据；头不改变过滤结果       | pass（F-001 已修复） | 单参数 query/header 均 403 + 审计；无参数由 interceptor 403；冲突场景 403；相同值/header-only 行为符合 ADR |

## 未验证

- 未重跑全量 API（EV-0761 已录 654 tests，提交 `dd22cfd`）；未跑 E2E、coverage、a11y、security 扫描与 `gate check`（任务级评审范围，M20 模块 review 承担）。
- 未重跑 `ModuleBoundaryTest`（EV-0759 在 `9eeb58f`）；本次修复只新增同模块 `UnauthenticatedException` import，未改变模块依赖，全量 API 编译/测试通过。
- 未注入审计写入失败动态验证 500；未动态覆盖 `hasPlatformCostPermission` 的通用 `RuntimeException`（权限读取 DB 失败）分支；两者均按代码路径 fail-closed 审查。
- 未单独构造“平台管理员 `X-Tenant-Id: platform` + `tenantId: other`”用例；该冲突分支与主体无关，已由平台非管理员同参数用例（`:107-114`）和平台管理员 header=其他租户的冲突用例（`:210-223`）共同覆盖。
- 未验证真实浏览器/UI（本任务无 UI 变更）；未验证其他 MySQL 版本/字符集下的多字节审计边界（本地 Testcontainers MySQL 8.4.13）。
- 未验证 M20 模块级 AC、模块 DoD 或模块 gate；本评审仅为任务级安全评审。

## 剩余风险

1. **F-003（P3 accepted）**：`docs/CODING-STANDARDS.md:242` 的 security 映射仍漏 `PlatformCostTenantFilterTest`；标签正确、执行不受影响，由 architect 在 M20 文档整理时补齐。
2. **审计 target_id 语义**：冲突时 `targetId` 取 query 值；反向冲突（header=伪造值、query=自身）时 target_id 可能指向自身/良性值。details 已同时记录 `header=...;query=...` 与 reason，审计事实完整；若未来需要按 target_id 精确检索冲突目标，建议将冲突场景的 targetId 置空或另立审计字段（需 ADR/白名单变更）。
3. **复合 details 截断**：超长 header 可能使 `header=<...>;query=<...>` 在 200 字符处截断，遮蔽 query 部分；`targetId` 仍保存 ≤64 的 query，实际租户代码受 64 字符约束，风险有界。`truncate` 不处理 null（当前调用点均非空），代理对边界可能截断半个码元；最坏触发审计失败返回 500，仍 fail-closed。
4. **401 文案与 ADR 正文精度**：ADR-0009:180 与 ADR-0022:243 的“缺少权限或权限读取失败 → 403 并审计”未显式区分会话失效 401；ADR-0009:183 的负向测试行和代码 javadoc 已记录 401。建议 M20 文档整理时把 401 分支补进 ADR 正文，避免维护者误读（不影响运行时安全）。
5. **双重权限查询**：filter 与 `PermissionInterceptor` 各调用一次 `requirePermission`（每次 `loadCurrentUser` 读租户/账号/角色/权限）；仅影响平台管理员带 `tenantId` 的成本请求，ADR-0009:184 已记录为可接受成本。
6. **例外信任 `platform` 租户代码与精确 URI**：新增平台成本路径或修改路由必须同步过滤器、ADR-0022 §2.4 与本测试；ADR-0009:184 已记录。
7. **兼容性收紧**：header 与 query 同时提供且不同值现在一律 403；依赖旧“header 优先/query 优先”行为的调用方必须修正。该行为已写入 ADR-0009/ADR-0022 并由测试固定。

## 下一步

1. Lead 可依据本 review 与 F-003 accepted 状态决定是否将 TASK-0142 从 `review` 推进到 `done`；高风险安全评审条件已满足，无 P0/P1、无 open finding。
2. Architect 在 M20 文档整理时更新 `docs/CODING-STANDARDS.md` §10.4 的 security 映射（F-003），due M20 模块 gate 前。
3. M20 模块 review 仍需在最终提交上跑全量合同 build/test/coverage/E2E/a11y/security 并绑定模块 `dod_evidence`；本任务 review 不替代模块评审。
4. 如需进一步收紧审计语义，可在 M20 文档整理时补齐 ADR-0009/ADR-0022 的 401 描述，并评估冲突场景 `targetId` 的取值策略（见剩余风险 2/3/4）。
