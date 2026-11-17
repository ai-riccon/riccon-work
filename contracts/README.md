# 契约

本目录是 Riccon 跨部署物、跨模块的公开契约唯一事实源：REST API、内部 Worker API、异步事件与流式事件。实现与契约必须在同一变更中更新；契约冲突时以本目录和 ADR 为准，不允许“代码先跑、文档后补”。

## 1. 目录

```text
contracts/
  openapi.yaml                              # 员工端/管理端公开 REST（/api/v1）
  internal-api.yaml                         # API -> Worker 内部 REST/SSE（/internal/v1）
  events/
    envelope.schema.json                    # outbox 事件信封
    outbox/                                 # API -> Worker 任务事件
      file-parse-requested.v1.schema.json
      knowledge-index-requested.v1.schema.json
      agent-run-requested.v1.schema.json
      agent-run-resume.v1.schema.json
      agent-run-cancel.v1.schema.json
    sse/                                    # Worker -> API/浏览器流式事件
      chat-event.v1.schema.json
      run-progress.v1.schema.json
```

## 2. 版本规则

| 类型      | 规则                               | 示例                            |
| --------- | ---------------------------------- | ------------------------------- |
| REST 路径 | `/api/v{n}/{资源复数}`，小写短横线 | `/api/v1/knowledge/documents`   |
| 事件      | `riccon.<domain>.<event>.v{n}`     | `riccon.agent.run.requested.v1` |
| JSON 字段 | camelCase                          | `tenantId`、`createdAt`         |
| 错误码    | `RICCON-<DOMAIN>-<NNN>`            | `RICCON-TENANT-404`             |
| 数据库列  | snake_case                         | `tenant_id`、`created_at`       |
| 事件 ID   | UUIDv7 字符串；数据库 `BINARY(16)` | `0193...`                       |

兼容变更（同一版本内允许）：

- 新增可选字段、新增事件类型、新增枚举值（消费方必须容忍未知枚举）；
- 新增可选查询参数、新增响应头；
- 放宽校验（例如提高上限）需同步产品 AC 与测试。

破坏性变更（必须升版本并写 ADR）：

- 删除/重命名字段、改变字段语义或类型；
- 把可选字段改为必填、收紧枚举或上限；
- 改变状态机迁移、幂等键语义、错误码含义；
- 改变认证方式或租户派生方式。

旧版本至少保留一个发布周期（N/N-1），消费方必须能忽略未知字段与未知事件类型。事件 schema 不做 `oneOf` 全量互斥校验时，消费者按 `eventType` 分派，未知类型只记录指标，不失败整个队列。

## 3. 事件与幂等

### 3.1 outbox 信封

所有 API → Worker 事件必须包含：

`eventId`、`eventType`、`eventVersion`、`occurredAt`、`tenantId`、`traceId`、`producer`、`idempotencyKey`、`payload`。

- `eventId` 为 UUIDv7 字符串；数据库 `agent_run_outbox.id` 为 `BINARY(16)`。
- `idempotencyKey` 与 `tenant_id` 组成唯一键；重复插入返回既有记录，不产生第二次执行。
- 投递语义为 at-least-once；Worker 必须用运行状态条件更新（`PENDING/RETRY_WAIT → RUNNING`）作为最终幂等守卫。
- payload 只放脱敏后的任务参数；禁止提示词、文件原文、知识库原文、工具结果原文、凭据。
- 事件生产者固定为 `api`；Worker 不写 outbox，只更新运行状态与审计。

### 3.2 幂等键约定

| 事件                                  | 幂等键                             |
| ------------------------------------- | ---------------------------------- |
| `riccon.file.parse.requested.v1`      | `file:{fileId}:parse:{parseId}`    |
| `riccon.knowledge.index.requested.v1` | `doc:{documentId}:index:{attempt}` |
| `riccon.agent.run.requested.v1`       | `run:{runId}:requested:{attempt}`  |
| `riccon.agent.run.resume.v1`          | `run:{runId}:resume:{approvalId}`  |
| `riccon.agent.run.cancel.v1`          | `run:{runId}:cancel`               |

### 3.3 SSE

- `chat-event.v1`：Worker 产生、API 转发；事件类型见 `EventTypes` 常量。
- `run-progress.v1`：API 根据 `agent_run`、`agent_run_stage`、`approval` 的权威状态派生。
- 每帧格式 `event: <eventType>` + `data: <JSON>` + 空行；`Cache-Control: no-store`。
- 流式事件不是审计证据；审计仍以 `audit_event` 为准。

## 4. REST 约定

- 认证：会话 Cookie `RICCON_SESSION`（HttpOnly、SameSite=Lax、非本地 Secure）；不使用 JWT。
- CSRF：所有非安全方法带 `X-CSRF-TOKEN`；令牌来自 `GET /api/v1/auth/csrf` 或登录响应。
- 租户：只从会话主体派生；忽略请求体/查询/请求头中的 `tenantId`。
- 分页：`page` 从 1 开始，`size` 默认 20、最大 100；禁止无界查询。
- 错误：RFC 9457 `application/problem+json`，至少 `type/title/status/code/traceId`；
  跨租户对象统一 404 `RICCON-TENANT-404`。
- 下载：服务端校验归属后签发预签名 URL，TTL ≤ 300 秒；URL 绑定对象 key。
- 幂等：`Idempotency-Key` 用于创建报告任务、审批决定、重试；同一主体重复请求返回既有结果。

## 5. 安全边界

- `openapi.yaml` 只描述浏览器/租户网络可达的 `/api/v1`；`internal-api.yaml` 只描述集群内部可达的 `/internal/v1`。
- Worker 不接受浏览器 Cookie，不提供通用 CRUD 或管理接口；内部调用使用 HMAC-SHA256 短期令牌。
- 契约中不得出现真实密钥、token、租户真实数据或 PII；示例使用 `tenant-a`、`user-0001` 等占位符。
- 审计查询与导出（M03）、模型路由与配额（M04）、文档 ACL/OCR（M05）、MCP 注册与凭据托管（M07）不在本契约中定义；
  `openapi.yaml` 的 `x-riccon-deferred` 记录后置范围。

## 6. 校验

契约变更必须能通过以下检查，并在评审包中给出命令与结果：

```bash
# JSON schema 语法
jq empty contracts/events/envelope.schema.json contracts/events/outbox/*.schema.json contracts/events/sse/*.schema.json

# YAML 语法（macOS 自带 Ruby）
ruby -e "require 'yaml'; YAML.load_file('contracts/openapi.yaml'); YAML.load_file('contracts/internal-api.yaml')"
```

实现落地后，Builder 必须增加契约测试：用 OpenAPI 生成/校验请求响应，用 JSON Schema 校验 outbox 与 SSE 样例；契约测试失败不得通过 gate。

## 7. 所有者与变更流程

- 公开 REST 与事件：`riccon/architect` 起草，`riccon/product` 确认语义，`riccon/verifier` 核对实现。
- 内部 API：`riccon/architect` 与 `riccon/builder`（worker）共同维护；安全声明变更必须同步威胁模型。
- 破坏性变更：ADR + 版本升级 + 迁移说明 + 回滚方案；生产发布由用户批准。
- 任何契约变更必须同步 `platform-domain` 的常量（`EventTypes`、`AuditActions`、`PermissionCodes`）与迁移 CHECK 值。
