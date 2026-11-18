# 架构

## 评审状态

| 检查                        | 状态    | 记录                                                                                           |
| --------------------------- | ------- | ---------------------------------------------------------------------------------------------- |
| Architect 草案              | pass    | M10 ADR-0019/0020 `proposed`；M03 ADR-0018 `proposed`；M02 ADR-0001..0017                      |
| Lead 完整性/合同一致性      | pending | M10 待评审；M03 已通过                                                                         |
| 用户批准（不可逆/高成本项） | pending | DEC-0013/0014/0015 已确认产品口径；ADR-0019/0020 本身未批准；DEC-0003 许可仍是 release blocker |
| Verifier 可测试性/安全边界  | pending | M10 待评审；REV-TASK-0033-002、REV-M02-003、REV-M03-001 为既有记录                             |
| Ops 可运维/可回滚           | pending | M10 IdP Secret 注入/轮换、V10 索引与回滚演练待评审；M09 Helm/备份/升级                         |
| 安全/合规评审（如适用）     | pending | 威胁模型已更新到 M10 设计；M03/M10 gate 安全评审待做                                           |

状态值：`draft`、`pending`、`pass`、`changes-requested`；不可逆或高成本决策未经用户批准，不进入实现。历史无记录用 `legacy`/`not-recorded`。

## 背景

Riccon 是面向企业私有化部署的多租户 AI 协同办公系统。员工在云工作空间中与 Agent 协作完成办公任务；平台统一管理模型、知识库、MCP 与权限。风险等级 T4，架构必须优先保证租户隔离、审计、可回滚和密钥安全。

本文描述 M01 落地的架构基线。后续模块在此基线上纵向扩展，不重新引入第二套 Web 框架、第二套 ORM 或第二套 Agent 运行时。

## 架构原则

1. **模块化单体优先**：M02–M06 在同一个 Maven reactor 内按模块纵向切片，避免过早拆微服务带来的分布式事务、观测和部署成本。
2. **两个部署物，一套契约**：同步 HTTP 服务 `apps/api` 与异步 Agent 运行器 `apps/worker` 分开部署、分开扩缩容；两者只通过契约通信。
3. **Agent 运行时隔离**：`io.agentscope` 只允许出现在 `apps/worker`。`apps/api` 与 `platform` 由 ArchUnit 与 Maven Enforcer 双重禁止依赖。
4. **默认拒绝**：安全过滤器链默认拒绝一切，只显式放行健康检查；认证、租户解析与 RBAC 在 M02 通过独立过滤器链接入。
5. **向前兼容的数据库**：Flyway 只做向前迁移；破坏性变更必须有 ADR、回滚脚本和演练记录。
6. **无聊、成熟、可运维**：技术选型以团队熟悉度和私有化运维成本为先，不为未来两年设计。

## 组件

| 组件     | 路径                                     | 职责                                                             | 边界                                             |
| -------- | ---------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------ |
| API 服务 | `apps/api`                               | 同步 REST、认证会话、RBAC、租户解析、业务读写、审计入口、OpenAPI | 不得依赖 `io.agentscope`；不得直接执行 Agent     |
| Worker   | `apps/worker`                            | 异步 Agent 运行、模型调用、MCP 代理、HITL 编排、长任务           | 唯一允许依赖 `io.agentscope`；不直接面向浏览器   |
| 共享内核 | `platform/platform-common`               | 租户上下文、通用值对象、主密钥 SPI、schema 版本                  | 不得依赖 Spring Web、`apps/*` 或 `io.agentscope` |
| 领域词汇 | `platform/platform-domain`               | 跨部署物共享的状态枚举、角色/权限/审计动作/事件名常量            | 只含 JDK 类型；不得依赖 Spring、JPA、`apps/*`    |
| 架构测试 | `platform/platform-architecture-tests`   | 模块边界、分层、技术栈隔离的机器校验                             | 只含测试，不进入生产包                           |
| Web 前端 | `apps/web`                               | 员工端与管理端（TASK-0003 建立）                                 | 只调用 API，不直连 Worker、模型或 MCP            |
| 测试栈   | `deploy/compose/docker-compose.test.yml` | MySQL、OpenSearch、S3 兼容对象存储                               | 只用于本地与 CI，不是生产编排                    |

### 为什么是 api + worker 两个部署物

- **安全边界**：Agent 运行需要访问模型网关与 MCP，风险面远大于同步 CRUD；分开部署后可以对 Worker 做网络隔离、独立限流和独立审计。
- **扩缩容差异**：API 是低延迟短请求，Worker 是长任务与流式输出，资源模型不同。
- **故障隔离**：模型或 MCP 故障不应拖垮登录与业务 API。
- **可回滚**：任一部署物可以独立回滚，不需要同时回滚数据库与 Agent 运行时。

## 数据流

### 同步请求

```text
浏览器 -> apps/web -> apps/api
                        |-- 认证（会话 Cookie + CSRF） / 租户解析 / RBAC
                        |-- 部门数据范围（workspace 谓词；跨部门统一 404）
                        |-- 业务读写 -> MySQL（tenant_id 行级隔离 + 复合主键）
                        |-- 检索 -> OpenSearch（按租户索引，MySQL 为权威）
                        |-- 产物/文件 -> S3 兼容对象存储
                        `-- 审计事件 -> audit_event（同事务，append-only）
```

### 身份与审计（M10）

```text
OIDC 登录（顶层浏览器导航）
浏览器 -> apps/api /auth/oidc/authorize/{registrationId}
        -> 企业 IdP（授权码 + PKCE + state/nonce）
        -> apps/api /auth/oidc/callback/{registrationId}
             |-- 会话内校验 state/nonce，换 token，校验 ID Token（iss/aud/exp/签名）
             |-- 丢弃 token；按 (tenant_id, issuer, subject) 查账号并检查启用
             |-- 轮换会话 ID/CSRF，写 RicconPrincipal 到 Spring Session
             `-- 审计 auth.login.succeeded/failed（同事务 fail-closed）

审计查询/导出
浏览器 -> apps/api /audit-events（只读，租户来自主体）
        -> TenantJdbc -> audit_event（时间范围 + 租户索引）
        -> 写 audit.queried
        -> /audit-exports 申请 -> 另一管理员审批 -> 申请人下载 CSV 流
        -> 写 audit.export.* / data.exported；导出不落盘
```

### Agent 任务

```text
J1 对话（低延迟，不走 outbox）
浏览器 -> apps/api --(HMAC 服务令牌 + SSE)--> apps/worker
                                                |-- 打开 TenantContext，校验 run.tenant_id
                                                |-- AgentScope / 模型网关 / 检索
                                                `-- SSE 增量 -> API -> 浏览器

J2 报告 / 文件解析 / 知识库索引（可排队）
apps/api --(同事务写 agent_run + agent_run_outbox)--> MySQL
apps/worker --(轮询领取 FOR UPDATE SKIP LOCKED)--> MySQL
             |-- 打开 TenantContext，校验令牌与 run 租户
             |-- 阶段时间线、审批、MCP、模型调用
             `-- 产物/审计写回 MySQL 与对象存储
```

API 不执行 Agent；Worker 不面向浏览器。任务契约、SSE 事件与幂等规则见 `contracts/` 与 ADR-0014；outbox 领取的跨租户例外见 ADR-0016。

## 数据模型

M02 数据模型由 ADR-0012 定稿，M03 组织与数据范围由 ADR-0018 定稿，M10 身份与审计由 ADR-0019/0020 定稿；迁移位于 `apps/api/src/main/resources/db/migration`（V2–V10；V1 不修改；V9 已在 M03 发布后冻结；V10 为 M10 只增不减的 additive 迁移）。

### 标识与租户键

- 业务对象主键：应用生成 UUIDv7，数据库 `BINARY(16)`；REST/JSON 用 UUID 字符串。
- 租户键：`tenant_id VARCHAR(64) ascii_bin`；所有租户表复合主键 `(tenant_id, id)`，唯一键与索引以 `tenant_id` 开头。
- 复合外键 `(tenant_id, parent_id) → parent(tenant_id, id)` 阻止跨租户关联；跨聚合/可空引用使用逻辑引用 + 索引。
- 状态列 `VARCHAR + CHECK`；时间 `DATETIME(6)` UTC；软删除 `deleted_at`；审计 `audit_event` 按月分区且 append-only。
- 全局表：`tenant`、`permission`、`model_provider`、`model`、`mcp_server`、`mcp_tool`、Flyway 元数据与 Spring Session 表；`audit_event` 主键与 outbox 领取索引是记录在案的特殊例外。

### 表分组

| 分组         | 表                                                                                                                                                    | 说明                                                                                    |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| 身份与租户   | `tenant`、`app_user`、`user_credential`、`role`、`permission`、`role_permission`、`user_role`                                                         | 内置账号；M10 接入 OIDC（`auth_source`/issuer/subject 已由 V2 预留，无新列）            |
| 组织与部门   | `department`、`app_user.department_id`                                                                                                                | 单级部门、单主部门；M03 新增                                                            |
| 工作空间     | `workspace`、`workspace_member`                                                                                                                       | 个人/团队；可见性 `PRIVATE`/`TENANT`/`DEPT`/`CUSTOM`；M03 扩展                          |
| 文件与知识库 | `file`、`file_parse`、`file_chunk`、`knowledge_document`、`knowledge_chunk`                                                                           | 原文在对象存储；分块与定位在 MySQL；知识库 M03 仍租户级                                 |
| 会话与引用   | `conversation`、`message`、`message_citation`                                                                                                         | `seq` 保证顺序；引用保存标题/片段/页码/工作表/段落快照                                  |
| 任务与运行   | `research_task`、`agent_run`、`agent_run_stage`、`approval`、`agent_run_outbox`                                                                       | J2 状态机、阶段时间线、HITL 与幂等投递                                                  |
| 产物         | `artifact`、`artifact_version`、`artifact_citation`                                                                                                   | 一个任务最多一个产物；版本唯一；引用清单可核对                                          |
| 模型与 MCP   | `model_provider`、`model`、`tenant_data_key`、`provider_credential`、`model_invocation`、`mcp_server`、`mcp_tool`、`mcp_tool_grant`、`mcp_invocation` | 凭据信封加密；M02 单一部署级模型端点                                                    |
| 审计         | `audit_event`、`audit_export_request`                                                                                                                 | 只追加；触发器拒绝 UPDATE/DELETE；M10 提供只读查询与流式导出，导出申请/审批记录单独存表 |
| 会话存储     | `SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES`                                                                                                         | 基础设施表，租户从主体派生                                                              |

### 删除与保留

- 会话与产物写入 `retention_expires_at`（默认 180 天）；账号只禁用不删除；文件/会话/知识库文档软删除。
- 审计至少保留 180 天；M02 无删除 API，M09 由维护账号按分区 `DROP PARTITION`。M10 增加只读查询与导出，不改变 append-only；导出文件不落盘，`audit_export_request` 记录随审计保留策略由 M09 清理。
- 迁移只向前；破坏性变更按 ADR-0010 的 expand → contract 两阶段，回滚以前向修复 + 备份恢复为主。

## M03 组织与部门数据范围

M03 的完整决策见 `docs/adr/ADR-0018-m03-organization-and-department-scope.md`（`proposed`，M03 gate 前评审）与 DEC-0015。本节只给实现者需要的最小地图。

### 组织模型

- 单级部门：`department(tenant_id, id)`，`name` 租户内唯一；`manager_user_id` 是关系字段，不是 RBAC 角色。
- 单主部门：`app_user.department_id` 直接列 + 复合外键；多部门归属后移（DEC-0014）。
- **管理主体**：平台管理员跨租户管理租户部门元数据，复用 `/api/v1/admin/tenants/{tenantId}` 路径族；只操作部门、成员归属、负责人，不读取目标租户业务内容。租户管理员不获得部门管理权限；部门负责人只管理本部门空间（DEC-0015）。
- 部门停用只改 `status`/`disabled_at`，保留成员归属；访问判定同时检查 `department.status='ACTIVE'`，停用后下一次请求即失去该部门空间访问。
- 负责人必须是本部门当前启用成员；移除负责人必须先移交（409）。

### 空间可见性

| `kind`     | `visibility` | `department_id` | 访问范围                                     |
| ---------- | ------------ | --------------- | -------------------------------------------- |
| `PERSONAL` | `PRIVATE`    | 空              | 仅 `owner_user_id`                           |
| `TEAM`     | `TENANT`     | 空              | 本租户启用员工；仅 M02 历史迁移、只读        |
| `TEAM`     | `DEPT`       | 必填            | 该部门当前启用成员                           |
| `TEAM`     | `CUSTOM`     | 必填            | 该部门当前启用成员且在 `workspace_member` 中 |

- M03 之后新建团队空间必须给部门，只允许 `DEPT`/`CUSTOM`；历史 `TENANT` 空间只读，M03 不提供转换，转换在 M04/M09 设计专用迁移。
- `workspace_member` 只服务 `CUSTOM`；`DEPT` 成员由 `app_user.department_id` 派生。
- 个人空间保持仅本人；任何成员行写入个人空间都拒绝。
- 租户管理员保留 M02 的 `workspace.write` 空间管理能力，但不管理部门元数据；平台管理员不管理业务空间；内容访问对所有角色使用同一空间谓词。

### 访问判定

唯一入口是 `WorkspaceService.requireAccessible(tenantId, workspaceId, userId)`，SQL 谓词固定在 `WorkspaceJdbc`；列表、详情、下载、报告、产物、运行详情复用同一谓词。对象不存在、跨租户、跨部门、个人空间他人统一 `RICCON-TENANT-404`；可见但无管理能力返回 `RICCON-AUTH-403`；其他部门空间的管理操作返回 404。

M03 不引入跨请求共享缓存；成员移出、部门停用、负责人移交下一次请求生效。若后续引入缓存，键必须含 `tenantId`/`userId`/`workspaceId` 并在关系变更时失效（ADR-0018 §3.3）。

### 权限与审计

| 权限码                       | `PLATFORM_ADMIN` | `TENANT_ADMIN` | `MEMBER` / 部门负责人                  |
| ---------------------------- | ---------------- | -------------- | -------------------------------------- |
| `platform.department.manage` | 是               | 否             | 否                                     |
| `department.read`            | 否               | 是             | 是（仅本人部门）                       |
| `workspace.write`            | 否               | 是             | 否（服务层按负责人关系授权本部门空间） |

审计动作：`department.created/renamed/disabled/enabled/member.added/member.removed/manager.changed`、`workspace.created/updated/visibility.changed/member.added/member.removed`；目标类型新增 `DEPARTMENT`。跨租户部门事件沿用 M02 管理员口径：`tenant_id=platform`，目标租户写入 `details.targetTenantId`；M10 的结论是目标租户不可见这些平台来源事件，见 ADR-0020 §3 与 M03 F-009。details 只允许 UUID、计数、枚举与部门名，不含姓名、邮箱、成员名单或业务内容（ADR-0018 §5、ADR-0007）。

### API 摘要

| 能力             | 端点                                                                                                                                |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 部门读取（只读） | `GET /api/v1/departments`、`GET /api/v1/departments/{id}/members`（普通员工本人部门，租户管理员本租户全部）                         |
| 部门空间管理     | `GET` / `POST /api/v1/departments/{id}/workspaces`、`POST /workspaces`（租户管理员）、`PATCH /workspaces/{id}`                      |
| CUSTOM 成员      | `GET` / `POST /workspaces/{id}/members`、`DELETE /workspaces/{id}/members/{userId}`                                                 |
| 平台部门管理     | `GET` / `POST /api/v1/admin/tenants/{tenantId}/departments`、`PATCH /.../departments/{id}`、`POST /.../disable`、`POST /.../enable` |
| 平台成员/负责人  | `GET` / `POST /.../departments/{id}/members`、`DELETE /.../members/{userId}`、`PUT /.../manager`                                    |
| 知识库口径       | 知识库端点不变；界面固定明示“全租户共享，文档级权限在后续版本”；引用打开源文件仍走空间判定                                          |

### 迁移与回滚

- V9（唯一迁移，发布前可变）：新增 `department`、`workspace_member`、`app_user.department_id`、`workspace.department_id/visibility`、`department.read` / `platform.department.manage` 权限与角色回填；历史 `PERSONAL→PRIVATE`、`TEAM→TENANT`，不猜部门、不删除数据。
- 发布前由 Builder 直接修正 V9：回填旧代码可能写入的 `NULL` 可见性，改 `NOT NULL` 并收紧 CHECK；不新增 V10。
- 回滚优先“前向修复 + 备份恢复”；V9 的精确回退 SQL 见 ADR-0018 §6.3。只有 `apps/api` 执行 Flyway，worker 保持关闭并校验 schema 版本（V9 → 9）。

### 交付计划

Builder 任务边界、写范围、验收和验证命令见 ADR-0018 §8：6 个 Builder 任务（B1a 领域词汇/共享接口、B2 平台部门后端、B3 空间可见性、B4 内容贯通、B1b V9 收紧与迁移测试、B5 Web 管理界面）+ Verifier 独立 `DEPT-001..010`、`TEN-027`、`E2E-M03-01/02/07` 与 a11y。Builder 写 `IT-DEPT-*` 与代码级测试；Verifier 不改生产代码。

## M10 身份与审计纵深

M10 的完整决策见 `docs/adr/ADR-0019-m10-oidc-sso.md` 与 `docs/adr/ADR-0020-m10-audit-query-export.md`（均为 `proposed`）。本节只给实现者需要的最小地图。

### 身份（OIDC/SSO）

- **每租户 1 个 IdP，配置只来自部署 Secret**：`RICCON_OIDC_IDPS`（JSON 数组）提供 `tenantCode`、`issuer`、`clientId`、`clientSecret`、可选 `providerName`/`scopes`/`endSessionEndpoint`/`enabled`。M10 不建 `tenant_idp_config` 表、不提供运维 UI；未配置即关闭企业登录，内置账号不受影响。
- **协议**：Authorization Code + PKCE（S256）+ OIDC nonce；registrationId `tenant-{tenantCode}`；授权入口 `/api/v1/auth/oidc/authorize/{registrationId}`，回调 `/api/v1/auth/oidc/callback/{registrationId}`；ID Token 只校验 `iss`/`aud`/`exp`/nonce/签名，之后丢弃。
- **无 token**：access/refresh/ID token 不写会话、不写库、不写日志、不回传浏览器；使用 no-op `OAuth2AuthorizedClientRepository`。会话仍是 `RICCON_SESSION` + Spring Session JDBC（ADR-0013）；成功后轮换会话 ID 与 CSRF，`RicconPrincipal` 落既有安全上下文，权限每请求查库。
- **账号**：身份键 `(tenant_id, issuer, subject)`；不自动开通、不按邮箱关联；管理员显式绑定已有账号（转换 `auth_source=OIDC`、删除本地密码凭据）或直接创建 OIDC 账号；同邮箱不同 issuer 生成不同账号；提供“解绑并设置本地密码”的恢复路径。
- **错误态**：`idp_unavailable`、`state_invalid`、`access_denied`、`account_not_bound`、`account_disabled`、`tenant_unavailable`、`issuer_mismatch`、`rate_limited`、`login_failed`；登录页按原因给下一步，不回显 IdP 原始错误、token、内部地址或堆栈。
- **登出**：本地登出权威，始终成功；IdP `end_session_endpoint` 可用时通过响应头 `X-Riccon-Federated-Logout-Url` 提供显式联合登出动作，不声称 IdP 不可达时自动完成。并发会话允许，M10 不做全局踢出；账号禁用下一次请求生效。
- **后置**：MFA、多 IdP、IdP 组映射、SCIM/LDAP、自动开通、平台管理员 OIDC 登录。MFA 不检查 `acr`/`amr`，不声称强认证（DEC-0013）。

### 审计查询与导出

- **只读查询**：`GET /api/v1/audit-events`（`audit.read`），租户只从主体派生；时间范围必带偏移、默认最近 24h、最大 180 天；动作/操作者/对象/结果过滤；固定 `occurred_at DESC, event_id DESC`；`page` 1..200、`size` ≤100；不返回 `source_ip`。查询写 `audit.queried`。
- **导出**：`POST /api/v1/audit-exports` 申请（服务端计算预计行数，默认上限 10,000）；同租户另一管理员用 `audit.export.approve` 审批，禁止自批；单管理员租户由平台管理员通过 `/api/v1/admin/tenants/{tenantId}/audit-exports` 审批（仅元数据）。批准后 `expires_at = decided_at + 24h`（上限 7 天）；只有申请人可下载。
- **不落盘**：下载是 `text/csv` 流，含 `#` 元数据（exportId、generatedAt、filters、rowCount）、固定字段白名单与 `details`；不含密码哈希、token、模型/MCP 凭据、文件原文、邮箱、`source_ip`。过期 410；无持久文件、无预签名 URL、无清理作业。每次成功下载写 `data.exported`。
- **平台边界**：平台管理员只能查询 `platform` 租户自身事件；不能读取目标租户审计明细。M03 平台来源事件（`tenant_id=platform` + `details.targetTenantId`）在 M10 对目标租户仍不可见（M03 F-009）；跨租户可见模型需另立 ADR（DEC-0013/OQ-13）。
- **保留/索引**：审计保留 ≥180 天，不随租户保留期缩短；M09 按分区清理。V10 新增 `(tenant_id, action, occurred_at)`、`(tenant_id, actor_id, occurred_at)` 索引；不改变分区与 append-only 触发器。
- **后置**：MFA、审计哈希链、跨租户审计明细、导出归档/常驻链接、在线删除、定时导出。

### 契约与迁移摘要

| 能力     | 端点                                                                                                                                            |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| 企业登录 | `GET /auth/oidc/providers`、`GET /auth/oidc/authorize/{registrationId}`、`GET /auth/oidc/callback/{registrationId}`                             |
| 账号绑定 | `PUT /users/{userId}/oidc-binding`、`POST /users/{userId}/oidc-binding/remove`（平台管理端同路径族）                                            |
| 审计查询 | `GET /audit-events`、`GET /audit-events/policy`                                                                                                 |
| 导出     | `POST /audit-exports`、`GET /audit-exports`、`GET /audit-exports/{id}`、`POST /audit-exports/{id}/decision`、`GET /audit-exports/{id}/download` |
| 平台审批 | `GET /admin/tenants/{tenantId}/audit-exports`、`POST /admin/tenants/{tenantId}/audit-exports/{id}/decision`                                     |

- V10 单迁移：`audit_export_request`、两个 `audit_event` 索引、`audit.read`/`audit.export.approve`/`platform.audit.export.approve` 权限与角色回填。OIDC 无 schema 变更。回滚 SQL 见 ADR-0020 §5.4；worker schema 期望版本 9→10。

## 契约

- REST 前缀 `/api/v1`；破坏性变更升 `/api/v2`，不允许在同一版本内改变字段语义。
- 错误响应使用 RFC 9457 `ProblemDetail`，扩展字段至少包含 `code`、`traceId`、`tenantId`（脱敏后）。
- 契约目录（M02 建立，M03/M10 扩展）：
  - `contracts/openapi.yaml`：认证（含 M10 OIDC）、工作空间与部门、文件、会话、知识库、报告任务、审批、产物、模型/MCP 目录、账号管理（含 OIDC 绑定）、审计查询/导出与平台导出审批；
  - `contracts/internal-api.yaml`：API → Worker 内部 HTTP/SSE，仅集群内可达（M03/M10 无变更）；
  - `contracts/events/`：outbox 信封与事件 JSON Schema、SSE 事件 schema；
  - `contracts/README.md`：版本、兼容、幂等与安全规则。
- 事件命名：`riccon.<domain>.<event>.v1`；事件必须带 `eventId`、`occurredAt`、`tenantId`、`traceId`、`idempotencyKey`。
- M10 契约版本 `1.2.0-m10`：OIDC/SSO、审计查询、导出审批已定义；`x-riccon-deferred` 明确标注 MFA、审计哈希链、平台跨租户审计明细、模型路由/配额（M04）、文档 ACL/OCR（M05）、MCP 注册/凭据托管（M07）。
- OpenAPI 契约手工维护为 `contracts/openapi.yaml`；实现可用 springdoc 生成运行时描述并在契约测试中与契约对比。M01 默认关闭文档端点，M02 按环境开启。

## 安全与隐私

- **默认拒绝**：`apps/api` 与 `apps/worker` 的过滤器链只放行 `/actuator/health`，其余请求在 M02 前一律拒绝。
- **无默认用户**：排除 Spring Boot 内存用户自动配置，避免生成默认密码。
- **租户隔离**：`TenantContext` 基于 ThreadLocal，入口 Filter 打开作用域，出口关闭；跨线程必须显式传递。M02 接入异步/响应式链路时按 ADR-0003 扩展。
- **认证**：M02 使用内置账号 + Spring Session JDBC 服务端会话；Cookie `HttpOnly`、`SameSite=Lax`、非本地 `Secure`；登录后轮换会话 ID，登出失效；所有写操作校验 `X-CSRF-TOKEN`。不使用 JWT。M10 OIDC 使用 Authorization Code + PKCE + nonce，复用同一会话层（ADR-0013/0019）；access/refresh/ID token 不落会话、不落库、不落日志。
- **登录防护**：统一失败提示、bcrypt 12、连续 5 次失败锁定 15 分钟、进程内按 IP + 租户限流；登录成功/失败/登出写审计。OIDC 失败使用稳定原因码，不回显 IdP 原始错误。
- **密钥**：只从环境变量或密钥管理服务读取；禁止写入仓库、镜像、日志、证据和提示词。OIDC `clientSecret` 走同一模式，配置只从 Secret 注入。
- **MCP**：工具白名单、参数校验、超时与出网限制；高风险工具必须人工审批。
- **提示注入**：知识库与 MCP 返回内容一律视为不可信数据，不进入系统指令；M02 建立注入用例集。
- **审计**：模型、工具、MCP、权限和租户变更必须留痕；审计写入失败时业务失败，不允许静默丢弃。M10 查询只读、强制租户与时间范围；导出需另一管理员审批、申请人不得自批、只有申请人可下载、CSV 字段白名单、无持久文件、过期即不可下载。平台管理员不读取目标租户审计明细。

## 性能预算

| 指标           | 预算     | 说明                               |
| -------------- | -------- | ---------------------------------- |
| API p95        | ≤ 500 ms | 不含模型与外部 MCP 调用            |
| 首字延迟 p95   | ≤ 3 s    | 假模型基准，不含真实模型排队       |
| 并发 Agent run | 50       | 单 Worker 实例基线，按水平扩展提升 |
| 健康检查       | ≤ 1 s    | 不依赖外部模型或 MCP               |

性能预算由 k6 在 sandbox/preview 验证；未达到预算的变更不得进入 release。

## 运行模型

- **local**：macOS + Docker Desktop；`deploy/compose/docker-compose.test.yml` 提供 MySQL、OpenSearch、对象存储。
- **CI**：GitHub Actions，JDK 21 + Maven 缓存 + Compose 测试栈 + `./mvnw -T1C verify` + `spotless:check`。
- **preview/production**：M09 提供 Helm 与备份、升级、回滚演练；M01 不写生产编排。
- **可观测性**：Actuator 健康检查；结构化日志（键值对）；traceId 在 M02 接入。审计与业务日志分离。

## 扩展点

| 扩展点       | 位置                    | 约定                                                                           |
| ------------ | ----------------------- | ------------------------------------------------------------------------------ |
| 认证方式     | `apps/api` 过滤器链     | 新增方式不得放宽 `anyRequest().denyAll()`；按需增加 `permitAll` 白名单并写 ADR |
| Agent 运行时 | `apps/worker` 内部 SPI  | `platform` 定义接口，`worker` 实现；接口不得泄漏 agentscope 类型               |
| 模型网关     | `apps/worker` 适配层    | OpenAI 兼容协议优先；多 key/路由/配额在 M04 扩展                               |
| 知识库       | `apps/api` + OpenSearch | 文档级 ACL 在 M05 引入；检索结果必须带引用                                     |
| MCP          | `apps/worker` 代理      | 注册审批、凭据托管、工具搜索在 M07 扩展                                        |
| 前端         | `apps/web`              | Umi Max 4 + Ant Design Pro 6 + Ant Design X 2；只消费 API 契约                 |

## 风险

| 风险                                            | 可能性 | 影响             | 缓解                                                                                               |
| ----------------------------------------------- | ------ | ---------------- | -------------------------------------------------------------------------------------------------- |
| 租户上下文在线程池/异步链路丢失                 | 中     | 高（跨租户泄漏） | ThreadLocal 只用于同步；跨线程显式传递；M02 增加越权测试套件                                       |
| AgentScope 依赖泄漏到 API                       | 中     | 高（边界腐蚀）   | ArchUnit + Maven Enforcer 双重门禁                                                                 |
| MySQL Connector/J 的 GPLv2 许可与私有化分发冲突 | 中     | 高（法务）       | 发布前由 Lead/法务确认 Oracle 商业许可或替代驱动；见遗留风险                                       |
| MinIO 官方镜像停止发布                          | 高     | 低（仅测试栈）   | 测试栈使用 RustFS（ADR-0002）；生产对象存储由 ops 选型                                             |
| 覆盖率门槛在骨架期被排除项稀释                  | 中     | 中               | 排除项仅限启动类；新增排除必须评审                                                                 |
| 假模型基准与真实模型差异                        | 中     | 中               | M02 建立假模型基准与真实模型对比记录                                                               |
| 审计触发器需要迁移账号权限                      | 中     | 高（迁移失败）   | 迁移账号需 `TRIGGER`；binlog 开启时需 `log_bin_trust_function_creators=1`，写入 RUNBOOK 与部署检查 |
| 会话表成为登录关键路径                          | 低     | 中（无法登录）   | MySQL 健康检查、会话过期清理、备份包含 `SPRING_SESSION*`                                           |
| 部门成员关系陈旧或缓存导致移出后仍可访问        | 中     | 高（跨部门泄露） | 每请求查库；无跨请求缓存；成员移出/部门停用/负责人移交下一次请求生效；`DEPT-007`                   |
| M02 遗留 `TENANT` 空间在 M03 仍全租户可见       | 中     | 中（租户内越权） | M03 不提供转换；评审列遗留清单；转换在 M04/M09 设计；新空间禁止 `TENANT`（R-11）                   |
| 知识库租户级共享被误读为部门隔离                | 中     | 中（预期错位）   | 界面固定明示；源文件打开/下载仍走空间 404；M05 文档 ACL                                            |
| IdP 配置漂移/密钥轮换失败导致企业登录不可用     | 中     | 高（无法登录）   | 配置启动校验；内置账号兜底；滚动重启；`oidc-binding/remove` 恢复本地密码；M09 运行手册             |
| OIDC token 或 IdP 原始错误泄漏到浏览器/日志     | 低     | 高（账号接管）   | 不存 token；no-op authorized client；失败只映射原因码；`SEC-OIDC-001`；日志脱敏                    |
| 审计导出被审批绕过或跨租户读取                  | 低     | 高（批量泄露）   | 申请人≠审批人；只有申请人下载；平台审批仅元数据；`TEN-028..030`、`SEC-AUDIT-001`                   |
| 深层分页/大范围查询拖慢审计库                   | 中     | 中（可用性）     | 最大 180 天、page ≤200、size ≤100、导出上限 10,000、V10 索引；M09 观测慢查询                       |
| 流式导出在有效期内重复生成导致行数变化          | 中     | 低（结果解释）   | `occurredTo` 固定；CSV 元数据写实际 `rowCount`；审批记录与 `data.exported` 可核对                  |

## 相关决策

- `docs/adr/ADR-0001-modular-monolith-two-deployables.md`
- `docs/adr/ADR-0002-test-object-storage-rustfs.md`
- `docs/adr/ADR-0003-tenant-context-thread-local.md`
- `docs/adr/ADR-0004-agentscope-core-not-starter.md`
- `docs/adr/ADR-0005-mysql-connector-j-license-gate.md`
- `docs/adr/ADR-0006-key-lifecycle-envelope-encryption.md`
- `docs/adr/ADR-0007-audit-event-model.md`
- `docs/adr/ADR-0008-mysql-outbox-async-and-observability.md`
- `docs/adr/ADR-0009-tenant-isolation-enforcement.md`
- `docs/adr/ADR-0010-flyway-single-migrator.md`
- `docs/adr/ADR-0011-b06-dependency-exceptions.md`
- `docs/adr/ADR-0012-m02-data-model-identifiers-and-tenant-keys.md`
- `docs/adr/ADR-0013-m02-session-auth-csrf.md`
- `docs/adr/ADR-0014-m02-api-event-contract-and-sse.md`
- `docs/adr/ADR-0015-m02-domain-modules-and-persistence.md`
- `docs/adr/ADR-0016-worker-outbox-claim-cross-tenant.md`
- `docs/adr/ADR-0017-approval-params-encrypted-replay.md`
- `docs/adr/ADR-0018-m03-organization-and-department-scope.md`
- `docs/adr/ADR-0019-m10-oidc-sso.md`
- `docs/adr/ADR-0020-m10-audit-query-export.md`
