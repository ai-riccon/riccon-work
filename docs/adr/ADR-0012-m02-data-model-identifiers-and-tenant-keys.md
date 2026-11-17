# ADR-0012：M02 数据模型、标识与租户键

Status: accepted
Date: 2026-11-17
关联：ADR-0006（信封加密）、ADR-0007（审计）、ADR-0008（outbox）、ADR-0009（租户隔离强制点）、ADR-0010（Flyway 单一迁移者）；后续数据模型变更必须新增迁移并更新本 ADR 的例外清单

## Context

M02 要落地 J1（文件、会话、引用、知识库）与 J2（报告任务、运行、审批、产物），并满足 T4 的租户隔离、审计、密钥与可回滚要求。M01 没有业务表，只有 `TenantContext` 与 Flyway 基线。现有 ADR-0009 已确定：Spring JDBC（无 ORM）、所有租户表带 `tenant_id`、复合主键 `(tenant_id, id)`、唯一键与索引以 `tenant_id` 开头、软删除、UUIDv7 对外 ID。ADR-0007 要求审计按月分区且 append-only；ADR-0008 要求 MySQL outbox 与运行状态机。

本 ADR 把“表怎么建、ID 怎么表示、哪些表是全局表、删除与保留怎么做、迁移如何回滚”固定下来，避免 Builder 在实现时各自发明。

## Decision

### 1. 持久化与实体

- M02 使用 **Spring JDBC（`JdbcClient`）+ 显式 SQL**，不引入 JPA/Hibernate。原因见 ADR-0009：ORM 会绕过 `TenantJdbc` 的 `tenant_id` 守卫，且当前团队没有 ORM 迁移成本预算。
- 不建立 `platform/platform-persistence` 模块，也不把 JPA 实体放进 `platform`。每个部署物在 `apps/api` 或 `apps/worker` 的 `..persistence..` 包内实现行映射与 SQL；`platform-platform-domain` 只放枚举与常量。
- 所有写操作必须经过部署物自己的 `TenantJdbc`；跨租户 outbox 领取是唯一例外，见 ADR-0016。

### 2. 标识

- 所有业务对象 ID 使用 **UUIDv7**，由应用生成，数据库存 `BINARY(16)`；REST/JSON/事件中表示为标准 UUID 字符串。
- 不使用 `BIGINT AUTO_INCREMENT`：会暴露规模、可枚举，且跨实例/离线生成困难。
- 不使用 `CHAR(26)` ULID：ADR-0009 已选 UUIDv7；`BINARY(16)` 比 26 字符更省索引空间，UUIDv7 同样时间有序。ADR-0007/0008 中提到的 ULID 语义由本 ADR 统一为 UUIDv7，时间有序与不可枚举语义不变。
- `tenant_id` 使用 `VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin`；租户代码同时作为登录标识，保留值 `platform`。不使用 UUID 作为租户 ID，便于私有化交付时人工识别与配置。

### 3. 租户键与约束

- 所有租户业务实体表：`tenant_id NOT NULL`、`CHECK (tenant_id <> '')`、主键 `(tenant_id, id)`。
- 关联表与密钥表使用自然复合主键，不额外生成 `id`：`role_permission (tenant_id, role_id, permission_code)`、`user_role (tenant_id, user_id, role_id)`、`tenant_data_key (tenant_id, key_id)`。它们仍有 `tenant_id` 且唯一键/主键以 `tenant_id` 开头。
- 唯一键以 `tenant_id` 开头；例如 `(tenant_id, username)`、`(tenant_id, research_task_id)`、`(tenant_id, idempotency_key)`。
- 结构性父子关系使用复合外键：`(tenant_id, parent_id) REFERENCES parent (tenant_id, id)`，从 schema 层阻止跨租户关联。
- 跨聚合、可空或跨版本前向引用（如 `message.run_id`、`research_task.mcp_tool_id`）使用逻辑引用 + 索引，不建外键；理由是该行可能由另一模块在后续迁移创建，且删除策略是软删除。评审时逐条核对。
- 指向全局维度表的外键（`permission_code`、`provider_id`、`model_id`、`mcp_tool_id`）会由 MySQL 自动生成不含 `tenant_id` 的单列索引；它们只服务外键完整性检查，业务查询必须使用另建的 `tenant_id` 前缀索引。
- 状态列使用 `VARCHAR(32)` + `CHECK`，不用 MySQL `ENUM`；新增状态是向前兼容的 `ALTER`，不改变列类型。
- 时间统一 `DATETIME(6)`，应用写入 UTC；数据库连接初始化必须 `SET time_zone='+00:00'`。禁止使用 `TIMESTAMP` 自动时区转换。

### 4. 全局表（ADR-0009 例外清单）

以下表**不带** `tenant_id`，属于平台目录或基础设施：

| 表                                            | 原因                                                    |
| --------------------------------------------- | ------------------------------------------------------- |
| `tenant`                                      | 租户根表，自身定义租户                                  |
| `permission`                                  | 权限字典全局稳定，避免每租户复制漂移                    |
| `model_provider`、`model`                     | M02 部署级模型目录；M04 再引入租户级路由                |
| `mcp_server`、`mcp_tool`                      | M02 平台内置白名单；M07 引入租户注册与审批              |
| `flyway_schema_history`                       | Flyway 元数据（ADR-0010）                               |
| `SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES` | Spring Session 基础设施，租户从会话主体派生（ADR-0013） |

特殊例外：

- `audit_event` 有 `tenant_id`，但主键为 `(event_id, occurred_at)`（分区键必须进入主键）；租户条件由应用层与 `(tenant_id, occurred_at)` 索引保证。
- `agent_run_outbox` 有 `tenant_id`，但领取索引 `(status, available_at, lease_until)` 故意不以 `tenant_id` 开头；跨租户领取见 ADR-0016。
- 模型凭据在 M02 为部署级，`provider_credential.tenant_id = 'platform'`；M04 可写租户级记录，不改变表结构。

### 5. 内容、索引与引用

- MySQL 是权威：会话、消息、引用、文件元数据、`file_parse`、`file_chunk`、`knowledge_document`、`knowledge_chunk`、运行、审批、产物与引用清单。
- OpenSearch 只保存可重建的检索索引：别名 `riccon-kb-{tenantId}` 指向 `riccon-kb-v1-{tenantId}`；索引文档 ID 使用本地 chunk ID，必须冗余 `tenant_id` 并同时做索引隔离与 term 过滤（ADR-0009 第 7 节）。
- 引用保存来源快照（标题、片段、定位、分数）；`chunk_id` 是逻辑引用，索引重建或源文件软删除后历史引用仍可展示。
- 文件原文、产物正文在对象存储；MySQL 只存 `storage_key`、大小、SHA-256、MIME 与解析状态。对象 key 固定 `tenants/{tenantId}/{category}/{yyyy}/{objectId}`。

### 6. 删除、保留与审计

- 业务表软删除：`deleted_at`；查询默认 `deleted_at IS NULL`，仍必须带 `tenant_id`。物理清理由 M09 维护任务按保留期执行，禁止跨租户批量删除。
- 会话与产物在创建时写入 `retention_expires_at = created_at + tenant.retention_days`（默认 180 天）；租户策略变更只影响新对象。
- 账号只禁用不删除；审计 actor 使用不可逆用户 ID，不存用户名/邮箱。
- `audit_event` 不建 `deleted_at`，不提供修改/删除 API；数据库触发器拒绝 UPDATE/DELETE；保留清理由 M09 按分区 `DROP PARTITION`。审计写入失败使业务失败（ADR-0007）。

### 7. 迁移与回滚

- 迁移只在 `apps/api/src/main/resources/db/migration`，命名 `V{n}__{snake_case}.sql`，序号连续；V1 不修改。
- M02 迁移拆分：V2 身份/工作空间、V3 内容/会话、V4 任务/运行/产物/outbox、V5 模型/MCP、V6 审计、V7 Spring Session。每个文件可独立理解，但版本顺序不可跳。
- 所有迁移只做向前兼容：新增表/列/索引；不删列、不改类型、不回填生产数据。破坏性变更按 ADR-0010 的 expand → contract 两阶段执行。
- 回滚以“前向修复 + 备份恢复”为主；不依赖 Flyway undo。对象存储对象不因数据库回滚而删除，避免破坏引用。
- 审计触发器需要 MySQL 账号具备 `TRIGGER`，且二进制日志开启时需要 `log_bin_trust_function_creators=1` 或等价权限；迁移账号（API）必须具备该能力。测试栈与生产部署检查清单必须包含此项，否则 V6 会失败。

## Consequences

- 正面：租户键在 schema 层可见、可测试；UUIDv7 不可枚举且时间有序；MySQL 权威 + OpenSearch 可重建；软删除与保留期可审计；无 ORM 使 `TenantJdbc` 成为单一强制点。
- 负面：复合主键/外键让 SQL 更啰嗦；`BINARY(16)` 调试时需转换；软删除增加查询条件；触发器对迁移账号权限有要求。
- 约束：新增业务表必须遵守第 3 节；新增全局表必须更新本 ADR；新增跨聚合逻辑引用必须在评审中说明为何不建外键；不得在业务代码中直接使用 `JdbcClient`。

## Alternatives

- **JPA/Hibernate + 多租户过滤器**：开发快，但绕过显式 tenant 条件、难以强制复合键，且引入第二套持久化栈；拒绝。
- **`CHAR(26)` ULID**：时间有序，但比 `BINARY(16)` 占空间，且与 ADR-0009 已选 UUIDv7 冲突；拒绝。
- **`VARCHAR(36)` 存 UUID**：可读性好，但索引膨胀约 2.25 倍；拒绝。
- **自增主键**：暴露规模、可枚举，跨实例生成困难；拒绝。
- **每租户独立数据库/schema**：隔离最强，但迁移、连接池、备份成本随租户线性增长；M09 可作为高隔离客户选项，不作为 M02 默认。
- **MySQL 原生 RLS**：MySQL 8.4 不提供；拒绝。
- **硬删除**：破坏审计与引用可解释性；拒绝。
- **把审计与业务分库**：M02 引入分布式事务，过度设计；拒绝。

## Evidence

- `apps/api/src/main/resources/db/migration/V2..V7`（本 ADR 落地）
- `platform/platform-domain` 的状态枚举与 `contracts/openapi.yaml`、`contracts/events/**` 的值一致
- `docs/security/THREAT-MODEL.md` TM-06、TM-11、TM-12、TM-16、TM-17、B-04、B-12
- `docs/adr/ADR-0009`、`ADR-0006`、`ADR-0007`、`ADR-0008`、`ADR-0010`
