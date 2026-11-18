---
review_id: REV-M10-ARCH-001
module_id: M10
task_id: TASK-0062
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0063_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: fail
reviewed_commits: [07e817c]
head_commit: df15176
created_at: 2026-11-19T05:19:39+08:00
reviewed_evidence: [EV-0386, EV-0387, EV-0388, EV-0389]
evidence: []
findings:
  - id: F-001
    severity: P1
    status: open
    title: OIDC 业务失败可能把 OidcUser/ID Token 留在会话
    summary: "Spring Security 在 successHandler 之前已 saveContext 保存 OAuth2AuthenticationToken；ADR 只要求成功时替换主体，未要求 account_not_bound/account_disabled/tenant_unavailable 等业务失败时清空上下文并失效会话。"
    evidence: "ADR-0019 §2/§4（74、111）；SecurityConfig:40-43；Spring Security AbstractAuthenticationProcessingFilter.successfulAuthentication 源码"
  - id: F-002
    severity: P1
    status: open
    title: AC-12 原文的“点击登出同时结束 IdP 会话”未实现，改为第二次点击且无产品决策
    summary: "PRODUCT:208 要求 IdP 支持结束会话时点击登出即同时结束；ADR-0019 §5（133-135）改为本地登出 + 可选响应头 + 登录页第二次点击按钮，并自称满足 AC-12。共享工作站下一位用户可经 IdP SSO 重新进入。"
    evidence: "docs/PRODUCT.md:208；ADR-0019 §5（131-135）"
  - id: F-003
    severity: P1
    status: open
    title: 审计读取禁止键检查未豁免 promptTokens/completionTokens，模型事件查询/导出会 fail-closed
    summary: "ADR-0020 §1（42）用 token 子串做读取检查且只豁免 contentType；worker AuditDetailsPolicy 合法允许 promptTokens/completionTokens（19-21）。AC-15 种子明确包含模型事件，按 ADR 字面实现会 500 RICCON-AUDIT-500。"
    evidence: "ADR-0020 §1（42）；apps/worker/.../AuditDetailsPolicy.java:19-21；PRODUCT:211"
  - id: F-004
    severity: P2
    status: open
    title: 平台审批未限制单管理员租户
    summary: "ADR-0020 §2（64）只声明平台端点用于单管理员租户，但没有校验或拒绝规则；platform.audit.export.approve 可审批任意租户导出，绕过“多管理员租户由同租户另一管理员审批”的口径。"
    evidence: "ADR-0020 §2（61-67，84-90）；DEC-0014"
  - id: F-005
    severity: P2
    status: open
    title: 审计动作词汇遗漏 worker 的 user.access.denied
    summary: "ADR-0020 §6.1 只提升 AdditionalAuditActions 的 7 个动作；worker 实际写 user.access.denied（WorkerAuditActions:15、AuditService.userAccessDenied:230），未进入 AuditPolicy.actions，动作过滤下拉不完整。"
    evidence: "ADR-0020 §6.1（215-237）；apps/worker/.../WorkerAuditActions.java:15；AuditService.java:225-235"
  - id: F-006
    severity: P2
    status: open
    title: Builder 任务写范围与“B1 先落地全部动作/白名单”约束冲突
    summary: "B1 要求先落地全部新动作/权限/错误码/AuditDetailsPolicy，但 B1 范围不含 AdditionalAuditActions.java；B3 的 audit/** 又包含该文件。B1 auth/** 与 B2 auth/repository/**、auth/persistence/** 重叠；B2/B3 都写 admin/**；B1/B3 都写 platform-domain/**、ErrorCodes.java。并行派单会冲突。"
    evidence: "ADR-0020 §8（286-300，尤其 297）；ADR-0019 §9（213）"
  - id: F-007
    severity: P2
    status: open
    title: 确定性测试 IdP 无任务归属与写范围
    summary: "ADR-0019 §6（171）要求本地 OIDC Provider（authorize/token/JWKS/discovery/end-session/故障注入），但任务图未把它分配给 B1/V1；deploy/compose/docker-compose.test.yml 不在任何任务写范围，E2E-M03-03/04/07 无法按任务图执行。"
    evidence: "ADR-0019 §6（171）、§9（216）；ADR-0020 §8（286-293）"
  - id: F-008
    severity: P2
    status: open
    title: 导出过期测试无时钟/短 TTL 策略
    summary: "ADR-0020 §2（65）默认 24h、上限 7 天；§9 要求 IT-AUDIT-105 覆盖过期，但未定义 Clock 注入、短 TTL 或 DB 时间回拨；按 24h 实现只能跳过或等待，存在假绿风险。"
    evidence: "ADR-0020 §2（65）、§9（307-315）"
  - id: F-009
    severity: P2
    status: open
    title: actorDisplayName 逐行解析未定义批量策略
    summary: "ADR-0020 §1（41）要求读取时按当前 app_user.display_name 解析；page size 最大 100，逐行查询会产生最多 100 次额外 SQL/页，逼近 API p95 500ms。"
    evidence: "ADR-0020 §1（41）；合同 constraints.performance"
  - id: F-010
    severity: P2
    status: open
    title: OIDC 配置解析失败可能把含 clientSecret 的原始 JSON 写入启动日志
    summary: "ADR-0019 §1（52）要求启动 fail-fast 校验 RICCON_OIDC_IDPS JSON；若解析异常直接携带原始值，clientSecret 会进入启动日志，违反同节“任何日志不得输出该值”。"
    evidence: "ADR-0019 §1（38、52、56）"
  - id: F-011
    severity: P2
    status: open
    title: “不调用 userinfo”依赖实现细节，未定义 user service/URI 清理
    summary: "若用 discovery 构建 ClientRegistration，userinfo_endpoint 会被带入；默认 OidcUserService 可能携带 access token 调用 IdP。ADR-0019 §2（68）只写“不配置 userinfo URI”，需明确自定义 user service/清空 URI 并测试无 userinfo 请求。"
    evidence: "ADR-0019 §2（68）"
  - id: F-012
    severity: P2
    status: open
    title: V10 分区表加索引/回滚/worker schema 升版需 Ops 评审
    summary: "ADR-0020 §4（100）承认分区表加索引会重建各分区；§5.4 回滚、worker schema 9→10、RICCON_OIDC_IDPS Secret 注入/轮换均需 Ops。ARCHITECTURE 评审状态 Ops=pending。"
    evidence: "ADR-0020 §4（100）、§5.4（196-209）；docs/ARCHITECTURE.md 评审状态表"
  - id: F-023
    severity: P2
    status: open
    title: 平台租户审计导出的申请/审批闭环未定义
    summary: "ADR-0020 §2 权限表写 audit.read 含“发起导出”，平台管理员（平台范围）也有 audit.read；但 §2 平台端点又写“平台管理员不得成为申请人”，且平台租户只有平台管理员、没有 audit.export.approve 审批人。平台租户导出因此无合法申请/审批路径。"
    evidence: "ADR-0020 §2（49-67，尤其 51、64）；§3（89）"
  - id: F-013
    severity: P3
    status: open
    title: UserCreateRequest 的 LOCAL 分支允许 password=null
    summary: "OpenAPI 的 else 分支 required:[password] 但 password 类型为 [string,null]；服务端必须显式拒绝 null，否则 LOCAL 分支可创建无密码账号。"
    evidence: "contracts/openapi.yaml:4678-4712"
  - id: F-014
    severity: P3
    status: open
    title: 审计 page≤200 未在契约中表达
    summary: "Page 参数只有 minimum:1；审计 page 上限 200 只在描述和 ADR 中，客户端/生成工具无法校验。建议增加审计专用 AuditPage 参数。"
    evidence: "contracts/openapi.yaml:2734-2741；ADR-0020 §1（40）"
  - id: F-015
    severity: P3
    status: open
    title: 403/404 预期码不一致
    summary: "ADR-0020 §2（71）写平台管理员下载 403；但下载端点按主体租户查询，平台管理员通常得到 404。另无 audit.read 的既有鉴权可能返回 RICCON-AUTH-403 而非 RICCON-AUDIT-403。测试预期需统一。"
    evidence: "ADR-0020 §2（63、71）、§6.2（249）"
  - id: F-016
    severity: P3
    status: open
    title: AC-17“范围/拒绝原因/行数”的审计可读性不足
    summary: "审计事件以 filtersHash 代替可读范围；decisionComment 不进审计；audit.export.approved 的 rowCount 未定义是预计还是实际。建议补 occurredFrom/occurredTo/action 等字段或明确 Product 接受 hash+导出记录。"
    evidence: "ADR-0020 §2（67、230-241）；PRODUCT:213"
  - id: F-017
    severity: P3
    status: open
    title: M03 F-009 在 M10 是“承接结论”而非修复
    summary: "ADR-0020 §3（87）明确目标租户仍看不到平台来源事件；这符合 DEC-0013/OQ-13，但 M10 评审包与 ACCEPTANCE 必须写“未解决、M10+ 另立显式授权 ADR”，不能记 fixed。"
    evidence: "ADR-0020 §3（84-90）；REV-M03-001 F-009；docs/ACCEPTANCE.md:52"
  - id: F-018
    severity: P3
    status: open
    title: PRODUCT AC 编号与 TEN 范围漂移未登记完整
    summary: "PRODUCT §8.3 表仍写 M03-AC-08..21，AC-20 线索仍 TEN-001..026；ADR 只登记 TEN-021 冲突，未登记 AC 编号与 027..030 的文本漂移。"
    evidence: "docs/PRODUCT.md:204-216；ADR-0020 §9（317）"
  - id: F-019
    severity: P3
    status: open
    title: x-riccon-deferred 的 M10+ 语义与 EXPIRED 过滤未定义
    summary: "MFA/哈希链/跨租户审计用 status: M10+，与其他条目的明确模块号不一致；AuditExportStatus 含派生 EXPIRED，但列表 status=EXPIRED 的查询语义未定义。"
    evidence: "contracts/openapi.yaml:80-90、3398-3403"
  - id: F-020
    severity: P3
    status: open
    title: CSV 公式注入与重复下载限流未覆盖
    summary: "导出 CSV 未提 Excel 公式注入/重复下载限流；details 以 { 开头降低风险，但 departmentName 等值仍应确认转义策略，下载端点也应考虑滥用限流。"
    evidence: "ADR-0020 §2（74-81）、威胁模型 D-12-1"
  - id: F-021
    severity: P3
    status: open
    title: Spring Boot 4.1 starter 名称与 provider 枚举/429
    summary: "Boot 4.1 canonical starter 为 spring-boot-starter-security-oauth2-client，ADR 写旧的 spring-boot-starter-oauth2-client；GET /auth/oidc/providers 的 429/枚举边界也未在 ADR 设计。"
    evidence: "ADR-0019 §6（141）；contracts/openapi.yaml:208-238"
  - id: F-022
    severity: P3
    status: open
    title: 其他契约/文案一致性
    summary: "POST /audit-exports 声明 409 但 ADR 无 409 场景；界面固定文案“保留 180 天”与可配置 retentionDays 可能不一致；V10 回滚删除 audit.read 的所有映射需记录当前无手工授予的前提。"
    evidence: "contracts/openapi.yaml:1725-1741；ADR-0020 §1（45）、§5.4（203-206）"
---

# REV-M10-ARCH-001：M10 架构独立评审（TASK-0062）

## 结论

**verdict: fail。** ADR-0019/0020 的主体设计方向正确：每租户 1 IdP、授权码 + PKCE + nonce、无 token 持久化、账号显式绑定、审计只读查询、双人审批、流式不落盘、平台不读目标租户审计明细，均与 DEC-0013/0014/0015 和 PRODUCT §8 一致；OpenAPI 1.2.0-m10 可 lint、无既有路径删除，V10 只增不减。但存在 **3 个 P1**：OIDC 业务失败可能把 `OidcUser`/ID Token 留在会话；AC-12 的联合登出被降级为第二次点击且无产品决策；审计读取禁止键检查会误杀含 `promptTokens`/`completionTokens` 的模型事件。P1 未关闭前 **不应进入 Builder**，ADR 也应保持 `proposed`。

## 验证范围与命令

- 工作区：`git status --short` 空；HEAD `df15176`（`chore(M10): 启动架构独立评审`）。
- 变更面：`git show --stat 07e817c` 仅 8 个 docs/contracts 文件；未改生产源码、迁移、`AGENTS.md`、`docs/PRODUCT.md`。
- `npx --yes @redocly/cli@1.34.3 lint contracts/openapi.yaml`：exit 0，2 条 `operation-2xx-response` warning（authorize/callback 仅有 302）。**判定可接受**：OIDC 授权入口/回调是 302 重定向端点，没有 2XX 响应是契约事实，不是缺响应；建议在契约注释或 Redocly 配置说明，不影响 lint 通过。
- `node ~/.config/opencode/riccon/scripts/riccon.mjs contract validate --strict --project .`：exit 0（0 errors/0 warnings）。
- `state validate`：exit 0，1 warning（M11 planned 但已有 done 任务，既有状态遗留，允许）。
- `git diff --check`：exit 0。
- 复核 TASK-0062 证据：EV-0386（Redocly）、EV-0387（Redocly + contract + diff）、EV-0388（contract）、EV-0389（diff），均 `manual:false`、`dirty:false`、exit 0、commit `07e817c`。
- 未运行代码测试/全量：本任务是架构评审，`07e817c` 没有实现代码；按任务要求不跑。未执行 V10 迁移、浏览器 E2E、a11y 或安全扫描；这些属于后续 Builder/Verifier 任务。

## OIDC（ADR-0019）

**成立的部分**：每租户 1 IdP 且配置只来自部署 Secret（§1）；issuer 规范化后全局唯一、启动 fail-fast 但未配置时正常启动（§1）；Authorization Code + PKCE(S256) + nonce，`requireProofKey=true`，state/nonce/PKCE verifier 只在服务端会话且一次消费（§2）；ID Token 校验 iss/aud/exp/nonce/签名、≤60s skew、sub ≤255（§2）；no-op authorized client、不请求 `offline_access`、不存 refresh token（§2）；复用 `RICCON_SESSION` + Spring Session JDBC，登录后轮换会话 ID/CSRF（§2/§5）；账号键 `(tenant_id, issuer, subject)`，不按邮箱、不自动开通、管理员显式绑定、冲突 409、解绑恢复本地密码（§3）；禁用/租户暂停拒绝建会话（§3）；九个稳定原因码且不回显 IdP 细节（§4）；本地登出权威、可选联合登出 URL（§5）；测试 IdP、轮换、回滚（§6）；AC-08..14/21 映射（§8）。Spring Security 7 的 PKCE/nonce 行为已用 context7 核对：`DefaultOAuth2AuthorizationRequestResolver` 在 `openid` scope 下生成 nonce，`requireProofKey` 触发 PKCE；`OidcAuthorizationCodeAuthenticationProvider` 校验哈希后的 nonce。

**P1 F-001（安全，必须先修）**：`AbstractAuthenticationProcessingFilter.successfulAuthentication` 在调用 `successHandler` **之前**执行 `securityContextRepository.saveContext`；本项目的 `SecurityConfig` 使用 `HttpSessionSecurityContextRepository`（`SecurityConfig.java:40-43`），因此 Spring Security 会先把包含 `OidcUser`/ID Token 的 `OAuth2AuthenticationToken` 写入会话。ADR-0019 §2 只要求成功处理器用 `RicconPrincipal` 替换主体，§4 只要求失败 302，**没有要求** `account_not_bound`、`account_disabled`、`tenant_unavailable` 这类“IdP 认证成功但业务校验失败”的路径清空已保存的安全上下文并使会话失效。后果是失败请求可能留下一个已认证的 OIDC 会话（ID Token 在 `OidcUser` 中），违反 §2“不存 token”和 AC-14/SEC-OIDC-001。修复建议：把绑定/禁用/租户校验放进自定义 `AuthenticationProvider`（在 filter 保存前执行），或成功处理器失败时 `SecurityContextHolder.clearContext()` + `securityContextRepository.saveContext(空上下文)` + `session.invalidate()`；`IT-OIDC-001/004` 必须断言失败路径会话未认证且 `SPRING_SESSION_ATTRIBUTES` 不含 `OidcUser`/ID Token。

**P1 F-002（AC，必须先决策）**：PRODUCT:208 原文为“点击登出；Then 本地会话立即失效并回到登录页，浏览器后退无法恢复登录态；IdP 支持结束会话时同时结束 IdP 会话，IdP 不可达时本地登出仍成功并提示已在本地退出”。ADR-0019 §5（133-135）把“同时结束 IdP 会话”改成“登录页提供‘同时退出企业账号’按钮，用户点击后导航到 IdP”，并自称满足 AC-12。实际点击一次登出后 IdP 会话仍在；共享工作站下一位用户可点“企业账号登录”经 IdP SSO 直接回到账号，与 AC-12 的防共享工作站目标冲突。当前没有任何 DEC/Product 对 AC-12 文案的修订记录。修复前不得声称 AC-12 通过；应由 Product 明确接受“本地登出权威 + 显式第二次动作”并更新 AC 文本，或改为登出时自动完成 end-session 导航并提供本地退出落地页（同时定义 IdP 不可达时的可见提示）。

**P2 F-010（安全）**：`RICCON_OIDC_IDPS` 启动解析失败若把原始 JSON 放进异常消息，`clientSecret` 会进入启动日志；ADR 需要求解析错误脱敏（字段名/位置/异常类型），并用 `SEC-OIDC-001` 扫描 malformed config 日志。

**P2 F-011（数据最小化）**：ADR 只写“不配置 userinfo URI”；若用 discovery 构建 `ClientRegistration`，`userinfo_endpoint` 会被带入，默认 `OidcUserService` 可能携带 access token 调用 IdP。需要明确自定义 user service/清空 URI，并测试无 userinfo 请求。

## 审计查询与导出（ADR-0020）

**成立的部分**：`GET /api/v1/audit-events` 只读、租户只从主体派生、不接受 `tenantId`（§1）；时间必带偏移、默认 24h、最大 180 天、`occurredTo` +5min、固定 `occurred_at DESC, event_id DESC`、page ≤200/size ≤100（§1）；不返回 `source_ip`/`request_id`/`schema_version`（§1）；查询写 `audit.queried`，审计写失败 fail-closed（§1）；策略端点返回保留/范围/动作/导出限制（§1）；导出申请由服务端算预计行数、上限 10,000、状态机条件更新、自批 403、并发 409、默认 24h 过期（§2）；只有申请人可下载、过期 410、下载前重新计数、CSV 流不落盘、字段白名单、`data.exported`（§2）；平台只查 `platform` 租户、审批只接触元数据、不能下载（§3）；保留 ≥180 天、两个新索引、V10 additive（§4/§5）；动作/错误码/API 表（§6/§7）；任务图与验证矩阵（§8/§9）。

**P1 F-003（功能/安全，必须先修）**：ADR-0020 §1（42）的读取禁止键检查使用 `password/token/secret/credential/prompt/content/email/phone` 子串，只豁免 `contentType`。但 worker `AuditDetailsPolicy` 合法允许 `promptTokens`/`completionTokens`（`apps/worker/.../AuditDetailsPolicy.java:19-21`），AC-15 的种子明确包含模型事件（PRODUCT:211）。按 ADR 字面实现，任何含 `model.invocation.completed` 的查询/导出都会命中 `token` 子串并 fail-closed（500 `RICCON-AUDIT-500`），AC-15/17/18 无法通过。修复：读取时执行与写入相同的 action-aware 白名单（`AuditDetailsPolicy`），或至少显式豁免 `promptTokens`/`completionTokens` 等既有合法键；`IT-AUDIT-101` 加入模型事件断言。

**P2 F-004（授权模型）**：§2（64）只说平台端点“用于单管理员租户”，没有校验目标租户启用管理员数。`platform.audit.export.approve` 因此可审批任意租户的导出，绕过 DEC-0014“多管理员租户由同租户另一管理员审批”。需要：平台列/决策前检查目标租户启用管理员数 ≤1（超过则 409/403），或 Product 明确允许平台审批任意租户并更新 AC/决策。

**P2 F-005（动作词汇）**：§6.1 只提升 `AdditionalAuditActions` 的 7 个动作；worker 实际写 `user.access.denied`（`WorkerAuditActions.java:15`、`AuditService.java:225-235`），未提升、未进入 `AuditPolicy.actions`，AC-15 的动作过滤下拉会缺该动作。修复：提升到 `AuditActions`，补 `AuditDetailsPolicy` 与 ADR-0007 白名单。

**P2 F-009（性能）**：§1（41）`actorDisplayName` 按当前 `app_user.display_name` 解析；page size 最大 100，逐行查询会产生最多 100 次额外 SQL/页，逼近 API p95 500ms。需要批量 `IN` 查询/一次 join，并在 `IT-AUDIT-101` 加 100 行性能断言。

**P2 F-023（平台导出闭环）**：§2 权限表（51）写 `audit.read` 含“发起导出；租户管理员与平台管理员（平台范围）”，但 §2 平台端点（64）写“平台管理员不得成为申请人”。平台租户只有平台管理员，且没有 `audit.export.approve`（只有 `platform.audit.export.approve`）；平台端点又只审批**目标租户**的导出。因此平台租户导出要么无法申请（按 64），要么申请后无人可批（按 51）；两条文字需二选一并补测试：若平台审计导出在 M10 范围内，定义平台租户审批人/自批补偿控制；若不在，移除平台管理员的“发起导出”权限并返回 403/404。

**P3 F-016（AC-17 可读性）**：审计事件以 `filtersHash` 代替可读范围，`decisionComment` 不进审计，`audit.export.approved` 的 `rowCount` 未定义是预计还是实际；建议补 `occurredFrom/occurredTo/action` 等字段，或由 Product 明确接受“hash + 导出记录”口径。

**P3 F-015（状态码）**：§2（71）写平台管理员下载 403，但下载端点按主体租户查询，平台管理员通常得到 404；无 `audit.read` 的既有鉴权可能返回 `RICCON-AUTH-403` 而非 `RICCON-AUDIT-403`。测试预期需统一，并保证跨租户无内容泄露。

## AC 对账（架构映射，不等于实现通过）

下表引用 AC 原文的用户可见结果；M10 的 AC 编号在 PRODUCT §8.3 表中仍写作 `M03-AC-08..21`，§8.5 将其中的 08–18 归属 M10。架构评审只能判定“设计是否覆盖”，不能替代实现/E2E 通过。

| AC    | 原文用户可见结果（摘要）                                                                                                                                       | 设计落点                                      | 架构结论                                                                                                 |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| AC-08 | 企业账号（授权码 + PKCE）登录后显示租户名、用户名、来源 IdP；Cookie 属性与 M02 一致；浏览器/响应体无 JWT/access/refresh token                                  | ADR-0019 §2/§3/§8；B1/B4                      | **mapped**；F-001 影响“无 token 存储”承诺                                                                |
| AC-09 | 不按邮箱自动绑定/覆盖；拒绝并提示联系管理员；管理员录入 issuer+subject 后命中同一账号与角色                                                                    | ADR-0019 §3；B2                               | **mapped**                                                                                               |
| AC-10 | 两个 IdP 返回相同邮箱但不同 issuer 时生成两个互不覆盖的账号；界面显示来源 IdP                                                                                  | ADR-0019 §3；IT-OIDC-003                      | **mapped，附注**：M10 不自动开通，两个账号由管理员显式创建；AC 原文“生成”需在测试中体现为管理员创建/绑定 |
| AC-11 | 禁用账号不建立会话；登录页提示；既有会话下一请求被拒绝                                                                                                         | ADR-0019 §3/§5；IT-OIDC-004/SEC-OIDC-002      | **mapped**                                                                                               |
| AC-12 | 点击登出本地会话立即失效、后退不可恢复；IdP 支持时同时结束 IdP 会话；IdP 不可达时本地登出仍成功并提示                                                          | ADR-0019 §5；E2E-M03-03/IT-OIDC-005           | **gap（P1 F-002）**：设计只提供第二次点击的联合登出                                                      |
| AC-13 | 四类失败各有文案与下一步；不显示 IdP 原始错误、令牌、内部地址、堆栈                                                                                            | ADR-0019 §4；E2E-M03-04/IT-OIDC-006           | **mapped**                                                                                               |
| AC-14 | 内置/OIDC 账号 Cookie/CSRF/超时一致；角色下一请求生效；响应体/前端存储无 JWT/refresh                                                                           | ADR-0019 §2/§5；SEC-OIDC-003/IT-OIDC-007      | **mapped**；F-001 的会话 token 风险需修                                                                  |
| AC-15 | ≥200 条审计（含登录、文件、模型、MCP、审批、权限变更）；按时间/动作/操作者/对象过滤、倒序、分页上限、每行字段、空态、查询写审计                                | ADR-0020 §1；IT-AUDIT-101..102/E2E-M03-05     | **gap（P1 F-003）**：读取禁止键会误杀模型事件；F-005 动作列表缺 worker 动作                              |
| AC-16 | 无修改/删除入口；接口拒绝写；数据库拒绝 UPDATE/DELETE；普通员工 403 且无内容                                                                                   | ADR-0020 §1；IT-AUDIT-103/TEN-028             | **mapped**；F-015 的错误码需统一                                                                         |
| AC-17 | 导出申请（申请人/查询条件/预计行数）；未批准不可下载；不得自批；另一管理员批准；有时效文件含条件/生成时间；动作/审批人/范围/行数入审计；拒绝不可下载且记录原因 | ADR-0020 §2/§6；IT-AUDIT-104..105/E2E-M03-06  | **mapped**；F-004（平台审批范围）、F-016（范围/原因可读性）需承接                                        |
| AC-18 | 保留 ≥180 天；界面显示策略/可查范围/无删除；导出不含密码哈希、会话令牌、模型/MCP 凭据、文件原文；过期不可下载                                                  | ADR-0020 §1/§2/§4；IT-AUDIT-106/SEC-AUDIT-001 | **mapped**；F-003 修复前含模型事件的导出会失败                                                           |
| AC-19 | 扩展越权套件覆盖导出请求段，零 skip/xfail/known-failure                                                                                                        | ADR-0020 §9；DEPT-010                         | **mapped**                                                                                               |
| AC-20 | M02 跨租户用例 + 审计导出对象全部通过；无缺失 ID/跳过                                                                                                          | ADR-0020 §9；TEN-028..030                     | **mapped**；F-018 的 TEN 范围文本漂移需登记                                                              |
| AC-21 | OIDC 登录页、审计查询/导出页的加载/空/错误/无权限/成功态；375px；键盘；axe                                                                                     | ADR-0019/0020 §9；E2E-M03-07/@a11y            | **mapped**；F-007 的测试 IdP 归属需先解决                                                                |

## 契约与版本

- `info.version` 已升 `1.2.0-m10`；既有路径无删除（比对 `07e817c^` 与当前 `path` 列表，新增 15 个 M10 路径）；组件 schema/parameter/response 无删除，只有新增。
- `x-riccon-deferred` 已移除 `oidc-sso`/`audit-query`/`audit-export`，保留 `platform-cross-tenant-audit`，新增 `mfa`/`audit-hash-chain`；与 DEC-0013 的后置口径一致。P3 F-019 建议把 `M10+` 改为明确的“M10 之后/目标模块”，并定义 `EXPIRED` 过滤。
- `UserCreateRequest` 的 `if/then/else` 是兼容扩展（LOCAL 仍需 password，OIDC 必须 issuer+subject 且 password=null）；P3 F-013 要求服务端拒绝 `password:null` 的 LOCAL 请求。
- `AuditEvent` 不包含 `source_ip`/`request_id`/`schema_version`；`AuditEventPage` 使用 `PageMeta`；`AuditExportRequest` 只含元数据；与 ADR-0020 §1/§2 一致。
- `POST /api/v1/audit-exports` 的 409 在 ADR 中没有场景（P3 F-022）；`GET /api/v1/audit-exports` 的 `status=EXPIRED` 派生语义需明确（P3 F-019）。
- 2 条 Redocly warning 判定为可接受（302-only OIDC 端点）；不是契约失败。

## 迁移、安全与 Ops

- V10 设计为单迁移：`audit_export_request`（PK `(tenant_id,id)`、两个 CHECK、FK 到 `app_user(tenant_id,id)`、状态/过期索引）；`audit_event` 两个 `(tenant_id, action|actor_id, occurred_at)` 索引；两个权限与 `TENANT_ADMIN`/`PLATFORM_ADMIN` 回填。`app_user` 已有 `(tenant_id,id)` 主键和 `(tenant_id,external_issuer,external_subject)` 唯一键，OIDC 无 schema 变更；V10 只增不减，旧代码可忽略新表/索引/权限。设计可行。
- 回滚 SQL（§5.4）顺序正确：先 `DROP TABLE`，再删索引，再删 `role_permission`，最后删 `permission`；`audit_event` 不受影响，`flyway_schema_history` 由维护账号按 ADR-0010 处理。P3 F-022 记录一个前提：回滚会删除 `audit.read` 的所有 role 映射；M10 之前确无授予，但若未来有手工授予需保留/记录。
- 分区表 `ALTER TABLE audit_event ADD KEY` 会重建各分区；ADR 已要求测试库 V1–V9 种子验证、生产 M09 维护窗口或确认 MySQL 8.4 online DDL。**这是 Ops 高风险项，必须 Ops 评审**（F-012），不是 Builder 单独可消化。
- B-06：07e817c 未改 `.trivyignore`/ADR-0011；M10 新增 `spring-boot-starter-oauth2-client` 为 Apache-2.0，例外未扩大，当前到期 2027-03-31 或 M07 gate，有效。
- 威胁模型已覆盖 TB-11/TB-12、TM-29..38、B-16/B-17、R-12..R-18；与 ADR 一致。Ops 仍需评审 Secret 注入/轮换、`publicBaseUrl`、V10 索引窗口、worker schema 9→10 的部署顺序。

## Builder 任务图

- 数量满足约束：5 Builder + 1 Verifier，每个 acceptance ≤6、建議 write_scope ≤8（B1 8、B2 6、B3 7、B4 6、B5 5、V1 3）。
- 依赖方向 `B1→B2→B4`、`B1→B3→B5`、`B3+B4+B5→V1` 合理；B4/B5 可在 B2/B3 完成后并行。
- **P2 F-006**：§8 的 glob 重叠与 §297“B1 先落地全部新动作/权限/错误码/AuditDetailsPolicy，B2/B3 只消费”冲突。B1 范围不含 `AdditionalAuditActions.java`，B3 的 `audit/**` 又包含它；B1 `auth/**` 覆盖 B2 的 `auth/repository/**`、`auth/persistence/**`；B2/B3 都写 `admin/**`；B1/B3 都写 `platform-domain/**`、`ErrorCodes.java`。派单前必须按文件级收窄（例如 B1 `auth/oidc/**`、B2 `admin/identity/**`、B3 `admin/audit/**` + `audit/**`），并指定 `AdditionalAuditActions.java`/`AuditDetailsPolicy.java` 的唯一 writer。
- **P2 F-007**：测试 IdP 未分配任务/写范围；`deploy/compose/docker-compose.test.yml` 不在任何任务。需要 V1 或 B1 明确拥有 `e2e/idp/**` 与 compose 测试服务，否则 E2E-M03-03/04/07 落不了地。

## 测试可行性

- OIDC 集成/E2E：PKCE/nonce/state 重放、IdP 不可达、`access_denied`、issuer 混淆、同邮箱不合并、禁用即时失效、无 token 扫描均有明确测试 ID；Spring Security 7 的默认 resolver/provider 行为已核对。测试 IdP 的授权码/令牌/JWKS/discovery/end-session/故障注入可行，但需 F-007 归属。
- 审计查询/导出：过滤组合、180 天边界、page>200、空态、查询审计、普通员工 403、无写路由、自批 403、并发 409、非申请人 403、平台不可下载、CSV 字段白名单均可测。**F-008**：24h 过期必须在测试中注入 Clock/短 TTL 或直接改 `expires_at`，否则会 skip/假绿。
- 迁移：V1–V9 种子 + V10、CHECK/索引/权限回填、回滚 SQL 可在测试库执行；空库通过不算完成，ADR 已写。
- a11y：OIDC 登录页、审计/导出页的 375px/键盘/axe 可测；键盘完整旅程依赖测试 IdP 的可访问表单（F-007）。
- 未验证：没有实现代码，不能声称上述测试已通过；本评审只确认测试计划可执行性和缺口。

## 状态与边界

- 工作区干净；`07e817c` 只改 `contracts/**`、`docs/**`，未改生产源码/迁移/`AGENTS.md`/`docs/PRODUCT.md`；ADR-0019/0020 仍 `proposed`；未被错误标 `accepted`。
- TASK-0062 证据在 `07e817c`、`dirty:false`、exit 0；EV-0387 命令以 `bash -lc` 包装，作为任务级架构证据可用，但模块 gate 不应复用这类包装命令作为合同命令证据。
- `docs/ARCHITECTURE.md` 评审状态表将 Lead/Verifier/Ops 标为 pending，与 ADR `proposed` 一致；本评审只给 Verifier 结论，不代替 Lead/Ops/Product。

## 是否可进入 Builder / Ops

- **Builder：否（先修 P1）。** F-001（OIDC 失败路径清空会话）和 F-003（读取禁止键豁免）是 ADR 文本即可修正的 P1，修完复审即可；F-002 需要 Product 对 AC-12 做明确决策或改设计。P2 F-004..F-012/F-023 可带入任务卡/评审包，不阻塞架构定稿，但 F-006/F-007 必须在派单前解决（否则并行写冲突/E2E 无法跑）。
- **Ops：需要，且在 M10 gate 前完成。** 重点：`RICCON_OIDC_IDPS` Secret 注入/轮换与 `publicBaseUrl`；V10 分区表索引构建的维护窗口/online DDL；回滚 SQL 演练；worker schema 9→10 的部署顺序；`audit_export_request` 的 M09 清理。Ops 评审不要求阻塞 B1/B2（无迁移）开始，但 B3 的 V10 生产执行前必须有 Ops 结论。

## 剩余 P2/P3 承接

- P2 F-004：Builder B3 + 任务卡验收（平台审批仅单管理员租户）。
- P2 F-005：Builder B1/B3 + 动作词汇同步测试。
- P2 F-006/F-007：Lead/Architect 在派单前改任务写范围与测试夹具归属。
- P2 F-008：Builder B3/Verifier V1 的 `IT-AUDIT-105` 测试策略。
- P2 F-009：Builder B3 的 `IT-AUDIT-101` 性能断言。
- P2 F-010/F-011：Builder B1 的日志脱敏/无 userinfo 测试，Verifier `SEC-OIDC-001` 复核。
- P2 F-012：Ops 评审包 + M10 模块评审 `dod` 的 Ops 项。
- P2 F-023：Builder B3 + 任务卡明确平台租户导出是否在 M10 范围及审批人；契约/ADR 同步。
- P3 F-013..F-022：Builder 任务卡/契约注释；模块评审包逐条登记，不阻塞 gate。

## 未验证与限制

- 未执行实现、迁移、E2E、a11y、Trivy；本任务是架构评审，不能把这些当作已通过。
- Spring Security 7 的 PKCE/nonce/保存顺序结论来自官方文档/源码（context7）与本地 `spring-security-web` 字节码核对，不是本项目 spike；Builder 落地时需用测试证实。
- MySQL 8.4 分区表 online DDL 的最终可行性未在本项目数据库验证；需 Ops/迁移测试确认。
- AC 对账是架构映射，不是模块验收；M10-AC-08..18 的最终 pass 仍需实现后的 E2E/集成/安全测试证据。
