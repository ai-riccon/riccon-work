# ADR-0019：M10 OIDC/SSO（每租户 1 个 IdP，授权码 + PKCE）

Status: proposed
Date: 2026-11-19
Reviewers: `riccon/lead`（完整性/合同一致性）、`riccon/verifier`（可测试性/安全边界）、`riccon/ops`（配置/轮换/回滚）；独立评审记录写入 `docs/ARCHITECTURE.md` 评审状态表
Approved-by: pending（DEC-0014 已确认产品口径；ADR 本身未经评审，不自行标 `accepted`）
Review-date: 2026-11-19T23:59:59+08:00
Supersedes: none
关联：ADR-0003（租户上下文）、ADR-0006（密钥与信封加密）、ADR-0007（审计）、ADR-0009（租户隔离）、ADR-0012（数据模型）、ADR-0013（会话/CSRF）、ADR-0018（M03 组织与数据范围）、DEC-0011、DEC-0013、DEC-0014、DEC-0015、PRODUCT §8 M10-AC-08..14/21

## Context

M03 已完成部门与数据范围，用户 accepted（DEC-0016）。M10 承接 `docs/PRODUCT.md` §8 的 M10-AC-08..14：企业 IdP 授权码 + PKCE 登录、账号按 issuer + subject 绑定、冲突/禁用/错误态、复用 M02 会话层且不引入 JWT。`apps/api` 当前只有内置账号 + Spring Session JDBC（ADR-0013）；`app_user` 已预留 `auth_source`、`external_issuer`、`external_subject` 与唯一键 `(tenant_id, external_issuer, external_subject)`（V2），无需为绑定新增表。

产品口径（DEC-0014）与用户故事（PRODUCT §8.2）明确：

- 每租户 1 个通用授权码 + PKCE 的 IdP；多 IdP 后移；
- 账号由管理员**显式绑定**，不按邮箱自动关联，防止账号接管；
- IT 运维通过**部署配置**提供 issuer 与客户端凭据，M10 不新增运维界面；
- 配错时登录页给出可读原因，内置账号仍可登录兜底；
- 复用 M02 会话层，浏览器与响应体不出现 JWT、access token 或 refresh token。

约束与现状：

- `SecurityConfig` 默认拒绝，仅有显式白名单；认证/租户解析/RBAC 在过滤器链与 `AuthService` 中完成。
- 会话为服务端 Spring Session JDBC，Cookie `RICCON_SESSION`；登录后轮换会话 ID、轮换 CSRF；权限每请求从数据库计算。
- 私有化部署可能单实例或多实例；数据库只有 MySQL，不引入 Redis。
- T4：身份是高风险面，必须有威胁建模、独立安全用例与回滚路径。

候选配置来源：数据库表 + 平台管理员 API、部署配置（环境变量/挂载 Secret）、外部配置中心。M10 无运维 UI（DEC-0014），数据库表需要额外的写入口、加密、缓存失效与审计，成本高于收益；外部配置中心引入新的运行时依赖。选择部署配置。

## Decision

### 1. 配置来源与托管

**M10 每租户 IdP 配置只来自部署配置，不入库、不提供写接口与 UI。**

- 配置属性 `riccon.oidc.idps`，由环境变量 `RICCON_OIDC_IDPS` 注入，内容为 JSON 数组；生产通过 Helm Secret/挂载 Secret 注入，禁止写入仓库、镜像、日志、证据或提示词。
- 每项字段：

| 字段                 | 必填 | 说明                                                                   |
| -------------------- | ---- | ---------------------------------------------------------------------- |
| `tenantCode`         | 是   | 目标租户代码，必须与 `tenant.id` 一致；同配置内唯一                    |
| `issuer`             | 是   | OIDC issuer，绝对 URL；规范化后全局唯一（禁止两个租户共享同一 issuer） |
| `clientId`           | 是   | 在 IdP 注册的客户端 ID                                                 |
| `clientSecret`       | 是   | 客户端密钥；只从 Secret 注入，禁止出现在任何日志/错误/审计             |
| `providerName`       | 否   | 登录页展示名，如“Acme 企业账号”；缺省用 `企业账号`                     |
| `scopes`             | 否   | 默认 `["openid"]`；不得移除 `openid`，不得包含 `offline_access`        |
| `endSessionEndpoint` | 否   | IdP RP-Initiated Logout 端点；缺失表示不支持联合登出                   |
| `enabled`            | 否   | 默认 `true`；`false` 等同未配置，但保留配置便于运维排查                |

- 启动校验（fail-fast，仅针对已提供的配置）：JSON 可解析、字段非空、`tenantCode` 符合 `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`、`issuer` 绝对 URL 且 HTTPS（`riccon.oidc.allow-http=true` 仅限 local/test）、issuer 规范化后唯一、`clientId`/`clientSecret` 非空、scopes 含 `openid` 且不含 `offline_access`。配置格式错误导致应用启动失败；**未配置任何 IdP 时正常启动**，仅关闭企业登录。
- 未在配置中的租户：企业登录不可用，登录页显示“该租户未开通企业账号登录”，内置账号不受影响。
- **启用/停用**：配置存在且 `enabled=true` 即启用；移除或置 `false` 后重启生效。M10 不提供运行时热更新，避免“配置已改但实例行为不一致”。多实例必须使用同一份配置滚动重启。
- **密钥轮换**：IdP 侧先允许新旧 secret 并存，再更新 Secret 并滚动重启；新登录使用新 secret，旧实例上的在途授权码可能失败并返回 `idp_unavailable`，用户重试即可。因为不存 token，轮换不需要失效会话。
- **托管方式**：M10 不把 `clientSecret` 写入数据库，因此不使用 ADR-0006 的信封加密；托管责任在部署 Secret/KMS（M09 可接 Vault/云 Secret Manager）。应用只在构建 `ClientRegistration` 时读取，任何日志、错误、审计、Actuator 端点都不得输出该值。
- **为什么不是数据库表**：M10 没有运维/平台配置 UI（DEC-0014），DB 表会变成“无写入口的加密表”，或倒逼一个平台管理员配置 API；两者都要新增密钥托管、缓存失效、审计与运维界面，收益不足。配置属于部署引导信息，放在 Secret 中与 DB/S3/服务令牌现有模式一致。
- **何时重开**：客户需要租户自助配置、无需重启轮换、或租户数量大到手工配置不可维护时，另立 ADR 引入 `tenant_idp_config` 表 + 信封加密（ADR-0006）+ 平台管理员 API + 审计；不在 M10 预埋空表。

### 2. 协议、会话与令牌

- **Authorization Code + PKCE（S256）+ OIDC nonce**；不使用 implicit/hybrid；不请求 `offline_access`，不保存 refresh token。
- 每租户一个 `ClientRegistration`，registrationId 固定 `tenant-{tenantCode}`；授权入口 `GET /api/v1/auth/oidc/authorize/{registrationId}`，回调 `GET /api/v1/auth/oidc/callback/{registrationId}`；回调 URI 为 `{publicBaseUrl}/api/v1/auth/oidc/callback/{registrationId}`，必须在 IdP 精确注册。
- 使用 Spring Security 7 的 `oauth2Login`（`spring-boot-starter-oauth2-client`，Apache-2.0，与既有 Spring Security 同栈）：
  - 动态 `ClientRegistrationRepository` 按 registrationId 从部署配置构建；`clientAuthenticationMethod=CLIENT_SECRET_BASIC`；`requireProofKey=true`，由默认 resolver 加 PKCE；
  - 默认 `DefaultOAuth2AuthorizationRequestResolver` 在 `openid` scope 下自动生成 nonce：原始 nonce 存服务端授权请求属性，哈希后的 nonce 发给 IdP，回调时由 `OidcAuthorizationCodeAuthenticationProvider` 校验；
  - `AuthorizationRequestRepository` 使用会话内实现（默认 `HttpSessionOAuth2AuthorizationRequestRepository`）：state、nonce、PKCE verifier 均只在服务端会话，回调一次后删除，重放/过期回调失败；
  - 不调用 userinfo：`ClientRegistration` 不配置 userinfo URI，只用 ID Token 的 `sub`；默认 scope 只含 `openid`，不请求 `email`/`profile`；
  - ID Token 校验：签名（JWKS）、`iss` 与配置/发现文档一致、`aud` 含 clientId、`exp`、nonce；允许时钟偏差 ≤ 60 秒；`sub` 非空且 ≤ 255 字符。
- **发现文档**：首次使用时懒加载 `{issuer}/.well-known/openid-configuration`，进程内缓存 10 分钟，失败退避 30 秒；发现失败返回 `idp_unavailable`，**不阻塞应用启动、不影响内置账号登录**。发现文档的 `issuer` 必须与配置规范化后完全一致，否则按 `issuer_mismatch` 失败。
- **不存 token**：提供 `NoopOAuth2AuthorizedClientRepository`，登录成功后 access/refresh/ID token 不写入会话、不写数据库、不写日志、不回传浏览器。浏览器只收到会话 Cookie 与最终的重定向；回调 URL 只出现标准 `code`/`state`，随后被服务端消费。响应体、前端存储、日志、审计、错误页均不得出现 token。
- **复用 ADR-0013 会话层**：同一 `RICCON_SESSION`、Spring Session JDBC、30 分钟空闲滑动、`HttpOnly`/`SameSite=Lax`/非本地 `Secure`。登录成功后：
  1. Spring Security 的会话策略轮换会话 ID（防会话固定）；
  2. 成功处理器用 `RicconPrincipal` 替换 OIDC 主体，显式 `SecurityContextRepository.saveContext`；
  3. 轮换 CSRF 令牌（旧令牌失效，前端通过 `GET /auth/session` 取新值）；
  4. 角色/权限仍每请求从数据库计算，账号禁用或角色撤销下一次请求生效。
- **CSRF**：所有写接口保持 `X-CSRF-TOKEN`；授权入口是顶层 GET 链接，回调是 GET。登录 CSRF 由“state 绑定本会话 + nonce + 授权请求一次消费”缓解；不接受跨站 POST 发起登录。
- **无 JWT**：不签发、不校验自有 JWT；ID Token 只在服务端回调内校验并立即丢弃，不作为 API 凭据。
- **过滤器链约束**：`SecurityConfig` 只对 `GET /api/v1/auth/oidc/providers`、`GET /api/v1/auth/oidc/authorize/**`、`GET /api/v1/auth/oidc/callback/**` 增加 `permitAll`，其余 `anyRequest().denyAll()` 与 `/api/v1/**` authenticated 保持不变；不得启用默认登录页、表单登录、请求缓存自动跳转或 `oauth2Client` 的 token 持久化。授权入口是显式链接，未认证的普通 API 仍返回 JSON 401。

### 3. 账号开通与绑定

身份键固定 `(tenant_id, issuer, subject)`；`issuer` 按第 1 节规范化后存储；唯一键由 V2 提供。

- **不自动开通、不按邮箱关联**：首次 OIDC 登录若没有匹配账号，直接拒绝并提示“账号未开通，请联系管理员关联”；不读取、不比较、不存储 IdP 返回的邮箱（即使 IdP 返回也不使用），因此同邮箱不同 issuer 不可能合并。
- **管理员显式绑定已有内置账号**（M10-AC-09）：
  - 租户管理员：`PUT /api/v1/users/{userId}/oidc-binding`，权限 `tenant.user.manage`；
  - 平台管理员：`PUT /api/v1/admin/tenants/{tenantId}/users/{userId}/oidc-binding`，权限 `platform.tenant.manage`；
  - 请求体 `{issuer, subject}`；`issuer` 必须等于该租户当前配置的 issuer（规范化后），否则 400 `RICCON-OIDC-400`；
  - 同一事务内：`auth_source=OIDC`、写入 `external_issuer`/`external_subject`、**删除 `user_credential`**（单认证来源，避免 SSO 账号仍可用密码绕过 IdP 策略）；角色与部门归属不变；
  - 写 `user.oidc.bound` 审计（只存 `userId`、`issuerHash`、`subjectHash`、`previousAuthSource`，不存 subject 原文）；
  - 冲突：同一身份已绑定其他账号、账号已绑定其他身份、唯一键冲突 → 409 `RICCON-OIDC-409`；账号不存在/跨租户 → 404 `RICCON-TENANT-404`。
- **直接开通 OIDC 账号**：`POST /users` 与 `POST /admin/tenants/{tenantId}/users` 的 `UserCreateRequest` 增加 `authSource`（默认 `LOCAL`）：
  - `LOCAL`：必须提供 `password`（12–200 字符），行为不变；
  - `OIDC`：必须提供 `issuer`、`subject`，不得提供 `password`；不写 `user_credential`；`issuer` 必须是租户已配置 issuer。
  - 响应 `UserSummary` 增加可选 `authSource`，管理界面据此显示“企业账号”/“内置账号”，并结合租户 provider 名称区分来源。
  - 直接开通 OIDC 账号在同一事务写 `user.created`（`role`、`targetTenantId`）与 `user.oidc.bound`（`userId`、`issuerHash`、`subjectHash`，不写 `previousAuthSource`），不扩展 `user.created` 的 details 白名单。
- **恢复路径（防止配置错误锁死）**：`POST /users/{userId}/oidc-binding/remove`（平台管理端同路径族）请求体 `{newPassword}`：恢复 `auth_source=LOCAL`、清空 issuer/subject、写入新密码凭据；写 `user.oidc.unbound` 审计。没有“无凭据解绑”，避免把账号变成不可登录状态。M10 不提供批量解绑、不解绑平台引导管理员。
- **禁用**：`app_user.status=DISABLED` 的 OIDC 账号在回调后拒绝建会话（`account_disabled`）；已存在会话下一次请求由既有 `AuthService.loadCurrentUser` 拒绝（M10-AC-11）。禁用优先于绑定校验。
- **租户暂停/不存在**：`tenant.status != ACTIVE` 或租户不存在 → `tenant_unavailable`，不建立会话；不泄露租户是否存在以外的信息。

### 4. 登录流程与错误态

```text
登录页（tenantCode）
  -> GET /api/v1/auth/oidc/providers?tenantCode=...        # enabled/providerName，未知租户统一 enabled=false
  -> GET /api/v1/auth/oidc/authorize/tenant-{code}         # 302 IdP，服务端会话保存 state/nonce/PKCE
  -> IdP 认证
  -> GET /api/v1/auth/oidc/callback/tenant-{code}          # 校验 state/nonce/code，换 token，校验 ID Token
       -> 成功：302 /（会话已建立，CSRF 已轮换）
       -> 失败：302 /login?oidcError=<稳定原因码>
```

- 失败原因码（稳定、面向 UI；不出现 IdP 原始错误、code、state、token、内部地址或堆栈）：
  `idp_unavailable`、`state_invalid`、`access_denied`、`account_not_bound`、`account_disabled`、`tenant_unavailable`、`issuer_mismatch`、`rate_limited`、`login_failed`。
- 登录页文案与下一步（M10-AC-13）：
  - `idp_unavailable`：稍后重试 / 改用内置账号；
  - `state_invalid`：重新发起企业登录；
  - `access_denied`：用户拒绝授权，可重试或改用内置账号；
  - `account_not_bound`：联系管理员关联账号；
  - `account_disabled`：账号已禁用，联系管理员；
  - `tenant_unavailable`：确认租户代码或联系管理员；
  - `rate_limited`：稍后重试。
- 限流：复用 `LoginRateLimiter`，键为客户端 IP + tenantCode；回调处理前检查。只在 `account_not_bound`、`account_disabled`、`issuer_mismatch` 时记失败，成功清零；`idp_unavailable`、`state_invalid`、`access_denied` 不记失败计数（避免 IdP 故障或用户取消把正常用户锁死），但全部写失败审计。超限返回 `rate_limited`。内置账号登录限流不变。
- 审计（ADR-0007 白名单内）：
  - 成功：`auth.login.succeeded`，`details.method=oidc`、`sessionIdHash`（新会话 ID 的 SHA-256）；
  - 失败：`auth.login.failed`，`details.reasonCode` 取上述原因码（`method=oidc` 暂不入白名单，避免扩展；租户 + 账号绑定已能区分来源）；
  - 审计写失败使登录失败（fail-closed）；未知 tenantCode 的失败只写进程日志，不写 `platform` 审计（避免伪造租户写入垃圾事件）。
- 租户枚举：`providers` 端点对未知租户与未配置租户返回同一响应（`enabled=false`），不返回租户名、IdP 地址或错误差异；该端点不写审计。

### 5. 登出与会话生命周期

- **本地登出是权威路径**：`POST /api/v1/auth/logout`（CSRF）使会话失效、写 `auth.logout`、清空安全上下文；响应 204，可选响应头 `X-Riccon-Federated-Logout-Url`（绝对 HTTPS，指向配置的 `endSessionEndpoint`，带 `client_id` 与 `post_logout_redirect_uri={publicBaseUrl}/login?loggedOut=1`）。是否提供该头由当前账号 `auth_source=OIDC` 且租户配置了 `endSessionEndpoint` 决定；LOCAL 账号不提供。
- **联合登出是可选动作**：RP-Initiated Logout 必须是浏览器顶层导航，且无法在 IdP 不可达时保持本页提示；因此 M10 的语义是：本地登出始终成功并显示“已退出本地会话”；若响应头存在，登录页提供“同时退出企业账号”按钮，用户点击后导航到 IdP。**不声称在 IdP 不可达时自动完成 IdP 登出**；这满足 AC-12 的“本地登出仍成功并提示已在本地退出”，并把 IdP 会话结束做成显式、可测试的动作。
- 不保存 ID Token，因此不使用 `id_token_hint`；只发送 `client_id` 与 `post_logout_redirect_uri`。IdP 要求 `id_token_hint` 时视为不支持联合登出，配置不填 `endSessionEndpoint`。
- 并发登录：允许多个并发会话（与内置账号一致），M10 不做会话登记表、不做“踢出其他设备”；账号禁用、密码解绑、IdP 配置移除是撤销路径。多设备会话统一失效后置。
- 浏览器后退不能恢复登录态：会话已失效、SPA 清空本地状态、API 响应 `Cache-Control: no-store`。

### 6. 依赖、配置样例、测试 IdP、轮换与回滚

- 依赖：`spring-boot-starter-oauth2-client`（Spring Security 7 自带 OAuth2 Client 与 Nimbus JOSE，Apache-2.0）。不引入新的外部服务、不引入 Redis、不引入自有 JWT 库。依赖进入 Trivy 扫描范围，B-06 例外不扩大。
- 配置样例（值全部来自环境/Secret，示例不是可用密钥）：

```yaml
riccon:
  public-base-url: ${RICCON_PUBLIC_BASE_URL:http://localhost:8000}
  oidc:
    # JSON 数组；生产用 Helm Secret 注入，禁止写入镜像/仓库/日志。
    idps: ${RICCON_OIDC_IDPS:[]}
    allow-http: ${RICCON_OIDC_ALLOW_HTTP:false}
    discovery-cache-ttl: 10m
    connect-timeout: 3s
    read-timeout: 5s
```

```json
[
  {
    "tenantCode": "acme",
    "issuer": "https://idp.acme.example/realms/acme",
    "clientId": "riccon",
    "clientSecret": "<from-secret>",
    "providerName": "Acme 企业账号",
    "scopes": ["openid"],
    "endSessionEndpoint": "https://idp.acme.example/realms/acme/protocol/openid-connect/logout",
    "enabled": true
  }
]
```

- **测试 IdP**：M10 需要一个确定性、可离线运行的本地 OIDC Provider 供 E2E 使用（authorize/token/JWKS/discovery/end-session，支持 state/nonce/PKCE 校验与 `access_denied`/`idp_down` 故障注入）。它可以是被测代码之外的测试夹具（Node mock 或测试容器），不得进入生产镜像；集成测试可用本地 stub 元数据与 JWKS。Keycloak 等真实 IdP 只做人工兼容抽查，不作为 gate 必需项。
- **可观测性**：按 method 统计登录成功/失败；`idp_unavailable` 计数；发现文档刷新失败日志只含 issuer 与错误类型，不含 token/code/secret；不记录回调查询串。
- **回滚**：
  - 配置回滚：移除/禁用租户配置并重启 → 企业登录关闭；内置账号不受影响；已绑定 OIDC 的账号无法登录，需恢复配置或由管理员用 `oidc-binding/remove` 设置本地密码。
  - 代码回滚：旧版本不识别 OIDC 配置与端点；`app_user` 中已绑定行为保留，旧版本密码登录会因无凭据失败；因此代码回滚必须同时恢复这些账号的本地凭据或回滚前先解绑。回滚步骤写入 M09 运行手册。
  - 无数据库迁移回滚（本 ADR 不新增表/列）。

### 7. API 与契约

新增/扩展（详见 `contracts/openapi.yaml` 与 ADR-0020 的版本规则）：

| 方法 | 路径                                                                  | 权限                     | 说明                                                    |
| ---- | --------------------------------------------------------------------- | ------------------------ | ------------------------------------------------------- |
| GET  | `/api/v1/auth/oidc/providers?tenantCode=`                             | 匿名                     | `{enabled, providerName}`；未知租户统一 `enabled=false` |
| GET  | `/api/v1/auth/oidc/authorize/{registrationId}`                        | 匿名                     | 302 到 IdP；registrationId 为 `tenant-{tenantCode}`     |
| GET  | `/api/v1/auth/oidc/callback/{registrationId}`                         | 匿名                     | 302 回 SPA；失败带 `oidcError`                          |
| PUT  | `/api/v1/users/{userId}/oidc-binding`                                 | `tenant.user.manage`     | 绑定/转换；`{issuer, subject}`                          |
| POST | `/api/v1/users/{userId}/oidc-binding/remove`                          | `tenant.user.manage`     | 恢复本地密码；`{newPassword}`                           |
| PUT  | `/api/v1/admin/tenants/{tenantId}/users/{userId}/oidc-binding`        | `platform.tenant.manage` | 平台管理员绑定                                          |
| POST | `/api/v1/admin/tenants/{tenantId}/users/{userId}/oidc-binding/remove` | `platform.tenant.manage` | 平台管理员恢复本地密码                                  |
| POST | `/api/v1/users`、`/api/v1/admin/tenants/{tenantId}/users`             | 既有权限                 | `UserCreateRequest.authSource=OIDC` 分支                |
| POST | `/api/v1/auth/logout`                                                 | 已认证                   | 204 + 可选 `X-Riccon-Federated-Logout-Url`              |

错误码：`RICCON-OIDC-400`（issuer 不匹配、OIDC 请求体非法）、`RICCON-OIDC-409`（身份已被绑定/账号已绑定其他身份）。登录失败不回 API 错误码，而是重定向 `oidcError` 原因码；原因码不是 RFC 9457 错误码，不用于 API 响应。

### 8. AC 映射

| AC       | 设计落点                                                         | 验证线索（Verifier 独立）        |
| -------- | ---------------------------------------------------------------- | -------------------------------- |
| AC-08    | 授权码 + PKCE、会话 Cookie 复用、无 JWT/无 token                 | `E2E-M03-03`、`SEC-OIDC-001`     |
| AC-09    | 显式绑定、不按邮箱、绑定后命中同一账号与角色                     | `E2E-M03-04`、`IT-OIDC-001..002` |
| AC-10    | 身份键 `(tenant, issuer, subject)`，不读邮箱；两租户同邮箱不合并 | `IT-OIDC-003`                    |
| AC-11    | 禁用拒绝建会话；既有会话下一请求失效                             | `IT-OIDC-004`、`SEC-OIDC-002`    |
| AC-12    | 本地登出权威；联合登出可选并显式；后退不可恢复                   | `E2E-M03-03`、`IT-OIDC-005`      |
| AC-13    | 四类失败独立原因码与下一步，无原始错误/令牌                      | `E2E-M03-04`、`IT-OIDC-006`      |
| AC-14    | Cookie 属性/CSRF/超时与内置账号一致；无 JWT/refresh token        | `SEC-OIDC-003`、`IT-OIDC-007`    |
| AC-21 段 | 登录页加载/空/错误/无权限/成功态、375 px、键盘、axe              | `E2E-M03-07`（OIDC 段）、`@a11y` |

### 9. Builder 任务边界

完整任务图见 ADR-0020 §8。本 ADR 对应：

- **M10-B1 OIDC 后端**：配置模型/校验、动态 `ClientRegistrationRepository`、发现缓存、`oauth2Login` 过滤器链、成功/失败处理器、登出响应头、审计、限流、领域常量；写范围限 `apps/api/.../oidc/**`、`auth/**`、`config/**`、`platform-domain` 常量、`AuditDetailsPolicy`、`ErrorCodes`、`application.yml` 与对应测试。
- **M10-B2 账号绑定后端**：`UserCreateRequest` OIDC 分支、绑定/解绑端点、`user_credential` 删除与重建、`UserSummary.authSource`、审计、冲突语义；写范围限用户管理/身份仓储/DTO。
- **M10-B4 Web OIDC 段**：登录页企业账号入口与错误态、租户 provider 查询、用户管理绑定/解绑 UI、375 px/键盘/axe。
- **M10-V 独立验证**：`SEC-OIDC-001..003`、`IT-OIDC-*` 对照、`E2E-M03-03/04`、a11y、无 token 检查、回滚演练。

### 10. 明确不做 / 后置

- **MFA**：不纳入 M10；不检查 `acr`/`amr`，不声称强认证。IdP 可能自行启用 MFA，但平台不保证、不验证、不声明。DEC-0013 缺口保留，客户合规要求时另立模块。
- 多 IdP 并存、租户自助配置、IdP 组到角色映射、SCIM/LDAP/HR 同步、按邮箱域自动开通/自动关联。
- 平台管理员 OIDC 登录（平台引导管理员仍为内置账号）。
- 全局会话失效/踢出、设备管理、refresh token、调用下游 API 的 token 转发。
- 运维界面、配置热更新、数据库配置表（重开条件见第 1 节）。

## Consequences

- 正面：不新增有状态服务；复用既有会话/CSRF/RBAC；账号接管面收敛为“管理员显式绑定 + issuer/subject 唯一”；不存 token，泄露面显著小于 JWT/客户端 token 方案；IdP 故障不影响内置账号和平台启动；配置与密钥走既有 Secret 模式。
- 负面：每租户配置需要运维在部署时维护，新增租户要改配置并重启；没有自助运维 UI；IdP 发现依赖首次登录时外网可达；绑定后删除本地密码，IdP 配置错误会锁住该账号（用显式解绑恢复）；联合登出是显式动作而非自动完成。
- 约束：OIDC 主体必须转换为 `RicconPrincipal` 并落既有会话层，禁止新增并行会话机制；禁止存 token；新增 scope 必须评审数据最小化；issuer 变更视为身份迁移，必须走运行手册，不能靠改配置静默完成。

## Alternatives

- **数据库 `tenant_idp_config` + 平台管理员配置 API**：更灵活、可热更新，但 M10 无 UI、需额外加密/审计/缓存/写入口；后置。
- **每租户一个 Spring `ClientRegistration` bean（静态 YAML）**：实现最简单，但租户动态开通、配置数量与代码耦合；用动态 repository 更干净。
- **自研授权码流程（手写 state/nonce/PKCE/ID Token 校验）**：可控但安全关键代码多、易错；Spring Security 的成熟实现更可靠。
- **JWT 会话/前端持有 token**：撤销、登出、角色即时性差，且暴露面更大；ADR-0013 已拒绝。
- **把 IdP 邮箱作为绑定键**：方便但可被 IdP 邮箱变更/伪造用于账号接管；DEC-0014 明确拒绝。
- **绑定后保留本地密码**：可用性更好，但形成绕过 IdP 策略的第二凭据；M10 选择单认证来源，并提供显式解绑恢复。
- **自动联合登出**：体验更顺，但 IdP 不可达时无法在本地页面给出成功提示；M10 选择本地权威 + 显式动作。
- **Keycloak 作为测试栈固定依赖**：真实但重；用本地确定性 mock IdP 做 E2E，真实 IdP 只做人工兼容抽查。

## Evidence

- 产品：`docs/PRODUCT.md` §8 M10-AC-08..14/21、§8.5、§9 OQ-6/OQ-10/OQ-13/OQ-15。
- 决策：DEC-0011、DEC-0013、DEC-0014、DEC-0015。
- 现有实现：`apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java`、`auth/controller/AuthController.java`、`auth/service/AuthService.java`、`auth/service/LoginRateLimiter.java`、`admin/service/TenantUserAdminService.java`。
- 迁移预留：`apps/api/src/main/resources/db/migration/V2__identity_and_workspace.sql`（`auth_source`/`external_issuer`/`external_subject`/唯一键）。
- 会话与 CSRF：ADR-0013；威胁模型 TM-02/03/04、TM-29..TM-33。
- 测试 IdP 与用例：M10 Verifier 交付 `SEC-OIDC-001..003`、`IT-OIDC-001..007`、`E2E-M03-03/04/07`。
