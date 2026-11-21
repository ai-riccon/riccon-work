# TASK-0144 隔离设计：Worker 租户级隔离替代每测试 39 表 TRUNCATE

日期：2026-11-21
Owner：riccon/builder-m19
范围：`apps/worker/src/test/**`（生产代码与迁移零改动）

## 1. 目标与约束

- 移除 `WorkerIntegrationTestBase` 每测试 39 张表的 `TRUNCATE`。
- 每个测试使用唯一租户/唯一数据；测试只清理本测试产生的全局状态。
- `audit_event` 是 append-only + 月分区，任何测试都不得 DELETE/TRUNCATE；审计断言必须带本测试租户或唯一目标 ID。
- 不减少、不跳过、不放宽任何断言；不启动新容器、不引入 schema-per-test。

## 2. 39 表分类

### 2.1 租户业务表（31 张，唯一租户隔离）

`tenant`、`app_user`、`user_credential`、`workspace`、`role`、`role_permission`、`user_role`、
`quota_limit`、`quota_usage`、`provider_credential`、`tenant_data_key`、`mcp_tool_grant`、
`file`、`file_parse`、`file_chunk`、`knowledge_document`、`knowledge_chunk`、`conversation`、
`message`、`message_citation`、`research_task`、`agent_run`、`agent_run_outbox`、
`agent_run_stage`、`model_invocation`、`model_invocation_attempt`、`mcp_invocation`、
`approval`、`artifact`、`artifact_version`、`artifact_citation`

隔离方式：

- `WorkerIntegrationTestBase.uniqueTenant(prefix)` 生成 `prefix-<10 位十六进制>`；每个测试独立。
- 测试的 SQL/断言全部带本测试租户，或按本测试生成的 UUID（runId/eventId/approvalId 等）查询。
- 数据在同一 external namespace 连续多次全量运行后仍保留；唯一租户保证主键、唯一键与列表查询不冲突。

例外与说明：

- `provider_credential` / `tenant_data_key` 的测试行归 `platform` 租户（M04 平台 key 池语义）；
  `TestDataFactory.createModelCatalog` 改为每次生成唯一 `key_id`（`dek-<credentialId>`）与唯一
  provider `code`，因此历史行不会被新 catalog 引用，也无需删除（`provider_credential` 对
  `tenant_data_key` 有复合外键）。
- `agent_run_outbox` 虽然按租户存储，但 `OutboxPoller.pollOnce` / `OutboxClaimer.claim` 是生产上
  唯一按状态跨租户读取的路径。凡使用它们的测试类在 `@BeforeEach` 调用 `clearOutbox()`（单表
  DELETE），否则 `pollOnce()==1` / `claim` 批量计数会被历史 PENDING 事件污染。

### 2.2 全局目录/种子表（7 张，按需恢复基线）

| 表                        | 分类                            | 测试是否修改             | 恢复策略                                                         |
| ------------------------- | ------------------------------- | ------------------------ | ---------------------------------------------------------------- |
| `tenant`                  | V2 种子 `platform` + 租户业务行 | 是（新增唯一租户）       | 唯一 ID；`platform` 行由 JVM 启动幂等补齐                        |
| `model_routing`           | V11 种子（CHAT/REPORT 两行）    | `updated_by` / `version` | 保留种子行；`resetModelGatewayState()` 复位 `updated_by`         |
| `model_gateway_switch`    | V11 种子（ROUTED/true/false）   | 是                       | `resetModelGatewayState()` 恢复发版种子                          |
| `model_provider`          | 全局目录                        | 是（唯一 provider）      | 不删除；旧 provider 保留但不再被 ACTIVE 模型引用                 |
| `model`                   | 全局目录                        | 是                       | `resetModelGatewayState()` 停用旧模型（`status='DISABLED'`）     |
| `model_routing_entry`     | 全局路由链                      | 是                       | `resetModelGatewayState()` 清空，测试显式重建                    |
| `mcp_server` / `mcp_tool` | 全局 MCP 目录（此处 2 张）      | 是                       | `resetMcpCatalog()` 清空；`mcp_tool_grant`/`mcp_invocation` 先删 |

说明：

- `model` 只停用、不删除：`model_invocation_attempt` 对 `model`/`model_provider` 有外键；
  删除会连带要求清理历史调用记录。停用已足够让 BASELINE/单模型解析只看到本测试新建的模型。
- V11 种子由 `WorkerIntegrationTestBase.ensureGlobalBaseline(...)` 在 JVM 启动后幂等补齐
  （`NOT EXISTS` 守卫），不覆盖测试/运维已修改的值。

### 2.3 append-only 审计（1 张）

`audit_event`：V6 建表 + 分区 + `trg_audit_event_no_update/no_delete` 触发器。

- 永不删除；测试使用唯一租户，断言按 `tenant_id` 过滤。
- 平台级事件（`model.credential.invalidated` 写入 `tenant='platform'`）无法按请求租户过滤，
  改为按本测试唯一的 `target_id`（credentialId）过滤；不依赖清空审计表。
- 同一次运行内/跨运行的历史审计行不会命中新租户或新 credentialId。

### 2.4 基础设施

- `flyway_schema_history`、`permission` 字典、一次性迁移、静态容器/热栈初始化、S3 桶创建保持原样。
- 每测试仍由 `prepareData()` 重建 `TestDataFactory`（廉价对象），不再执行任何全表清理。

## 3. 固定租户/全局计数审计结论

对全部 `apps/worker/src/test/**` 做了符号与文本交叉检索，处理如下：

- 固定租户 `tenant-a`/`tenant-b` 的数据库写入点全部改为 `uniqueTenant(...)` 或由 fixture/seed
  内部生成唯一值；非数据库的单元测试（crypto/token/chat event）保留常量。
- 全局计数断言（`auditCount`、`artifactCount`、`versionCount`、`citationCount`）全部加本测试
  租户过滤；`ModelFailoverIntegrationTest` 的平台审计按 targetId 过滤。
- `ReportArtifactIntegrationTest` 的 `SELECT research_task_id FROM artifact LIMIT 1` 改为使用
  fixture 的 taskId，避免读到其他租户产物。
- 依赖“前一个测试清库”的测试类（模型、MCP、outbox、产物）改为显式调用对应 reset helper；
  helper 注释写明了恢复的基线。

## 4. 清理例外（仍需按表 DELETE 的类）

| helper                     | 表                                                                      | 使用者                                                                                                                                                         | 理由                          |
| -------------------------- | ----------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| `clearOutbox()`            | `agent_run_outbox`                                                      | OutboxPollerTest、OutboxClaimerTest、HitlIntegrationTest、ReportArtifactIntegrationTest、FileParseServiceIntegrationTest、KnowledgeIndexServiceIntegrationTest | 生产轮询/领取按状态跨租户     |
| `resetModelGatewayState()` | `model`、`model_routing_entry`、`model_routing`、`model_gateway_switch` | ChatStream*、Hitl、ReportArtifact、Model*                                                                                                                      | 全局目录/开关，无法按租户隔离 |
| `resetMcpCatalog()`        | `mcp_tool_grant`、`mcp_invocation`、`mcp_tool`、`mcp_server`            | ChatStreamIntegrationTest、Hitl、ReportArtifact、McpGateway                                                                                                    | 全局唯一 server code / 工具名 |

这些是 P3 保留的明确例外，均为小表按需 DML，不再是 39 表全量 TRUNCATE。

## 5. 基线与结果

- P2 基线（EV-0695）：worker 4 个分片报告 tests=93+80+89+94 = **356**，累计 suite time
  91.760+105.214+134.432+113.808 = **445.214s**（注意：任务卡写的 358 与 EV-0695 的
  356 不一致；以证据报告为准，本次未删除任何用例）。
- P3 两次全量（同一 namespace `m19p3w`，第二次不 reset）：均为 **356 tests / 0 failures /
  0 errors / 0 skipped**；累计 suite time 见 EV 记录与 `$TMPDIR/m19p3w/run*/` 报告。
