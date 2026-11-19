---
review_id: REV-TASK-0069-001
module_id: M10
task_id: TASK-0069
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0078_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [397d723]
head_commit: b86a467
reviewed_evidence: [EV-0418, EV-0419, EV-0420, EV-0421, EV-0422, EV-0423]
findings:
  - id: F-001
    severity: P2
    status: open
    owner: "riccon/architect + riccon/lead（迁移/ADR 决策）；riccon/verifier（V1 用例）"
    title: "OIDC 身份键 subject 在 MySQL 中大小写/重音不敏感，弱于 OIDC sub 的精确匹配语义"
    summary: "V2 的 app_user.external_issuer/external_subject 继承表默认 utf8mb4_0900_ai_ci。同一 issuer 下若出现仅大小写或重音不同的两个 subject，唯一键会视为重复（第二个账号无法绑定，409），且按 subject 查询会命中第一个账号，存在账号错配/接管风险。该列与唯一键由 V2 提供，B2 写范围不含迁移，无法在本任务修复；ADR-0019 第 3 节只写‘身份键固定 (tenant_id, issuer, subject)’，未规定排序规则。"
    evidence: "V2__identity_and_workspace.sql:42-50（external_issuer/external_subject 无显式 collation，表默认 utf8mb4_0900_ai_ci；uk_app_user_external_identity 由三列组成）；独立探针（riccon-test-mysql，一次性临时表）：information_schema.COLUMNS 返回 utf8mb4_0900_ai_ci，插入 SubjectABC 后 WHERE subject='subjectabc' 命中 1 行，再插入 subjectabc 报 ERROR 1062 Duplicate entry；OidcBindingJdbc.FIND_BOUND_USER:39-41；TenantUserAdminService.bindOidcIdentity:331-376；ADR-0019 §3（100-109）"
  - id: F-002
    severity: P3
    status: open
    owner: "riccon/builder（TASK-0079）；riccon/verifier（V1/B4 验收）"
    title: "SessionInfo.user.authSource 未填充；管理端已返回，会话摘要保持省略"
    summary: "B2 的管理端路径（创建/列表/绑定/解绑）均返回真实 authSource；AuthController.sessionInfo 仍使用 UserSummaryResponse 的 8 参兼容构造器，authSource 为 null 并被 @JsonInclude(NON_NULL) 省略。OpenAPI 中 authSource 为可选属性，因此不违约，但同一 UserSummary 在会话端与管理端表现不一致。既有 TASK-0079 专门承接；B4 若要读取 session.user.authSource，必须先等该补丁落地，否则只能从 /api/v1/users 获取来源。"
    evidence: "AuthController.java:204-219（8 参构造器，未传 authSource）；UserSummaryResponse.java:26-61（NON_NULL + 兼容构造器）；UserSummaryAuthSourceTest.java:45-55（只验证序列化省略）；OidcIdentityBindingApiTest.java:433-455（注释写‘列表与会话摘要’，实际只测列表）；contracts/openapi.yaml:3095-3125（authSource 可选）；.riccon/tasks/TASK-0079.json（planned）"
  - id: F-003
    severity: P3
    status: open
    owner: "riccon/verifier（V1）+ riccon/architect（ADR 口径）"
    title: "绑定/解绑不撤销既有会话；旧密码会话在凭据删除后仍可用至空闲超时"
    summary: "bind 只更新 app_user 并删除 user_credential，不触发任何会话失效；AuthService.loadCurrentUser 只校验租户/账号状态与角色，不校验 authSource 或凭据存在。因此绑定前建立的密码会话可继续访问，解绑后旧会话同样不失效。ADR-0019 §5 明确允许多并发会话、不做踢出，账号禁用/密码解绑属于撤销路径但未要求即时失效，故当前行为与 ADR 一致；风险受 30 分钟空闲会话限制，属已记录残余风险。V1 应加入显式行为断言，避免未来误以为绑定即撤销。"
    evidence: "TenantUserAdminService.java:331-376（bind 仅 update + deleteCredential + audit）、391-420（unbind 仅 update + upsertCredential + audit）；AuthService.java:308-327（loadCurrentUser 无 authSource/credential 检查）；ADR-0019 §5（149-158，并发会话与撤销路径）"
  - id: F-004
    severity: P3
    status: open
    owner: "riccon/architect + riccon/lead（ADR/白名单决策）；riccon/verifier（V1 审计断言）"
    title: "平台管理员跨租户绑定/解绑审计事件在 platform 租户，details 不含 targetTenantId"
    summary: "平台管理员执行绑定时，审计事件 tenant_id=platform（actor 租户），target_id=userId，details 只有 userId/issuerHash/subjectHash/previousAuthSource。目标租户看不到该事件（M03 F-009 既有 residual），平台审计也无法在 details 内直接看出目标租户；只能通过 userId 回查 app_user 关联。ADR-0019 §3 与 ADR-0020 §6.1 的字段清单本身未包含 targetTenantId，因此实现与 ADR 一致；若要求平台审计自描述，需要改 ADR 与 AuditDetailsPolicy（B1 owner），不能在 B2 静默扩展。"
    evidence: "TenantUserAdminService.java:701-725（appendAudit 使用 actor.tenantId()）、362-373、408-417（details 无 targetTenantId）；AuditDetailsPolicy.java:119-122（USER_OIDC_BOUND/UNBOUND 白名单）；OidcIdentityBindingApiTest.java:318-322（断言审计 tenant=platform）；ADR-0019 §3（108、114）；ADR-0020 §6.1（245-246）；TASK-0071 评审 §6 的 M03 F-009 residual"
  - id: F-005
    severity: P3
    status: open
    owner: "riccon/builder（可选补充）/ riccon/verifier（V1 必测）"
    title: "B2 测试缺若干负向与端到端路径：subject 原值修复、enabled=false、fail-closed、绑定后 OIDC 登录"
    summary: "397d723 把 subject 从 strip() 改为保留原值（修复身份键误匹配），但新增测试只有目标租户 404；现有 subject 均无首尾空白，旧实现也会通过，修复本身无回归断言。另未覆盖：issuer 配置存在但 enabled=false、非法 issuer URL、subject>255、绑定/解绑审计写入失败使业务回滚、解绑后角色不变、以及‘绑定后经 B1 OIDC 流程登录命中同一账号’的端到端路径（B1 的 IT-OIDC-002 用 SQL 直接造绑定数据，未走 B2 API）。威胁模型已把 IT-OIDC-002 归 V1，故不阻塞 B2，但 V1 必须补齐。"
    evidence: "397d723 diff（TenantUserAdminService 581-595；OidcIdentityBindingApiTest 仅 +9 行 404 用例）；OidcIdentityBindingApiTest.java 全文 subject 常量无空白；M10 测试无 @Disabled/assumeTrue；AuditFailClosedTest 只覆盖登录；docs/security/THREAT-MODEL.md:619（IT-OIDC-002 归 V1）"
  - id: F-006
    severity: P3
    status: open
    owner: "riccon/builder（证据计数口径）/ riccon/verifier"
    title: "EV-0418 的测试计数口径不可复现：734/263 与 surefire 报告不一致，且含陈旧报告"
    summary: "按 EV-0418 时间窗（12:53:36–13:06:39）的 surefire 报告重算：API 434（420 个其他套件 + identity 14），worker 262，platform 49，合计 745，0 skip/0 fail/0 error。宣称的 worker 263 多出的 1 个来自 target 里 2026-11-18 的 AgentStreamTimingProbeTest.xml；该测试源码已不存在（仓库与 git 历史均无此路径），是本地探针遗留报告。‘734’本身也与列出的 434+263+49=746 不一致。覆盖率 91.47% line / 73.89% branch 与 jacoco.csv 精确一致。属证据统计口径问题，不是测试失败或假绿。"
    evidence: "EV-0418.json（commit 397d723、dirty=false、exit 0、782,980 ms、日志 SHA-256 一致）；apps/api/target/surefire-reports（434/0/0/0）；apps/worker/target/surefire-reports（fresh 262/0/0/0）；platform/*/target/surefire-reports（49/0/0/0）；apps/worker/target/surefire-reports/TEST-com.riccon.worker.agent.AgentStreamTimingProbeTest.xml（tests=1，mtime 2026-11-18 07:51，源码不存在）"
evidence: [EV-0419, EV-0420, EV-0421, EV-0422, EV-0423]
created_at: "2026-11-19T13:30:00+08:00"
---

# REV-TASK-0069-001：TASK-0069（M10-B2 账号开通/绑定）独立评审

## 1. 结论

**verdict: pass（任务级）。** B2 的 OIDC 创建、绑定/解绑、凭据删除与重建、冲突/租户边界、审计 details、管理端 `authSource` 均可独立复验；P0/P1 = 0。开放 **P2 1 条**（V2 身份键 collation 弱于 OIDC 精确匹配语义，B2 写范围无法修复）与 **P3 5 条**（SessionInfo authSource、会话撤销、平台审计 targetTenantId、测试缺口、EV 计数口径）。

三点边界：

1. 本结论只针对 TASK-0069/REV-TASK-0069-001，不代表 M10 模块 gate，也不代表 ADR-0019/0020 已接受；ADR 仍为 `proposed`，架构评审状态仍为 `changes-requested`（REV-M10-ARCH-001）。
2. 全量证据使用 builder 在 `397d723` 干净工作区录制的 **EV-0418**（`./mvnw -q -T1C verify`，exit 0，13:06:39 结束）；本次按分层要求只独立重跑 `OidcIdentityBindingApiTest` + `UserSummaryAuthSourceTest`（14 tests，0 skip），不重复 13 分钟全量。
3. B2 可 done 的前提是 Lead 记录本评审并接受/承接 F-001 P2（修复或书面接受，最迟在 M10 gate 前关闭）；B4 可在 B2 done 后启动，但不得依赖 `SessionInfo.authSource`，TASK-0079 必须先落地。

## 2. 评审范围与独立复验

评审起点：会话开始时 `git status --short` 空；HEAD `b86a467`（`397d723` 之后只有 `.riccon/` 证据/任务/状态 chore 提交，生产代码零差异）；被评审 B2 代码提交 `397d723`（`ada8089` 功能 + `ea2e658` 收敛 + `397d723` 修复）。

| 检查/命令                                               | 退出码 | 结果                                                                                                                                                                  |
| ------------------------------------------------------- | ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `git status --short`；`git rev-parse HEAD`              | 0      | 会话开始时空；HEAD `b86a467`；`397d723` 仅 2 文件（service + test）                                                                                                   |
| `git show --stat 397d723`                               | 0      | 13 行新增/8 删除；`TenantUserAdminService` + `OidcIdentityBindingApiTest`                                                                                             |
| `git diff --name-only 3a7fc78..397d723`                 | 0      | 生产/测试文件全部落在 B2 的 7 个写范围（admin/{controller,service,identity/**}、identity/dto、test/m10/identity）；无 auth/repository、V10、audit API、Web、contracts |
| `git diff --diff-filter=D --name-only 3a7fc78..397d723` | 0      | 空（无删测/删文件）                                                                                                                                                   |
| EV-0418 审计                                            | —      | `commit=397d723`、`dirty=false`、`exit=0`、782,980 ms；日志 SHA-256 `68036b…` 与 EV JSON 一致                                                                         |
| EV-0418 surefire 重算                                   | —      | API 434 / worker 262（宣称 263 含 1 条陈旧探针报告）/ platform 49，合计 745，0 skip/0 fail/0 error（见 F-006）                                                        |
| EV-0418 JaCoCo                                          | —      | API line 91.47%（6747/7376）、branch 73.89%（1503/2034），高于合同 80/70                                                                                              |
| 独立定向测试（EV-0419）                                 | 0      | `OidcIdentityBindingApiTest` 12 + `UserSummaryAuthSourceTest` 2 = 14 tests，0 fail/0 error/0 skip                                                                     |
| `./mvnw -q spotless:check`（EV-0420）                   | 0      | 通过                                                                                                                                                                  |
| `riccon contract validate --strict`（EV-0421）          | 0      | 0 errors / 0 warnings                                                                                                                                                 |
| `riccon state validate`（EV-0422）                      | 0      | 0 errors；1 条与 B2 无关的 M11 历史警告                                                                                                                               |
| `git diff --check`（EV-0423）                           | 0      | 无空白错误                                                                                                                                                            |

**证据干净度说明：** EV-0419–EV-0423 录制时 `dirty=true`，原因是并行的 TASK-0075/B5 builder 在主工作区留下了未跟踪的 `apps/web/src/pages/admin/audit/**`、`tenant-exports/**`、`services/audit/**`、`apps/web/tests/**` 并修改了 `apps/web/config/routes.ts`。被评审的后端生产代码在 `397d723`/`b86a467` 完全一致，定向命令 `-pl apps/api -am` 不编译这些 Web 文件；EV-0418 仍是本任务严格的干净全量证据。上述 dirty 记录不用于模块 gate。

未重复/未执行：13 分钟全量 `verify`（按分层要求只核对 EV-0418）、`pnpm -w test`、E2E、a11y、security、k6、真实浏览器 UI；这些属 M10-V1/模块 gate。

## 3. OIDC 创建（ADR-0019 §3）

**通过项：**

- `UserCreateRequest.authSource` 省略/`LOCAL` 走 `createLocalUser`，要求 12–200 字符密码并写 `user_credential`；`authSource=OIDC` 走 `createOidcUser`，要求 issuer+subject、拒绝非空密码、不写凭据（`TenantUserAdminService.java:166-190、205-244、259-316`；B2-02/B2-03）。
- issuer 必须命中该租户当前**启用**的 IdP：`requireConfiguredIssuer` 先规范化，再与 `findEnabledByTenantCode` 的 issuer 严格比较；`enabled=false`/未配置返回 `RICCON-OIDC-400`（`TenantUserAdminService.java:566-581`；B2-02 覆盖 mismatch，未单测 disabled，见 F-005）。
- 平台保留租户拒绝 OIDC 账号：`rejectPlatformTenant` 在创建/绑定/解绑前执行（`TenantUserAdminService.java:553-557`；B2-02/B2-10）。
- subject 必填、非空白、≤255；397d723 改为保留原值不 trim（`581-595`），与 B1 `OidcLoginService.validateLogin`（119-124、132-134）的 `sub` 精确查询一致。
- 不按邮箱：B2 不读取/存储/比较邮箱，DTO 无 email 字段，审计白名单禁止 `email`；`OidcBindingRequest` 只有 issuer/subject（`OidcBindingRequest.java`；`AuditDetailsPolicy.java:136-140`）。
- OIDC 创建同一事务写 `user.created`（每个角色一条，含 `role`/`targetTenantId`）与 `user.oidc.bound`（`userId`/`issuerHash`/`subjectHash`，无 `previousAuthSource`），未扩展 `user.created` 白名单（`TenantUserAdminService.java:293-314`；`AuditDetailsPolicy.java:27、121`）。

**边界：** `findUserByUsername` 预检查 + 唯一键兜底，`DuplicateKeyException` 转 `RICCON-OIDC-409`；并发下用户名冲突会被当作 OIDC 冲突（罕见语义偏差，不阻塞）。LOCAL 分支不拒绝多余的 issuer/subject 字段（契约未禁止，行为不变；OpenAPI 的 `additionalProperties: false` 是否在 API 层全局强制不属 B2 变更面，未单独验证）。

## 4. 绑定/解绑与凭据（ADR-0019 §3）

**通过项：**

- 租户端 `PUT /api/v1/users/{userId}/oidc-binding`、`POST …/oidc-binding/remove` 与平台端 `PUT/POST /api/v1/admin/tenants/{tenantId}/users/{userId}/oidc-binding[/remove]` 均落到同一 service，权限分别 `tenant.user.manage` / `platform.tenant.manage`（`TenantUserController.java:144-175`；`TenantAdminController.java:200-226`；B2-04/B2-07/B2-08）。
- 绑定命中同账号/角色：`bind` 只更新 `auth_source/external_issuer/external_subject`，不触碰 `user_role`/部门；响应 `roles` 不变（B2-04；B2-11 证明 DISABLED 状态保留）。
- 单认证来源：绑定成功后删除 `user_credential`，密码登录随即 401（B2-04；`OidcBindingJdbc.DELETE_CREDENTIAL:64-65、186-188`）。
- 解绑必须提供 12–200 字符新密码，恢复 `auth_source=LOCAL`、清空 issuer/subject、`UPSERT user_credential` 并清除失败计数/锁定；新密码可登录；不存在“无凭据解绑”（`OidcBindingJdbc.UNBIND:68-72、UPSERT:75-88、212-220`；B2-09/B2-10）。
- 幂等：同一身份同账号重复绑定返回 200、不重复写审计，并顺手删除残留凭据（B2-05）。
- 冲突：同身份他账号、账号他身份、并发更新失败均 `RICCON-OIDC-409`；issuer mismatch/非法/平台租户 `RICCON-OIDC-400`；跨租户/不存在账号/不存在目标租户 `RICCON-TENANT-404`（B2-06/B2-07/B2-09；`OidcBindingConflictException.java`、`OidcBindingInvalidException.java`；`ErrorCodes.java:71-75`）。
- 并发：`BIND`/`UNBIND` 都是条件 UPDATE（`WHERE auth_source='LOCAL'/'OIDC'`），受影响 0 行转 409；`DuplicateKeyException` 兜底唯一键竞态；审计与凭据操作同一 `@Transactional`，失败回滚（`TenantUserAdminService.java:331-420`；`OidcBindingJdbc.java:57-72、164-220`）。
- 跨租户 SQL 守卫：`PlatformJdbc` 强制 `tenant_id = :tenantId`，参数由 `bindTenant` 覆盖；B2 所有绑定 SQL 均带租户条件（`PlatformJdbc.java:99-210`）。

**未覆盖：** 解绑后角色不变未单独断言；绑定后实际走 B1 OIDC 流程登录未在 B2 测（B1 IT-OIDC-002 用 SQL 直接造数）；审计写入失败回滚未测（见 F-005）。

## 5. 审计（ADR-0019 §3、ADR-0020 §6.1）

**通过项：**

- `user.oidc.bound` details 固定 `userId`/`issuerHash`/`subjectHash`/`previousAuthSource`；`user.oidc.unbound` 固定 `userId`/`issuerHash`；创建路径不带 `previousAuthSource`（`TenantUserAdminService.java:304-314、362-373、408-417`；`AuditDetailsPolicy.java:119-122`）。
- `issuerHash`/`subjectHash` 为 SHA-256 hex；B2 测试用独立计算的 SHA-256 断言，并断言 details **不含** issuer/subject 原文、不含 password 字样（B2-01/B2-04/B2-09）。
- 审计写入与业务同事务、无 catch；审计校验/插入失败会使绑定/解绑/创建回滚（fail-closed；`AuditService.java:35-38`；`AuditEvent.create` 先执行白名单校验）。
- 平台租户拒绝：平台引导管理员无法被绑定/解绑（B2-02/B2-10）。
- EV-0418 日志中未出现测试密码、clientSecret 或 issuer 明文（独立 grep：`initial-password-12`/`restored-password-12`/`b2-test-secret-*`/`idp.b2.example` 均为 0）。

**缺口：** 平台跨租户绑定的审计事件 tenant=`platform`，details 无 `targetTenantId`（F-004）；目标租户看不到该事件是 M03 F-009 既有 residual，B2 未改变。审计 fail-closed 仅由代码路径保证，无 B2 用例（F-005）。

## 6. authSource 与会话

- 管理端 `UserSummary.authSource` 已真实下发：创建/列表/绑定/解绑响应均包含 `LOCAL`/`OIDC`；`UserSummaryResponse` 用 `@JsonInclude(NON_NULL)`，8 参兼容构造器只给旧调用方（`UserSummaryResponse.java:26-61`；B2-03/B2-04/B2-12）。
- `SessionInfo.user.authSource` 未填充：`AuthController.sessionInfo` 仍用 8 参构造器（`AuthController.java:204-219`），字段被省略；OpenAPI 中可选，B4 不能把它当作已交付接口。TASK-0079 已排队（F-002）。
- 会话撤销：绑定/解绑不失效既有会话；`AuthService.loadCurrentUser` 只检查租户/账号状态与角色（`AuthService.java:308-327`）。ADR-0019 §5 允许并发会话、不做踢出，当前行为符合 ADR，但属于需要 V1 明确断言的残余风险（F-003）。
- 不泄露 token/secret：B2 不触碰 token；响应只有 UserSummary 字段；审计只有 hash。

## 7. 测试与假绿审计

- 无删测、无跳测：B2 只新增 `m10/identity` 两个测试文件（12+2）；`git diff --diff-filter=D` 为空；EV-0418 与独立 EV-0419 均 0 skip/0 fail/0 error；测试文件无 `@Disabled`/`assumeTrue`。
- 断言质量：B2-01…B2-12 共 101 处断言/错误码断言，覆盖成功、幂等、冲突、跨租户、权限、禁用、平台租户、凭据行数、审计 details 哈希与原文缺失；不是只做快照。
- 假绿风险：EV-0418 的 worker 263 含 1 条无源码的陈旧探针报告（F-006）；`mvn verify` 未 `clean` 时 target 会保留旧报告，计数应以本次时间窗或 clean 后为准。定向 EV-0419 因并行 B5 未跟踪文件标记 dirty，已在本节与 §2 披露；后端代码未受影响。
- 覆盖率：B2 新增类 `OidcBindingJdbc` line 100%、`TenantUserAdminService` line 94.4%/branch 76.8%、`OidcBindingRepository` line 100%；API 总体 91.47%/73.89%，高于门槛。

## 8. 边界、契约与架构

- 写范围：B2 三个功能提交的净 diff 只落在授权目录；`ea2e658` 先加的 1 行 `AuthController` 改动随后被移除，最终净 diff 不含 `auth/controller`。未改 B1 词汇（`AuditActions`/`ErrorCodes`/`RoleDefinitions`）、V10、审计 API、`contracts/**`、`apps/web/**`。
- 契约：端点/权限/错误码通过 `contract validate --strict`；`OidcBindingRequest`/`OidcUnbindRequest`/`UserCreateRequest`/`UserSummary` 与 openapi 字段、长度、枚举一致。
- ADR-0019 仍 `proposed`，架构评审状态 `changes-requested`（3 P1 待复审）；B2 实现不能改变该状态，M10 gate 前必须由 architect/Lead 复审并记录。
- F-001 是 V2 迁移层的身份键语义缺口，不是 B2 代码可修；需要 architect 决定新增迁移（大小写/重音敏感 collation）或书面接受，并同步 ADR/威胁模型。

## 9. B2/B4/V1 启动条件与承接

- **B2（TASK-0069）**：本评审 pass（P0/P1=0），Lead 记录评审后可置 done。F-001 P2 必须明确 owner 与处理方式（修复或书面接受），最迟 M10 gate 前关闭；F-002…F-006 P3 可接受/承接。
- **B4（TASK-0076，Web OIDC/绑定）**：B2 done 后可启动；用户管理页从 `/api/v1/users` 拿 `authSource`，不依赖 `SessionInfo.authSource`。若 B4 任何页面要读 `session.user.authSource`，必须先完成 TASK-0079；TASK-0079 最迟在 B4 的 authSource 验收前或 V1 前落地。
- **B5（TASK-0075）**：与 B2 无依赖，继续独立推进；不得因本评审修改后端契约。
- **V1（M10 独立验证）**：必须补齐 IT-OIDC-002 端到端（通过 B2 API 绑定 → 真实 OIDC 流程登录 → 命中同一账号/角色）、case-variant subject 探针（F-001）、绑定后既有会话行为（F-003）、平台审计 targetTenantId（F-004）、审计 fail-closed（F-005）；并跑合同 test/coverage/E2E/a11y/security 与模块 gate。B2 的任务级 EV 不能替代模块 gate 证据。

## 10. 未验证与剩余风险

- 未重复 13 分钟全量 verify；未跑 `pnpm -w test`、E2E、a11y、security、k6、真实浏览器 UI、V10 迁移/回滚演练。
- B2 没有跑 B1 的 OIDC 登录流程；绑定数据的“可登录性”由 B1 IT-OIDC-002 的 SQL 夹具间接覆盖，V1 必须闭环。
- F-001 若客户 IdP 的 sub 全部为 UUID/大小写单值，实际风险低；但身份键语义应按 OIDC 规范精确匹配，不能依赖 IdP 实现巧合。
- ADR-0019/0020 仍 proposed；F-003（会话撤销）如需即时踢出，需要产品/架构决策，不能在 B2 静默实现。
- 环境层面：B5 并行未跟踪文件导致本次 EV dirty；EV-0418 仍是本任务唯一干净全量证据。

## 11. 下一步

1. Lead：记录本评审（REV-TASK-0069-001）；接受/承接 F-001 P2（建迁移或 ADR 决议），把 TASK-0069 置 done；启动 B4（B4 不依赖 SessionInfo.authSource；TASK-0079 排入队列）。
2. Architect：决定 F-001 的 collation 修复方式（迁移/ADR/风险接受），并确认 F-004 是否需要 targetTenantId 扩展。
3. Verifier（V1）：在 M10 gate 前补 F-001/F-003/F-004/F-005 的独立用例，运行合同全量命令并写模块评审；不要用 EV-0419 的 dirty 记录作模块证据。
