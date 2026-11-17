# ADR-0013：M02 会话认证、CSRF 与登录防护

Status: accepted
Date: 2026-11-17
关联：ADR-0003（租户上下文）、ADR-0009（租户隔离）、ADR-0012（数据模型）、TM-02/03/04（THREAT-MODEL）、M02-AC-01/14

## Context

M02 需要内置账号登录（M02-AC-01），并在 T4 下满足会话固定、Cookie、CSRF、登出、登录限流与审计要求。M03 才接入 OIDC/SSO，但账号模型现在就要预留外部身份字段，避免 M03 重建用户表。私有化部署可能单实例或多实例，重启后登录态不能全部丢失。

候选方案：JWT、无状态签名 Cookie、Spring Session JDBC、内存 Session、外部 Redis Session。项目当前只有 MySQL，没有 Redis；合同要求不提前引入中间件。

## Decision

### 1. 会话：Spring Session JDBC + 服务端 Cookie

- 使用 `spring-session-jdbc`，会话表 `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES` 由 Flyway V7 创建，**关闭** Spring Session 自动建表（`spring.session.jdbc.initialize-schema=never`）。
- Cookie 名 `RICCON_SESSION`：
  - `HttpOnly=true`；
  - `SameSite=Lax`；
  - 非本地环境 `Secure=true`；
  - `Path=/`；不设置 `Domain`，避免跨子域共享。
- 空闲超时 30 分钟（可配置 `server.servlet.session.timeout`），滑动续期；不做“记住我”。
- 登录成功后由 Spring Security 轮换会话 ID（`changeSessionId`），防会话固定；登出调用 `invalidate` 并使 CSRF 令牌失效。
- 会话中只存 `SecurityContext` 与必要的主体 ID；权限在每次请求从数据库/角色缓存重新计算，避免角色撤销后旧会话继续持有旧权限（M02 每请求查询，M04 再评估缓存）。
- 不使用 JWT：JWT 的撤销、登出、角色变更即时生效与密钥轮换在私有化场景更复杂；服务端会话更简单、可审计、可强制失效。M03 OIDC 仍复用同一会话层。

### 2. CSRF

- 使用 Spring Security `HttpSessionCsrfTokenRepository` + `CsrfTokenRequestAttributeHandler`（非 XOR 掩码），令牌存会话，不写 JS 可读 Cookie。
- `GET /api/v1/auth/csrf` 返回 `{headerName: "X-CSRF-TOKEN", token}`；未登录时建立仅承载 CSRF 令牌的匿名会话。
- 所有 `POST/PUT/PATCH/DELETE` 必须带 `X-CSRF-TOKEN`；缺失/过期返回 403 `RICCON-AUTH-403`。
- 登录成功后 Spring Security 会清除旧令牌；登录响应返回新的 `csrfToken`，前端后续请求使用新值。
- 同时校验 `Origin`/`Referer`（同源）作为纵深；`SameSite=Lax` 不替代令牌。
- 纯 JSON API 不接受 `application/x-www-form-urlencoded` 的写操作，降低简单请求 CSRF 面。

### 3. 登录与密码

- 登录请求：`tenantCode + username + password`；`tenantCode` 即 `tenant.id`，避免全局邮箱枚举，也避免同邮箱跨租户冲突。
- 失败统一返回 401 `RICCON-AUTH-001`，不区分租户不存在、账号不存在、密码错误、账号锁定；响应时间保持同量级。
- 密码哈希：`DelegatingPasswordEncoder` + `{bcrypt}`，强度 12（JDK/Spring Security 自带，不引入 BouncyCastle）；`user_credential.algorithm` 记录算法，便于未来迁移 Argon2id。
- 账号锁定：连续 5 次失败锁定 15 分钟（`failed_attempts`、`locked_until`）；成功登录清零。
- 登录限流：API 进程内按 `IP + tenantCode` 做滑动窗口（默认 10 次/分钟）；M02 单实例足够，多实例部署在 M04/M09 改为共享限流并写 ADR。超限返回 429 `RICCON-RATE-429`。
- 登录成功/失败/登出均写审计（`auth.login.succeeded/failed`、`auth.logout`），审计写入失败使登录失败（ADR-0007）。

### 4. 账号与角色

- M02 只有内置账号；`app_user.auth_source=LOCAL`，`user_credential` 保存密码哈希。
- M03 OIDC 预留：`app_user.external_issuer`、`external_subject` 与 `auth_source=OIDC`；唯一键 `(tenant_id, external_issuer, external_subject)`。OIDC 账号可以没有 `user_credential`。
- 角色码固定：`PLATFORM_ADMIN`（仅 `platform` 租户）、`TENANT_ADMIN`、`MEMBER`；权限字典全局，租户开通时复制角色-权限映射。
- 平台管理员不读取租户业务内容；租户管理员不跨租户；普通员工只能访问本租户内授权资源。
- 账号禁用立即阻止新登录；已有会话在下一请求被拒绝（每请求校验账号状态）。

### 5. 引导管理员

- 迁移不创建默认账号或默认密码。
- 首次启动时，`BootstrapAdminRunner` 读取 `RICCON_BOOTSTRAP_ADMIN_USERNAME` 与 `RICCON_BOOTSTRAP_ADMIN_PASSWORD`（至少 12 位）：
  - 仅当系统中没有 `PLATFORM_ADMIN` 时创建；已存在则跳过并记录告警；
  - 创建 `platform` 租户管理员与 `PLATFORM_ADMIN` 角色，写 `user.created` / `permission.changed` 审计；
  - 启动日志只记录“created/skipped”，不记录密码；密码只从环境变量读取。
- 生产部署必须在首次启动后移除引导变量；M09 运行手册覆盖。

### 6. 会话表与租户

- `SPRING_SESSION*` 是基础设施表，无 `tenant_id`；租户从会话中的 `SecurityContext` 派生，禁止从 Cookie/请求头读取租户。
- 会话表按 Spring Session 官方 schema 使用大写表名，避免 Linux 大小写敏感导致找不到表；`spring.session.jdbc.table-name=SPRING_SESSION`。
- 会话属性使用 JDK 序列化；`RicconPrincipal` 必须实现 `Serializable`，且只存 ID/租户/用户名等非敏感字段，不存密码哈希或权限全集。

## Consequences

- 正面：无 Redis/无 JWT 新依赖；重启后会话保留；登出与角色撤销即时生效；CSRF 令牌不经 JS 可读 Cookie；登录防护可测试。
- 负面：每请求一次会话表读写（MySQL 压力可接受，M02 目标 50 并发）；进程内 IP 限流在多实例下不共享；JDK 序列化需要在升级时保持兼容。
- 约束：新增认证方式不得绕过会话层；新增敏感 Cookie 必须走同一属性基线；不得把权限写入长期会话而不校验数据库；会话表迁移归 Flyway，禁止自动建表。

## Alternatives

- **JWT（自签）**：无状态、扩展方便，但撤销/登出/角色变更即时性差，密钥轮换与泄露处置复杂；拒绝。
- **无状态签名 Cookie**：同样存在撤销问题，且 Cookie 体积受限；拒绝。
- **内存 Session**：重启丢失、无法多实例；拒绝。
- **Redis Session**：成熟，但引入新的有状态服务与运维成本；M02 不需要，M04/M09 若会话压力增大再评估。
- **Cookie 存用户信息**：客户端可篡改，违反“租户只从已认证主体派生”；拒绝。
- **仅靠 SameSite 不做 CSRF 令牌**：浏览器兼容与跨站场景不可控；拒绝。
- **Argon2id**：更强，但要额外 BouncyCastle 依赖；M02 用 bcrypt 12，M03 评估迁移。

## Evidence

- `apps/api/src/main/resources/db/migration/V7__spring_session.sql`
- `apps/api/pom.xml` 的 `spring-session-jdbc` 依赖
- `contracts/openapi.yaml` 的 `/api/v1/auth/**`、CSRF 参数与错误码
- 测试（Builder 实现）：`IT-AUTH-001..004`、`IT-CSRF-001`、`IT-RATE-001`、`E2E-AC-01`
- `docs/security/THREAT-MODEL.md` TM-02/03/04、S-02-1/2、T-02-1
