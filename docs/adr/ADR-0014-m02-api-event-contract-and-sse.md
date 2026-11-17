# ADR-0014：M02 API 与事件契约、SSE 和版本规则

Status: accepted
Date: 2026-11-17
关联：ADR-0007（审计）、ADR-0008（outbox/SSE）、ADR-0009（服务身份）、ADR-0012（数据模型）、`contracts/README.md`

## Context

M02 有三类跨边界通信：

1. 浏览器 → API（REST + 会话 Cookie/CSRF）；
2. API → Worker（内部 HTTP/SSE，J1 对话）；
3. API → Worker（MySQL outbox，J2 报告任务、文件解析、知识库索引）。

M01 只建立了命名约定，没有具体契约。若 M02 让 API 与 Worker 各自定义字段，会出现租户、trace、幂等与错误语义漂移；若把 SSE 当普通 JSON，会让前端无法稳定处理首字、停止、引用和失败。需要把公开 REST、内部 API、outbox 事件与 SSE 事件一次定稿，并给出兼容规则。

## Decision

### 1. 契约文件是唯一事实源

- 公开 REST：`contracts/openapi.yaml`（OpenAPI 3.1，路径 `/api/v1`）。
- 内部 API：`contracts/internal-api.yaml`（OpenAPI 3.1，路径 `/internal/v1`，仅集群内可达）。
- outbox 事件：`contracts/events/envelope.schema.json` + `contracts/events/outbox/*.v1.schema.json`（JSON Schema 2020-12）。
- SSE：`contracts/events/sse/chat-event.v1.schema.json`、`run-progress.v1.schema.json`。
- Java 共享常量：`platform-domain` 的 `EventTypes`、`AuditActions`、`PermissionCodes`、`HttpHeaders`；实现必须引用常量，不得写字符串字面量。

### 2. REST 语义

- 路径 `/api/v1`；破坏性变更升 `/api/v2`，同一版本内不改变字段语义。
- 错误统一 RFC 9457 `application/problem+json`，至少 `type/title/status/code/traceId`；错误码 `RICCON-<DOMAIN>-<NNN>`。
- 跨租户对象统一 404 `RICCON-TENANT-404`；不得区分“不存在”和“属于其他租户”。
- 列表强制分页：`page` 1 开始，`size` 默认 20、最大 100；禁止无界查询。
- 每个操作声明 `x-riccon-permission`，实现必须在 URL + 方法级做双重 RBAC 校验；前端守卫只作 UX。
- 写操作可带 `Idempotency-Key`；创建报告任务、审批决定与重试必须使用。幂等键与主体、资源绑定，不跨用户复用。
- 下载只返回预签名 URL，TTL ≤ 300 秒；URL 绑定对象 key，客户端不能传入 key。

### 3. 事件信封与幂等

- 所有 outbox 事件必须包含 `eventId/eventType/eventVersion/occurredAt/tenantId/traceId/producer/idempotencyKey/payload`。
- `eventType` 格式 `riccon.<domain>.<event>.v<n>`；数据库 outbox 存 `event_type`、`event_version`、`idempotency_key` 三个独立列，payload 只存业务参数。
- `eventId` UUIDv7；`idempotencyKey` 与 `tenant_id` 唯一。投递语义 at-least-once，消费端用运行状态条件更新做最终幂等守卫。
- payload 白名单：只放 ID、阶段、尝试号、原因等；禁止提示词、文件原文、知识库原文、工具结果原文、凭据、完整 PII。
- 事件生产者为 `api`；Worker 不写 outbox。Worker 的进度通过共享 MySQL 状态被 API 读取，不通过回调 API。

### 4. SSE 语义

- 对话：`POST /api/v1/conversations/{conversationId}/messages` 返回 `text/event-stream`；事件类型
  `riccon.chat.started.v1`、`delta.v1`、`citation.v1`、`completed.v1`、`failed.v1`、`stopped.v1`。
- 首字前失败：返回 `ProblemDetail`，不建立 SSE；首字后失败：发送 `failed` 事件并结束流，已生成内容保留。
- 报告进度：`GET /api/v1/research-tasks/{researchTaskId}/events` 由 API 根据 `agent_run`、`agent_run_stage`、`approval` 的权威状态派生；事件类型
  `riccon.run.stage.v1`、`approval.required.v1`、`approval.decided.v1`、`completed.v1`、`failed.v1`、`cancelled.v1`。
- 所有 SSE 响应 `Cache-Control: no-store`，空闲超时 60 秒；事件 ID 单调，支持 `Last-Event-ID` 重连去重。
- SSE 不是审计证据；审计仍以 `audit_event` 为准。

### 5. 内部服务身份

- API → Worker 使用 HMAC-SHA256 短期令牌（`X-Riccon-Service-Token`）：
  `iss=riccon-api`、`aud=riccon-worker`、`sub=user:{userId}`、`tenantId`、`runId`、`scope=agent:run`、`iat`、`exp`（≤300 秒）、`jti`。
- Worker 校验签名、`aud`、`exp`、`scope`，并强制 `tenantId == agent_run.tenant_id`；不一致返回 403 并写审计。
- Worker 不接受浏览器 Cookie，不提供通用 CRUD 或管理接口；除健康检查与内部端点外默认拒绝。

### 6. 版本与兼容

- 兼容变更：新增可选字段、新增事件类型、新增枚举值（消费方容忍未知值）、新增响应头。
- 破坏性变更：删除/重命名字段、改变语义/类型、可选改必填、收紧枚举/上限、改变状态机或幂等语义；必须升版本并写 ADR。
- 消费方必须忽略未知字段与未知事件类型；未知事件只记录指标，不阻塞队列。
- 旧版本至少保留一个发布周期（N/N-1）；事件 schema 与 OpenAPI 在同一 PR 更新。
- M02 不定义审计查询/导出、模型路由/配额、文档 ACL/OCR、MCP 注册/凭据托管；这些在 OpenAPI 的 `x-riccon-deferred` 标注，提前实现需要重开威胁模型与决策包。

### 7. 契约测试

- Builder 必须实现：
  - OpenAPI 请求/响应契约测试（至少覆盖认证、上传、消息、报告创建、审批、产物）；
  - outbox 事件 JSON Schema 校验（写入前）；
  - SSE 事件 JSON Schema 校验（发送前）；
  - 错误码与 `ProblemDetail` 字段断言；
  - 未知字段/未知枚举的消费方容忍测试。
- 契约测试失败不得通过 gate；契约变更必须同步更新 `contracts/README.md` 的清单。

## Consequences

- 正面：API/Worker 可以独立实现而不猜字段；幂等、错误、租户、trace 语义统一；SSE 的失败与停止可测试；版本规则防止同版本漂移。
- 负面：契约文件较大，维护成本增加；JSON Schema 与 OpenAPI 需要生成/校验工具；事件与数据库列需要人工保持一致。
- 约束：不得在代码中复制事件名/权限码；新增字段必须先改契约；契约不得包含真实密钥或 PII；内部 API 不得暴露到公网。

## Alternatives

- **只在代码里定义 DTO，不维护契约文件**：实现快，但 API/Worker 漂移、前端无法并行；拒绝。
- **Protobuf/gRPC**：强类型，但引入新工具链与浏览器适配成本；M02 过度设计。
- **Kafka/消息中间件**：ADR-0008 已拒绝；M02 用 MySQL outbox。
- **WebSocket**：双向能力更强，但 M02 只需服务端流；SSE 更简单、易穿透代理。
- **Worker 回调 API 推送进度**：需要反向服务身份、重试与去重；M02 共享 MySQL 状态足够，M06 再评估。
- **同版本内直接改字段**：会造成前端/Worker 静默错误；拒绝。

## Evidence

- `contracts/openapi.yaml`、`contracts/internal-api.yaml`、`contracts/events/**`、`contracts/README.md`
- `platform/platform-domain` 的 `EventTypes`、`AuditActions`、`PermissionCodes`、`HttpHeaders`
- `docs/security/THREAT-MODEL.md` TM-17、TM-18、TM-21、S-03-1、T-03-1
- 测试（Builder 实现）：`IT-RUN-001..003`、`IT-SVC-001..003`、`IT-OBS-001`、`E2E-AC-03/05/06/09/11`

## 澄清（不改本 ADR 决策）

- 2026-11-18：第 5 节“令牌 `tenantId` 与 `agent_run.tenant_id` 不一致返回 403”按第 2 节的跨租户统一 404 执行：run 在令牌租户内不可见（包括属于其他租户）时返回 `404 RICCON-TENANT-404`，不返回 403，避免泄露“该 run 存在但属于其他租户”；仍写 `outcome=denied` 审计。403 仅用于同一租户内的绑定冲突（`sub` 与请求 `userId` 不符、run kind 不匹配、conversation/user 绑定不符、发起人已禁用）。请求体 `tenantId` 与令牌不一致属于请求不合法，返回 `400 RICCON-CHAT-400`，租户始终以令牌为准。该澄清使契约与 Worker 现有安全策略一致，不改变租户隔离强度。
- 2026-11-18：`agent_run_stage.details` 在 `contracts/openapi.yaml` 中显式定义 `RunStageDetails`：`paramsPreview`、`resultSummary`、`invocationId` 为可选字段，并给出长度与脱敏上限；`paramsPreview` 只是脱敏摘要，完整审批参数加密保存（ADR-0017），任何 API 都不返回密文。
