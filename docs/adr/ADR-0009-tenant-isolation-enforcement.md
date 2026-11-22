# ADR-0009：租户隔离强制点与越权测试入口

Status: accepted
Date: 2026-11-17

## Context

M02 的硬指标是“跨租户越权用例零漏检”（`docs/PRODUCT.md` 成功指标、M02-AC-12/13）。当前只有 `TenantContext`（ThreadLocal + fail-fast，ADR-0003），没有任何业务表、数据访问层或认证主体。若只靠“记得加 `tenant_id`”，在线程池、异步任务、对象存储、检索索引和管理接口上都会出现漏点。需要把“租户隔离”从约定变成可执行、可测试、可审计的强制点。

M02 技术栈是 Spring JDBC（无 ORM）、MySQL 8.4、OpenSearch 3.x、S3 兼容对象存储；M02 不引入文档级 ACL（M05）。

## Decision

### 1. 租户身份来源（唯一）

- 租户**只能**从已认证主体派生：会话中的 `tenantId` 或内部服务令牌的 `tenantId`。
- 请求头 `X-Tenant-Id`、请求体 `tenantId`、查询参数 `tenantId`、Cookie 中的租户字段一律忽略；与主体不一致时拒绝并写审计。
- 平台管理员是 `platform` 保留租户中的账号，角色 `PLATFORM_ADMIN`；租户管理员是租户内角色 `TENANT_ADMIN`。两者权限不重叠。
- 公开 REST 路径不包含 `tenantId`；平台管理接口需要显式目标租户时，路径参数只允许 `PLATFORM_ADMIN`，且写 `audit.queried`/`permission.changed`。

### 2. 请求生命周期

```text
认证过滤器 -> 解析主体 tenantId -> TenantContext.open(tenantId)
  -> controller（只做协议与校验）
  -> service（事务与业务规则）
  -> repository -> TenantJdbc（强制 tenant_id 条件）
  -> finally: TenantScope.close()
```

- 任何跨线程提交（线程池、`CompletableFuture`、流式回调）必须显式捕获租户并在目标线程重新打开；禁止依赖线程继承。
- 未设置租户时访问数据访问层 → 立即抛 `IllegalStateException`，不降级为全租户查询。
- 异步任务以 `agent_run.tenant_id` 为准，Worker 打开作用域前先校验令牌与任务记录一致。

### 3. 统一数据访问层（强制）

- 每个部署物有且只有一个 `TenantJdbc`（包 `..persistence..`），包装 Spring `JdbcClient`；`JdbcClient`/`JdbcTemplate` 不得注入到其他包（ArchUnit 规则）。
- `TenantJdbc` 的所有读写方法要求传入 `TenantId`，并校验 SQL 含有 `tenant_id = :tenantId`（写语句要求含 `tenant_id` 列）；缺失即抛 `IllegalStateException`，不执行 SQL。
- repository 只依赖 `TenantJdbc`，不直接拼接表名、不接受调用方提供的 SQL/排序字段；排序字段使用枚举白名单。
- 跨租户查询只允许在 `..admin..` 包中的 `PlatformJdbc`，必须显式传入目标租户并写审计（M03 起；M02 不提供跨租户业务查询）。
- 这个运行时守卫能拦住单表查询漏条件；多表 join 由复合主键、代码评审与 `TEN-*` 用例共同保证，不能只依赖字符串检查。

### 4. 数据库约束与索引

- 所有租户业务表：`tenant_id VARCHAR(64) NOT NULL`，字符集与 `TenantId` 规则一致；`CHECK (tenant_id <> '')`。
- 主键使用 `(tenant_id, id)`，`id` 为应用生成的 UUIDv7；禁止自增主键。
- 唯一约束必须以 `tenant_id` 开头，例如 `(tenant_id, external_ref)`。
- 外键使用复合键：`(tenant_id, parent_id)` 引用父表 `(tenant_id, id)`，从结构上阻止跨租户关联。
- 索引以 `tenant_id` 为最左前缀；列表默认按 `(tenant_id, created_at DESC)`。
- 例外（必须在迁移中注释并由本 ADR 记录）：`tenant` 表为全局表；`flyway_schema_history` 为迁移元数据；`audit_event` 因按月分区使用 `(event_id, occurred_at)` 主键，租户条件由应用层与索引 `(tenant_id, occurred_at)` 保证。
- 删除为软删除（`deleted_at`）时，查询仍必须带租户条件；物理清理走维护任务，不允许跨租户批量删除。

### 5. 对象 ID 与统一拒绝

- 所有对象对外只暴露 UUIDv7；不使用自增或可预测 ID。
- 查询条件始终是 `(tenant_id, id)`；不匹配时返回统一 404（`RICCON-TENANT-404`），不得区分“不存在”和“属于其他租户”。
- 404 响应体不包含对象类型之外的元数据；响应时间与普通 404 保持同量级。
- 拒绝事件写审计（`outcome=denied`）并递增 `riccon_tenant_access_denied_total{reason="not_found"}`。

### 6. 对象存储

- 桶私有，禁止公共 ACL 与匿名读；生产启用 TLS 与服务端加密（M09）。
- key 固定格式：`tenants/{tenantId}/{category}/{yyyy}/{objectId}`；`category ∈ file|artifact|export`。
- 下载只通过 API：先按 `(tenant_id, objectId)` 校验元数据，再签发预签名 URL，TTL ≤ 300 秒；URL 绑定 key，不允许客户端传入 key。
- 上传使用服务端生成的 key；文件名只作为元数据保存，净化后用于 `Content-Disposition`，不进入路径。
- 删除对象前先校验归属；对象存储生命周期规则不得跨租户前缀。

### 7. 检索（OpenSearch）

- 每个租户独立索引：别名 `riccon-kb-{tenantId}` 指向版本化索引 `riccon-kb-v1-{tenantId}`；租户创建时初始化索引模板。
- 检索客户端只接受查询文本与过滤条件，索引名由 `TenantContext.require()` 派生；任何调用方传入的索引名/别名一律拒绝。
- 文档写入必须带 `tenant_id` 字段作为冗余校验；查询必须同时带索引隔离与 `tenant_id` term 过滤（双保险）。
- M02 无文档级 ACL：同租户内所有员工可检索该租户全部知识库文档；这是已知残余风险（`THREAT-MODEL` R-01），接入真实敏感文档前必须完成 M05。
- M05 在同一租户索引内增加文档 ACL 字段与查询过滤，不改变索引按租户隔离的边界。

### 8. 服务身份、Agent 委托与数据范围

- API → Worker 使用 HMAC-SHA256 签名的短期令牌（`RICCON_INTERNAL_TOKEN_SECRET` 至少 32 字节，支持双密钥轮换）：
  - 声明：`iss=riccon-api`、`aud=riccon-worker`、`sub=user:{userId}`、`tenantId`、`runId`、`scope=agent:run`、`iat`、`exp`（≤300 秒）、`jti`；
  - Worker 校验签名、`aud`、`exp`、`scope`，并校验 `tenantId == agent_run.tenant_id`、`sub` 对应的用户仍启用；
  - Worker 不接受浏览器 Cookie，不提供通用 CRUD 或管理接口。
- 委托不扩权：令牌只允许执行指定的 `runId`；Worker 不能用同一令牌创建新任务、读取其他运行、访问管理接口或跨租户检索。
- 用户被禁用或角色被撤销：下一次阶段开始前生效（最长延迟一个阶段）；运行中的模型流在下一检查点终止。
- 模型网关凭据在 M02 为部署级（所有租户共用），调用按租户计审计；M04 引入按租户配额与多 key。MCP 凭据在 M02 由静态配置提供，按租户授权表校验；M07 改为租户级凭据托管。
- Agent 状态存储的键必须包含 `tenantId`（例如 `tenantId:userId:sessionId`），禁止只用 `userId/sessionId`。

### 9. 缓存与其他派生数据

- M02 不引入跨请求共享缓存。若后续引入（M04+），缓存键必须以 `tenantId` 开头，并纳入 `TEN-016` 用例。
- 导出、报表、异步任务的中间结果必须带 `tenant_id`，并在生成、读取、清理三个环节校验。

### 10. 越权测试套件（M02 gate 必需）

**入口**：JUnit tag `tenant-isolation`，默认包含在 `./mvnw verify` 中；快速核对命令：

```bash
./mvnw -q -T1C verify -Dgroups=tenant-isolation
```

**用例矩阵**（`TEN-001`–`TEN-020`，M02 建立；编号写入测试方法或 `@DisplayName`）：

| ID      | 场景                                                             | 期望                                             |
| ------- | ---------------------------------------------------------------- | ------------------------------------------------ |
| TEN-001 | 会话列表只返回本租户                                             | 列表数量与内容仅含 A 租户                        |
| TEN-002 | 请求体/查询参数/请求头伪造 `tenantId=B`                          | 忽略并拒绝，或按 A 处理；不得访问 B              |
| TEN-003 | 读取 B 的会话 ID                                                 | 统一 404，响应体无 B 信息                        |
| TEN-004 | 更新/删除 B 的会话                                               | 统一 404，B 数据不变                             |
| TEN-005 | 读取 B 的文件元数据                                              | 统一 404                                         |
| TEN-006 | 下载 B 的文件 / 复用 A 的签名 URL 访问 B 的对象                  | 拒绝；签名 URL 绑定对象且 TTL≤300 秒             |
| TEN-007 | 读取 B 的产物                                                    | 统一 404                                         |
| TEN-008 | 产物列表                                                         | 仅本租户                                         |
| TEN-009 | 检索时传入其他租户索引名或跨索引查询                             | 拒绝或忽略；只查本租户别名                       |
| TEN-010 | 读取 B 的知识库文档                                              | 统一 404                                         |
| TEN-011 | 租户管理员调用平台管理接口                                       | 403；平台管理员可调用但写审计                    |
| TEN-012 | 普通员工访问审计接口                                             | 403 或路由不存在（M02 无该接口）                 |
| TEN-013 | 篡改对象 key 前缀（`tenants/B/...`）                             | 拒绝                                             |
| TEN-014 | Worker 收到 token 租户与 run 租户不一致                          | 拒绝、任务失败、写审计                           |
| TEN-015 | 线程池/异步任务未携带租户                                        | 快速失败，不执行全表查询                         |
| TEN-016 | 缓存键（若启用）缺少租户                                         | 测试守卫失败（M02 记录为不适用也需显式断言）     |
| TEN-017 | 服务令牌跨租户/跨 run 使用                                       | 401/403，写审计                                  |
| TEN-018 | 跨租户 ID 枚举的响应差异（状态码、错误码、响应时间）             | 与普通 404 一致，不泄露存在性                    |
| TEN-019 | 审计事件的 `tenant_id` 与 actor 所属租户                         | 一致；平台事件为 `platform`                      |
| TEN-020 | 导出能力：M02 不存在导出接口或一律拒绝；M03 起按租户过滤且需审批 | M02 返回 404/403；M03 只含目标租户，无审批不导出 |

**完整性守卫**（防止套件被静默删减）：

```bash
# 每个 ID 必须出现在 surefire 报告中
for i in $(seq -w 1 20); do
  grep -q "TEN-0$i" apps/api/target/surefire-reports/*.xml || echo "MISSING TEN-0$i"
done

# 不允许 skip/failure/error
grep -E 'skipped="[1-9]|failures="[1-9]|errors="[1-9]' apps/api/target/surefire-reports/*.xml
```

- 两条命令任一不满足即 gate 失败；禁止 `@Disabled`、`assumeTrue`、profile 排除或环境变量跳过。
- `TEN-014`/`TEN-017` 位于 `apps/worker` 时，守卫同时检查 `apps/worker/target/surefire-reports`。
- E2E 对应 `E2E-AC-12`（直接访问他租户对象）与 `E2E-AC-13`（完整套件）；Playwright 报告作为附加证据，不替代 JUnit 套件。

## Consequences

- 正面：租户隔离有单一强制点（数据访问层 + 复合主键 + 索引派生 + 服务令牌绑定），漏点可被测试捕获；越权套件有固定入口与防删减守卫。
- 负面：`TenantJdbc` 增加一层封装；复合主键让部分 SQL 更啰嗦；平台管理员跨租户能力受限，M03 前无法做跨租户审计查询。
- 约束：任何绕过 `TenantJdbc` 的 JDBC 访问都必须先改本 ADR；新增业务表必须遵守第 4 节；新增外部数据源（连接器、MCP 缓存）必须重新评估隔离点。

## Alternatives

- **仅靠代码评审与 `WHERE tenant_id`**：无法防止遗漏，也无法阻止线程池串租户。
- **Hibernate 多租户过滤器**：项目未使用 ORM，引入 ORM 与过滤器会改变整个持久化栈，超出 M02 成本。
- **每租户独立数据库/ schema**：隔离最强，但迁移、连接池、备份和运维成本随租户数线性增长，私有化交付不现实；可作为高隔离客户的 M09 选项。
- **MySQL 行级安全（RLS）**：MySQL 8.4 不提供原生 RLS，无法作为强制点。
- **共享 OpenSearch 索引 + 强制过滤**：实现简单，但一个查询漏过滤就是批量泄露；索引按租户隔离成本可接受。
- **请求头传租户**：实现方便但可伪造，直接违反“主体派生”原则。
- **只在 Controller 校验租户**：服务层、异步层和 Worker 仍会漏，必须下沉到数据访问层。

## Evidence

- `platform/platform-common/src/main/java/com/riccon/platform/common/tenant/TenantContext.java` 与 `TenantContextTest`（已有：嵌套恢复、线程清理、缺失失败）
- `platform/platform-architecture-tests` 新增规则（M02）：`JdbcClient` 仅限 `..persistence..`；`..admin..` 之外不得使用 `PlatformJdbc`
- 迁移（M02）：业务表 `tenant_id` 非空、复合主键/外键/索引
- `TEN-001..020` 测试与 tag；第 10 节守卫命令输出
- `E2E-AC-12`、`E2E-AC-13`
- `docs/security/THREAT-MODEL.md` TM-06、5.1 节、B-02

## 澄清（M03 扩展，不改本 ADR 决策）

- 2026-11-18：M03 在租户边界内增加部门数据范围，强制点仍是本 ADR 第 3 节的 `TenantJdbc` 与第 4 节的复合键；新增空间访问谓词见 ADR-0018。
- `TEN` 套件在 M03 扩展到 `TEN-021..027`（部门/空间/成员跨租户，027 为平台部门端点），编号连续、不重排；新增 `DEPT-001..010`，使用 `@Tag("dept-isolation")`，与 `tenant-isolation` 同套件运行。
- 平台管理员跨租户查看租户业务/审计明细仍不在 M03 范围（DEC-0013）；本 ADR 第 1 节的平台/租户角色分离不变。

## 平台成本端点例外（M20/TASK-0142）

本小节记录 ADR-0022 §2.4 平台成本过滤的**唯一窄例外**，不改变第 1 节的默认拒绝原则：除下表规则外，`X-Tenant-Id` 与查询参数 `tenantId` 与主体不一致时仍一律拒绝并写审计。

| 项         | 规则                                                                                                                                                                                                                                                                                                                                                                                    |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 路径与方法 | 仅 `GET /api/v1/admin/costs`；按 request URI 精确匹配，trailing slash、子路径与其他方法不享受例外。                                                                                                                                                                                                                                                                                     |
| 主体       | 仅 `platform` 保留租户中具备 `platform.cost.read` 的账号。                                                                                                                                                                                                                                                                                                                              |
| 权限强制点 | `TenantContextFilter` 在放行“主体租户 ≠ 过滤值”前调用 `AuthService.requirePermission(platform.cost.read)`；`PermissionInterceptor` 在 controller 前再次强制同一权限（每次请求从数据库加载，双保险）。缺少权限或权限读取失败都 fail-closed，返回 403 `RICCON-AUTH-403` 并写审计，不放行到 controller。                                                                                   |
| 头处理     | `X-Tenant-Id` 只用于触发例外判断，**不参与过滤**；过滤值只来自查询参数 `tenantId`，头不会改变聚合结果，也不能作为授权依据。`X-Tenant-Id` 与查询参数 `tenantId` 同时出现且不一致时，一律按 `tenant_mismatch` 拒绝并写审计（不进入本例外），避免任意一方掩盖另一方；两者一致时按一个值处理。                                                                                              |
| 拒绝行为   | 平台非管理员命中例外候选 → 403 `RICCON-AUTH-403` + 审计 `tenant.access.denied`（`attemptedTenant`、`reason=missing_permission`）；其他路径/方法/主体的不一致值 → `reason=tenant_mismatch`；header/query 冲突 → 403 + `reason=tenant_mismatch`（details 的 `attemptedTenant` 记录为 `header=<头值>;query=<查询值>`）；响应均不含租户、金额或明细。                                       |
| 负向测试   | `apps/api/src/test/java/com/riccon/api/admin/cost/PlatformCostTenantFilterTest.java`（TASK-0142）：平台非管理员带查询参数/请求头均 403 且无数据并写审计；租户端点伪造查询参数/请求头 403 并审计；平台管理员 POST 与 trailing slash 403；平台管理员查询参数过滤生效、请求头不改变结果；header 与 query 冲突 403 并审计（平台管理员与租户管理员各一例）；平台成本例外内会话失效返回 401。 |
| 残余       | 例外仍信任 `platform` 租户代码与精确 URI；新增平台成本路径或修改路由必须同步更新过滤器、ADR-0022 §2.4 与本测试。过滤器与拦截器各加载一次权限，每请求多一次只读查询，属可接受成本。                                                                                                                                                                                                      |

**401 与 403 的分工（TASK-0156）**：

- 例外候选主体（`platform` 租户账号）会话缺失/过期或账号已禁用时，`AuthService.requirePermission` 抛 `UnauthenticatedException`，过滤器返回 401 `RICCON-AUTH-401`，不写 `tenant.access.denied`、不放行到 controller，响应不含租户、金额或明细；与常规 `PermissionInterceptor` 的未认证语义一致。
- 缺少 `platform.cost.read`（`ForbiddenException`）或权限读取失败（`RuntimeException`，记录 ERROR 后）仍 fail-closed，返回 403 `RICCON-AUTH-403` 并写审计 `tenant.access.denied`（`attemptedTenant`、`reason=missing_permission`）。401 与 403 两条路径互斥：认证失败返回 401 且不写拒绝审计，授权失败才返回 403 并写审计。
