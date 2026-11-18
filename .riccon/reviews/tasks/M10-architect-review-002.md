---
review_id: REV-M10-ARCH-002
module_id: M10
task_id: TASK-0062
reviewer:
  agent: riccon/verifier
  session: ses_verifier_M10_TASK-0063_20261119
  model: deepseek-v4.1-flash
  independent: true
verdict: pass
reviewed_commits: [07e817c, e4dd9ad]
head_commit: fa06069
previous_review: REV-M10-ARCH-001
created_at: 2026-11-19T06:25:54+08:00
reviewed_evidence:
  [EV-0386, EV-0387, EV-0388, EV-0389, EV-0392, EV-0393, EV-0394, EV-0395]
evidence: []
findings:
  - id: F-001
    severity: P1
    status: fixed
    title: OIDC 业务失败会话清理
    summary: "ADR-0019 §2.1 新增 OIDC-aware SecurityContextRepository、业务校验前移、失败清理（clearContext/空上下文/删除授权请求/失效会话）与 IT-OIDC-001/004/006 断言；不得持久化 OidcUser/OAuth2AuthenticationToken/ID Token。"
    evidence: "ADR-0019 §2.1（82-96）、§4（133）；docs/ARCHITECTURE.md M10 无 token 段"
  - id: F-002
    severity: P1
    status: fixed
    title: AC-12 一次点击自动联合登出
    summary: "DEC-0017 A 已选定；ADR-0019 §5 改为本地先失效后自动顶层导航到 IdP end-session，无第二次点击；测试 IdP 实现 end-session 并由 E2E-M03-03 断言。残留 P2 F-024/F-025。"
    evidence: "DEC-0017；ADR-0019 §5（149-158）、§8 AC-12；OpenAPI logout 描述"
  - id: F-003
    severity: P1
    status: fixed
    title: 审计读取 action-aware 白名单
    summary: "ADR-0020 §1/§6.1 改为与写入相同的 action-aware 白名单，明确允许 promptTokens/completionTokens/sessionIdHash/paramsSha256/contentType，禁止 token/secret/content 子串误杀，未知动作非空 details fail-closed；IT-AUDIT-101 覆盖模型事件。"
    evidence: "ADR-0020 §1（43）、§6.1（248、253）、§9（327）；OpenAPI AuditEvent.details 描述"
  - id: F-004
    severity: P2
    status: fixed
    title: 平台审批仅单管理员租户
    summary: "平台端点在列表/决策前统计 ACTIVE TENANT_ADMIN；≥2 返回 409 RICCON-AUDIT-409；OpenAPI admin 端点补 409；IT-AUDIT-104 覆盖单/多管理员。"
    evidence: "ADR-0020 §2（68）、§9（328）；OpenAPI admin audit-exports 409"
  - id: F-005
    severity: P2
    status: fixed
    title: worker user.access.denied 动作词汇
    summary: "ADR-0020 §6.1 纳入 user.access.denied（userId/reason），AuditPolicy.actions 要求包含 API 与 Worker 动作；B1 唯一 owner。"
    evidence: "ADR-0020 §6.1（223-234、249）；WorkerAuditActions:15"
  - id: F-006
    severity: P2
    status: fixed
    title: Builder 写范围文件级不重叠
    summary: "ADR-0020 §8 按文件/目录拆分：B1 独占共享词汇与 SecurityConfig，B2 admin/identity|controller|service，B3 audit/controller|service|repository|persistence + admin/audit + V10，V1 独占测试 IdP/compose。glob 覆盖重叠已消除；表格验收摘要条数的 P3 见 F-026。"
    evidence: "ADR-0020 §8（289-314）"
  - id: F-007
    severity: P2
    status: fixed
    title: 测试 IdP 任务归属
    summary: "测试 IdP 明确归 V1：e2e/oidp/**、e2e/support/oidc/**、e2e/tests/m10/**、playwright.config.ts、docker-compose.test.yml；含 end-session、故障注入、/userinfo 计数 0。"
    evidence: "ADR-0019 §6（193）、§9（238）；ADR-0020 §8（306、314）"
  - id: F-008
    severity: P2
    status: fixed
    title: 导出过期测试 Clock/TTL
    summary: "ADR-0020 §2 注入 java.time.Clock，测试用短 TTL/MutableClock 验证过期，禁止 sleep/依赖 24h；生产 TTL 上限 7 天、≤0 启动失败。"
    evidence: "ADR-0020 §2（70）、§9（328）"
  - id: F-009
    severity: P2
    status: fixed
    title: actorDisplayName 批量解析
    summary: "ADR-0020 §1 要求 LEFT JOIN 或一次 IN 批量解析，禁止逐行查询；IT-AUDIT-101 以 100 行断言无 N+1。"
    evidence: "ADR-0020 §1（42）、§9（327）"
  - id: F-010
    severity: P2
    status: fixed
    title: OIDC secret 解析失败脱敏
    summary: "ADR-0019 §1 要求解析/字段异常只记录序号/字段路径/错误类型/位置，不打印原始 JSON/配置对象/clientSecret；OidcProperties.toString 掩码；SEC-OIDC-001 增加 malformed config 日志扫描。"
    evidence: "ADR-0019 §1（53）、§9（238）"
  - id: F-011
    severity: P2
    status: fixed
    title: 不调用 userinfo
    summary: "ADR-0019 §2 明确清空/忽略 discovery userinfo_endpoint，使用只从 ID Token 构建的自定义 OidcUserService；测试 IdP 断言 /userinfo 请求为 0。"
    evidence: "ADR-0019 §2（69）、§6（193）"
  - id: F-012
    severity: P2
    status: fixed
    title: V10/Ops 前置
    summary: "ADR-0020 §4/§8 明确 OPS-M10 在 V10 生产执行前与 M10 gate 前完成：Secret 注入/轮换、代理头、分区索引窗口/online DDL、回滚演练、worker schema 9→10、M09 清理；不阻塞 B1/B2。"
    evidence: "ADR-0020 §4（108）、§8（296、308）、§9（331）"
  - id: F-013
    severity: P3
    status: fixed
    title: UserCreateRequest LOCAL password 非空
    summary: "OpenAPI else 分支把 password 收紧为 string/minLength 12，null 不再通过。"
    evidence: "contracts/openapi.yaml UserCreateRequest if/then/else"
  - id: F-014
    severity: P3
    status: fixed
    title: 审计 page≤200 契约化
    summary: "新增 AuditPage 参数 maximum:200，审计查询改用 AuditPage。"
    evidence: "contracts/openapi.yaml AuditPage；ADR-0020 §7（283）"
  - id: F-015
    severity: P3
    status: fixed
    title: 403/404 语义
    summary: "ADR-0020 §2/§6.2 区分 RICCON-AUTH-403（无权限）、RICCON-AUDIT-403（有权限但业务拒绝，含平台租户导出）、平台管理员租户下载 404；F-027 仍有一处表内重复。"
    evidence: "ADR-0020 §2（76-77）、§6.2（259-266）"
  - id: F-016
    severity: P3
    status: fixed
    title: AC-17 范围/原因可读性
    summary: "audit.export.requested/approved/rejected details 增加 occurredFrom/occurredTo/action/actorId/targetType/targetId/outcome + estimatedRowCount/filtersHash；decisionComment 保存在导出记录，评审包可读。"
    evidence: "ADR-0020 §2（72）、§6.1（241-248）"
  - id: F-017
    severity: P3
    status: accepted
    title: M03 F-009 residual
    summary: "ADR-0020 §3 明确目标租户仍看不到平台来源事件，是 residual/deferred 不是 fixed；M10 评审包/ACCEPTANCE 必须照此记录。"
    evidence: "ADR-0020 §3（93-96）；REV-M03-001 F-009"
  - id: F-018
    severity: P3
    status: accepted
    title: PRODUCT AC 编号/TEN 范围漂移
    summary: "ADR-0020 §9 登记 PRODUCT §8.3 仍写 M03-AC-*、AC-20 仍写 TEN-001..026；M10 按 §8.5 映射并保留 TEN-028..030，不重排已发布编号。"
    evidence: "ADR-0020 §9（333）；docs/PRODUCT.md §8.3"
  - id: F-019
    severity: P3
    status: fixed
    title: deferred 状态与 EXPIRED 语义
    summary: "x-riccon-deferred 改为 M10+ (unplanned)，新增 platform-tenant-audit-export/audit-export-archive；OpenAPI 定义 status=EXPIRED 等价 APPROVED 且 expiresAt<now。"
    evidence: "contracts/openapi.yaml x-riccon-deferred、AuditExportStatus"
  - id: F-020
    severity: P3
    status: fixed
    title: CSV 公式注入
    summary: "ADR-0020 §2 对危险前缀前置单引号，IT-AUDIT-106 断言；重复下载专用配额 deferred（F-030 记录通用限流表述问题）。"
    evidence: "ADR-0020 §2（85-87）"
  - id: F-021
    severity: P3
    status: fixed
    title: Starter 名称与 providers 限流
    summary: "ADR-0019 改用 canonical spring-boot-starter-security-oauth2-client；providers 端点按客户端 IP 60 次/分钟限流，超限 429。"
    evidence: "ADR-0019 §2（65）、§4（147）"
  - id: F-022
    severity: P3
    status: fixed
    title: 409/保留文案/回滚前提
    summary: "POST /audit-exports 移除无场景 409；策略文案使用 policy.retentionDays；V10 回滚记录 audit.read 无历史手工授予的前提与 worker 9→10 回退顺序。"
    evidence: "contracts/openapi.yaml；ADR-0020 §1（46）、§5.4（217）"
  - id: F-023
    severity: P2
    status: fixed
    title: 平台租户审计导出闭环
    summary: "ADR-0020 §2/§10 明确平台租户自身导出 M10 一律 403 RICCON-AUDIT-403，列为 platform-tenant-audit-export deferred；不引入自批；OpenAPI 描述同步。"
    evidence: "ADR-0020 §2（56）、§10（340）；OpenAPI audit-exports 403"
  - id: F-024
    severity: P2
    status: open
    title: ADR-0019 Consequences/Alternatives 仍写“显式动作”
    summary: "§5 已按 DEC-0017 A 自动联合登出，但 Consequences（251）仍写“联合登出是显式动作而非自动完成”，Alternatives（262）仍写“M10 选择本地权威 + 显式动作”。与 DEC-0017 A、§5、§8 AC-12 直接矛盾，需在 ADR 接受前修正。"
    evidence: "ADR-0019:251、262 对照 §5（149-158）与 DEC-0017"
  - id: F-025
    severity: P2
    status: open
    title: AC-12 IdP 不可达提示依赖导航前页面/浏览器后退
    summary: "§5 先显示“已退出本地会话”然后在 ≤300ms 内 window.location.assign；IdP 不可达时跨域顶层导航失败由浏览器处理，用户会先离开登录页，提示只存在于历史/后退。需 E2E 证明同页提示或改为可见延迟/退出落地页；否则 AC-12“IdP 不可达仍提示”证据不足。"
    evidence: "ADR-0019 §5（152-156）；DEC-0017；PRODUCT:208"
  - id: F-026
    severity: P3
    status: open
    title: 任务表验收摘要条数超过 6
    summary: "§8 声称 acceptance≤6，但表格验收列为 7–9 个分号项（B1 8、B2/B4/B5 7、B3 9、V1 9）；§316 说明“指任务卡 acceptance 条数”。Lead 建任务卡时必须合并/拆分到 ≤6，不能照抄表格。"
    evidence: "ADR-0020 §8（289-316）"
  - id: F-027
    severity: P3
    status: open
    title: §6.2 将平台审批多管理员租户同时列为 403 和 409
    summary: "§2/§6.1 与 OpenAPI 均为 409 RICCON-AUDIT-409；§6.2 的 403 行仍列“平台审批多管理员租户”。需删除 403 项或说明不同场景，避免测试预期分裂。"
    evidence: "ADR-0020 §2（68）、§6.2（261-262）"
  - id: F-028
    severity: P3
    status: open
    title: TTL 测试配置 PT2S 与 ttlHours 契约不一致
    summary: "AuditPolicy.exportLimits.ttlHours 为 integer min 1；测试用 PT2S 无法在策略响应表达。建议测试用 MutableClock 保持 24h TTL，或策略返回秒/毫秒字段。"
    evidence: "ADR-0020 §2（70）；OpenAPI AuditExportLimits"
  - id: F-029
    severity: P3
    status: open
    title: OidcAwareSecurityContextRepository 未说明 loadContext 过滤
    summary: "§2.1 只规定 saveContext 跳过 OIDC 主体；若会话中已有 OidcUser/OAuth2AuthenticationToken（历史/异常），loadContext 仍可能恢复。建议 loadContext 遇到 OIDC 主体返回空上下文并删除该属性。"
    evidence: "ADR-0019 §2.1（86）"
  - id: F-030
    severity: P3
    status: open
    title: “沿用通用 API 限流”缺少既有组件依据
    summary: "ADR-0020 §2（87）称重复下载沿用通用 API 限流；当前 API 只有登录限流/对话队列 429，没有通用 API 限流。需明确实际限流来源，或把重复下载配额标记为完全 deferred 风险。"
    evidence: "ADR-0020 §2（87）；apps/api 限流组件检索"
  - id: F-031
    severity: P3
    status: open
    title: data.exported 与流式响应的顺序未定义
    summary: "§2 要求每次成功下载写 data.exported；若在流式提交后才写，审计失败无法使下载失败（R-12-1 fail-closed）。建议在开始流式前写入，或明确失败补偿语义。"
    evidence: "ADR-0020 §2（87）、威胁模型 R-12-1"
---

# REV-M10-ARCH-002：M10 架构复审（TASK-0062）

## 结论

**verdict: pass。** 上一轮 `REV-M10-ARCH-001` 的 3 个 P1 均已在 `e4dd9ad` 中修复：F-001（OIDC 失败会话清理）、F-002（DEC-0017 A 一次点击自动联合登出）、F-003（审计读取 action-aware 白名单）。P2 F-004..F-012/F-023 与 P3 F-013..F-022 均有对应修改或明确 residual；新增 2 个 P2（ADR 文案残留、IdP 不可达提示证据）和 6 个 P3，均不阻断架构定稿或 B1/B2 启动。**P0=0，P1=0**，ADR-0019/0020 可从 `proposed` 进入复审批准流程；本评审不代替 Lead/Ops 的正式批准。

## 验证范围与命令

- 起点：`git status --short` 空；HEAD `fa06069`（`chore(M10): 重启架构复审，记录 DEC-0017`）。
- 变更面：`git show --stat e4dd9ad` 仅 8 个 docs/contracts 文件；`git show --stat bfb8856` 为 DEC-0017、EV-0392..0395、state/task，无生产/迁移/测试。
- `npx --yes @redocly/cli@1.34.3 lint contracts/openapi.yaml`：exit 0，2 条 302 `operation-2xx-response` warning；**判定可接受**（OIDC authorize/callback 是 302-only 顶层导航端点，不是缺 2XX）。
- `contract validate --strict`：exit 0（0 errors/0 warnings）；`state validate`：exit 0（仅既有 M11 legacy warning）；`git diff --check`：exit 0。
- 复核 TASK-0062 证据 EV-0392..0395：均 `manual:false`、`dirty:false`、exit 0、commit `e4dd9ad`；EV-0386..0389 为上轮 Redocly/contract/diff 证据。
- 未运行代码测试、迁移、E2E、a11y、security：本任务为架构复审，`e4dd9ad` 无实现代码；按任务要求不跑。

## F-001（fixed）

ADR-0019 §2.1（82-96）与 §4（133）已覆盖上一轮指出的缺口：

1. `OidcAwareSecurityContextRepository` 包装 `HttpSessionSecurityContextRepository`，`saveContext` 遇到 `OidcUser`/`OAuth2AuthenticationToken` 不落会话；只有 `RicconPrincipal` 落会话。
2. 绑定、禁用、租户状态、issuer 校验前移到 `OidcUserService`/`AuthenticationProvider`，在认证成功前抛 `OAuth2AuthenticationException`，不调用成功处理器。
3. `OidcLoginCleanup.reject` 统一 `clearContext`、保存空上下文并删除 `SPRING_SECURITY_CONTEXT`、移除授权请求、`session.invalidate()`、no-op authorized client、失败审计、302 原因码。
4. `IT-OIDC-001/004/006` 断言失败回调后 `GET /auth/session` 401、`SPRING_SESSION_ATTRIBUTES` 不含 `SPRING_SECURITY_CONTEXT`/`OidcUser`/`id_token`；`SEC-OIDC-001` 扫描会话序列化字节与日志。

这与 Spring Security 7 的 `AbstractAuthenticationProcessingFilter.successfulAuthentication` 先 `saveContext` 后 `successHandler` 的行为一致；设计已把“框架先保存 OIDC 主体”的风险显式收口。**F-001 fixed。**

## F-002（fixed，附 P2 F-024/F-025）

DEC-0017 已由用户回答 A（`status: answered`，`answer_kind: choose`）：配置 end-session 时点击一次登出即导航结束 IdP 会话，IdP 不可达则本地登出成功并提示。ADR-0019 §5（149-158）已按此实现：

- 本地 `POST /auth/logout` 先失效会话、写 `auth.logout`，不依赖 IdP；
- 204 + `X-Riccon-Federated-Logout-Url`；SPA 存 URL 后自动 `window.location.assign` 顶层导航；
- 测试 IdP 实现 end-session，校验 `client_id`/`post_logout_redirect_uri`；`E2E-M03-03` 断言一次点击后 IdP 会话清除、后退不可恢复；IdP 不可达用 Playwright 路由中止验证本地失效与提示；
- 不再有第二次点击；OpenAPI logout 描述同步 DEC-0017 A。

核心 P1 已 fixed。残留：

- **P2 F-024**：ADR-0019 Consequences（251）仍写“联合登出是显式动作而非自动完成”，Alternatives（262）仍写“M10 选择本地权威 + 显式动作”，与 §5/DEC-0017 A 矛盾；ADR 接受前必须改。
- **P2 F-025**：IdP 不可达时，§5 的“提示”发生在自动导航前（≤300ms），跨域导航失败后用户会离开登录页，提示只留在历史/后退；需 E2E 证明同页提示，或改为可见延迟/退出落地页。这不是新的 P1：本地登出已成功且 DEC-0017 A 已接受浏览器导航限制，但 AC-12 的“提示”证据要补。

## F-003（fixed）

ADR-0020 §1（43）与 §6.1（248、253）已把读取策略改为与写入相同的 **action-aware 白名单**：

- 明确允许 `promptTokens`、`completionTokens`、`sessionIdHash`、`paramsSha256`、`contentType`；
- 禁止 `token`/`secret`/`content` 子串匹配；
- 白名单外键、未知 action 的非空 details、精确敏感键命中 → 500 `RICCON-AUDIT-500` fail-closed；
- `IT-AUDIT-101` 覆盖模型 `promptTokens`/`completionTokens` 与 worker `user.access.denied`；
- `AuditPolicy.actions` 要求包含 API 与 Worker 动作。

这修复了上一轮“模型事件会被 token 子串误杀”的 P1。**F-003 fixed。**

## P2/P3 处置核对

- **F-004 fixed**：平台审批仅单管理员租户（ACTIVE TENANT_ADMIN ≤1），≥2 返回 409；OpenAPI admin 列表/决策补 409；IT-AUDIT-104 覆盖单/多管理员。
- **F-005 fixed**：`user.access.denied` 纳入动作表与策略 actions；B1 唯一 owner。
- **F-006 fixed**：B1/B2/B3 写范围按 `auth/controller` vs `auth/repository|persistence`、`admin/identity|controller|service` vs `admin/audit`、`audit/domain` vs `audit/controller|service|repository|persistence` 拆分；共享词汇只归 B1；V10 只归 B3。表格验收摘要条数的 P3 见 F-026。
- **F-007 fixed**：测试 IdP 归 V1 `e2e/oidp/**`/`e2e/support/oidc/**`、E2E 夹具、compose 测试配置；含 end-session、故障注入、/userinfo 计数 0。
- **F-008 fixed**：Clock 注入 + 短 TTL/MutableClock，禁止 sleep/依赖 24h；生产 TTL 上限 7 天。
- **F-009 fixed**：actorDisplayName LEFT JOIN 或一次 IN 批量解析，禁止 N+1；IT-AUDIT-101 100 行断言。
- **F-010 fixed**：secret 解析失败只记录序号/字段/错误类型，toString 掩码；SEC-OIDC-001 覆盖 malformed config。
- **F-011 fixed**：清空/忽略 discovery userinfo_endpoint，自定义 OidcUserService 只从 ID Token 构建，测试断言无 /userinfo。
- **F-012 fixed**：OPS-M10 在 V10 生产执行与 M10 gate 前完成；不阻塞 B1/B2；项包括 Secret/代理头/分区索引/回滚/worker schema 9→10/M09 清理。
- **F-023 fixed**：平台租户自身导出 M10 一律 403 `RICCON-AUDIT-403`，`platform-tenant-audit-export` deferred；不引入自批。
- **F-013 fixed**：OpenAPI `UserCreateRequest` else 分支 password 收紧为 string/12–200，null 不再通过。
- **F-014 fixed**：新增 `AuditPage` maximum 200。
- **F-015 fixed**：`RICCON-AUTH-403`（无权限）与 `RICCON-AUDIT-403`（业务拒绝）区分；平台管理员租户下载 404。
- **F-016 fixed**：audit.export.* details 增加 occurredFrom/occurredTo/action/actorId/targetType/targetId/outcome + estimatedRowCount/filtersHash；decisionComment 保留在导出记录。
- **F-017 accepted**：M03 F-009 明确 residual/deferred，不是 fixed；目标租户仍不可见平台来源事件。
- **F-018 accepted**：PRODUCT §8.3 的 M03-AC-* 与 AC-20 TEN-001..026 文本漂移已登记，M10 按 §8.5 映射并使用 TEN-028..030。
- **F-019 fixed**：`x-riccon-deferred` 改 `M10+ (unplanned)`，新增 platform-tenant-audit-export/audit-export-archive；`status=EXPIRED` 派生语义在 OpenAPI 定义。
- **F-020 fixed**：CSV 危险前缀转义；重复下载专用配额 deferred（F-030 记录“通用 API 限流”表述问题）。
- **F-021 fixed**：canonical `spring-boot-starter-security-oauth2-client`；providers 按 IP 60 次/分钟限流 429。
- **F-022 fixed**：POST /audit-exports 移除无场景 409；策略文案使用 `policy.retentionDays`；V10 回滚记录 audit.read 前提与 worker 回退顺序。

## 契约与 AC 对账

- `info.version` 仍 `1.2.0-m10`；既有路径无删除；`AuditPage`、`UserCreateRequest`、admin 409、平台租户 403、`EXPIRED`、`x-riccon-deferred`、logout 自动导航描述均已更新。
- **2 条 302 warning 判定可接受**：authorize/callback 是 302-only 顶层导航，缺少 2XX 是契约事实；lint exit 0。
- **AC-08**：OIDC 授权码 + PKCE + nonce、会话 Cookie、无 JWT/token，映射 E2E-M03-03/SEC-OIDC-001；**mapped**（F-001 已 fixed）。
- **AC-09**：不按邮箱、拒绝未绑定、管理员显式绑定后命中同一账号/角色；**mapped**。
- **AC-10**：两租户同邮箱不同 issuer 不合并；M10 不自动开通，账号由管理员创建/绑定；IT-OIDC-003；**mapped（附注）**。
- **AC-11**：禁用不建会话、既有会话下一请求拒绝；IT-OIDC-004/SEC-OIDC-002；**mapped**。
- **AC-12**：一次点击本地先失效 + 自动 end-session + IdP 不可达提示；E2E-M03-03/IT-OIDC-005；**mapped，P2 F-025 证据需补**。
- **AC-13**：四类失败独立原因码/下一步、无原始错误/token/地址/堆栈；E2E-M03-04/IT-OIDC-006；**mapped**。
- **AC-14**：Cookie/CSRF/超时一致、角色下一请求生效、响应/前端无 JWT/refresh；SEC-OIDC-003/IT-OIDC-007；**mapped**（F-001 已 fixed）。
- **AC-15**：≥200 条审计（含模型）、过滤/倒序/分页/空态、查询写审计；IT-AUDIT-101..102/E2E-M03-05；**mapped**（F-003 已 fixed，F-009 批量）。
- **AC-16**：无修改/删除入口、接口拒绝写、数据库拒绝 UPDATE/DELETE、普通员工 403；IT-AUDIT-103/TEN-028；**mapped**。
- **AC-17**：申请/审批/自批禁止/另一管理员/时效/CSV 条件与生成时间/审计范围与行数/拒绝原因；IT-AUDIT-104..105/E2E-M03-06；**mapped**（F-016 补可读范围，F-023 平台租户 403）。
- **AC-18**：保留 ≥180、策略/可查范围/无删除、敏感字段剔除、过期不可下载；IT-AUDIT-106/SEC-AUDIT-001；**mapped**（F-003 允许模型事件、F-020 公式转义）。
- **AC-19/20/21**：DEPT-010 导出段、TEN-028..030、E2E-M03-03..07/axe；**mapped**。
- MFA、审计哈希链、平台跨租户审计明细均保持 deferred，未声称实现；M03 F-009 仍是 residual。

## 任务图与启动条件

- **数量**：5 Builder + 1 Verifier + 1 Ops；B1..B5/V1 的 write_scope 分别为 8/7/8/5/5/7，均 ≤8；文件级不重叠（F-006 fixed）。
- **验收条数**：表格验收摘要列有 7–9 个分号项，超过“acceptance≤6”；§316 说明“指任务卡 acceptance 条数，不是测试条数”。Lead 建任务卡时必须合并/拆分为 ≤6，不能照抄摘要（P3 F-026）。
- **依赖**：B1→B2→B4；B1→B3→B5；B4+B5→V1；OPS-M10→B3 V10 生产/M10 gate。B1 可立即启动；B2 在 B1 共享词汇落地后启动（或按 Lead 拆分并行）；B3 代码/V10 迁移测试可在测试库进行，V10 生产执行与 gate 需 Ops；V1 在 B4/B5 后。
- **风险**：B1 独占 `AuditActions`/`AdditionalAuditActions`/`AuditDetailsPolicy`/`RoleDefinitions`/`ErrorCodes`；B2/B3 只消费；B3 独占 V10。跨任务不得同时改同一文件。满足“B1/B2 可先启动、B3 V10 需 Ops”。

## Ops 评审

**必须**，但范围与时机已明确：OPS-M10 在 **B3 的 V10 生产执行前**与 **M10 gate 前**完成；不阻塞 B1/B2。必须覆盖：

1. `RICCON_OIDC_IDPS`/`RICCON_PUBLIC_BASE_URL` Secret 注入、轮换、代理 scheme/host 与 `publicBaseUrl`；
2. 分区表 `audit_event` 两个索引的构建窗口/MySQL 8.4 online DDL 结论；
3. V10 回滚 SQL 演练、`audit_export_request` 的 M09 清理；
4. worker `SchemaVersionChecker` 9→10 与 API/Worker 滚动顺序；
5. `SEC-OIDC-001`/Trivy 对 secret 的处理。

## 边界与状态

- `e4dd9ad` 仅 docs/contracts；`bfb8856` 仅 DEC-0017、evidence、state/task；未改生产源码、迁移、测试、AGENTS/PRODUCT；工作区 `git status --short` 空；ADR-0019/0020 仍 `proposed`。
- `docs/ARCHITECTURE.md` 评审状态表仍显示 Verifier `changes-requested`、Lead/Ops `pending`；本复审结论为 pass，请 Lead/Architect 在复审记录中更新；Verifier 不修改该表。
- 未验证：没有实现代码，无法证明上述设计在 Spring Security 7 的实际过滤器链中按预期工作；Builder 必须用 `IT-OIDC-001..007`/`SEC-OIDC-001..003` 证明。V10 未执行迁移；Ops/迁移测试负责。

## 剩余 P2/P3 承接

- **P2 F-024**：Architect 修正 ADR-0019 Consequences/Alternatives 的“显式动作”残留；ADR 接受前完成。
- **P2 F-025**：Builder B4/V1 补 IdP 不可达的同页提示/E2E 证据；若实现只能依赖历史后退，需 Product 明确接受。
- **P3 F-026**：Lead 建任务卡时把验收合并到 ≤6；B1 8→6、B3 9→6、V1 9→6。
- **P3 F-027**：Architect 统一 §6.2 的平台多管理员错误码为 409，删除 403 项。
- **P3 F-028**：Builder B3 用 MutableClock/24h TTL，或扩展策略字段；不要让 `ttlHours` 与 PT2S 冲突。
- **P3 F-029**：Builder B1 在 `OidcAwareSecurityContextRepository.loadContext` 过滤 OIDC 主体并删除属性。
- **P3 F-030**：Builder B3/Ops 明确通用限流来源，或把重复下载配额完全标 deferred 并登记风险。
- **P3 F-031**：Builder B3 在开始 CSV 流式前写 `data.exported`（或定义失败补偿），保持审计 fail-closed。

## 是否可进入 Builder / Ops

- **B1/B2：可以启动。** B1 可立即开始；B2 在 B1 共享词汇落地后开始（Lead 可按任务卡拆分并行）。两者无文件级重叠；V10 不在 B1/B2。
- **B3：代码与迁移测试可启动；V10 生产执行需 Ops。** B3 必须在测试库用 V1–V9 种子验证 V10 与回滚；生产执行前完成 OPS-M10。
- **B4/B5：在 B1/B2/B3 的契约 schema 稳定后启动；V1 在 B4/B5 后。**
- **Ops：必须评审，但不阻塞 B1/B2；阻塞 V10 生产与 M10 gate。**
- **ADR-0019/0020**：从 `proposed` 进入 Lead/User/Ops 复审批准流程；本 Verifier 结论为 pass，不代替正式批准。
