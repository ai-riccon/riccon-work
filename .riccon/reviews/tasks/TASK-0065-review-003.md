---
review_id: REV-TASK-0065-003
module_id: M10
task_id: TASK-0065
reviewer:
  agent: riccon/verifier
  session: ses_df2728ecbffemLxQuSwhpsQOMZ
  model: opencode-go/deepseek-v4.1-flash
  independent: true
verdict: pass
previous_review: REV-TASK-0065-002
reviewed_commits: [fb6661b, e07f35b, f60435d, 9a5651e]
head_commit: 9a5651efca5b9b13e06888cd31da11de0b445559
reviewed_evidence:
  [
    EV-0399,
    EV-0400,
    EV-0401,
    EV-0402,
    EV-0409,
    EV-0410,
    EV-0411,
    EV-0493,
    EV-1015,
  ]
evidence: [EV-0409, EV-0410, EV-1015]
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: RoleDefinitions 两条导出审批权限已由 B3a 收口
    summary: "REV-TASK-0065-002 的唯一 P1 已由 TASK-0068（B3a，e07f35b）修复并经 REV-TASK-0068-001 独立确认：TENANT_ADMIN 仅新增 audit.export.approve，PLATFORM_ADMIN 仅新增 platform.audit.export.approve，MEMBER 不含任何审计权限；V10 权限字典/既有角色回填与新租户/空库引导两条路径均有测试。当前 HEAD 上 RoleDefinitionsTest 5/5、V10MigrationTest 11/11 通过。"
    evidence: "REV-TASK-0068-001 F-001；apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:79-81、98-100；apps/api/src/test/java/com/riccon/api/identity/domain/RoleDefinitionsTest.java:54-72；apps/api/src/main/resources/db/migration/V10__audit_query_export.sql:84-135；apps/api/src/test/java/com/riccon/api/migration/V10MigrationTest.java:496-532、579-611；EV-0409、EV-0410、EV-1015"
  - id: F-002
    severity: P2
    status: accepted
    owner: riccon/ops
    due: M09 gate 前
    accepted_reason: "B2 已在创建/绑定/解绑路径拒绝 platform 租户（TenantUserAdminService:566、OidcIdentityBindingApiTest B2-02/B2-10），platform 租户无法创建 OIDC 账号；配置解析仍接受 tenantCode=platform，但无账号可用，无法形成登录路径。M09 运行手册需明确禁止 platform 配置项。"
    title: platform 租户 OIDC 绑定已拒绝，配置侧 platform 约束转 M09
    summary: "核心越权路径已关闭；配置侧拒绝 platform tenantCode 仍缺测试/运行手册约束。"
    evidence: "apps/api/src/main/java/com/riccon/api/admin/service/TenantUserAdminService.java:566；apps/api/src/test/java/com/riccon/api/m10/identity/OidcIdentityBindingApiTest.java:127-140、386-410；REV-TASK-0069-001 F-002"
  - id: F-003
    severity: P3
    status: accepted
    owner: riccon/ops
    due: M09 gate 前
    accepted_reason: "OidcIdpRegistry 已校验 absolute http(s)、无 query/fragment、去尾斜杠；生产 HTTPS fail-fast、Secure Cookie 与反代访问日志脱敏属 M09 部署配置，M10 不声称已强制。"
    title: publicBaseUrl 生产 HTTPS fail-fast（OPS-015）
    summary: "当前允许生产 http，需 M09 配置基线兜底。"
    evidence: "apps/api/src/main/java/com/riccon/api/oidc/service/OidcIdpRegistry.java:206-245；TASK-0064-review-002 OPS-015"
  - id: F-004
    severity: P3
    status: accepted
    owner: riccon/lead + riccon/verifier
    due: M10 归档完成时
    accepted_reason: "EV-0402 是历史证据，其 673 计数含 2026-11-18 陈旧 surefire 报告；当前结论改用 2026-11-20 重录的 EV-0493（后端 765、前端 384，0 fail/0 error/0 skip），不再以 673 作为当前计数。历史 EV 不改写。"
    title: EV-0402 计数含陈旧报告，当前结论改用 EV-0493
    summary: "证据叙述已纠正，代码/覆盖率结论不受影响。"
    evidence: "EV-0402；EV-0493；REV-M10-002 §0"
  - id: F-005
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: M09 gate 前
    accepted_reason: "V1 真实栈已补失败路径会话属性扫描与 401；登录前旧 CSRF 令牌失效仍未单列断言。属测试护栏缺口，不影响当前行为。"
    title: CSRF 旧令牌失效与失败路径会话属性断言仍缺
    summary: "真实栈失败清理已覆盖，旧 CSRF 断言待补。"
    evidence: "OidcLoginFlowTest.java:208-251、438-452；REV-M10-002 F-011；EV-0448"
  - id: F-006
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: M09 gate 前
    accepted_reason: "state_invalid/重放/login_failed 的 auth.login.failed 细粒度断言与成功审计 sessionIdHash 内容校验仍未补齐；真实栈已覆盖失败原因码与会话清理。"
    title: 失败审计覆盖与 sessionIdHash 校验仍缺
    summary: "审计动作存在，断言粒度不足。"
    evidence: "OidcLoginFlowTest.java:379-433；OidcLoginService.java:168-189；REV-M10-002 F-011"
  - id: F-007
    severity: P3
    status: accepted
    owner: riccon/architect
    due: M09 gate 前
    accepted_reason: "OpenAPI 已记录 authorize 404/429 与 callback 404，OidcAuthorizePreflightFilter 对未知/停用注册返回 404、providers 限流返回 429；callback 未知注册仍以登录页原因码呈现，未单列 404 断言，作为契约/测试残余由 architect 收敛。"
    title: OpenAPI callback 404 / authorize 429 漂移已部分对齐
    summary: "authorize 侧已对齐；callback 未知注册的失败语义未单列。"
    evidence: "contracts/openapi.yaml:299-350；apps/api/src/main/java/com/riccon/api/oidc/controller/OidcAuthorizePreflightFilter.java:85-100；REV-TASK-0065-002 F-007"
  - id: F-008
    severity: P3
    status: accepted
    owner: riccon/verifier + riccon/ops
    due: M09 gate 前
    accepted_reason: "B1 已交付 header 与联合登出；OIDC 无 end-session 场景与 IdP 不可达同页提示由真实栈 E2E/V1 覆盖；endSessionEndpoint 的 fragment/query 校验仍建议 Ops 在 M09 配置评审加约束。"
    title: endSessionEndpoint fragment/query 与 AC-12 UI 提示
    summary: "功能路径已覆盖，边界配置校验待补。"
    evidence: "OidcConfigurationParser.java:170-188；OidcLogoutService.java:73-81；REV-M10-002 §3；EV-0448"
  - id: F-009
    severity: P3
    status: accepted
    owner: riccon/verifier
    due: M09 gate 前
    accepted_reason: "invalid_request/invalid_grant 的映射不会泄露敏感信息，但可能把 IdP/token 错误归为 state_invalid/login_failed；完整失败原因矩阵仍待 V1 补测。"
    title: invalid_request/invalid_grant 失败原因映射需矩阵确认
    summary: "行为安全，归因粒度待补。"
    evidence: "apps/api/src/main/java/com/riccon/api/oidc/controller/OidcFailureReasonMapper.java:89-109；REV-TASK-0065-002 F-009"
  - id: F-010
    severity: P3
    status: fixed
    title: B3b 任务卡已创建并完成
    summary: "TASK-0071（M10-B3b 审计查询/导出 API）已创建、加入 M10.tasks 并 done，评审 REV-TASK-0071-001；B3b 的审计 API、导出与测试承载已落地。"
    evidence: ".riccon/tasks/TASK-0071.json；.riccon/modules/M10.json:17-40；.riccon/reviews/tasks/TASK-0071-review.md；EV-0413"
created_at: "2026-11-24T21:09:00+08:00"
---

# REV-TASK-0065-003：TASK-0065 B1 OIDC 复审（F-001 fixed）

## 1. 结论

**verdict: pass。** `REV-TASK-0065-002` 的唯一 P1（F-001 RoleDefinitions 两条导出审批权限）已由 TASK-0068（B3a）在 `e07f35b` 修复，并经 `REV-TASK-0068-001` 独立确认；当前 HEAD `9a5651e` 上 `RoleDefinitionsTest` 5/5、`V10MigrationTest` 11/11、`SchemaVersionCheckerTest` 9/9 通过。F-002..F-009 已按残余风险逐条 accepted 并写 owner/due/accepted_reason；F-010（B3b 任务卡）已 fixed。

本复审是任务级独立技术验收，不代替模块 gate、Product 预验收或用户最终验收。

## 2. 验证证据

- **EV-1015**（TASK-0064，但覆盖本任务关键类，exit 0、`commit=9a5651e`、`dirty=false`）：`RoleDefinitionsTest` 5/5、`V10MigrationTest` 11/11、`SchemaVersionCheckerTest` 9/9、`OidcStartupValidationTest` 2/2、`OidcConfigurationParserTest` 11/11，合计 38 tests、0 skip。
- **EV-0409**：B3a 全量 `./mvnw -q -T1C verify`，exit 0、`commit=e07f35b`、`dirty=false`；`REV-TASK-0068-001` 已复核 V10 权限字典/回填、RoleDefinitions、worker schema 与回滚。
- **EV-0410/EV-0411**：B3a 评审的定向测试与治理检查，exit 0、`commit=8e2ca65`、`dirty=false`。
- **EV-0493**：M10 合同 test/coverage 全量重录，exit 0、`commit=cb977f9`、`dirty=false`；用于 F-004 的证据口径纠正。

## 3. F-001 复核

- `RoleDefinitions.java:79-81`：TENANT_ADMIN 仅新增 `audit.export.approve`；`:98-100`：PLATFORM_ADMIN 仅新增 `platform.audit.export.approve`；MEMBER 仍无审计权限。
- `RoleDefinitionsTest.java:54-72`：断言两条审批权限只出现在对应角色，MEMBER 不含 `audit.read`/两条审批权限。
- `V10__audit_query_export.sql:84-135`：权限字典两条 + 既有 TENANT_ADMIN/PLATFORM_ADMIN 回填；`V10MigrationTest:496-532、579-611` 覆盖既有租户回填、空库 V1–V10 fresh install 与 B1 租户幂等。
- 结论：F-001 **fixed**；证据链 `REV-TASK-0068-001` + EV-0409/EV-0410 + EV-1015。

## 4. F-002..F-010

- **F-002（P2，accepted）**：platform 租户的创建/绑定/解绑已被 B2 拒绝（`TenantUserAdminService:566`，`OidcIdentityBindingApiTest` B2-02/B2-10）；配置解析仍接受 `tenantCode=platform`，但没有 platform OIDC 账号可创建，无法形成登录；M09 运行手册需禁止 platform 配置项。
- **F-003（P3，accepted）**：publicBaseUrl 已做基础 URL 校验，生产 HTTPS fail-fast/日志脱敏归 M09。
- **F-004（P3，accepted）**：EV-0402 的 673 含陈旧报告，当前结论使用 EV-0493 的 fresh 计数；历史 EV 不改写。
- **F-005/F-006（P3，accepted）**：真实栈失败清理已覆盖，旧 CSRF 失效与失败审计细粒度断言仍待 V1/M09 补强。
- **F-007（P3，accepted）**：authorize 404/429 已与 OpenAPI/preflight 对齐；callback 未知注册的 404 语义仍未单列断言。
- **F-008/F-009（P3，accepted）**：end-session 边界与失败原因矩阵归 V1/Ops。
- **F-010（P3，fixed）**：TASK-0071 已建卡并 done（REV-TASK-0071-001），B3b 审计 API/导出有承载。

## 5. 未验证与剩余风险

1. 本次未重跑 OIDC 真实栈 E2E/a11y/security；这些由 M10 模块评审的 EV-0440/EV-0439/EV-0436/EV-0448 覆盖，本轮不扩大结论。
2. F-002 的配置侧 platform 约束、F-005/F-006 的断言补强、F-007 的 callback 语义、F-008/F-009 的端到端矩阵仍属残余，已按 owner/due 跟踪。
3. B1 代码未在本轮修改；本复审只确认 F-001 已由 B3a 收口，并复核当前 HEAD 上的测试仍通过。

## 6. 回执

- **F-001：fixed。** B1 的 RoleDefinitions 两条导出审批权限不再悬空。
- **F-002..F-009：accepted（owner/due/reason 见 front matter）；F-010：fixed。无 open P2/P3。**
- **剩余风险：** platform 配置侧约束、旧 CSRF/失败审计断言、callback 404 语义、end-session/失败原因矩阵；均不影响 F-001 关闭与 M10 gate。
