# ADR-0007：审计事件模型与 append-only 存储

Status: accepted
Date: 2026-11-17

## Context

T4 与合同要求：登录、文件上传/删除、会话创建、模型调用、MCP 调用、审批、权限变更和数据导出必须留痕；审计至少保留 180 天；append-only，禁止在线删除，导出需审批；审计写入失败时业务请求失败（M02-AC-14/15）。M02 只要求写入与禁止普通员工访问，审计查询与导出在 M10（DEC-0011/DEC-0013）。需要在不引入消息中间件的前提下，给出可执行的事件模型、表结构和失败语义。

## Decision

### 1. 事件信封

每条审计事件至少包含：

| 字段             | 说明                                                                                          |
| ---------------- | --------------------------------------------------------------------------------------------- |
| `event_id`       | ULID，时间有序且全局唯一                                                                      |
| `occurred_at`    | 事件发生时间（UTC，毫秒精度）                                                                 |
| `tenant_id`      | 租户；平台级事件使用 `platform` 保留租户                                                      |
| `actor_type`     | `user` / `service` / `system`                                                                 |
| `actor_id`       | 用户 ID 或服务 ID；不存用户名、邮箱等 PII                                                     |
| `action`         | 稳定事件名，见第 2 节                                                                         |
| `target_type`    | `session` / `file` / `artifact` / `model` / `mcp_tool` / `user` / `role` / `tenant` / `audit` |
| `target_id`      | 目标 ID；无具体目标时为空                                                                     |
| `outcome`        | `success` / `failure` / `denied`                                                              |
| `trace_id`       | 与日志、错误响应一致的 traceId                                                                |
| `request_id`     | 单次 HTTP 或任务请求 ID                                                                       |
| `source_ip`      | 登录事件的来源 IP（可选，仅安全审计可见）                                                     |
| `details`        | JSON 白名单字段，见第 5 节                                                                    |
| `schema_version` | 当前为 `1`                                                                                    |

事件名使用 `riccon.<domain>.<event>.v1` 的语义，但数据库存储 `action` 时使用第 2 节的短名，映射关系固定，避免表内出现版本后缀导致查询分裂。

### 2. 最小事件清单

| action                       | 触发                                     | 必填 `details`（白名单）                                               | 对应 AC      |
| ---------------------------- | ---------------------------------------- | ---------------------------------------------------------------------- | ------------ |
| `auth.login.succeeded`       | 登录成功                                 | `method=password/oidc`、`sessionIdHash`                                | M02-AC-01/14 |
| `auth.login.failed`          | 登录失败（含账号不存在、密码错误、锁定） | `reasonCode`、`attemptCount`                                           | M02-AC-01/14 |
| `auth.logout`                | 主动登出                                 | 无                                                                     | M02-AC-14    |
| `tenant.created`             | 平台管理员创建租户                       | `plan`（M02 固定 `standard`）                                          | M02-AC-14    |
| `user.created`               | 创建账号                                 | `role`                                                                 | M02-AC-14    |
| `user.disabled`              | 禁用/启用账号                            | `enabled`                                                              | M02-AC-14    |
| `permission.changed`         | 角色授予/撤销                            | `targetUserId`、`role`、`change=grant/revoke`                          | M02-AC-14    |
| `file.uploaded`              | 文件上传并登记成功                       | `fileId`、`sizeBytes`、`contentType`、`sha256`                         | M02-AC-02/14 |
| `file.deleted`               | 删除文件                                 | `fileId`、`objectKeyHash`                                              | M02-AC-14    |
| `session.created`            | 新建会话                                 | `sessionId`                                                            | M02-AC-14    |
| `model.invocation.started`   | 开始调用模型（流式首字前）               | `modelId`、`purpose=chat/report`                                       | M02-AC-14    |
| `model.invocation.completed` | 模型调用正常结束                         | `modelId`、`durationMs`、`promptTokens`、`completionTokens`            | M02-AC-14    |
| `model.invocation.failed`    | 模型调用失败                             | `modelId`、`durationMs`、`errorCode`、`retryable`                      | M02-AC-14    |
| `mcp.tool.invoked`           | 一次 MCP 工具调用结束                    | `toolName`、`paramsSha256`、`resultSha256`、`durationMs`、`approvalId` | M02-AC-08/14 |
| `approval.decided`           | 员工批准/拒绝审批                        | `approvalId`、`decision`、`approverUserId`、`toolName`                 | M02-AC-09/14 |
| `artifact.saved`             | 报告产物保存                             | `artifactId`、`reportRunId`、`version`                                 | M02-AC-10/14 |
| `data.exported`              | 审计/业务数据导出（M10）                 | `exportId`、`scope`、`rowCount`、`approvalId`                          | M10          |
| `audit.queried`              | 查询审计（M10）                          | `filtersHash`、`rowCount`                                              | M10          |

模型调用固定为 `started + completed/failed` 两条。`started` 在打开模型流之前写入，确保即使进程崩溃也留有调用痕迹；`completed/failed` 在响应终止前写入。缺少 `completed/failed` 的 `started` 事件是运维告警信号。

### 3. 存储与 append-only

建表 `audit_event`（Flyway，`apps/api`）：

- 按 `occurred_at` 月分区，便于到期整分区删除；
- 主键 `(event_id, occurred_at)`（MySQL 分区要求分区键进入主键；`event_id` 由 ULID 生成且单写入，跨分区重复概率可忽略，因此不另建单列唯一索引）；
- 索引 `(tenant_id, occurred_at)`、`(action, occurred_at)`；
- `details` 使用 MySQL `JSON`，写入前做白名单裁剪与长度限制；
- 不建外键（审计不得因业务删除而级联）。

append-only 由三层共同保证：

1. **应用层**：只提供 `AuditWriter.append(event)`，没有 update/delete 方法；ArchUnit 禁止 `..audit..` 之外的代码直接访问 `audit_event` 表。
2. **数据库触发器**：`BEFORE UPDATE` 与 `BEFORE DELETE` 触发器 `SIGNAL SQLSTATE '45000'`，任何在线修改/删除直接失败。
3. **权限**：生产环境应用账号仅授予 `INSERT, SELECT`（M09 落地账号拆分）；保留清理使用维护账号按分区 `DROP PARTITION`，不执行 `DELETE`。

M02 至少落地第 1、2 层；第 3 层在 M09 完成。触发器与“无删除 API”必须在集成测试中断言。

### 4. 写入失败语义（fail-closed）

| 场景            | 事务边界                                            | 失败行为                                                                   |
| --------------- | --------------------------------------------------- | -------------------------------------------------------------------------- |
| 登录成功/失败   | 认证结果与审计同事务                                | 审计失败 → 登录失败，返回 500，不建立会话                                  |
| 上传            | 对象写入后，元数据与 `file.uploaded` 同事务         | 事务失败 → 删除已写对象（补偿），返回 500，不留下可用文件                  |
| 删除文件/会话   | 业务更新与审计同事务；对象删除在提交后执行          | 一起回滚；对象删除失败进入重试，元数据保持 `deleted`，不回滚审计           |
| 会话创建        | 同事务                                              | 一起回滚                                                                   |
| 模型调用        | `started` 独立事务；`completed/failed` 在终止响应前 | `started` 失败 → 不调用模型；终止事件写入失败 → 流以错误结束，任务标记失败 |
| MCP 调用        | 调用结果与 `mcp.tool.invoked` 同事务                | 审计失败 → 任务失败，不保存结果                                            |
| 审批            | 审批状态变化与 `approval.decided` 同事务            | 一起回滚，任务保持待审批                                                   |
| Worker 任务状态 | 状态迁移与审计同事务                                | 状态不迁移，租约释放后重试                                                 |

指标 `riccon_audit_write_failures_total` 期望恒为 0；非 0 即告警（M09 接告警规则）。

### 5. 脱敏与白名单

`details` 只允许第 2 节列出的字段，禁止写入：

- 密码、token、Cookie、密钥、连接串；
- 提示词、模型回答、文件原文、知识库原文、MCP 请求/响应原文；
- 完整 PII（邮箱、手机号、身份证号）。

需要引用内容时存 SHA-256 与长度，不存原文。`source_ip` 只在登录事件中记录，视为安全审计数据，不展示给普通管理员；M10 查询接口默认不返回，需显式字段选择。

### 6. 查询、导出与保留

- **M02**：不提供审计查询 API；员工与租户管理员均无法访问审计表。审计只写不读，满足 AC-15。
- **M03**：不提供审计查询/导出；DEC-0013 将两项后移到 M10。
- **M10**：平台管理员可查询全部租户；租户管理员仅本租户。查询强制分页（默认 20，最大 100），每次查询写 `audit.queried`。平台来源的部门事件（`tenant_id=platform` + `details.targetTenantId`）是否对目标租户管理员可见，由 M10 显式设计；M03 不声称。
- **导出**：M10 实现；仅平台管理员发起，必须经过审批决策并绑定 `release`/环境；导出文件加密、短期有效，写 `data.exported`；禁止直接 SQL 导出给客户。
- **保留**：至少 180 天。M02 不实现删除；M09 由维护账号按月 `DROP PARTITION` 删除超过保留期的分区，支持 legal hold 暂停。用户删除请求不删除审计（合同要求），actor 使用不可逆用户 ID。

### 7. 完整性增强（后置）

M10 评估每日哈希链（`prev_hash` + `event_hash`）与外部锚定。M02/M03 不引入，因为分区删除与链式校验需要额外的跨分区锚点设计；当前以触发器、权限与数据库备份保证基本完整性。

## Consequences

- 正面：审计与业务同事务，失败即业务失败；append-only 在数据库层可验证；事件模型覆盖 M02-AC-14 全部动作。
- 负面：每条模型调用至少两次插入；流式路径增加一次写库延迟（在 3 秒首字预算内）；触发器会阻止任何误更新，排障需通过新增事件而非修改历史。
- 约束：`details` 新增字段必须更新本 ADR 与白名单测试；审计表不得加外键；不得为“修数据”临时禁用触发器。

## Alternatives

- **只写日志文件**：实现快，但不可查询、可被轮转删除、无法保证 180 天，不满足合同。
- **Kafka/独立审计服务**：扩展性好，但 M02 不引入消息中间件，运维成本与故障面增大。
- **软删除 + 定期清理**：允许在线修改/删除，违反 append-only。
- **M02 就上哈希链**：更强篡改证据，但与月分区删除、轮换和查询复杂度叠加，收益低于成本；M10 再评估。
- **审计与业务分库**：隔离更好，但引入分布式事务或最终一致，M02 过度设计。

## Evidence

- `apps/api/src/main/resources/db/migration/V*__audit_event.sql`（M02 建立：表、分区、触发器）
- 测试（M02 建立）：`AUDIT-001` 写失败回滚、`AUDIT-002` 字段完整、`AUDIT-003` UPDATE/DELETE 被拒、`AUDIT-004` 无修改/删除 API、`AUDIT-005` 普通用户 403、`AUDIT-006` 流式中断仍记录 started/failed
- `E2E-AC-14`、`E2E-AC-15`
- `docs/security/THREAT-MODEL.md` TM-11、B-04、B-12；合同 `data.retention`、`constraints.compliance`

## 澄清（不改本 ADR 决策）

- 2026-11-17：`event_id` 的数据库表示由 ADR-0012 统一为 UUIDv7 `BINARY(16)`；仍要求时间有序与全局唯一，本文其余字段、分区与 append-only 语义不变。
- 2026-11-17：M02 目标类型在本文最小列表外增加 `WORKSPACE`、`CONVERSATION`、`KNOWLEDGE_DOCUMENT`、`RESEARCH_TASK`、`RUN`、`APPROVAL`，统一由 `AuditTargetType` 枚举约束；`details` 白名单与禁止字段不变。
- 2026-11-18：`approval.params_preview` 恢复为本文第 5 节要求的**脱敏摘要**，不再保存完整工具参数；完整参数由 `params_ciphertext` + `params_key_id` 加密保存，只在审批通过后由 Worker 解密重放（ADR-0017、V8 迁移）。`mcp.tool.invoked` 仍只允许 `paramsSha256`/`resultSha256`，`approval.decided` 仍只允许审批 ID、决定、审批人与工具名；审计 `details` 不得出现参数预览、密文或明文。
- 2026-11-18：DEC-0015 允许平台管理员跨租户管理租户**部门元数据**（部门、成员归属、负责人），复用 `/api/v1/admin/tenants/{tenantId}` 路径。审计沿用 `TenantUserAdminService` 口径：事件 `tenant_id` 写操作者租户（`platform`），目标租户只进入 `details.targetTenantId`，`target_id` 写部门 ID；平台管理员仍不得读取目标租户业务内容。本文第 6 节原 M03 审计查询/导出已按 DEC-0013 后移到 M10；平台来源部门事件对目标租户的可见模型由 M10 设计。
- 2026-11-18：M03 部门管理新增 `AuditTargetType.DEPARTMENT`；`department.*` 动作的 details 白名单见 ADR-0018 第 5 节，禁止姓名、邮箱、成员名单与业务内容。
- 2026-11-19（M10）：审计查询与导出按 ADR-0020 启用。本文第 6 节“M10 平台管理员可查询全部租户”**不执行**：DEC-0013 明确跨租户审计明细后移，M10 的平台管理员只能查询 `platform` 租户自身事件。平台来源部门/租户/账号事件继续写 `platform` + `details.targetTenantId`，目标租户在 M10 不可见（M03 F-009 的承接结论）。`source_ip` 不进查询响应与导出；导出字段白名单见 ADR-0020 §2。`AdditionalAuditActions` 中已在写入的动作在 M10 提升为 `platform-domain` 的 `AuditActions`；新增 `audit.export.*`、`data.exported`、`user.oidc.*` 动作与 `AUDIT_EXPORT` 目标类型，details 白名单见 ADR-0020 §6.1。哈希链仍后移，本文第 7 节不变。
