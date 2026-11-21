# TASK-0145 设计：API 集成测试 fixture 缓存与 setup 摊销

日期：2026-11-21
Owner：riccon/builder-m19
范围：`apps/api/src/test/**`（生产代码、迁移、pom、CI 零改动）

## 1. 目标与约束

- 摊销 API 集成测试的昂贵 setup：平台管理员登录（bcrypt 强度 12 + 会话写入）、重复的 CSRF 会话、
  每个测试方法新建 HTTP 客户端与连接。
- 每个测试仍使用唯一租户、唯一账号、唯一工作空间/部门/产物；**不共享任何可变业务数据**。
- 缓存必须可绕过：认证、会话生命周期、会话计数、CSRF 轮换、登录审计等测试走真实登录入口。
- 不减少用例、不跳过、不放宽断言；生产认证/安全代码不改。

## 2. 可缓存项（安全、不可变或可重置）

| 项                         | 位置                                                   | 为什么可缓存                                                                                                 | 失效/绕过                                                                                                                                       |
| -------------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| 平台管理员会话 + CSRF      | `ApiIntegrationTestSupport.cachedPlatformAdminSession` | 引导账号由 V2 种子/`BootstrapAdminRunner` 创建，测试不修改其密码、角色或状态；会话只是“已通过真实登录”的结果 | 每次复用前 `GET /api/v1/auth/session` 只读探针；非 200 立即重新真实登录。认证/会话语义测试调用 `freshPlatformAdminLogin()`                      |
| HTTP 连接池                | `ApiTestClient.SHARED_HTTP_CLIENT`                     | JDK `HttpClient` 不保存 Cookie/认证状态；Cookie 与 CSRF 由每次请求显式传入，连接池复用不改变请求语义         | 进程级常量；测试之间没有可变状态。需要独立客户端时仍可自行 new                                                                                  |
| fixture 密码的 bcrypt 哈希 | `ApiIntegrationTestSupport.FIXTURE_PASSWORD_HASHES`    | 只有两个固定测试密码；相同密码只做一次强度 12 编码，哈希可被任意多个 fixture 账号复用                        | 进程级缓存；创建端点测试仍用真实 HTTP `createUser`，不读取该缓存；账号创建路径目前无短密码/策略拒绝断言（既有缺口），种子路径未取代任何策略测试 |

### 2.1 fixture 账号种子（`seedLocalUser` / `seedAndLoginLocalUser`）

- 仅用于**测试目标不是账号创建端点、权限、审计或密码策略**的套件（租户/部门隔离、空间范围、文件、报告、
  会话、运行等）。
- 直接写入 `app_user`、`user_credential`（复用 bcrypt 哈希）与 `user_role`（授予人为平台管理员），
  字段值与 `TenantUserAdminService.createLocalUser` 一致；**登录仍走真实 HTTP**（CSRF、bcrypt 校验、
  会话轮换、登录审计全部保留）。
- 账号创建成功路径、权限分配与创建审计的真实链路由 `AuthFlowTest`、`TenantIsolationTest`、
  `TenantIsolationSecurityTest`、`OidcIdentityBindingApiTest`、`QuotaAdminApiTest` 与
  `AuditTestSupport` 等继续通过 `createUser` 覆盖（创建端点与 `user.created` 审计仍有真实 HTTP 断言）。
  基线有 33 个真实 HTTP `createUser` 调用，本次从 fixture helper 移除 7 个，剩余 26 个调用点不变。
- 账号创建路径目前没有短密码/密码策略拒绝断言（既有缺口；`AuthFlowTest` 只断言 bcrypt 前缀与强度 12，
  `OidcIdentityBindingApiTest` 的短密码断言针对 OIDC 解绑而非创建）；种子路径未取代任何策略测试。
- `DepartmentAdminApiTest`/部门与空间范围套件只把账号当 fixture，账号创建不是其断言目标。

## 3. 不可缓存项（每个测试必须唯一）

- 租户（`uniqueTenantCode`）、账号、个人/团队工作空间、部门、会话、文件、知识库文档、报告任务、
  审批、产物与运行：每个测试都使用本测试唯一的行，绝不跨测试复用。账号行按 §2.1 种子或真实 HTTP 创建，
  但同样是本测试唯一账号；其余业务对象通过公开 HTTP 创建。
- 任何“全局目录/开关”写入（模型、路由、MCP、配额）：仍按原测试的 reset helper 执行，不进入缓存。
- 会话计数、登录/登出、CSRF 轮换、登录审计断言：使用 `freshPlatformAdminLogin()` 或手工
  `csrfSession()+POST /auth/login`，不受缓存影响。

## 4. 失效与顺序安全

- 探针判定：`/auth/session` 返回 200 才复用；登出、禁用、过期或切换到其他数据库上下文时返回 401，
  自动重新登录，缓存不会把失败掩盖成成功。
- 缓存生命周期：单个 surefire JVM（默认 `forkCount=1, reuseForks=true`）；跨 JVM/进程不共享。
- 唯一租户与唯一业务数据保证每个测试互不影响；顺序验证见 §5 与 `ORDER-DEPENDENCE.md`：
  - 改造后正向全量 `EV-0700` 通过；
  - 改造后关键认证/安全类反转顺序 `EV-0703` 通过（114/0/0/0）；
  - 全量反转顺序在改造前基线 `bc75c04` 上也同样失败（7 个既有 `ModelCatalog` 平台 DEK 顺序依赖），
    证明不是 P3b 回归；同 namespace 不 reset 的失败同属既有全局模型/成本测试依赖（`EV-0701`）。

## 5. 结果

| 运行             | 条件                                                          | 用例 | suite 时间 | 墙钟 | 证据                                        |
| ---------------- | ------------------------------------------------------------- | ---: | ---------: | ---: | ------------------------------------------- |
| 基线             | HEAD `bc75c04`，namespace `m19p3b0`，先 reset                 |  642 |   902.821s | 948s | 基线测量（本任务）                          |
| 改造后（验证）   | namespace `m19p3b2`，先 reset                                 |  642 |   593.638s | 644s | 验证运行（非 EV）                           |
| 改造后（正式）   | `EV-0700`，namespace `m19p3b4`，先 reset                      |  642 |   467.249s | 512s | `EV-0700`                                   |
| 反转顺序（定向） | `EV-0703`，namespace `m19p3b6`，先 reset，reversealphabetical |  114 |   224.315s | 245s | `EV-0703`（认证/安全 8 类）                 |
| 全量反转（诊断） | `EV-0702`，namespace `m19p3b5`，先 reset，reversealphabetical |  642 |          — |    — | 7 个既有失败；`ORDER-DEPENDENCE.md`         |
| 基线全量反转诊断 | `bc75c04` worktree，namespace `m19p3bx`，reversealphabetical  |  642 |          — | 976s | 同样 7 个失败；`BASELINE-REVERSE-ORDER.log` |

正式改造后相对基线：API suite **-435.572s（-48.2%）**，用例数与失败/错误/跳过（642/0/0/0）均与基线一致；
较慢的验证运行仍为 **-309.183s（-34.2%）**，两种口径都远超“API 至少再降约 20s”的要求。

计时口径说明：`EV-0700`/`EV-0703` 的 `test-timings` 包含 `apps/worker` 的 84.809s 残留报告（来自
TASK-0144 `EV-0698`），不是同一轮运行，不能当作本轮 worker 时间。P3 总体改用 `EV-0696` 的 worker
约 92s 与独立 Verifier `EV-0704` 的 API 423.582s 计算。

P3 总体（按任务口径对 P2 全量 1236.717s）：

- Worker：`EV-0696` P3 约 91.6–93.3s，P2 为 445.214s，约 -352s。
- API：独立 Verifier `EV-0704` 423.582s（builder `EV-0700` 467.249s）；对基线 902.821s 分别 -53.1% / -48.2%。
- platform ≈1s。
- 合计 **517.137s**（worker ~92 + API 423.582 + platform ≈1），相对 1236.717s 降幅 **58.2%**；
  用 builder 的 API 467.249s 计算为约 560s，降幅 54.7%。两种口径均 ≥30%。

P2 基线参考：`EV-0695` API 642 用例；P1 `EV-0694` API suite 828.200s（含 11 个重命名残留用例，仅参考）。

## 6. 顺带修正 TASK-0144 F-001（仅文档）

`.riccon/evidence/TASK-0144/ISOLATION-DESIGN.md` 已按评审 F-001 校正：

- `provider_credential`/`tenant_data_key` 不再描述为“全部归 platform”：租户级凭据（
  `ModelFailoverIntegrationTest.stream_tenantCredentialIsIgnored`）与租户 DEK（`ApprovalParamsCryptoTest`，
  含第二把 DEK）按唯一租户隔离。
- 新增全局写法例外：`ModelRegistryIntegrationTest` 的 `DELETE FROM provider_credential`（无 WHERE）与
  `UPDATE model_provider SET base_url = ...`（无 WHERE），说明其在 `@BeforeEach resetModelGatewayState()`
  后顺序执行、旧 provider 不再被引用的现状与并行风险。
- 未重跑 Worker 套件，仅改文档。
