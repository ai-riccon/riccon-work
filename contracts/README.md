# 契约

本目录是 Riccon 跨部署物、跨模块的公开契约唯一事实源：REST API、内部 Worker API、异步事件与流式事件。实现与契约必须在同一变更中更新；契约冲突时以本目录和 ADR 为准，不允许“代码先跑、文档后补”。

当前版本：`1.6.0-m06`（M06 编排 Studio：流程定义/草稿/版本、手动运行、节点时间线、审批节点、运行历史/重试/取消、内置模板与运行限制）。上一版本 `1.5.0-m05b`（M05b 知识库连接器：S3 兼容目录、凭据托管、连接测试、手动/固定周期同步、同步条目），再上一版本 `1.4.0-m05`（M05 知识库纵深：Excel/OCR、文档级 ACL、下载/引用/删除/重索引）、`1.3.0-m04`（M04 模型网关纵深）与 `1.2.0-m10`；同一 `/api/v1` 内只做向后兼容增量。

**M05 交付（`1.4.0-m05`）**：文档级 ACL（`PUT/GET /api/v1/knowledge/documents/{documentId}/acl`）、软删除（`DELETE /api/v1/knowledge/documents/{documentId}`）、服务端流式下载（`GET .../download`）、重索引（`POST .../reindex?force=`）；`KnowledgeDocument` 增加可见范围/解析/保留字段；`Citation`/`ArtifactCitation` 增加 `bbox`/`ocrConfidence`/`sourceStatus`。M04 单模块 48h 与 18 条 AC 的交付口径保持不变，历史见 `docs/PRODUCT.md` §10。

**M05b 交付（`1.5.0-m05b`）**：S3 兼容目录连接器列表/创建/更新/软删除（`GET/POST /api/v1/knowledge/connectors`、`PATCH/DELETE .../{connectorId}`）、凭据只写不读与掩码（`PUT .../credential`）、连接测试（`POST .../test`）、手动同步 202（`POST .../sync`，并发 409）与同步条目分页（`GET .../items`）；`KnowledgeDocument` 增加 `connectorId`/`externalId`，`sourceType` 增加 `CONNECTOR`；新增 `Connector`、`ConnectorCreate/Update`、`ConnectorCredential`、`ConnectorItemPage` 等 schema。连接器导入文档复用 M05 文档 ACL、下载与引用遮蔽语义，不新增第二套文档权限。

**M06 交付（`1.6.0-m06`）**：流程定义/草稿（`GET/POST /api/v1/workflows`、`GET /workflows/{id}`、`PUT .../draft`、`POST .../duplicate`、`POST .../validate`）、发布版本（`POST .../publish`、`GET .../versions`）、停用/启用/移交（`POST .../disable`、`.../enable`、`.../transfer`）、运行（`POST/GET .../runs`、`GET /workflow-runs`、`GET /workflow-runs/{id}`、`.../cancel`、`.../retry`）、审批（`GET /workflow-approvals`、`GET .../{id}`、`POST .../decision`）、模板（`GET /workflow-templates`、`POST .../{templateId}/copy`）与运行限制（`GET /workflows/runtime-limits`）。新增 `WorkflowGraph`/`WorkflowDefinition`/`WorkflowVersion`/`WorkflowRun`/`WorkflowNodeRun`/`WorkflowApproval`/`WorkflowTemplate`/`WorkflowRuntimeLimits` 等 schema；M06 不使用 outbox/事件，`workflow_run` 自身为持久运行队列。节点/算子/占位符为封闭白名单，不支持代码/脚本/任意 HTTP/并行/循环/子流程/定时触发；运行详情轮询，不新增 SSE；M06b 模板共享/升级字段标 `x-riccon-deferred: M06b`。`contracts/internal-api.yaml` 不变（工作流通过 `workflow_run` 队列轮询触发，Worker 不新增入站端点）。

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

| 事件                                  | 幂等键                                                                                                 |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `riccon.file.parse.requested.v1`      | `file:{fileId}:parse:{parseId}`                                                                        |
| `riccon.knowledge.index.requested.v1` | `doc:{documentId}:index:{attempt}`                                                                     |
| `riccon.agent.run.requested.v1`       | `run:{runId}:requested:{attempt}`                                                                      |
| `riccon.agent.run.resume.v1`          | `run:{runId}:resume:{approvalId}`                                                                      |
| `riccon.agent.run.cancel.v1`          | `run:{runId}:cancel`                                                                                   |

### 3.3 SSE

- `chat-event.v1`：Worker 产生、API 转发；事件类型见 `EventTypes` 常量。
- `run-progress.v1`：API 根据 `agent_run`、`agent_run_stage`、`approval` 的权威状态派生。
- 每帧格式 `event: <eventType>` + `data: <JSON>` + 空行；`Cache-Control: no-store`。
- 流式事件不是审计证据；审计仍以 `audit_event` 为准。

## 4. REST 约定

- 认证：会话 Cookie `RICCON_SESSION`（HttpOnly、SameSite=Lax、非本地 Secure）；不使用 JWT。
- OIDC：每租户 1 个 IdP，授权码 + PKCE + nonce；授权入口/回调是顶层 302 导航；不保存或回传 access/refresh/ID token。登出按 DEC-0017 A：本地先失效，配置 end-session 时自动顶层导航结束 IdP 会话（ADR-0019）。
- CSRF：所有非安全方法带 `X-CSRF-TOKEN`；令牌来自 `GET /api/v1/auth/csrf` 或登录响应。M04 的 10 个写端点（provider PUT、model PATCH、routing PUT、credential POST/rotate/disable/enable、switches PUT、tenant/user quota PUT）全部显式声明 `CsrfToken`；M05/M05b 的 ACL、删除、重索引、连接器创建/更新/删除/凭据/测试/同步同样全部显式声明 `CsrfToken`。
- 租户：只从会话主体派生；忽略请求体/查询/请求头中的 `tenantId`。
- 分页：`page` 从 1 开始，`size` 默认 20、最大 100；禁止无界查询。审计查询另有 `page ≤ 200`、时间跨度 ≤ 180 天。
- 错误：RFC 9457 `application/problem+json`，至少 `type/title/status/code/traceId`；
  跨租户与跨部门对象统一 404 `RICCON-TENANT-404`；部门/空间成员冲突使用 `RICCON-DEPT-409`；OIDC 绑定冲突使用 `RICCON-OIDC-409`；审计查询/导出使用 `RICCON-AUDIT-*`；模型配置使用 `RICCON-MODEL-400/409`；配额使用 `RICCON-QUOTA-429/503`；成本查询使用 `RICCON-COST-400/503`；知识库 ACL/下载/重索引/连接器使用 `RICCON-KB-400/404/409/502/503`（无源文件 `RICCON-KB-404` 只在 ACL 通过后出现，不替代不可见时的 `RICCON-TENANT-404`；源文件读取失败为 `RICCON-KB-502`；连接器跨租户统一 `RICCON-TENANT-404`，同租户非管理员 `RICCON-AUTH-403`）。429 用 `code` 判别：`RICCON-QUOTA-429` → `QuotaExceededProblem`；`RICCON-RATE-429`/`RICCON-QUEUE-429` → `RateLimitedProblem`。
- 下载：工作空间业务文件由服务端校验归属后签发预签名 URL，TTL ≤ 300 秒；URL 绑定对象 key。知识库文档下载（M05）是例外：`GET /api/v1/knowledge/documents/{documentId}/download` 每次请求重判文档 ACL，由 API 流式代理返回，`Content-Type` 为文档 `mimeType`，`Content-Disposition` 使用 `filename*=UTF-8''`（RFC 5987），不返回对象 key、预签名 URL 或存储端点；对象存储读取失败返回 502 `RICCON-KB-502`，响应不含桶名/key/存储端点；原工作空间路径仍只按空间谓词判定。审计导出是另一例外：CSV 由 API 流式生成，不写对象存储、无预签名 URL，只有申请人可在批准有效期内下载；平台租户自身导出 M10 不支持（403）。
- 幂等：`Idempotency-Key` 用于创建报告任务、审批决定、重试、工作流创建/发布/取消/重试/审批决定；同一主体重复请求返回既有结果。审计导出审批使用状态条件更新，不做幂等重放。模型路由/开关使用 `version` 乐观锁；凭据新增/轮换不承诺 `Idempotency-Key`，由 HMAC fingerprint 唯一键与状态机兜底。连接器创建/立即同步可选 `Idempotency-Key`；连接器凭据 `PUT` 为覆盖写（轮换），不做幂等重放。
- 模型网关：端点只来自部署允许列表，没有端点创建 API；`PUT /api/v1/admin/model-providers/{providerId}` 只改展示名，`baseUrl`/`environmentLabel`/`protocol` 写入返回 400；`apiKey` 只进不出，响应只返回 `maskedHint`；路由/开关保存后下一次请求生效。员工 `/models` 使用 `EmployeeModel`（无价格字段）；平台用 `AdminModel`。
- 配额/成本：配额按请求数、租户+用户、日+月、`Asia/Shanghai`；超限用 `QuotaExceededProblem`（`RICCON-QUOTA-429`）；成本金额用 6 位小数字符串。平台 `PlatformCostReport` **不含**用户字段，租户 `TenantCostReport` 含用户明细；`unbucketedCalls` 显式呈现 `cost_day NULL`。

### 4.1 连接器（M05b）

| 方法   | 路径                                                    | 权限                         | 说明                                                                                   |
| ------ | ------------------------------------------------------- | ---------------------------- | -------------------------------------------------------------------------------------- |
| GET    | `/api/v1/knowledge/connectors`                          | `knowledge.connector.read`   | 分页列表；返回配置（含 `bucket`/`prefix`），凭据仅掩码，不含端点 URL/对象 key          |
| POST   | `/api/v1/knowledge/connectors`                          | `knowledge.connector.manage` | 创建；必须显式 `defaultVisibility`，默认 `PAUSED`，凭据可选写入；同名 409              |
| PATCH  | `/api/v1/knowledge/connectors/{connectorId}`            | 同上                         | 部分更新配置/可见范围/端点/`scheduleEnabled`/`status`；不支持改 type 或自定义 cron     |
| DELETE | `/api/v1/knowledge/connectors/{connectorId}`            | 同上                         | 软删除；`documents=KEEP`（默认）/`SOFT_DELETE`；凭据密文 30 天后由 M09 置空            |
| PUT    | `/api/v1/knowledge/connectors/{connectorId}/credential` | 同上                         | 只写不读；响应只返回 `credentialMaskedHint`，轮换为覆盖写入                            |
| POST   | `/api/v1/knowledge/connectors/{connectorId}/test`       | 同上                         | 连接测试（`ListObjectsV2`、`max-keys=1`），中文分类原因；失败仍 200 且 `success=false` |
| POST   | `/api/v1/knowledge/connectors/{connectorId}/sync`       | 同上                         | 202 受理；并发同步 409；实际同步由 Worker heavy 通道执行                               |
| GET    | `/api/v1/knowledge/connectors/{connectorId}/items`      | `knowledge.connector.read`   | 分页同步条目；无原文、无 bucket/端点；`externalId` 只显示末段                          |

- 错误码：`RICCON-KB-400`（可见范围/端点/配置/凭据非法）、`RICCON-TENANT-404`（跨租户或不存在）、`RICCON-AUTH-403`（同租户非管理员）、`RICCON-KB-409`（同名、并发同步或状态冲突）、`RICCON-KB-503`（连接器功能未启用或同步/测试服务不可用）。
- 调度：只支持“立即同步 + 部署固定周期 + `scheduleEnabled`”，周期由 `RICCON_CONNECTOR_SYNC_INTERVAL_MINUTES` 计算，不存 per-connector cron，也没有独立调度端点。
- 同步状态：`status`（`ACTIVE`/`PAUSED`/`ERROR`）、`lastSyncStatus`（`SUCCESS`/`PARTIAL`/`ERROR`）、`lastSyncComplete`、`lastSyncSummary` 与 `lastErrorCode` 由连接器详情返回；只有 `lastSyncComplete=true` 才传播源端删除。
- 数据边界：API 响应向有权限的管理员返回连接器配置（含 `config.bucket`/`config.prefix`），凭据只返回 `credentialMaskedHint`，不返回明文/密文/DEK；只返回 `endpointId`，不返回端点 URL 或对象 key。日志不记 bucket 全名、`externalId`、对象 key 或凭据；审计 details 只记 `bucket`/`prefix` 摘要（`prefix` 截断 ≤200），不记凭据、正文或完整 `externalId`。连接器导入文档的 `sourceType=CONNECTOR`，`fileId` 为空、`connectorId`/`externalId` 非空。

### 4.2 编排 Studio（M06）

| 方法     | 路径                                                     | 权限                              | 说明                                                          |
| -------- | -------------------------------------------------------- | --------------------------------- | ------------------------------------------------------------- |
| GET/POST | `/api/v1/workflows`                                      | `workflow.read` / `workflow.edit` | 列表（`view=MINE/RUNNABLE/ALL`）/ 新建草稿或从模板复制        |
| GET      | `/api/v1/workflows/runtime-limits`                       | `workflow.read`                   | 生效限制、保留、开关；租户只读；字面路径优先于 `{workflowId}` |
| GET      | `/api/v1/workflows/{workflowId}`                         | `workflow.read`                   | 详情 + `draftGraph`（仅所有者/租户管理员）                    |
| PUT      | `/api/v1/workflows/{workflowId}/draft`                   | `workflow.edit`                   | 保存草稿；`version` 乐观锁，冲突 409                          |
| POST     | `/api/v1/workflows/{workflowId}/duplicate`               | `workflow.edit`                   | 另存副本                                                      |
| POST     | `/api/v1/workflows/{workflowId}/validate`                | `workflow.edit`                   | 逐项校验问题                                                  |
| POST     | `/api/v1/workflows/{workflowId}/publish`                 | `workflow.publish`                | 发布不可变版本；`runScope` 受角色/部门限制                    |
| GET      | `/api/v1/workflows/{workflowId}/versions`                | `workflow.read`                   | 版本列表                                                      |
| POST     | `/api/v1/workflows/{workflowId}/disable`、`/enable`      | `workflow.publish`                | 停用/启用；审计前后状态                                       |
| POST     | `/api/v1/workflows/{workflowId}/transfer`                | `workflow.manage`                 | 移交所有权（仅租户管理员）                                    |
| POST/GET | `/api/v1/workflows/{workflowId}/runs`                    | `workflow.run` / `workflow.read`  | 创建运行 / 该流程运行列表                                     |
| GET      | `/api/v1/workflow-runs`                                  | `workflow.read`                   | 运行历史（流程/版本/状态/时间/发起人过滤）                    |
| GET      | `/api/v1/workflow-runs/{workflowRunId}`                  | `workflow.read`                   | 详情：节点时间线、审批、引用、结果                            |
| POST     | `/api/v1/workflow-runs/{workflowRunId}/cancel`、`/retry` | `workflow.run`                    | 取消 / 重试失败节点                                           |
| GET      | `/api/v1/workflow-approvals`、`/{id}`                    | `workflow.approve`                | 待办（仅分配给本人）/ 审批详情                                |
| POST     | `/api/v1/workflow-approvals/{id}/decision`               | `workflow.approve`                | 批准/拒绝；自批 403；拒绝理由必填                             |
| GET      | `/api/v1/workflow-templates`                             | `workflow.read`                   | 内置模板列表（M06 至少 1 个）                                 |
| POST     | `/api/v1/workflow-templates/{templateId}/copy`           | `workflow.edit`                   | 复制为当前用户草稿                                            |

- 图约束：1 个 `START`、≥1 个 `END`、每个节点入边 ≤1、`CONDITION` 出边恰好 `TRUE/FALSE`、无环/无孤立、节点 ≤50、边 ≤100；保存/发布/运行三处校验。
- 版本：发布不可变；运行绑定版本；`runScope` 为版本属性，变更需发布新版本；旧版本仍可运行，除非流程停用。
- 审批：指定 `USER` 或 `ROLE`（`TENANT_ADMIN`/`WORKFLOW_EDITOR`/`MEMBER`）；发起人不得审批；无可用审批人保持等待；写工具/高风险工具必须前置审批，重试重新审批；审批摘要按审批人 ACL 最小可见。
- 运行：默认 180 天保留、到期不可见、无在线删除；节点/步数/超时/并发/5 MiB 限制由部署配置；超限 `RICCON-WF-429` 或 `WF_*` 稳定码。
- 错误：`RICCON-WF-400`（图/参数/校验/无审批人）、`RICCON-WF-409`（草稿版本冲突/审批已决定/状态冲突）、`RICCON-WF-429`（并发上限）、`RICCON-WF-503`（编排关闭/引擎不可用）；跨租户/跨部门/不可见仍 `RICCON-TENANT-404`；同租户无能力 `RICCON-AUTH-403`；平台管理员 404。
- 轮询：运行详情/历史按 `updatedAt` 轮询，不新增 SSE；事件只用于 API → Worker。
- M06b：用户另存模板、租户内共享、升级提示、模板库写端点标 `x-riccon-deferred: M06b`；M06 只返回内置模板与只读 `source/visibility/builtInVersion/latestBuiltInVersion/upgradeAvailable` 字段。

## 5. 安全边界

- `openapi.yaml` 只描述浏览器/租户网络可达的 `/api/v1`；`internal-api.yaml` 只描述集群内部可达的 `/internal/v1`。
- 平台管理员的租户部门管理使用 `/api/v1/admin/tenants/{tenantId}/departments`，权限 `platform.department.manage`；只返回组织元数据，不返回目标租户业务内容。
- 平台管理员的审计导出审批使用 `/api/v1/admin/tenants/{tenantId}/audit-exports`，权限 `platform.audit.export.approve`；仅单管理员租户（ACTIVE `TENANT_ADMIN` ≤1），多管理员 409；只返回审批元数据，不返回审计内容、不提供下载。
- 模型网关：`platform.model.manage` 写路由/模型/key/开关；`platform.model.read` 读目录；`quota.read`/`cost.read` 租户只读；`platform.quota.manage`/`platform.cost.read` 平台；员工只有 `model.read`。平台成本/配额端点不返回用户明细；租户端点不接受 `tenantId`。
- Worker 不接受浏览器 Cookie，不提供通用 CRUD 或管理接口；内部调用使用 HMAC-SHA256 短期令牌。
- 契约中不得出现真实密钥、token、租户真实数据或 PII；示例使用 `tenant-a`、`user-0001` 等占位符。
- 审计哈希链、MFA、平台跨租户审计明细、租户 BYO 模型凭据、模型价格编辑、多币种/账单对账、MCP 注册与凭据托管（M07）、引用页图渲染/框选、物理对象与分块清理（M09）、代码执行/脚本/任意 HTTP/并行/循环/子流程/定时触发、跨租户模板市场不在本契约中定义；
  `openapi.yaml` 的 `x-riccon-deferred` 记录后置范围。M05 已交付文档级 ACL/OCR 引用定位，`knowledge-acl-ocr` deferred 已移除；M05b 已交付连接器契约（`1.5.0-m05b`），不存在 `knowledge-connectors` deferred；M06 已交付内置模板列表/复制，`workflow-template-sharing`（用户另存/租户共享/升级提示）标 M06b，`workflow-code-execution` 标 not planned。
- 知识库 ACL：`knowledge.read` 控制入口，`knowledge.acl.manage`（当前仅 `TENANT_ADMIN`）控制 ACL/删除/重索引；创建人是服务层资源例外，不是角色授权。不可见/跨租户统一 404，已可见但无管理能力 403；租户管理员绕过 ACL 的管理性读取写 `knowledge.document.admin_read` 审计；平台管理员不读取租户文档内容。
- 编排 Studio：`workflow.read/edit/publish/run/approve/manage` 分离；`WORKFLOW_EDITOR` 由租户管理员经既有角色管理授予/撤销；部门负责人关系只限制 `DEPARTMENT` 发布范围，不授权。跨租户/跨部门/私有草稿/私有模板/他人运行详情统一 404；平台管理员访问租户工作流统一 404；自批 403 并审计；审批参数只存脱敏摘要 + 密文，运行详情不返回完整 prompt/工具参数/凭据/正文；运行结果默认 180 天到期不可见，物理清理归 M09。
- 连接器：`knowledge.connector.read` 控制列表/条目，`knowledge.connector.manage` 控制创建/更新/删除/凭据/测试/同步；当前仅 `TENANT_ADMIN` 持有。端点只来自部署允许列表，连接器只存 `endpointId`，不能提交任意 URL、查询串或重定向。API 响应只向有权限的管理员返回配置（含 `bucket`/`prefix`）与 `credentialMaskedHint`，不返回凭据明文/密文/DEK、端点 URL 或对象 key；日志不记 bucket 全名、`externalId`、对象 key 或凭据；审计 details 只记 `bucket`/`prefix` 摘要（`prefix` ≤200），不记凭据、正文或完整 `externalId`；跨租户 `connectorId` 统一 404，同租户非管理员 403。

## 6. 校验

契约变更必须能通过以下检查，并在评审包中给出命令与结果：

```bash
# JSON schema 语法
jq empty contracts/events/envelope.schema.json contracts/events/outbox/*.schema.json contracts/events/sse/*.schema.json

# YAML 语法（macOS 自带 Ruby）
ruby -e "require 'yaml'; YAML.load_file('contracts/openapi.yaml'); YAML.load_file('contracts/internal-api.yaml')"
```

实现落地后，Builder 必须增加契约测试：用 OpenAPI 生成/校验请求响应，用 JSON Schema 校验 outbox 与 SSE 样例；契约测试失败不得通过 gate。

M04 契约测试位置（B2 交付）：`apps/api/src/test/java/com/riccon/api/contract/ModelContract108aTest`（模型/路由/凭据/开关 schema）与 `ModelContract108bTest`（配额/成本 schema）；三个模型端点的运行时响应形状由 `apps/api/src/test/java/com/riccon/api/model/catalog/ModelCatalogApiTest` 断言。

M05 契约测试位置（B2 交付）：`apps/api/src/test/java/com/riccon/api/contract/KnowledgeContractM05Test`，每个 M05 operationId 一个用例（`putKnowledgeDocumentAcl`/`getKnowledgeDocumentAcl`/`deleteKnowledgeDocument`/`downloadKnowledgeDocument`/`reindexKnowledgeDocument`、`getKnowledgeDocument`/`createKnowledgeDocument`/`listKnowledgeDocuments` 的 schema 与文案），并校验 `Citation`/`ArtifactCitation` 的 `bbox`/`ocrConfidence`/`sourceStatus`。

M05b 契约测试位置（B2b 交付）：`apps/api/src/test/java/com/riccon/api/contract/ConnectorContractM05bTest`，8 个连接器 operationId 各一个用例（`listConnectors`/`createConnector`/`updateConnector`/`deleteConnector`/`putConnectorCredential`/`testConnector`/`syncConnector`/`listConnectorItems`），并校验 `Connector`/`ConnectorCreate`/`ConnectorUpdate`/`ConnectorCredential`/`ConnectorItemPage`/`KnowledgeDocument` 连接器字段。运行命令：

```bash
./mvnw -q -T1C -pl apps/api -am test -Dtest='*Contract*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

M06 契约测试位置（T-M06-01 交付）：`apps/api/src/test/java/com/riccon/api/contract/WorkflowContractM06Test`，覆盖 `listWorkflows`/`createWorkflow`/`getWorkflowRuntimeLimits`/`getWorkflow`/`saveWorkflowDraft`/`duplicateWorkflow`/`validateWorkflow`/`publishWorkflow`/`listWorkflowVersions`/`disableWorkflow`/`enableWorkflow`/`transferWorkflow`/`listWorkflowRunsForWorkflow`/`createWorkflowRun`/`listWorkflowRuns`/`getWorkflowRun`/`cancelWorkflowRun`/`retryWorkflowRunNode`/`listWorkflowApprovals`/`getWorkflowApproval`/`decideWorkflowApproval`/`listWorkflowTemplates`/`copyWorkflowTemplate` 的 schema、枚举、错误码与 `x-riccon-permission`。

## 7. 所有者与变更流程

- 公开 REST 与事件：`riccon/architect` 起草，`riccon/product` 确认语义，`riccon/verifier` 核对实现。M05 实现期 `contracts/openapi.yaml` 与 `contracts/README.md` 由 **M05-B2 独占写入**（`1.4.0-m05`：ACL/下载/删除/重索引/解析/引用字段与 `KnowledgeContractM05Test`）；M05b 的连接器契约 `1.5.0-m05b` 由 **M05b-B2b 独占写入**（连接器 CRUD/凭据/test/sync/items 与 `ConnectorContractM05bTest`），B2b 完成后其他任务只读或向 B2b 提变更请求。M06 的编排契约 `1.6.0-m06` 由 **T-M06-01 独占写入**（工作流端点/schema 与 `WorkflowContractM06Test`），其他任务只读或向 T-M06-01 提变更请求；M06b 的 `1.7.0-m06b` 由 M26-B1 独占写入。
- **M06 实现边界**：`apps/api/src/main/java/com/riccon/api/workflow/**` 与 `apps/worker/src/main/java/com/riccon/worker/workflow/**` 必须按本契约的路径、权限、错误码、枚举与 schema 落地；不得把 `workflow-template-sharing` 标为已实现或返回伪造成功；运行详情轮询，不新增 SSE。契约与实现冲突时以 `contracts/openapi.yaml` 和 ADR-0024 为准，由 T-M06-01 评估变更。
- **M05b-B6 实现边界**：B6 只实现 `apps/api/src/main/java/com/riccon/api/knowledge/connector/**` 与 `apps/worker/src/main/java/**/connector/**` 的端点/同步/凭据逻辑，必须按本契约的路径、权限、错误码与 schema 落地；不得修改 `contracts/**`，不得继续把连接器标为 deferred 或返回 404 占位。契约与实现冲突时以 `contracts/openapi.yaml` 和 ADR-0023 §7.4 为准，由 B2b 评估变更。
- 内部 API：`riccon/architect` 与 `riccon/builder`（worker）共同维护；安全声明变更必须同步威胁模型。
- 破坏性变更：ADR + 版本升级 + 迁移说明 + 回滚方案；生产发布由用户批准。
- 任何契约变更必须同步 `platform-domain` 的常量（`EventTypes`、`AuditActions`、`PermissionCodes`）与迁移 CHECK 值。
