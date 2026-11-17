# 架构

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
                        |-- 业务读写 -> MySQL（tenant_id 行级隔离 + 复合主键）
                        |-- 检索 -> OpenSearch（按租户索引，MySQL 为权威）
                        |-- 产物/文件 -> S3 兼容对象存储
                        `-- 审计事件 -> audit_event（同事务，append-only）
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

M02 数据模型由 ADR-0012 定稿，迁移位于 `apps/api/src/main/resources/db/migration`（V2–V7，V1 不修改）。

### 标识与租户键

- 业务对象主键：应用生成 UUIDv7，数据库 `BINARY(16)`；REST/JSON 用 UUID 字符串。
- 租户键：`tenant_id VARCHAR(64) ascii_bin`；所有租户表复合主键 `(tenant_id, id)`，唯一键与索引以 `tenant_id` 开头。
- 复合外键 `(tenant_id, parent_id) → parent(tenant_id, id)` 阻止跨租户关联；跨聚合/可空引用使用逻辑引用 + 索引。
- 状态列 `VARCHAR + CHECK`；时间 `DATETIME(6)` UTC；软删除 `deleted_at`；审计 `audit_event` 按月分区且 append-only。
- 全局表：`tenant`、`permission`、`model_provider`、`model`、`mcp_server`、`mcp_tool`、Flyway 元数据与 Spring Session 表；`audit_event` 主键与 outbox 领取索引是记录在案的特殊例外。

### 表分组

| 分组         | 表                                                                                                                                                    | 说明                                                   |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| 身份与租户   | `tenant`、`app_user`、`user_credential`、`role`、`permission`、`role_permission`、`user_role`                                                         | 内置账号；M03 预留 OIDC issuer/subject                 |
| 工作空间     | `workspace`                                                                                                                                           | 个人/团队；团队在 M02 对本租户可见                     |
| 文件与知识库 | `file`、`file_parse`、`file_chunk`、`knowledge_document`、`knowledge_chunk`                                                                           | 原文在对象存储；分块与定位在 MySQL；OpenSearch 可重建  |
| 会话与引用   | `conversation`、`message`、`message_citation`                                                                                                         | `seq` 保证顺序；引用保存标题/片段/页码/工作表/段落快照 |
| 任务与运行   | `research_task`、`agent_run`、`agent_run_stage`、`approval`、`agent_run_outbox`                                                                       | J2 状态机、阶段时间线、HITL 与幂等投递                 |
| 产物         | `artifact`、`artifact_version`、`artifact_citation`                                                                                                   | 一个任务最多一个产物；版本唯一；引用清单可核对         |
| 模型与 MCP   | `model_provider`、`model`、`tenant_data_key`、`provider_credential`、`model_invocation`、`mcp_server`、`mcp_tool`、`mcp_tool_grant`、`mcp_invocation` | 凭据信封加密；M02 单一部署级模型端点                   |
| 审计         | `audit_event`                                                                                                                                         | 只追加；触发器拒绝 UPDATE/DELETE；M03 才提供查询       |
| 会话存储     | `SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES`                                                                                                         | 基础设施表，租户从主体派生                             |

### 删除与保留

- 会话与产物写入 `retention_expires_at`（默认 180 天）；账号只禁用不删除；文件/会话/知识库文档软删除。
- 审计至少保留 180 天；M02 无删除 API，M09 由维护账号按分区 `DROP PARTITION`。
- 迁移只向前；破坏性变更按 ADR-0010 的 expand → contract 两阶段，回滚以前向修复 + 备份恢复为主。

## 契约

- REST 前缀 `/api/v1`；破坏性变更升 `/api/v2`，不允许在同一版本内改变字段语义。
- 错误响应使用 RFC 9457 `ProblemDetail`，扩展字段至少包含 `code`、`traceId`、`tenantId`（脱敏后）。
- 契约目录（M02 建立）：
  - `contracts/openapi.yaml`：认证、工作空间、文件、会话、知识库、报告任务、审批、产物、模型/MCP 目录与账号管理；
  - `contracts/internal-api.yaml`：API → Worker 内部 HTTP/SSE，仅集群内可达；
  - `contracts/events/`：outbox 信封与事件 JSON Schema、SSE 事件 schema；
  - `contracts/README.md`：版本、兼容、幂等与安全规则。
- 事件命名：`riccon.<domain>.<event>.v1`；事件必须带 `eventId`、`occurredAt`、`tenantId`、`traceId`、`idempotencyKey`。
- 审计查询/导出（M03）、模型路由/配额（M04）、文档 ACL/OCR（M05）、MCP 注册/凭据托管（M07）不在 M02 契约中定义，OpenAPI 用 `x-riccon-deferred` 标注。
- OpenAPI 契约手工维护为 `contracts/openapi.yaml`；实现可用 springdoc 生成运行时描述并在契约测试中与契约对比。M01 默认关闭文档端点，M02 按环境开启。

## 安全与隐私

- **默认拒绝**：`apps/api` 与 `apps/worker` 的过滤器链只放行 `/actuator/health`，其余请求在 M02 前一律拒绝。
- **无默认用户**：排除 Spring Boot 内存用户自动配置，避免生成默认密码。
- **租户隔离**：`TenantContext` 基于 ThreadLocal，入口 Filter 打开作用域，出口关闭；跨线程必须显式传递。M02 接入异步/响应式链路时按 ADR-0003 扩展。
- **认证**：M02 使用内置账号 + Spring Session JDBC 服务端会话；Cookie `HttpOnly`、`SameSite=Lax`、非本地 `Secure`；登录后轮换会话 ID，登出失效；所有写操作校验 `X-CSRF-TOKEN`。不使用 JWT。OIDC Authorization Code/PKCE 在 M03 接入，复用同一会话层（ADR-0013）。
- **登录防护**：统一失败提示、bcrypt 12、连续 5 次失败锁定 15 分钟、进程内按 IP + 租户限流；登录成功/失败/登出写审计。
- **密钥**：只从环境变量或密钥管理服务读取；禁止写入仓库、镜像、日志、证据和提示词。
- **MCP**：工具白名单、参数校验、超时与出网限制；高风险工具必须人工审批。
- **提示注入**：知识库与 MCP 返回内容一律视为不可信数据，不进入系统指令；M02 建立注入用例集。
- **审计**：模型、工具、MCP、权限和租户变更必须留痕；审计写入失败时业务失败，不允许静默丢弃。

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
