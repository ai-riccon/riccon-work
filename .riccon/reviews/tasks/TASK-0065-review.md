---
review_id: REV-TASK-0065-001
module_id: M10
task_id: TASK-0065
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0067_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: [2edfa6c, a24871b, 878c89a, fb6661b]
head_commit: b651875
reviewed_evidence: [EV-0399, EV-0400, EV-0401, EV-0402, EV-0403]
evidence: [EV-0403]
findings:
  - id: F-001
    severity: P1
    status: open
    title: "RoleDefinitions 未写入导出审批权限，且没有任务承接；fresh install/新建租户的审批链断裂"
    summary: "B1 只给 TENANT_ADMIN/PLATFORM_ADMIN 增加 audit.read，未增加 audit.export.approve/platform.audit.export.approve；Javadoc 说由 V10+B3 承接，但 ADR-0020 §8 规定 RoleDefinitions 只归 B1、B3 只消费，当前任务图没有任何 owner。V10 的角色回填只覆盖迁移前已存在的 role 行；绿地/fresh install 在 V10 后由 BootstrapAdminRunner/新建租户通过 RoleDefinitions 建角色，因此永远拿不到审批权限，AC-17 的审批 / 平台单管理员审批不可用。"
    evidence: "ADR-0020 §5.2（docs/adr/ADR-0020-m10-audit-query-export.md:193）要求 RoleDefinitions 同步两个 approve 权限；§8（:312）规定 RoleDefinitions 只归 B1、B2/B3 不得修改。apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java:17-19 记录该冲突，:41-70 实际只有 audit.read。apps/api/src/main/java/com/riccon/api/admin/service/TenantAdminService.java:122-124 用 RoleDefinitions 创建新租户角色；apps/api/src/main/java/com/riccon/api/auth/service/BootstrapAdminRunner.java:113-116 在 V10 之后创建平台管理员角色。apps/api/src/test/java/com/riccon/api/m10/oidc/M10VocabularyTest.java:50-52 只断言 audit.read，未覆盖 approve 权限。"
  - id: F-002
    severity: P2
    status: open
    title: "配置校验允许 tenantCode=platform，可能启用 ADR-0019 §10 后置的平台管理员 OIDC 登录"
    summary: "OidcConfigurationParser 只校验 tenantCode 正则，没有拒绝保留租户 platform。若部署配置了 platform 的 IdP 且 B2 绑定端点允许绑定 platform 用户，OIDC 登录会命中 platform 租户并拿到平台管理员权限，这与 ADR-0019 §10“平台管理员 OIDC 登录后置、平台引导管理员仍为内置账号”的边界冲突。B2 未实现前无法证明绑定端点会拒绝 platform；应在 B1 配置校验或 B2 绑定端点二选一显式拒绝，并加负向测试。"
    evidence: "apps/api/src/main/java/com/riccon/api/oidc/domain/OidcConfigurationParser.java:30、82-88（只校验正则/唯一）；apps/api/src/main/java/com/riccon/api/oidc/service/OidcIdpRegistry.java:100-106；apps/api/src/main/java/com/riccon/api/oidc/service/OidcLoginService.java:125-138（按配置 tenantCode 找租户并建会话）；docs/adr/ADR-0019-m10-oidc-sso.md:244（平台管理员 OIDC 登录后置）。"
  - id: F-003
    severity: P3
    status: open
    title: "publicBaseUrl 缺少生产 fail-fast（OPS-015）"
    summary: "OidcIdpRegistry.normalizePublicBaseUrl 接受 http、localhost 与任意路径，只做了绝对地址/去尾斜杠校验。生产误配 http 或 localhost 会造成 redirect_uri 不匹配与 Secure Cookie 失效；OPS-015 建议非 local/test 时 fail-fast 或启动告警。B1 未实现 profile/环境感知校验，仍由 Ops 在 M09 运行手册兜底。"
    evidence: "apps/api/src/main/java/com/riccon/api/oidc/service/OidcIdpRegistry.java:209-239；.riccon/reviews/tasks/M10-OPS-review.md:118-119（OPS-015）。"
  - id: F-004
    severity: P3
    status: open
    title: "EV-0402 的 673 tests 含陈旧 surefire 报告；EV 窗口内 fresh 为 672"
    summary: "EV-0402（./mvnw -q -T1C verify，exit 0，dirty=false）窗口内实际 fresh surefire 汇总为 672 tests / 0 failures / 0 errors / 0 skipped：API 361、worker 262、ArchUnit 12、platform-common 33、platform-domain 4。task brief/回执中的 673 是把 apps/worker/target/surefire-reports 下 2026-11-18 07:51 的 AgentStreamTimingProbeTest 陈旧报告（源码已不存在，1 test）算入；不是本次运行结果。覆盖率不受影响（API line 90.57%、branch 71.83%）。"
    evidence: "find apps platform -path '*/target/surefire-reports/*.txt' -newermt '2026-11-19 07:48:00' | xargs grep -h 'Tests run:' 汇总 672；apps/worker/target/surefire-reports/com.riccon.worker.agent.AgentStreamTimingProbeTest.txt（mtime 2026-11-18 07:51，Tests run: 1）；.riccon/evidence/TASK-0065/EV-0402.json。"
  - id: F-005
    severity: P3
    status: open
    title: "CSRF 只断言新令牌可用，未断言旧令牌失效；失败路径未显式扫描会话属性"
    summary: "IT-OIDC-001 断言成功会话只含 RicconPrincipal 且无 token/PKCE verifier；IT-OIDC-007 断言新 CSRF 可登出，但没有拿登录前/授权会话的旧 CSRF 断言 403，因此“CSRF 轮换 = 旧令牌失效”未被负向验证。失败路径 IT-OIDC-002/004/006 断言 /auth/session 401 与会话行数，但未像 ADR-0019 §2.1 那样显式查询 SPRING_SESSION_ATTRIBUTES 断言无 SPRING_SECURITY_CONTEXT/OidcUser/id_token；依赖 session.invalidate() 删除行来间接证明。"
    evidence: "apps/api/src/test/java/com/riccon/api/m10/oidc/OidcLoginFlowTest.java:208-251（成功扫描）、438-452（CSRF 只用新令牌）、257-271/303-338/379-433（失败 401/行数，无属性扫描）；ADR-0019 §2.1（:96）。"
  - id: F-006
    severity: P3
    status: open
    title: "失败审计覆盖不完整；sessionIdHash 未校验等于新会话 ID"
    summary: "IT-OIDC-006 对 access_denied/tenant_unavailable/idp_unavailable/rate_limited 断言了 auth.login.failed，但对 state_invalid、重放、login_failed 没有断言失败审计；ADR-0019 §4 要求全部失败写审计。成功审计只断言 sessionIdHash 长度为 64，没有断言它等于新会话 ID 的 SHA-256，无法防止实现把旧/空会话 ID 写入。"
    evidence: "apps/api/src/test/java/com/riccon/api/m10/oidc/OidcLoginFlowTest.java:379-433、248-251；ADR-0019 §4（:142-146）。"
  - id: F-007
    severity: P3
    status: open
    title: "OpenAPI callback/authorize 的响应集合与实现存在漂移"
    summary: "contracts/openapi.yaml 为 authorize 声明 429、为 callback 声明 404；实现中 authorize 预检不做限流（429 只属于 providers），未知注册的 callback 由 Spring Security 失败处理器 302 到 login?oidcError=login_failed，不会返回 404。当前契约不报错但会误导前端/测试预期；建议 architect 收敛响应集合或由 B1/V1 补实现与测试。"
    evidence: "contracts/openapi.yaml:248-300；apps/api/src/main/java/com/riccon/api/oidc/controller/OidcAuthorizePreflightFilter.java:83-107（无 429）；apps/api/src/main/java/com/riccon/api/oidc/controller/OidcLoginFailureHandler.java:75-118。"
  - id: F-008
    severity: P3
    status: open
    title: "endSessionEndpoint 允许 fragment/query；OIDC 无 end-session 与 AC-12 UI 提示未覆盖"
    summary: "requireAbsoluteEndpoint 只校验绝对 http(s)，不拒绝 fragment/query；OidcLogoutService 直接在端点后拼 ?/&，若配置带 fragment 会生成无效 URL。IT-OIDC-005 只覆盖 LOCAL 账号无 header 与“OIDC+end-session+IdP 不可达”，未覆盖“OIDC 配置但无 endSessionEndpoint”只退出本地；AC-12 的 IdP 不可达同页提示与自动顶层导航属于 B4/V1，B1 只交付了后端 header。"
    evidence: "apps/api/src/main/java/com/riccon/api/oidc/domain/OidcConfigurationParser.java:170-188；apps/api/src/main/java/com/riccon/api/oidc/service/OidcLogoutService.java:73-81；apps/api/src/test/java/com/riccon/api/m10/oidc/OidcLoginFlowTest.java:341-374；ADR-0019 §5（:152-156）、§8 AC-12。"
  - id: F-009
    severity: P3
    status: open
    title: "失败原因映射把 invalid_request 归为 state_invalid，invalid_grant 归为 login_failed"
    summary: "OidcFailureReasonMapper 将 invalid_request 与 state 错误一起映射为 state_invalid，但 invalid_request 也可能来自 token 端点；invalid_grant/invalid_client 之外的 token 错误会落到 login_failed。两者都不会泄露敏感信息，但会把 IdP 配置/代码错误显示成“重新发起登录”，影响 AC-13 的“下一步”准确性。建议 V1 用真实错误码矩阵确认或补齐映射测试。"
    evidence: "apps/api/src/main/java/com/riccon/api/oidc/controller/OidcFailureReasonMapper.java:89-109；apps/api/src/test/java/com/riccon/api/m10/oidc/OidcFailureReasonMapperTest.java:38-53（未覆盖 invalid_request/invalid_grant）。"
created_at: "2026-11-19T08:16:00+08:00"
---

# REV-TASK-0065-001：M10-B1 OIDC 登录/登出与共享词汇独立评审

## 1. 结论

**verdict: fail（P0=0，P1=1，P2=1，P3=7）。** B1 的实现主体质量达到可交付水平：`RICCON_OIDC_IDPS` 以 String 承接并在应用内脱敏解析；PKCE(S256)+nonce+state 一次性、会话 ID/CSRF 轮换、OIDC-aware 仓储、失败清理、无 token/无 userinfo、联合登出 header、providers 限流与共享审计词汇均有代码与定向测试支撑。独立重跑 45 个 OIDC/词汇用例全部通过（0 skip）；EV-0402 的真实窗口为 **672 tests / 0 skipped**，API 覆盖率 **line 90.57% / branch 71.83%**，高于合同门槛。

**但 TASK-0065 不能按现状 done：** F-001 是一个未被任何任务承接的 P1 角色回填缺口。`RoleDefinitions` 独占 owner 是 B1，但 B1 在 V10 之前不能安全加入 `audit.export.approve`/`platform.audit.export.approve`（权限字典行尚不存在，`TenantAdminService` 创建租户时会触发 `role_permission` 外键失败）；ADR-0020 §8 又规定 B3“只消费、不得修改” RoleDefinitions。绿地项目 V10 在空库上执行时没有任何既有 role 可回填，之后 `BootstrapAdminRunner` 与新建租户都从 RoleDefinitions 建角色，因此**所有 fresh install / 新建租户都拿不到导出审批权限**，AC-17 的“同租户另一管理员审批 / 平台审批单管理员租户”不可用。

**B1 是否可 done：** 在 Lead 记录 F-001 的所有权转移（B3 或新任务在 V10 后补 RoleDefinitions 两行权限 + 测试）之前，**不建议标记 done**。B1 代码本身无需返工；若 Lead 先落一条任务/决策，B1 可作为“代码完成、审批权限由 B3 收口”的 done。**B2 可以启动；B3 代码/V10 可以启动，但 V10 生产执行与 M10 gate 必须等 F-001、OPS-002/004/005/006/007、OPS-014 关闭。**

## 2. 验证范围与命令

评审时工作区干净，HEAD `b651875`（`fb6661b` 是代码最终提交，`b651875` 只记录 EV/状态/任务）。

| 检查/命令                                                                                                            | 退出码 | 结果                                                                                                                                                               |
| -------------------------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `git status --short` / `git rev-parse --short HEAD`                                                                  | 0      | 空输出；HEAD `b651875`                                                                                                                                             |
| `git merge-base --is-ancestor` 2edfa6c/a24871b/878c89a/fb6661b HEAD                                                  | 0      | 四个代码/测试提交均为 HEAD 祖先                                                                                                                                    |
| `git show --stat fb6661b a24871b 878c89a 2edfa6c`                                                                    | 0      | 2edfa6c 48 文件（生产/测试）；a24871b 仅移动 OIDC 基础设施适配器；878c89a/fb6661b 只加测试断言与 EV/状态                                                           |
| `git diff --name-only 7ff2409..fb6661b -- . ':!.riccon'`                                                             | 0      | 48 个代码/测试文件全部落在 TASK-0065 的 8 个 write_scope 内；无 binding/V10/Web/contracts/migration/docs                                                           |
| `./mvnw -q -T1C -pl apps/api -am -Dtest='*Oidc*Test,M10VocabularyTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 0      | **45 tests / 0 failures / 0 errors / 0 skipped**：Aware 5、Parser 11、Discovery 5、Mapper 7、LoginFlow 8、ProviderRateLimiter 3、StartupValidation 2、Vocabulary 4 |
| `./mvnw -q spotless:check`                                                                                           | 0      | 通过                                                                                                                                                               |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict --project .`                           | 0      | 0 errors / 0 warnings                                                                                                                                              |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs state validate --project .`                                       | 0      | 0 errors；仅既有 M11 legacy warning（M11 planned 但有 done 任务），与本任务无关                                                                                    |
| `git diff --check`                                                                                                   | 0      | 无空白错误                                                                                                                                                         |
| `node ~/.config/opencode/riccon/scripts/riccon.mjs gate check --module M10 --project .`                              | 1      | 预期 FAIL：TASK-0065 review、TASK-0067 未 done、无 M10 模块 review；本任务评审不改变模块 gate                                                                      |

**EV-0402 审计：** `commit=fb6661b`、`dirty=false`、`exit_code=0`、命令 `./mvnw -q -T1C verify`，输出 SHA-256 与 JSON 一致（`0ed5ea64…`）。窗口内 fresh 672 tests / 0 skipped；`target/site/jacoco/jacoco.csv`（mtime 07:58:05，落在 EV 窗口）API line **90.57%（5818/6424）**、branch **71.83%（1242/1729）**，高于合同 80/70。EV-0399 是 ArchUnit 分层失败的探索性证据（exit 1，随后 a24871b 修复、EV-0400 exit 0），不应作为 done 证据。EV-0401/0402 为后续 clean 通过。

**未重复 10 分钟全量 verify：** 按任务要求只重跑 OIDC 定向套件并审计 EV-0402。模块级 gate 仍必须由 M10-V1 在最终提交上跑合同 `commands.test`/`coverage`/`test_e2e`/`a11y`/`security`，不能复用本任务的 EV-0402。

## 3. 配置与 Secret（对应 acceptance 1；OPS-003）

- `OidcProperties.Oidc.idps` 是 `String`（`apps/api/src/main/java/com/riccon/api/oidc/config/OidcProperties.java:74-105`），未直接绑定类型化对象；`OidcConfigurationParser` 自行解析 JSON，错误消息只含 `riccon.oidc.idps[n].field`、错误类型与行列，不含原始 JSON/字段值/secret（:59-116、:198-244、:283-295）。`OidcProperties.toString` 掩码 `<redacted>`，`OidcIdpConfig.toString` 掩码 `clientSecret=***`（:66-69；OidcIdpConfig.java:56-67）。
- 启动 fail-fast 覆盖：JSON 可解析/数组、tenantCode 正则与唯一、issuer 绝对 URL+HTTPS（allow-http 例外）+规范化后全局唯一、clientId/clientSecret 非空、scopes 含 openid 且不含 offline_access、endSessionEndpoint 绝对 URL。未配置 IdP 返回空列表并正常启动（parser :60-62；registry :64-79）。
- **OPS-003 可视为 fixed（B1 层）**：`OidcStartupValidationTest` 使用完整 `SpringApplication` 启动（关闭 Flyway/不连库），分别注入 malformed JSON 与缺 `clientSecret` 的配置，断言启动失败、根因只含字段路径、日志与异常链不含 secret/原文（:33-67）。缺陷是它用 `--riccon.oidc.idps` 命令行属性而非真实 `RICCON_OIDC_IDPS` 环境变量；两者经过同一 Spring 属性绑定路径，且故障发生在应用内 String 解析，OPS-003 的核心“不得由 Spring 绑定异常带出 secret”已覆盖。V1 的 `SEC-OIDC-001` 仍必须用真实启动/容器日志做独立扫描（含 token 不下日志）。
- 发现文档：懒加载 `{issuer}/.well-known/openid-configuration`，成功缓存 10 分钟、失败退避 30 秒，issuer 规范化后严格比对，失败只记 issuer+reason（OidcDiscoveryClient.java:98-146、154-213）。`clientRegistration` 显式不设置 userinfo 端点，`OidcUserServiceImpl` 只从 ID Token 构建用户；测试 stub 故意提供 userinfo_endpoint，断言 `userInfoCalls()==0`（OidcDiscoveryClientTest:63-78；OidcLoginFlowTest:220）。
- providers 60 次/分钟按 IP 限流，超限 429 `RICCON-RATE-429`；不按 tenantCode 计数；未知/暂停租户统一 `enabled=false`（OidcProviderRateLimiter.java:73-87；OidcAuthController.java:53-62；OidcLoginFlowTest:455-489）。

**F-003（P3）**：`publicBaseUrl` 仅校验绝对 http(s)、去尾斜杠，未在非 local/test 拒绝 http/localhost（OidcIdpRegistry:209-239）；OPS-015 的 fail-fast/告警未落地。

## 4. 登录、会话与失败清理（对应 acceptance 2）

- **PKCE/nonce/state**：`ClientRegistration` 固定 `tenant-{tenantCode}`、`CLIENT_SECRET_BASIC`、`requireProofKey(true)`，scope 只含配置值（默认 openid）；默认 resolver 生成 S256 challenge/nonce，授权请求存 `HttpSessionOAuth2AuthorizationRequestRepository`，回调消费后删除（OidcIdpRegistry:137-161；OidcConfig:67-70）。IT-OIDC-001 断言 `code_challenge_method=S256`、nonce/state 非空、redirect_uri 精确、无 offline_access；IT-OIDC-006 的重放断言 state_invalid。
- **会话轮换**：成功处理器在框架 session-fixation 策略之外再 `request.changeSessionId()`，旧 Cookie 在 IT-OIDC-001 中返回 401；随后以 `RicconPrincipal` + `UsernamePasswordAuthenticationToken` 显式 `saveContext`（OidcLoginSuccessHandler:135-149）。
- **CSRF 轮换**：成功处理器 `saveToken(null)` 后重新生成/保存（:157-161）；IT-OIDC-007 取新 token 可登出。**F-005** 指出没有断言旧 token 失效。
- **OIDC-aware 仓储**：`saveContext` 遇到 `OidcUser`/`OAuth2AuthenticationToken`/`OAuth2LoginAuthenticationToken` 只更新当前线程、不写会话；`loadContext` 遇到历史 OIDC 残留会删除 `SPRING_SECURITY_CONTEXT` 并返回空上下文（F-029）；单元测试覆盖 5 个场景（OidcAwareSecurityContextRepository.java:51-105；Test:49-107）。
- **无 token**：`NoopOAuth2AuthorizedClientRepository` 的 save/load/remove 均为空/空返回；IT-OIDC-001 扫描会话属性字节，断言无 `OAuth2AuthorizedClient`、access token、ID Token、`id_token`、`OidcUser`、`code_verifier`、`authorization_request`，并只含 `RicconPrincipal`；成功审计 details 也不含 token（:223-251）。
- **业务校验前移**：`OidcUserServiceImpl.loadUser` 在认证成功前调用 `OidcLoginService.validateLogin`，拒绝时抛 `OAuth2AuthenticationException`（:46-57）；成功处理器再做一次防御性校验（OidcLoginSuccessHandler:105-111）。这满足 ADR-0019 §2.1 的“不得先成功再拒绝”。
- **失败清理**：`OidcLoginCleanup.reject` 依次 clearContext、删 `SPRING_SECURITY_CONTEXT`、保存空上下文、删授权请求、失效会话（:52-66）；失败处理器统一先 cleanup，再尽力写审计/累计限流，最后 302 `/login?oidcError=<code>`（OidcLoginFailureHandler:95-118）。IT-OIDC-002/004/006 断言失败后 `/auth/session` 401、`SPRING_SESSION` 行数回到基线、个别失败写审计；会话被 invalidate 后属性自然消失，但**F-005** 指出没有对失败路径显式扫描 `SPRING_SESSION_ATTRIBUTES`。
- **原因码**：`idp_unavailable`、`state_invalid`、`access_denied`、`account_not_bound`、`account_disabled`、`tenant_unavailable`、`issuer_mismatch`、`rate_limited`、`login_failed` 全部存在；只有 account_not_bound/account_disabled/issuer_mismatch 计入 IP+tenant 限流（OidcLoginFailureReason.java:12-31；Mapper:59-109）。**F-009** 指出 invalid_request/invalid_grant 的归类可能误标。
- **审计 fail-closed**：成功走 `recordLoginSuccess`（@Transactional，审计失败向上抛），成功处理器捕获任何异常后调用失败处理器清理会话并重定向 login_failed；失败审计尽力而为、清理失败不掩盖原错误（OidcLoginService:168-227；SuccessHandler:112-122；FailureHandler:103-111）。代码满足 ADR；F-006 指出成功审计 sessionIdHash 未校验等于新会话 ID。

## 5. 登出（对应 acceptance 3）

- `POST /auth/logout` 先计算 `X-Riccon-Federated-Logout-Url`（只读配置/账号来源，不调用 IdP），再 `authService.logout` 写 `auth.logout`，最后 clearContext + `session.invalidate()`；只失效当前会话，不删除并发会话（AuthController.java:142-157；OidcLogoutService.java:58-82）。
- URL 只带 `client_id` 与 URL 编码的 `post_logout_redirect_uri={publicBaseUrl}/login?loggedOut=1&federated=1`，无 `id_token_hint`；LOCAL 账号不返回 header（OidcLogoutService:73-81）。
- IT-OIDC-005 覆盖：204、header 前缀、client_id、post_logout_redirect_uri、无 id_token_hint、`STUB.logoutCalls()==0`（B1 不调用 IdP）、旧会话 401、第二并发会话仍 200、sessionCount=1、LOCAL 无 header。IdP 不可达时本地登出仍成功已由“end-session 指向 closed port + 204”覆盖；**同页提示/自动顶层导航属于 B4/V1**，B1 只交付后端 header。
- **F-008**：未覆盖“OIDC 已配置但无 endSessionEndpoint”的仅本地退出；endSessionEndpoint 允许 fragment/query 的 URL 拼接风险。

## 6. 共享词汇与权限（对应 acceptance 4）

- `AuditActions` 新增 `audit.export.requested/approved/rejected`、`user.oidc.bound/unbound`，并把 M02/M03 既有动作提升为共享常量；`AdditionalAuditActions` 的每个常量改为引用 `AuditActions`，不再有第二份字符串（platform/.../AuditActions.java:49-80；AdditionalAuditActions.java:1-40）。`AuditTargetType` 新增 `AUDIT_EXPORT`；`PermissionCodes` 新增两个 approve 权限（:65-71）。
- `AuditDetailsPolicy` 改为 action-aware + 白名单优先：显式允许 `promptTokens`/`completionTokens`/`sessionIdHash`/`contentType` 等合法键，白名单外的 token/secret/content 子串仍拒绝；M10 查询/导出、OIDC 绑定、worker 模型/MCP/产物动作均已加入（AuditDetailsPolicy.java:21-133、152-171）。M10VocabularyTest 覆盖合法指标、敏感键与白名单外键。
- `ErrorCodes` 增加 `RICCON-OIDC-400/409` 与 `RICCON-AUDIT-400/403/409/410/500`；与 ADR-0020 §6.2/OpenAPI 的域一致（ErrorCodes.java:71-91）。
- `RoleDefinitions`：TENANT_ADMIN/PLATFORM_ADMIN 增加 `audit.read`，MEMBER 不获得；M10VocabularyTest:50-52 断言。**F-001（P1）**：`audit.export.approve`/`platform.audit.export.approve` 未加入 RoleDefinitions，且没有任务承接；fresh install 与新建租户的审批链断裂。详见第 8 节。
- 无 token/JWT 落库：B1 未新增 JWT；OIDC token 只存在请求内存，Noop 仓储不持久化；测试扫描会话与审计。B3/B2 未实现前，导出/绑定权限的运行时完整性不能由 B1 单独证明。

## 7. 测试与证据

- 45 个定向用例全部通过（8 个类，0 skip；见第 2 节命令）。`OidcStubProvider` 在测试运行时生成 RSA 密钥（不是提交静态 key），discovery 故意带 userinfo_endpoint、token 校验 Basic client、一次性 code、PKCE S256、nonce；记录 userinfo/logout/token 计数（OidcStubProvider.java:92-98、271-372）。这满足 OPS-011 对测试 IdP 不新增 `.trivyignore` 的方向（V1 的独立测试 IdP 仍须同样运行时生成）。
- 无删测/跳测：`git diff 7ff2409..fb6661b -- apps/api/src/test` 无删除文件、无 `@Disabled`/`assume`；定向报告全部 `Skipped: 0`。
- EV-0402：fresh 672 tests（不是 673；见 F-004），0 skip；API 覆盖率 line 90.57/branch 71.83；EV-0402 日志中无 `oidc-test-secret`/`stub-secret-value`/`stub-access-token`/JWT 片段（独立扫描 0 命中）。`OidcStartupValidationTest` 的启动失败栈只含 `riccon.oidc.idps … is not valid JSON` 或字段路径，不含 secret。
- 未跑：模块级 E2E/a11y/security/perf、真实 IdP 兼容、MySQL 副本迁移——均属 M10-V1/M09；B1 无 UI、无迁移，不在本任务范围。

## 8. F-001（P1）详解与承接建议

**问题链条：**

1. ADR-0020 §5.2（:193）要求 `RoleDefinitions` 同步 `TENANT_ADMIN += audit.read, audit.export.approve`、`PLATFORM_ADMIN += audit.read, platform.audit.export.approve`。
2. ADR-0020 §8（:312）规定 B1 是 `RoleDefinitions.java` 的唯一 owner，B2/B3 只消费、不得修改；B3 只独占 V10。
3. B1 在 V10 之前若把 approve 权限写进 RoleDefinitions，`TenantAdminService.createTenant`/`BootstrapAdminRunner` 会立即插入 `role_permission`，而 `permission` 字典行要到 V10 才存在 → 外键失败。B1 的 Javadoc（RoleDefinitions.java:17-19）因此选择不写，并把责任指向 V10+B3。
4. V10 的 `INSERT ... SELECT FROM role` 只回填迁移时已存在的 role 行。绿地/fresh install 在空库执行 V10 时没有 role；随后 `BootstrapAdminRunner` 创建平台管理员角色、`TenantAdminService.createTenant` 创建租户角色，均读取 RoleDefinitions，因此没有 approve 权限。
5. 结果：M10-AC-17“同租户另一管理员审批 / 单管理员租户由平台管理员审批”在 fresh install 与所有新建租户不可用；现有租户只有 V10 回填路径且仅对迁移前角色有效。

**建议（需 Lead 决策，B1 不改）：** 在 B1 done 前把 `RoleDefinitions.java` 的窄范围修改转给 B3（B3 已在 V10 后能安全加入），或新建一个小任务 `M10-B6 RoleDefinitions 审批权限与租户开通测试`（依赖 B3/V10）。改动仅两行权限 + 一条测试：新建租户的 TENANT_ADMIN 角色包含 `audit.export.approve`，fresh/空 schema 引导的 PLATFORM_ADMIN 角色包含 `platform.audit.export.approve`；同时更新 ADR-0020 §8 的“B1 唯一 owner”措辞/任务图，避免再次无主。M10 gate 与 V10 生产执行前必须关闭。

## 9. B2/B3 启动条件

- **B2：可以启动。** B1 的共享词汇（除 F-001 的 approve 角色授予）、`OidcIdentityRepository`/`OidcIdentityJdbc` 只读查询与 `AuditDetailsPolicy` 的 `user.oidc.bound/unbound` 白名单已就绪；B2 只消费，不写 `audit/domain/**`。B2 必须：删除/重建 `user_credential` 的绑定事务、`authSource` 分支、冲突 409、审计 details 合规；并处理 **F-002**（拒绝 `tenantId=platform` 的绑定，或由 architect 在 B1 配置校验拒绝 `tenantCode=platform`）。
- **B3：代码与 V10 可以启动。** V10 在测试库用 V1–V9 种子/空库两种基线验证；必须包含权限字典行、角色回填、两索引、worker `EXPECTED_SCHEMA_VERSION` 9→10（OPS-002 需 Lead 给 owner；B3 写范围目前不含 `apps/worker/**`）。**F-001** 的 RoleDefinitions 两行权限必须在 V10 之后由 B3/新任务补；V10 生产执行前仍需 OPS-004/005/006/007、OPS-014 和 M09 清理。B3 不阻塞 B1 的代码评审，但阻塞 M10 gate。
- **B4/B5** 在 B2/B3 schema 稳定后启动；**V1** 在 B4/B5 后跑 SEC-OIDC-001..003、IT-OIDC/AUDIT、E2E-M03-03..07、a11y、security、V10 回滚演练。

## 10. 未验证

- 未跑模块级 `commands.test`/`coverage`/`test_e2e`/`a11y`/`security`/perf；未跑真实 IdP（Keycloak）兼容；未跑 MySQL 副本上的 V10/回滚（B3/V1/Ops）。
- 未在真实浏览器/UI 验证 AC-12 的“IdP 不可达同页提示”与自动顶层导航（B4/V1）；B1 只交付后端 header。
- 未做日志全量脱敏扫描（只扫描 EV-0402 与定向测试日志的已知 token/secret 串）；V1 SEC-OIDC-001 必须补真实启动/容器日志与 Actuator/代理访问日志。
- 未验证 B2/B3 代码；F-001/F-002 的运行时后果需在 B2/B3 完成后复核。

## 11. 剩余风险

1. **F-001（P1）**：fresh install/新建租户无导出审批权限；必须在 M10 gate 前由 Lead 指定 owner 并补 RoleDefinitions/测试。
2. **F-002（P2）**：platform tenant OIDC 配置/B2 绑定可能形成未评审的平台管理员 OIDC 登录路径；建议 B1 或 B2 显式拒绝。
3. **OPS-003** 在 B1 层 fixed；`SEC-OIDC-001` 的独立真实启动日志扫描仍归 V1。
4. **OPS-015**（publicBaseUrl 生产校验）仍 open；生产误配风险由 M09 运行手册兜底。
5. **证据口径**：EV-0402 的 673 含陈旧报告，fresh 672；不改变 0 skip/覆盖率结论，但下次 EV 应清理 target 或使用 `-Dsurefire...` 断言。
6. 已知 M10 架构/ Ops P2/P3（F-024/F-025、F-027、F-029 已由 B1 的 loadContext 修复、OPS-008/013/017 等）继续按原 owner 承接；本评审不重复。
