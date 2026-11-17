# ADR-0016：Worker outbox 跨租户领取例外

Status: accepted
Date: 2026-11-17
关联：ADR-0003（租户上下文）、ADR-0008（outbox）、ADR-0009（租户隔离）、ADR-0012（数据模型）、TEN-014/017

## Context

ADR-0009 规定：所有业务查询必须带 `tenant_id`，跨租户读取只允许在 `..admin..` 的 `PlatformJdbc`，且 M02 不提供跨租户业务查询。但 Worker 的职责是领取所有租户的待处理任务：它必须在不知道下一个任务属于哪个租户的情况下，先按状态与可用时间领取 outbox，再从领取结果中读取 `tenant_id`、打开租户上下文并校验服务令牌。

如果强制 outbox 领取先有 `tenant_id`，Worker 只能轮询每个租户，租户数增长后不可行；如果让 Worker 使用 `PlatformJdbc`，会把“平台管理员跨租户读取”与“任务分发”混为一谈，扩大高权限接口。需要为这一条路径给出明确、最小、可测试的例外。

## Decision

### 1. 例外范围

- 只有 `apps/worker` 的 outbox 领取组件可以跨租户读取 `agent_run_outbox`。
- 只允许读取以下列：`id`、`tenant_id`、`run_id`、`event_type`、`event_version`、`idempotency_key`、`payload`、`attempts`、`available_at`、`lease_until`、`status`。
- 不允许读取会话、文件、消息、知识库、产物、审计等业务内容；不允许更新除 outbox 状态与租约之外的跨租户数据。
- 不允许通过该路径创建、删除或枚举业务对象。

### 2. 领取流程

```text
OutboxClaimer（worker，..persistence..）
  1. SELECT id FROM agent_run_outbox
      WHERE (status='PENDING' AND available_at <= NOW(6))
         OR (status='DISPATCHED' AND lease_until <= NOW(6))
      ORDER BY id
      LIMIT ?
      FOR UPDATE SKIP LOCKED;
  2. UPDATE agent_run_outbox
        SET status='DISPATCHED', attempts=attempts+1,
            lease_until=NOW(6)+INTERVAL 90 SECOND, lease_owner=?
      WHERE id IN (...);
  3. COMMIT;
  4. 逐条读取 outbox 行，校验服务令牌声明的 tenantId 与 outbox.tenant_id 一致；
  5. TenantContext.open(tenantId) + 校验 agent_run.tenant_id/用户状态/未取消；
  6. 执行任务；状态、阶段、审计全部在租户作用域内写入。
```

- 第 1 步查询必须使用 `FOR UPDATE SKIP LOCKED`，避免多 Worker 抢同一行。
- `ORDER BY id` 依赖 UUIDv7 时间有序；不按 `created_at` 排序，避免索引回表。
- 领取失败/进程崩溃：`DISPATCHED` 且 `lease_until` 过期后由同一查询回收；`attempts` 递增，超过上限置 `DEAD`。
- 第 4 步的令牌校验是强制的：outbox 行的 `tenant_id` 不能被当作可信输入；必须与 HMAC 令牌的 `tenantId`、`agent_run.tenant_id` 三方一致（TEN-014/TEN-017）。
- 第 5 步之后所有业务读写回到 `TenantJdbc`，不得继续使用 OutboxClaimer 的跨租户连接。

### 3. 代码与规则

- `OutboxClaimer` 位于 `apps/worker/src/main/java/com/riccon/worker/run/persistence/`，只依赖 `JdbcClient`（或 `JdbcTemplate`）与平台词汇。
- `JdbcClient` 仍只允许在 `..persistence..`（ArchUnit 规则不变）；`OutboxClaimer` 不暴露给 controller/service 之外的包。
- 禁止把 `OutboxClaimer` 命名为 `PlatformJdbc` 或放入 `..admin..`；它是任务分发基础设施，不是管理查询。
- 指标：`riccon_outbox_retry_total{reason}`、`riccon_agent_queue_depth`、`riccon_agent_queue_oldest_age_seconds`；日志只输出 `runId/tenantId/attempts`，不输出 payload 原文。
- 审计：领取本身不写业务审计；任务状态迁移与关键动作写 `audit_event`。跨租户校验失败写 `outcome=denied` 审计（若租户无法确定，写 `platform` 租户、actor=service）。

### 4. 索引与数据最小化

- `agent_run_outbox` 领取索引为 `(status, available_at, lease_until)`，故意不以 `tenant_id` 开头；这是本 ADR 的例外。
- outbox 行只存任务参数（runId、taskId、stage、attempt），不存提示词、文件原文、知识库原文、工具结果或凭据。
- outbox 行保留期与 `agent_run` 一致；清理任务按 `status='DONE'` 且 `created_at` 超过保留期执行，必须带 `tenant_id` 或按分区，不得无条件全表删除。

## Consequences

- 正面：Worker 可以单队列领取，租户数增长不影响轮询复杂度；跨租户读取面被限制在一张只含任务元数据的表；令牌与 run 记录双重校验可测试。
- 负面：这是一个明确的租户隔离例外，需要评审与测试持续守卫；如果未来 outbox 增加业务字段，必须重新评估。
- 约束：任何新增跨租户查询都必须写 ADR；`OutboxClaimer` 不得读取业务表；不得把 outbox 当作跨租户数据总线传输敏感内容；`agent_run_outbox` 的字段变更必须同步本 ADR。

## Alternatives

- **每租户一个轮询器**：租户数增长后连接/查询线性增长；拒绝。
- **使用 `PlatformJdbc` 领取**：把任务分发与管理员跨租户读取混在一起，权限过大；拒绝。
- **让 API 推送任务到 Worker**：需要 Worker 入站服务与队列，增加攻击面；M02 不引入。
- **Redis/Kafka**：ADR-0008 已拒绝；M02 不引入中间件。
- **按租户分表/分库**：迁移与运维成本高；拒绝。

## Evidence

- `apps/api/src/main/resources/db/migration/V4__research_runs_and_artifacts.sql` 的 `agent_run_outbox` 与 `idx_outbox_claim`
- `platform/platform-architecture-tests` 的 `jdbcClientOnlyLivesInPersistence`、`tenantJdbcLivesInPersistencePackage`
- 测试（Builder 实现）：`IT-RUN-001` 重复投递、`IT-RUN-003` 租约恢复、`TEN-014`/`TEN-017` 令牌与租户绑定
- `docs/security/THREAT-MODEL.md` TM-17、TM-21、I-03-1
