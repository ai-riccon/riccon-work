# ADR-0008：M02 异步承载与可观测性

Status: accepted
Date: 2026-11-17

## Context

M02 有两条异步/长连接路径：

- **J1 通用问答**：员工提问后 3 秒内出现首字，流式输出，可中途停止（M02-AC-03/05）。
- **J2 市场调研报告**：检索 → 工具取数 → 人工审批 → 生成报告，阶段可恢复，审批可能等待很久（M02-AC-06..11）。

API 不能直接执行 Agent（ADR-0001），模型与 MCP 调用只能在 Worker。项目当前只有 MySQL、OpenSearch、对象存储，没有消息中间件；合同要求“不提前引入消息中间件”，且私有化运维成本必须低。M02 还必须满足 `traceId`、结构化日志、基础指标与失败可恢复。

## Decision

### 1. J2 任务分发：MySQL outbox + Worker 轮询（选择）

- API 在业务事务内写 `agent_run` 与 `agent_run_outbox`；事务提交即“已受理”。
- Worker 以固定间隔（默认 500 ms，带 0–200 ms 抖动）批量领取（默认 10 条）：

```sql
SELECT id FROM agent_run_outbox
 WHERE (status = 'PENDING' AND available_at <= NOW(3))
    OR (status = 'DISPATCHED' AND lease_until <= NOW(3))
 ORDER BY id
 LIMIT ?
 FOR UPDATE SKIP LOCKED;
```

领取后在同一事务内将状态置为 `DISPATCHED`，写入 `lease_until = NOW(3) + 90s` 与 `lease_owner`，提交后再执行。执行期间每 30 秒续租（同时续 `agent_run.lease_until`）；进程崩溃则租约到期后重新可领取。outbox 状态集合为 `PENDING | DISPATCHED | DONE | DEAD`，过期租约行依靠第二条 `OR` 条件回收，不依赖单独的清理任务。

恢复语义：领取到过期租约时，若 `agent_run` 为 `RUNNING` 且运行租约已过期，说明上一个 Worker 在阶段中途崩溃；领取者将 `attempt + 1`，从该阶段的起点重新执行（不从中途流恢复），并写审计。阶段边界必须幂等：M02 的 MCP 工具是只读的，重复调用可接受；写操作（M07 起）必须携带幂等键并在服务端去重。

- 事件类型（内部契约，`contracts/` M02 定稿）：
  - `riccon.agent.run.requested.v1`：开始一次任务；
  - `riccon.agent.run.resume.v1`：审批通过后恢复下一阶段；
  - `riccon.agent.run.cancel.v1`：员工取消或权限撤销。
- outbox 行包含 `event_id`（ULID，唯一）、`tenant_id`、`run_id`、`stage`、`payload`（脱敏后的任务参数）、`created_at`、`available_at`、`attempts`、`lease_until`、`lease_owner`、`status`。
- **审批不占用 Worker 租约**：任务到达审批点后写入 `WAITING_APPROVAL` 状态并结束本次执行；API 收到审批决定后写 `resume` 事件。

### 2. J1 流式回答：API → Worker 内部 HTTP/SSE

- 浏览器只连 API；API 用服务身份令牌调用 Worker 内部端点，Worker 以 SSE 返回模型增量，API 原样转发并维持租户与 trace 上下文。
- 流式路径不进 outbox：首字预算 3 秒，排队会直接破坏体验。Worker 对并发流做信号量限制（默认每实例 16）。
- 客户端断开或点击停止 → API 取消上游请求；Worker 终止模型调用并保留已生成内容（AC-03/05）。
- 流式请求空闲超时 60 秒，总时长上限可配置（默认 10 分钟）；超时以错误事件结束，已生成内容不丢失。

### 3. 状态机与幂等

`agent_run.status`：`PENDING → RUNNING → WAITING_APPROVAL → RUNNING → SUCCEEDED | FAILED | CANCELLED`，可重试阶段进入 `RETRY_WAIT`。

- 投递语义为 **at-least-once**；Worker 以条件更新领取：`UPDATE agent_run SET status='RUNNING' WHERE id=? AND status IN ('PENDING','RETRY_WAIT')`，受影响行数为 0 则视为重复消息并跳过。
- outbox `event_id` 唯一约束；重复插入返回既有记录，不产生第二次执行。
- 产物写入使用唯一键 `(tenant_id, run_id, version)`；重试沿用同一 `run_id` 与递增 `attempt`，不生成新产物（AC-11）。
- Worker 在开始执行前校验：令牌租户 = `agent_run.tenant_id`，用户仍启用，任务未取消；任一不满足 → 拒绝并写审计。

### 4. 重试与背压

| 错误类型                                            | 处理                                          |
| --------------------------------------------------- | --------------------------------------------- |
| 模型 5xx、连接重置、MCP 超时、429（带 Retry-After） | 可重试；退避 2 s → 8 s → 30 s（含抖动）       |
| 参数校验失败、审批拒绝、策略拒绝、401/403           | 不可重试；直接 `FAILED`/`CANCELLED`           |
| 审计写入失败                                        | 不可重试当前事务；任务失败，等待人工排查      |
| 超过最大尝试（默认 3 次）                           | `FAILED`，记录 `failure_code`，员工可手动重试 |

背压：

- Worker 并发由信号量限制（`riccon.worker.concurrency`，默认 8；流式另有上限）。
- 每租户活跃任务上限（默认 2），超出时 API 返回 429 `RICCON-QUEUE-429`；M04 用正式配额替换。
- 队列最老 `PENDING` 年龄超过 60 秒时，API 对新任务快速失败（429），避免无限排队；指标与告警见第 6 节。
- 轮询批量与并发均可配置，禁止无界查询。

### 5. 上下文与日志

- API 入口生成 `traceId`（ULID），写入 MDC 与响应头 `X-Trace-Id`，并放入 `ProblemDetail.traceId`；outbox payload 与内部 HTTP 头都携带。
- Worker 从令牌/载荷恢复 `traceId`、`tenantId`、`userId`、`runId`，不信任客户端提供的值。
- 结构化日志使用 Spring Boot 内置结构化输出（非本地环境 `logging.structured.format.console=ecs`）；消息用英文短句 + 键值对，例如 `agent.run.completed runId=... tenantId=... durationMs=...`。
- 禁止记录提示词、回答、文件原文、工具结果原文、凭据；需要引用时记录 hash 与长度。
- M02 不引入 OpenTelemetry collector；traceId 在 API/Worker 日志与审计中串联即可，M09 再接入分布式追踪后端。

### 6. 最小指标集（Micrometer）

| 指标                                    | 类型      | 标签              | 用途                   |
| --------------------------------------- | --------- | ----------------- | ---------------------- |
| `riccon_agent_runs_total`               | counter   | `status`          | 任务结果分布           |
| `riccon_agent_run_duration_seconds`     | timer     | `status`          | 任务耗时               |
| `riccon_agent_queue_depth`              | gauge     | 无                | 待处理任务数           |
| `riccon_agent_queue_oldest_age_seconds` | gauge     | 无                | 排队年龄，触发快速失败 |
| `riccon_model_requests_total`           | counter   | `outcome`         | 模型调用结果           |
| `riccon_model_first_token_seconds`      | histogram | `model`           | 首字延迟（性能预算）   |
| `riccon_mcp_calls_total`                | counter   | `tool`、`outcome` | MCP 调用结果           |
| `riccon_outbox_retry_total`             | counter   | `reason`          | 重试与重复投递         |
| `riccon_audit_write_failures_total`     | counter   | 无                | 期望恒为 0             |
| `riccon_tenant_access_denied_total`     | counter   | `reason`          | 跨租户探测信号         |

标签禁止包含 `tenantId`、`userId`、`runId`（高基数）；需要按租户排查时用日志与审计，不扩指标基数。健康检查：liveness 不依赖外部服务；readiness 检查数据库与 outbox 轮询线程存活，不把模型/MCP 探活塞入 readiness。

### 7. 失败语义

| 场景               | 行为                                                                                                           |
| ------------------ | -------------------------------------------------------------------------------------------------------------- |
| API 写 outbox 失败 | 业务事务回滚，返回 500，不产生“已受理”状态                                                                     |
| Worker 领取后崩溃  | 租约到期（90 s）后重新投递；RUNNING 且租约过期的运行 `attempt+1`，从阶段起点幂等重试，已完成阶段不重复产生产物 |
| 重复投递           | 条件更新失败 → 记录 `duplicate` 指标并跳过                                                                     |
| 模型/MCP 超时      | 可重试错误；重试不产生重复产物；超过上限 → `FAILED` 且保留已完成阶段                                           |
| 流式首字前失败     | 返回 `ProblemDetail`（含 traceId），不发送半截回答                                                             |
| 流式首字后失败     | 发送错误事件并结束流，已生成内容保留（AC-05），记录 `model.invocation.failed`                                  |
| 审批拒绝           | 任务终止，写 `approval.decided` 与终态；不触发生成阶段                                                         |
| 用户取消           | 取消事件优先于后续阶段；已生成内容保留，不产生新产物                                                           |
| 审计写入失败       | 业务失败（ADR-0007），不得静默继续                                                                             |

## Consequences

- 正面：不新增中间件；outbox 与业务同事务，任务不会“业务成功但未入队”；轮询延迟（≤1 s）对 J2 可接受，J1 走直连 SSE 保住首字预算。
- 负面：数据库承担队列职责，高吞吐时轮询与锁竞争需要调优；outbox 表需要清理与监控；至少一次语义要求消费端严格幂等。
- 约束：Worker 不得直接面向浏览器；API 与 Worker 的契约必须版本化；新增阶段必须扩展状态机与幂等键，不得绕过 outbox 直写任务状态。

## Alternatives

- **Redis Stream**：延迟低、消费组成熟，但引入新的有状态服务（持久化、内存、升级、监控），且无法与 MySQL 业务事务原子提交；私有化交付与 M09 运维成本明显增加。仅当队列吞吐或延迟超出预算时（M04 评估）再引入。
- **Kafka/RabbitMQ**：吞吐与解耦更强，但同样需要新基础设施与运维能力，M02 过度设计。
- **纯同步 HTTP（API 阻塞等待 Worker）**：审批可能等待数小时，连接与线程不可持续；崩溃后任务丢失。
- **Quartz/数据库定时任务**：适合周期任务，不适合按事件分发；仍需自建领取与重试语义，复杂度不低于 outbox。
- **文件/日志队列**：不可靠、不可查询、无法保证幂等。

## Evidence

- `contracts/` 中 `riccon.agent.run.requested.v1`、`resume.v1`、`cancel.v1`（M02 建立）
- 迁移：`agent_run`、`agent_run_outbox`（M02，`apps/api/src/main/resources/db/migration`）
- 测试（M02 建立）：`IT-RUN-001` 重复投递不重复执行、`IT-RUN-002` 每租户并发上限与 429、`IT-RUN-003` 崩溃后租约恢复、`IT-SVC-001..003` 服务身份与租户绑定、`IT-OBS-001` traceId 贯穿
- 指标清单与 `PERF-001`（`k6 run e2e/perf/mvc-smoke.js`，`RICCON_FAKE_MODEL=1`）
- `docs/security/THREAT-MODEL.md` TM-17、TM-18、B-10

## 澄清（不改本 ADR 决策）

- 2026-11-17：`event_id`/运行 ID 的数据库表示由 ADR-0012 统一为 UUIDv7 `BINARY(16)`；outbox 领取索引与跨租户例外见 ADR-0016。本文的事件类型、状态机与幂等语义不变。
