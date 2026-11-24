# ADR-0024：M06 编排 Studio（工作流定义、版本、串行执行与审批节点）

Status: proposed
Date: 2026-11-25
Reviewers: `riccon/lead`（范围/合同一致性、回滚口径）、`riccon/verifier`（权限矩阵、审批边界、资源上限、可测性）、`riccon/ops`（并发/租约/开关/回滚/指标）、安全评审（提示注入、工具审批、跨租户/跨部门、审计白名单）
Approved-by: DEC-0046=A（2026-11-25）已批准 `docs/PRODUCT.md` §12 的 25 条 AC、non-goals、角色模型与 16 条 OQ 默认值，并批准拆分 M06 120h + M06b/M26 48h；M06 先做，M06b 在 M06 用户验收后启动。后续 DEC-0047=A（2026-11-25）已确认 AC-23 回滚口径；DEC-0048=A（2026-11-25）已确认工作流配额按运行准入 1 单位。ADR 本身仍为 `proposed`，待 Lead/Verifier/Ops/安全设计评审。
Review-date: 待定（M06 gate 前完成 Lead/Verifier/Ops/安全评审；M06b 另在 M06b gate 前复核）
Supersedes: none
Scope: M06「编排 Studio 核心」的流程定义/版本、串行执行 + 条件分支、审批节点、运行历史/重试/取消、权限/审计/资源限制/回滚；并给出 M06b/M26「模板库与共享」的前向兼容边界
关联: `docs/PRODUCT.md` §12、`docs/ARCHITECTURE.md`、`docs/security/THREAT-MODEL.md`、ADR-0006（信封加密）、ADR-0007（审计）、ADR-0008（outbox）、ADR-0009（租户隔离）、ADR-0010（Flyway）、ADR-0012（数据模型）、ADR-0014（契约/SSE）、ADR-0016（outbox 跨租户领取）、ADR-0017（审批参数加密）、ADR-0018（部门范围）、ADR-0020（审计查询）、ADR-0021/0022（模型网关/配额）、ADR-0023（知识库 ACL）、`contracts/README.md`

> 状态口径：`proposed` 只表示架构草案已写出。DEC-0046=A 已答复的范围、拆分与 OQ 默认值按本 ADR 记录；DEC-0047=A/0048=A 已确认回滚与配额口径；本 ADR 不修改 `docs/PRODUCT.md`，§14 只是对 Product §12 AC 的设计映射，AC 以 Product 定稿为准。本文不含生产源码、测试实现或迁移 SQL；V15 只给结构顺序与回滚约束，SQL 由 Builder 在任务内编写。

---

## 1. Context

### 1.1 现状事实（作为设计前提）

| 事实                                                                                                                                                                           | 代码/契约证据                                                                                                   | 对 M06 的含义                                                                                          |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| J2 审批表是 research 专用：`approval.research_task_id NOT NULL`、FK `research_task`、`action_type` CHECK 固定 `MCP_TOOL_CALL`、唯一键 `(tenant_id, run_id, stage, attempt_no)` | `apps/api/src/main/resources/db/migration/V4__research_runs_and_artifacts.sql`、`ApprovalService`（api/worker） | 通用审批节点不能直接复用 `approval`；扩展现有表会破坏 J2 语义并需要表重建                              |
| `mcp_invocation.run_id NOT NULL` FK `agent_run`、`approval_id` FK `approval`                                                                                                   | `V5__model_gateway_and_mcp.sql`                                                                                 | 工作流工具调用不能写入 `mcp_invocation`，否则要创建伪 `agent_run` 或放开 NOT NULL/FK                   |
| `agent_run.kind` CHECK 仅 `CHAT/FILE_PARSE/KNOWLEDGE_INDEX/RESEARCH_REPORT`；`agent_run_stage.stage` 仅 4 个 J2 阶段                                                           | `V4`                                                                                                            | 复用 `agent_run` 作为工作流运行需要改 CHECK/阶段枚举，直接扩大 J2 回归面                               |
| `model_invocation.run_id` 可空、`purpose` CHECK `CHAT                                                                                                                          | REPORT`；M04 成本按租户/模型/日聚合，不依赖 `run_id`                                                            | `V5`、`V11`、`ADR-0021/0022`                                                                           | 工作流模型调用可复用 `model_invocation`（`run_id=NULL` + 新增工作流引用），并保持 `purpose=REPORT`，无需改 CHECK |
| outbox 分为 `default`/`heavy` 两通道，通道与事件类型映射封闭；默认最大处理 30 分钟、heavy 65 分钟 | `OutboxLane`、`application.yml`、`ADR-0023 §7.3` | 30 分钟运行超时无法安全放在 default；需评估新通道或独立运行队列 |
| `agent_run_outbox.run_id NOT NULL` FK `agent_run`；`OutboxPoller` 按 `agent_run.kind/status` 推进并在成功后 `markSucceeded` | `V4`/`V12`、`OutboxClaimer`、`OutboxPoller` | 工作流事件不能复用该表/轮询器；M06 用 `workflow_run` 自身作持久运行队列，不改 J2 outbox |
| 检索入口 `AgentContextJdbc.search(tenantId, actorUserId, unrestricted, query, candidateLimit, limit)` 已带文档 ACL 谓词；MySQL FULLTEXT 为权威                                 | `AgentContextJdbc`、`V14`、`ADR-0023 §5.10.1`                                                                   | 检索节点直接复用该入口；运行发起人 ACL 是检索边界，不引入第二套检索                                    |
| 审计 details 是 action-aware 白名单，未知动作 fail-closed；M10 读取同样白名单校验（`AuditDetailsReader`）                                                                      | `AuditDetailsPolicy`、`AuditDetailsReader`、`ADR-0020`                                                          | 新增 `workflow.*` 动作必须同时进写入与读取白名单；**旧 API 无法读取含新动作的审计行**，决定回滚口径    |
| 内置角色只有 `PLATFORM_ADMIN/TENANT_ADMIN/MEMBER`；`department.manager_user_id` 是关系字段，不是 RBAC 角色；租户开通时由 `RoleDefinitions` 创建系统角色                        | `RoleDefinitions`、`V2`、`ADR-0018 §3`                                                                          | “流程编辑权限”必须显式授予，不能把部门负责人关系直接当权限；新增角色码需要为新租户创建、为存量租户回填 |
| 配额准入在 API 侧 `QuotaAdmissionService`，按“每次 run 请求数”计费，先锁 `tenant` 行再条件累加                                                                                 | `ResearchTaskService`、`ADR-0022 §1.3`                                                                          | 工作流运行准入复用同一锁顺序；M06 不新增 token/成本配额                                                |
| 当前契约 `1.5.0-m05b`，最新迁移 `V14`，worker 期望 schema 14                                                                                                                   | `contracts/openapi.yaml`、`apps/api/.../db/migration`、`SchemaVersionChecker`                                   | M06 契约升 `1.6.0-m06`，迁移 `V15`，worker 期望 15                                                     |
| 仓库不存在 workflow/orchestration 代码或表                                                                                                                                     | `rg workflow`、codegraph                                                                                        | M06 是 additive 绿地；不需要迁移历史数据                                                               |

### 1.2 约束

- **T4**：租户隔离、审计 fail-closed、不可逆/高成本变更需用户决策、回滚可演练；私有化生产数据不出客户内网。
- **范围**：只做手动触发、串行 + 条件分支、7 类节点（开始/结束/检索/模型/MCP 工具/审批/条件）；不做代码执行、脚本、任意 HTTP、并行、循环、子流程、定时/事件触发、跨租户模板市场、审批通知/代理、AI 生成 DAG、J2 迁移。
- **技术栈**：Java 21 + Spring Boot 4.1 + Spring JDBC（无 ORM）；`io.agentscope` 只在 worker；不新增第二套 Agent 运行时、消息中间件或图数据库。
- **预算**：M06 120h、M06b 48h；超 20% 回到用户决策（DEC-0046）。
- **迁移**：Flyway 只向前；只有 `apps/api` 执行迁移；破坏性变更必须 ADR + 回滚脚本 + 演练。
- **时间**：数据库 `DATETIME(6)` UTC；API/日志/UI 带偏移；展示与保留窗口按 `Asia/Shanghai` 计算。

### 1.3 必须回答的问题

1. 数据模型：复用还是新建运行/审批表；与 `agent_run`、`model_invocation`、`mcp_invocation` 的关系；V15 additive 迁移与回滚。
2. 执行模型：节点执行器、串行调度、条件求值、超时/取消/重试/幂等、写工具重审批、部分完成、运行队列/租约、并发准入、kill switch。
3. 审批模型：扩展现有 `approval` vs 新建 `workflow_approval`；审批人解析、禁自批、ACL 最小可见、无审批人阻断。
4. 权限：新增权限码/角色、部门负责人显式授权、403/404 矩阵、平台管理员 404、部门范围与空间关系。
5. 契约：OpenAPI/internal API、版本、幂等键、错误码、轮询/SSE、M06b 预留。
6. 审计：动作清单、details 白名单、fail-closed、管理性读取、导出不变。
7. 安全：威胁模型增补、提示注入、工具白名单、写工具审批、canary、跨租户/跨部门、PII/外部模型端点、日志脱敏、代码执行负向控制。
8. 运维/回滚：部署配置、kill switch、指标、回滚限制、历史可读、J1/J2 不受影响。
9. a11y：画布 + 列表/表单等价路径、键盘、aria、375px、axe。
10. M06b/M26：模板库/2 个内置模板/升级提示/租户内共享的预留，不增加 M06 预算。
11. 任务拆分：23 条 M06 AC 的可实施任务、工时、风险、证据；M06b 2 条 AC 草案。
12. 合同影响：尽量不改 `AGENTS.md`；契约版本、迁移编号、依赖复用。

---

## 2. 决策总览

| 能力       | 决策                                                                                                                                | 状态                                    |
| ---------- | ----------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- |
| 运行聚合   | **新建** `workflow_run` / `workflow_node_run`，不复用 `agent_run`；`workflow_run` 是唯一运行权威，节点重试不新建 run                | 团队决定                                |
| 定义与版本 | `workflow_definition`（可变草稿 + 乐观锁）+ `workflow_version`（不可变发布快照）；运行绑定版本                                      | 团队决定                                |
| 模型调用   | 复用 `model_invocation`，新增可空 `workflow_run_id`/`workflow_node_run_id`；`purpose=REPORT`，不新增 `WORKFLOW` 用途、不改 CHECK    | 团队决定                                |
| 工具调用   | **新建** `workflow_tool_invocation`；不复用 `mcp_invocation`（其 FK 绑定 `agent_run`/`approval`）                                   | 团队决定                                |
| 审批       | **新建** `workflow_approval` + `workflow_approval_assignee`；不扩展 J2 `approval`；复用 ADR-0017 信封加密保存可重放工具参数         | 团队决定                                |
| 图模型     | 单入边树状 DAG（indegree ≤ 1，不允许合并），最多 50 节点/100 边；条件节点两个出边 `TRUE`/`FALSE`；无并行/循环/子流程                | 团队决定                                |
| 条件求值   | 封闭算子白名单（EQ/NE/GT/GTE/LT/LTE/IN）+ 受控数据源（运行输入/检索命中数/模型分类标签/审批决定）；**无表达式、无脚本**             | 团队决定                                |
| 执行       | 串行；`workflow_run` 自身作为持久运行队列（租约 90s + 心跳；默认并发 10）；审批等待释放执行槽位；不新增 outbox 表/事件 | 团队决定 |
| 资源限制   | 节点 50、步数 200、模型 300s、检索 120s、MCP 60s、运行 30min（不含审批等待）、租户并发 10、单流程 5、结果 5 MiB；部署配置、租户只读 | DEC-0046 已批准                         |
| 配额/成本 | 按运行准入 1 单位计（DEC-0048=A），与 M04 按请求数计一致；模型成本按 `model_invocation` 聚合；资源上限约束单运行模型节点数量；不新增 token/成本配额 | DEC-0048=A：按运行准入 1 单位 |
| 权限       | 新增 `workflow.read/edit/publish/run/approve/manage`；新增系统角色 `WORKFLOW_EDITOR`；租户管理员经既有角色管理显式授予/撤销         | 团队决定；角色模型 DEC-0046 已批准      |
| 审计       | 新增 `workflow.*` 动作；同事务 fail-closed；租户管理员查看他人运行写 `workflow.run.admin_read`；M10 查询/导出机制不变               | 团队决定                                |
| 契约       | `1.6.0-m06`，全部 additive；internal API 不变；运行详情用**轮询**，不新增 SSE；M06b 端点标 `x-riccon-deferred`                      | 团队决定                                |
| 回滚       | 不做 down migration；受支持回滚 = 关闭开关 + 回滚 Worker + 保留/前向修复 API；**有工作流审计行后不支持 API 回滚到 M06 前**；全量 API 回滚仅限无 workflow 数据/审计行 | DEC-0047=A 已确认 AC-23 口径 |
| M06b       | V15 预留 `workflow_user_template` 与模板字段；M06 仅 1 个全局内置模板；M06b 无迁移                                                  | 团队决定                                |

---

## 3. 数据模型（V15，additive）

### 3.1 聚合关系

```text
workflow_definition 1 ── N workflow_version 1 ── N workflow_run 1 ── N workflow_node_run
                                                        │
                                                        ├── N workflow_approval 1 ── N workflow_approval_assignee
                                                        └── N workflow_tool_invocation（1 个 approval 最多 1 条）

workflow_template（全局内置目录，只读）
workflow_user_template（租户内用户模板；M06 预留、M06b 启用）

model_invocation.workflow_run_id / workflow_node_run_id（可空逻辑引用，成本/排障用）
```

- 所有租户表遵循 ADR-0012：`tenant_id VARCHAR(64) ascii_bin`、主键 `(tenant_id, id)`、唯一键/索引以 `tenant_id` 开头、状态 `VARCHAR + CHECK`、时间 `DATETIME(6)` UTC、UUIDv7 `BINARY(16)`。
- 父子结构使用复合外键；跨聚合/可空引用（`definition.published_version_id`、`node_run.approval_id`、`model_invocation.workflow_run_id`、模板来源）使用逻辑引用 + 索引，并在评审逐条说明。

### 3.2 与 `agent_run` 的关系：不复用

| 维度      | 复用 `agent_run`                                                      | 新建 `workflow_run`                                 |
| --------- | --------------------------------------------------------------------- | --------------------------------------------------- |
| J2 回归面 | 需改 `kind` CHECK、阶段枚举、J2 handler/poller 与查询                 | 零改动；J2 查询不扫描新表                           |
| 运行语义  | 单阶段/J2 任务；一个 `research_task` 一个当前 run                     | 多节点、节点级重试、审批暂停/恢复；一个版本多次运行 |
| 审批绑定  | `approval.research_task_id NOT NULL`、`mcp_invocation.approval_id` FK | 自有审批/工具调用表，语义自洽                       |
| 并发/租约 | J2 outbox handler 与 `agent_run` 状态耦合 | 独立租约、独立运行队列、独立看门狗 |
| 迁移成本  | `agent_run` 可能已是大表，CHECK 变更需 COPY/阻塞写                    | 只新增表，`INSTANT/INPLACE`                         |
| 成本/审计 | 可复用但需伪 task/run                                                 | 复用 `model_invocation` 成本；审计独立动作          |

**结论**：新建 `workflow_run`；不创建伪 `agent_run`、不扩展 `agent_run.kind`。J2 的 `research_task/agent_run/approval/mcp_invocation` 全部保持现状。

### 3.3 表定义（列级摘要；SQL 由 Builder 在 V15 编写）

#### 3.3.1 `workflow_definition`（租户表）

| 列                        | 类型/约束                               | 说明                                           |
| ------------------------- | --------------------------------------- | ---------------------------------------------- |
| `tenant_id`               | `VARCHAR(64) ascii_bin NOT NULL`        | 租户键；复合主键首列                           |
| `id`                      | `BINARY(16) NOT NULL`                   | 流程 ID（UUIDv7）                              |
| `name`                    | `VARCHAR(200) NOT NULL`                 | 流程名；不要求租户内唯一                       |
| `description`             | `VARCHAR(1000) NULL`                    | 说明                                           |
| `owner_user_id`           | `BINARY(16) NOT NULL`                   | 所有者；FK `app_user`                          |
| `draft_version`           | `INT NOT NULL DEFAULT 1`                | 草稿乐观锁版本                                 |
| `draft_graph`             | `JSON NOT NULL`                         | 当前草稿图；允许非法（未发布），发布前必须校验 |
| `status`                  | `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'` | `ACTIVE`/`DISABLED`；停用阻止新运行            |
| `published_version_id`    | `BINARY(16) NULL`                       | 最近发布版本（逻辑引用）                       |
| `published_version_no`    | `INT NULL`                              | 冗余版本号，便于列表                           |
| `source_template_id`      | `BINARY(16) NULL`                       | 来源模板 ID（内置或用户模板；逻辑引用）        |
| `source_template_type`    | `VARCHAR(16) NULL`                      | `BUILT_IN`/`USER`                              |
| `source_template_version` | `INT NULL`                              | 来源模板版本，升级提示用                       |
| `created_by`/`updated_by` | `BINARY(16) NOT NULL`                   | FK `app_user`                                  |
| `created_at`/`updated_at` | `DATETIME(6)`                           | UTC                                            |

键：`PK (tenant_id,id)`；`KEY (tenant_id, owner_user_id, updated_at)`、`KEY (tenant_id, status, updated_at)`、`KEY (tenant_id, name)`；FK `tenant`、`owner/created_by/updated_by → app_user`。无 `deleted_at`：M06 不提供删除流程端点，已发布流程只能停用。

#### 3.3.2 `workflow_version`（租户表，不可变）

| 列                          | 类型/约束              | 说明                               |
| --------------------------- | ---------------------- | ---------------------------------- |
| `tenant_id`/`id`            | 同前                   | 版本 ID（UUIDv7）                  |
| `workflow_id`               | `BINARY(16) NOT NULL`  | FK `workflow_definition`           |
| `version_no`                | `INT NOT NULL`         | 从 1 递增；租户内流程内唯一        |
| `graph_json`                | `JSON NOT NULL`        | 发布时规范化的图快照               |
| `graph_sha256`              | `CHAR(64) NOT NULL`    | 规范化图哈希；运行绑定与排障       |
| `node_count`                | `INT NOT NULL`         | 节点数                             |
| `run_scope`                 | `VARCHAR(16) NOT NULL` | `ONLY_SELF`/`DEPARTMENT`/`TENANT`  |
| `department_id`             | `BINARY(16) NULL`      | `DEPARTMENT` 必填；FK `department` |
| `published_by`              | `BINARY(16) NOT NULL`  | 发布人                             |
| `published_at`/`created_at` | `DATETIME(6) NOT NULL` | UTC                                |

键：`PK (tenant_id,id)`；`UNIQUE (tenant_id, workflow_id, version_no)`；`KEY (tenant_id, workflow_id, published_at)`；FK `workflow_definition`、`department`。CHECK：`run_scope='DEPARTMENT' → department_id IS NOT NULL`，其余为空。
不可变：`BEFORE UPDATE`/`BEFORE DELETE` 触发器拒绝修改（M09 如需清理须先删触发器并走迁移/演练）；应用层没有更新/删除路径。

#### 3.3.3 `workflow_run`（租户表）

| 列                                                   | 类型/约束                             | 说明                                                          |
| ---------------------------------------------------- | ------------------------------------- | ------------------------------------------------------------- |
| `tenant_id`/`id`                                     | 同前                                  | 运行 ID（UUIDv7）                                             |
| `workflow_id`                                        | `BINARY(16) NOT NULL`                 | FK `workflow_definition`                                      |
| `workflow_version_id`                                | `BINARY(16) NOT NULL`                 | FK `workflow_version`；运行绑定版本                           |
| `version_no`                                         | `INT NOT NULL`                        | 版本号快照                                                    |
| `requester_user_id`                                  | `BINARY(16) NOT NULL`                 | 发起人；FK `app_user`                                         |
| `status`                                             | `VARCHAR(16) NOT NULL`                | `PENDING/RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/CANCELLED` |
| `inputs_json`                                        | `JSON NOT NULL`                       | 运行输入（≤64 KiB；键/长度服务端校验）                        |
| `result_json`                                        | `JSON NULL`                           | 最终结果（≤5 MiB）；`result_status` 说明是否保存              |
| `result_status`                                      | `VARCHAR(16) NOT NULL DEFAULT 'NONE'` | `NONE/SAVED/OVER_LIMIT`                                       |
| `result_size_bytes`                                  | `BIGINT NOT NULL DEFAULT 0`           | 结果大小                                                      |
| `current_node_key`                                   | `VARCHAR(64) NULL`                    | 当前节点（图内 key）                                          |
| `execution_ms`                                       | `BIGINT NOT NULL DEFAULT 0`           | 累计执行时间（不含审批等待）                                  |
| `approval_wait_ms`                                   | `BIGINT NOT NULL DEFAULT 0`           | 累计审批等待                                                  |
| `lease_owner`/`lease_until`                          | `VARCHAR(128)`/`DATETIME(6)`          | 执行租约（审批等待时为空）                                    |
| `claim_attempts`                                     | `INT NOT NULL DEFAULT 0`              | 租约丢失重领次数；正常恢复/重试归零；超过 5 次 → `WF_INTERNAL` 失败，防崩溃循环 |
| `idempotency_key`                                    | `VARCHAR(128) ascii_bin NOT NULL`     | HTTP 创建幂等键                                               |
| `error_code`/`error_message`                         | `VARCHAR(64)`/`VARCHAR(500)`          | 稳定码 + 脱敏原因                                             |
| `retention_expires_at`                               | `DATETIME(6) NOT NULL`                | 到期不可见；默认创建时间 + 租户保留天数（默认 180 天）        |
| `created_at`/`updated_at`/`started_at`/`finished_at` | `DATETIME(6)`                         | UTC                                                           |

- `approvalStatus`/`pendingApprovalCount`/`approvedApprovalCount`/`rejectedApprovalCount` 是 `workflow_approval` 的派生聚合（不新增列）：任一 PENDING→PENDING；否则任一 REJECTED→REJECTED；否则任一 APPROVED→APPROVED；否则 null。

键：`PK (tenant_id,id)`；`UNIQUE (tenant_id, workflow_id, requester_user_id, idempotency_key)`；`KEY (tenant_id, workflow_id, created_at)`、`KEY (tenant_id, status, created_at)`、`KEY (tenant_id, requester_user_id, created_at)`、`KEY (tenant_id, retention_expires_at)`、`KEY (tenant_id, status, lease_until)`、`KEY (status, lease_until, id)`（跨租户领取例外，TM-85）；FK `workflow_definition`、`workflow_version`、`app_user`。

#### 3.3.4 `workflow_node_run`（租户表）

| 列                                       | 类型/约束                            | 说明                                                                  |
| ---------------------------------------- | ------------------------------------ | --------------------------------------------------------------------- |
| `tenant_id`/`id`                         | 同前                                 | 节点运行 ID（UUIDv7）                                                 |
| `workflow_run_id`                        | `BINARY(16) NOT NULL`                | FK `workflow_run`                                                     |
| `node_key`                               | `VARCHAR(64) ascii_bin NOT NULL`     | 图内节点 ID（客户端提供，`[A-Za-z0-9_-]{1,64}`）                      |
| `node_type`                              | `VARCHAR(32) NOT NULL`               | `START/END/RETRIEVAL/MODEL/MCP_TOOL/APPROVAL/CONDITION`               |
| `node_name`                              | `VARCHAR(200) NOT NULL`              | 快照                                                                  |
| `attempt`                                | `INT NOT NULL DEFAULT 1`             | 节点尝试号；重试递增                                                  |
| `status`                                 | `VARCHAR(24) NOT NULL`               | `PENDING/RUNNING/WAITING_APPROVAL/SUCCEEDED/FAILED/SKIPPED/CANCELLED` |
| `input_summary`                          | `VARCHAR(2000) NULL`                 | 节点输入摘要（运行输入/上游摘要组合；不返回完整提示词或参数）          |
| `output_json`                            | `JSON NULL`                          | 下游使用的完整输出（内部，不直接返回 API；单节点 ≤256 KiB）           |
| `output_summary`                         | `VARCHAR(2000) NULL`                 | 运行详情展示的摘要                                                    |
| `citations_json`                         | `JSON NULL`                          | 引用快照（≤20 条，复用 `Citation` 语义）                              |
| `approval_id`                            | `BINARY(16) NULL`                    | 逻辑引用 `workflow_approval`                                          |
| `tool_invocation_id`                     | `BINARY(16) NULL`                    | 逻辑引用 `workflow_tool_invocation`                                   |
| `error_code`/`error_message`             | `VARCHAR(64)`/`VARCHAR(500)`         | 稳定码 + 脱敏原因                                                     |
| `started_at`/`finished_at`/`duration_ms` | `DATETIME(6)`/`DATETIME(6)`/`BIGINT` | 时间线                                                                |
| `created_at`/`updated_at`                | `DATETIME(6)`                        | UTC                                                                   |

键：`PK (tenant_id,id)`；`UNIQUE (tenant_id, workflow_run_id, node_key, attempt)`；`KEY (tenant_id, workflow_run_id, started_at)`；FK `workflow_run`。

#### 3.3.5 `workflow_approval`（租户表）

| 列                                                  | 类型/约束                                  | 说明                                                                    |
| --------------------------------------------------- | ------------------------------------------ | ----------------------------------------------------------------------- |
| `tenant_id`/`id`                                    | 同前                                       | 审批 ID（UUIDv7）                                                       |
| `workflow_run_id`/`workflow_id`                     | `BINARY(16) NOT NULL`                      | FK `workflow_run`/`workflow_definition`；`workflow_id` 冗余用于待办查询 |
| `node_key`                                          | `VARCHAR(64) NOT NULL`                     | 审批节点 key                                                            |
| `attempt`                                           | `INT NOT NULL`                             | 节点尝试号                                                              |
| `status`                                            | `VARCHAR(16) NOT NULL`                     | `PENDING/APPROVED/REJECTED/CANCELLED`                                   |
| `assignee_type`                                     | `VARCHAR(16) NOT NULL`                     | `USER`/`ROLE`（二选一）                                                 |
| `assignee_role_code`                                | `VARCHAR(64) NULL`                         | `TENANT_ADMIN`/`WORKFLOW_EDITOR`/`MEMBER`                               |
| `action_kind`                                       | `VARCHAR(16) NOT NULL`                     | `MCP_TOOL`/`MODEL`/`CONTINUE`                                           |
| `target_node_key`                                   | `VARCHAR(64) NULL`                         | 解锁的下游节点（工具/模型）或空                                         |
| `target_tool_id`/`target_tool_name`                 | `BINARY(16)`/`VARCHAR(128)`                | 工具快照（逻辑引用）                                                    |
| `params_preview`                                    | `JSON NOT NULL`                            | 审批人可见的脱敏摘要（≤16 键、单值 ≤200 字符、≤4096 字节）              |
| `params_ciphertext`/`params_key_id`/`params_sha256` | `VARBINARY(8192)`/`VARCHAR(64)`/`CHAR(64)` | 仅 `MCP_TOOL`：可重放参数密文（复用 ADR-0017，AAD=`tenantId\|workflowApprovalId`） |
| `plan_sha256`                                       | `CHAR(64) NOT NULL`                        | 版本 + 节点配置 + 上游输出摘要的绑定哈希；执行前重算，漂移 fail-closed  |
| `requested_by`/`decided_by`                         | `BINARY(16)`                               | 运行发起人/审批人；FK `app_user`                                        |
| `decided_at`/`decision_comment`                     | `DATETIME(6)`/`VARCHAR(500)`               | 决定时间；拒绝时理由必填                                                |
| `created_at`/`updated_at`                           | `DATETIME(6)`                              | UTC                                                                     |

键：`PK (tenant_id,id)`；`UNIQUE (tenant_id, workflow_run_id, node_key, attempt)`；`KEY (tenant_id, status, created_at)`、`KEY (tenant_id, workflow_id, created_at)`；FK `workflow_run`、`workflow_definition`、`requested_by/decided_by`。CHECK：`USER` → `assignee_role_code IS NULL`；`ROLE` → `assignee_role_code IS NOT NULL`；`APPROVED/REJECTED` → `decided_by/decided_at` 非空；`PENDING` → 二者为空；`REJECTED` → `decision_comment` 非空。

- `dataScopeSummary` 是派生字段（不新增列）：`runScope`/`departmentId` 取运行绑定版本，`documentReferenceCount` 取审批节点上游引用的去重计数；不返回文档标题/片段/正文，审批人按 M05 ACL 自行访问。

`workflow_approval_assignee`：`(tenant_id, approval_id, user_id)` 复合主键，FK approval/app_user，`KEY (tenant_id, user_id, approval_id)`；仅 `assignee_type=USER` 时写入，最多 50 行。角色审批不写该表，待办按 `user_role` 联接解析。

#### 3.3.6 `workflow_tool_invocation`（租户表）

| 列                                                   | 类型/约束                           | 说明                                                                    |
| ---------------------------------------------------- | ----------------------------------- | ----------------------------------------------------------------------- |
| `tenant_id`/`id`                                     | 同前                                | 调用 ID（UUIDv7）                                                       |
| `workflow_run_id`/`workflow_approval_id`             | `BINARY(16) NOT NULL`               | FK；`UNIQUE (tenant_id, workflow_approval_id)` 保证一个审批最多执行一次 |
| `node_key`/`attempt`                                 | `VARCHAR(64)`/`INT`                 | 节点与尝试                                                              |
| `mcp_tool_id`/`tool_name`                            | `BINARY(16)`/`VARCHAR(128)`         | 工具快照；FK `mcp_tool`（全局）                                         |
| `status`                                             | `VARCHAR(16)`                       | `STARTED/SUCCEEDED/FAILED/DENIED`                                       |
| `params_sha256`/`params_preview`                     | `CHAR(64)`/`JSON NULL`              | 入参哈希/脱敏摘要                                                       |
| `result_sha256`/`result_size_bytes`/`result_preview` | `CHAR(64)`/`BIGINT`/`VARCHAR(1000)` | 结果摘要；不存凭据                                                      |
| `duration_ms`/`error_code`                           | `BIGINT`/`VARCHAR(64)`              | 耗时/错误                                                               |
| `started_at`/`finished_at`/`created_at`              | `DATETIME(6)`                       | UTC                                                                     |

键：`PK (tenant_id,id)`；`UNIQUE (tenant_id, workflow_approval_id)`；`KEY (tenant_id, workflow_run_id, started_at)`；FK `workflow_run`、`workflow_approval`、`mcp_tool`。

#### 3.3.7 `workflow_template`（全局内置目录）与 `workflow_user_template`（租户，M06b 预留）

`workflow_template`（**全局表**，ADR-0012 §4 例外清单由本 ADR 扩展）：`id BINARY(16)` PK、`template_key VARCHAR(64)` 唯一（如 `market-research`）、`name`、`scenario`、`description`、`graph_json`、`version INT`、`status`、`created_at/updated_at`。只读目录，无租户键；内置模板 `visibility=BUILT_IN`（不是 `PRIVATE`/`TENANT`），ID 使用固定 UUID（迁移种子，升级不改变 ID），用户副本使用新 UUIDv7；内置模板升级 = 新版本行或 version+1（M06b 决定），不静默覆盖已复制草稿。

`workflow_user_template`（租户表，V15 预留，M06 无读写路径）：`(tenant_id,id)` PK、`name`、`description`、`scenario`、`graph_json`、`visibility`（`PRIVATE/TENANT`，默认 `PRIVATE`）、`owner_user_id`、`source_workflow_id`/`source_workflow_version_no`、`status`、`created_at/updated_at/created_by`；`KEY (tenant_id, visibility, updated_at)`、`KEY (tenant_id, owner_user_id)`。

### 3.4 运行结果、节点输出与引用

- 最终结果只存 MySQL `workflow_run.result_json`，不写对象存储、不签预签名 URL；避免扩 ADR-0009 对象 key 类别与 M02 F-015（>2 MiB 产物下载）债务。
- 单节点 `output_json` ≤256 KiB；单次运行所有节点输出 + 最终结果合计 ≤5 MiB（部署配置）；超限 → 运行失败 `WF_RESULT_LIMIT`，`result_status=OVER_LIMIT`，不静默截断。
- 引用快照存 `workflow_node_run.citations_json`；读取时按 M05 `Citation` 语义批量计算 `sourceStatus`（`AVAILABLE/NO_ACCESS/DELETED/UPDATED`），不可访问时遮蔽标题/片段/定位。运行详情聚合所有已执行检索节点的引用并去重。
- 审批只存脱敏 `params_preview` 与工具参数密文；运行详情/日志/审计不返回完整提示词、完整工具参数、凭据或文档正文（AC-13）。

### 3.5 迁移 V15 与回滚

**V15 顺序**（单迁移，只增不删）：

1. 新表：`workflow_definition` → `workflow_version` → `workflow_run` → `workflow_node_run` → `workflow_approval` → `workflow_approval_assignee` → `workflow_tool_invocation` → `workflow_template` → `workflow_user_template`（按 FK 依赖排序）。
2. `model_invocation` 追加 `workflow_run_id`、`workflow_node_run_id`（可空、末尾、INSTANT）与 `idx_model_invocation_workflow (tenant_id, workflow_run_id, started_at)`（INPLACE/LOCK=NONE）；**不加 CHECK、不改 `purpose`**。
3. 权限字典：`workflow.read/edit/publish/run/approve/manage`；回填既有 `TENANT_ADMIN`（全部 6 个）、`MEMBER`（read/run/approve）；创建 `WORKFLOW_EDITOR` 角色行并为存量租户回填（见 §6.1）。
4. 内置模板种子：`market-research`（J2 等价，1 个）；`version=1`。
5. `workflow_version` 不可变触发器（最后创建；失败按下方矩阵处置）。
6. `apps/worker` 期望 schema 14 → 15。
7. 联动断言：`.github/workflows/ci.yml`（触发器数；当前硬编码 2，V15 后为 4，建议从 `CREATE TRIGGER` 推导）、`ApiApplicationTests`（migrations 14→15、tables 49→58、triggers 2→4）、`SchemaVersionCheckerTest`（expected=15）、`scripts/ops/restore-drill.mjs`（14/2→15/4，含 self-test fixtures 49/48 表）、`docs/RUNBOOK.md`（V1–V14→V1–V15、48/49→57/58 表、2→4 触发器）必须在同一提交更新，写范围见 §13.1。

**生产预检（T01 编码前、发布前）**：

- `SELECT MAX(version) FROM flyway_schema_history` = 14；备份可恢复；`SHOW GRANTS` 含 `TRIGGER`；binlog 开启时 `log_bin_trust_function_creators=1` 或等价 SUPER；无长事务/未提交 DDL；MDL 等待与维护窗口已确认；磁盘余量满足 V15 + 备份。
- `@@max_allowed_packet` 与 JDBC `maxAllowedPacket` ≥ 8 MiB（且 ≥ `RICCON_WORKFLOW_RESULT_MAX_BYTES` + JSON/协议余量）；否则先调服务器/驱动，不得靠 5 MiB 结果上线后再发现。
- 测试栈的 `--log-bin-trust-function-creators=1` 只作测试便利，不得作为生产预检证据。

**MySQL DDL 非事务失败处置**（必须先停写/停迁移；生产默认备份恢复）：

| 失败点 | 可能残留 | 处置 |
| --- | --- | --- |
| 建表/索引/列中途失败 | 已提交的表/列/索引 | 记录最后成功语句；按 FK 逆序 drop 半成品（全局 `workflow_template` 与租户表分开）；删除 `success=0` 的 history 行；`flyway repair`；重跑 |
| 权限/角色/模板 DML 冲突 | 部分回填 | 所有 DML 必须 `INSERT ... WHERE NOT EXISTS` / `ON DUPLICATE KEY UPDATE` 幂等；修因后 repair/重跑；迁移测试断言二次执行无重复行 |
| 触发器失败（1419/权限） | 表已建、触发器缺失 | 补 `TRIGGER`/binlog 变量；单独重放触发器或按上表清理；不得删除审计触发器绕过 |
| 包大小/超时/连接中断 | 取决于最后提交语句 | 提升 `max_allowed_packet`/维护窗口后 repair/重跑；无法可靠定位则备份恢复 |

- 不修改已应用的 V15 文件；所有修复走后续迁移或备份恢复（ADR-0010）。
- 应急 drop 只在无 workflow 数据/审计行且用户书面同意时执行；全局模板与租户表分两段脚本（§10.4）。

## 4. 执行模型

### 4.1 图模型与发布快照

- 节点类型封闭：`START/END/RETRIEVAL/MODEL/MCP_TOOL/APPROVAL/CONDITION`；契约与 `platform-domain` 枚举一致；未知类型在保存/发布/运行三处拒绝（AC-24）。
- 结构约束：恰好 1 个 `START`、≥1 个 `END`；每个节点 indegree ≤1（不允许合并/并行）；`CONDITION` 恰好 2 条出边且 `branch=TRUE/FALSE`；其他非 `END` 节点恰好 1 条出边；无环、无孤立节点、所有路径以 `END` 结束；节点 ≤50、边 ≤100。
- `END` 配置 `resultFrom` 必须指向上游已执行节点；运行结果 = 该节点输出（文本/JSON）+ 聚合引用。
- 节点 `id` 由客户端生成（`[A-Za-z0-9_-]{1,64}`），在流程内唯一；服务端不依赖数据库自增。
- 发布：服务端重新校验、规范化 JSON（键排序、去空白）、计算 `graph_sha256`，写 `workflow_version`；之后草稿修改不影响已发布版本（AC-03）。
- 运行：API 只写 `workflow_run`（`PENDING` + `current_node_key`）；Worker 用 `workflow_run` 作为持久队列领取，从 `workflow_version.graph_json` 快照执行，启动时校验 `graph_sha256` 与租户/版本绑定。

### 4.2 状态机

```text
workflow_run:    PENDING -> RUNNING -> SUCCEEDED | FAILED
                   │           └──> WAITING_APPROVAL -> (resume) RUNNING
                   └──> CANCELLED（PENDING/RUNNING/WAITING_APPROVAL 均可）
workflow_node_run: PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
                     └──> WAITING_APPROVAL（审批节点）-> RUNNING/…
                     └──> SKIPPED（未走分支/上游失败）
workflow_approval: PENDING -> APPROVED | REJECTED | CANCELLED
run queue:        PENDING -> RUNNING（领取）；RUNNING + lease 过期 -> 可被重新领取
```

- 运行状态推进使用条件更新（`WHERE status=:expected`）；重复领取/重复投递不得二次执行。
- 审批等待时 `lease_owner/lease_until` 清空、worker 释放线程；审批决定把运行回到 `PENDING` 并设置 `current_node_key=targetNodeKey`，下轮轮询继续。
- 节点重试不新建 `workflow_run`，只新增 `workflow_node_run.attempt` 并设置 `current_node_key=failedNodeKey`。

### 4.3 调度、租约与运行队列

**不复用 `agent_run_outbox`**：该表 `run_id NOT NULL` + FK `agent_run`，`OutboxPoller` 以 `agent_run.kind/status` 推进并在成功后 `markSucceeded`；把工作流事件塞进去需要改 NOT NULL/FK/Poller，直接扩大 J2 回归面。也不新增第二个 outbox 表（复制 claimer/看门狗/清理，或重构 `OutboxPoller`）。M06 使用 `workflow_run` 自身作为持久运行队列：

- API 在创建/审批恢复/重试时把运行置 `PENDING` 并写 `current_node_key`（创建时为 `START` 节点，恢复/重试为目标节点）；取消置 `CANCELLED`；不需要事件表/事件 schema。
- **槽位与领取批量闭合**：`WorkflowRunScheduler` 使用**独立**的 `ThreadPoolTaskScheduler`（不复用 `spring.task.scheduling.pool.size`），池大小 = `RICCON_WORKFLOW_WORKER_CONCURRENCY`（默认 10），启动校验池 ≥ 并发。每个槽位空闲时只领取 **1** 个 run（claim SQL `LIMIT 1`；`RICCON_WORKFLOW_CLAIM_BATCH` 在 v1 固定为 1，配置 >1 启动失败）；禁止“一次领 10 个再串行执行”。租约从领取时刻起算；领取成功后**先启动心跳**，再加载图/执行节点。
- **心跳池**：独立 `WorkflowLeaseHeartbeat`，线程数 ≥ worker 并发（`RICCON_WORKFLOW_HEARTBEAT_CONCURRENCY`，默认 10）；续期间隔 30s（`RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS`，必须 ≤ lease/3 = 30s）；领取后立即首次续期。心跳同时检查运行状态/取消；续期失败或租约丢失记 `lease_lost_total` 并放弃结果。启动校验心跳池 ≥ 并发、间隔 ≤ lease/3。
- `WorkflowRunClaimer`（worker `..persistence..`）按 `(status='PENDING') OR (status='RUNNING' AND lease_until <= NOW(6))` 以 `FOR UPDATE SKIP LOCKED` + `LIMIT 1` 领取，SELECT **恰好 4 列** `id/tenant_id/status/lease_until`（ArchUnit/字符串断言静态守卫，不得 SELECT *）；更新使用 `(tenant_id,id)` + 状态/租约条件写 `lease_owner/lease_until`。这是 ADR-0009/0016 的**新增跨租户领取例外**：只限 `workflow_run` 领取列；领取后立即 `TenantContext.open` 并回到 `TenantJdbc`，不通过该路径读取 `inputs_json`/`result_json` 等业务内容。
- 非法/空 `tenant_id` fail-closed：不打开 TenantContext、不读取业务列、不执行；记 `claim_failures_total{reason=INVALID_TENANT}` 并写 `platform` 租户 denied 审计（可归属时）。`IT-WF-103` 与 `TEN-M06-01` 必须覆盖非法 tenant、跨租户行、列集合断言；TM-85 的跨租户领取需独立安全评审（F-014/U-13）。
- 领取索引 `(status, lease_until, id)` 故意不以 `tenant_id` 开头（与 outbox 领取同类例外）；`ORDER BY id` 利用 UUIDv7 时间有序；两段 status 查询可能 filesort，作为可接受项观察 claim 延迟。
- 领取/完成/续期均使用条件更新；重复领取不得二次执行：`lease_owner` 不匹配、状态非 `RUNNING`、节点/尝试唯一键共同兜底。`IT-WF-103` 必须覆盖“批量/并发领取同一行、租约过期重领、心跳丢失、重复执行防护”。
- 仅当从租约过期的 `RUNNING` 重新领取时递增 `claim_attempts`；新建/恢复/重试的 `PENDING` 归零。超过 5 次仍无法推进 → 运行 `FAILED`（`WF_INTERNAL`），并把当前节点 `workflow_node_run` 置 `FAILED`（若节点行不存在，按 `current_node_key` 创建一次失败尝试），保证 `POST /retry` 可用；写审计。
- 优雅停机/停止领取：`RICCON_WORKFLOW_CLAIM_ENABLED=false` 时槽位停止领取新 run，在途 run 最多等待 `RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS`（默认 120s）后退出；未完成运行靠租约过期被其他实例恢复。不得把“缩容到 0”作为唯一停止手段。
- 运行超时按“累计执行时间”计算，审批等待不计；每次暂停/恢复累加 `execution_ms`。

### 4.4 节点执行器

| 节点        | 配置（`config`）                                                                | 行为                                                                                                                          | 超时                                                                             | 输出/失败                                                                |
| ----------- | ------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `START`     | 无                                                                              | 记录开始；校验运行输入必填项                                                                                                  | —                                                                                | —                                                                        |
| `END`       | `resultFrom`、`resultTitle?`                                                    | 组装结果与引用；写 `result_json`                                                                                              | —                                                                                | 超 5 MiB → `WF_RESULT_LIMIT`                                             |
| `RETRIEVAL` | `queryTemplate`、`topK`(1..20)                                                  | 用运行输入/上游摘要渲染查询；以**发起人** ACL 调 `AgentContextJdbc.search(..., unrestricted=false, ...)`；`hitCount=返回条数` | 120s                                                                             | `WF_RETRIEVAL_FAILED`；引用快照                                          |
| `MODEL` | `promptTemplate`、`modelId?`、`outputKind`(`TEXT/CLASSIFICATION`)、`labels?` | `purpose=REPORT` 路由；`modelId` 必须在 REPORT 链且 ACTIVE；分类输出必须命中标签 | 300s | `WF_MODEL_UNAVAILABLE`/`WF_MODEL_OUTPUT_INVALID` |
| `MCP_TOOL`  | `toolId`、`argsTemplate`                                                        | 前置审批 → 解密/校验参数 → 授权/状态复查 → 调用；结果截断策略见 §4.7                                                          | 60s                                                                              | `WF_TOOL_NOT_AUTHORIZED`/`WF_TOOL_RESULT_LIMIT`/`WF_SIDE_EFFECT_UNKNOWN` |
| `APPROVAL`  | `assigneeType`、`assigneeRole`/`assigneeUserIds`、`actionKind`、`targetNodeKey` | 解析审批人；创建 `workflow_approval`；运行进入 `WAITING_APPROVAL`                                                             | 无自动过期                                                                       | `WF_NO_APPROVER`（保持等待，不自动放行）                                 |
| `CONDITION` | `left`、`operator`、`right`                                                     | 封闭算子求值；只走 `TRUE` 或 `FALSE` 分支，另一分支全部 `SKIPPED`                                                             | —                                                                                | `WF_CONDITION_INVALID`（不默认走任一分支）                               |

- 执行器接口（设计约束，不写代码）：`WorkflowNodeExecutor` 以 `(run, node, inputs, deadline)` 为输入，返回 `NodeResult{status, output, citations, summary, errorCode, invocationRef}`；实现按节点类型分派，禁止在节点内解释任意脚本/表达式/HTTP。
- 节点开始前做条件更新 `PENDING -> RUNNING`；成功后写输出与摘要；失败写稳定码并标记所有下游为 `SKIPPED`。
- 步骤预算：每次节点尝试计 1 步（重试也计）；超过 200 → 运行失败 `WF_STEP_LIMIT`；条件未走分支不计步。
- 节点输出 `output_json` 只供下游使用；API 返回 `inputSummary`/`outputSummary`、引用与最终结果，不返回完整提示词或工具参数。

### 4.5 条件求值与模板占位符

- **不支持任意表达式/脚本/正则**。条件节点配置：
  - `left`: `{source: RUN_INPUT|NODE_OUTPUT, nodeId?, path, valueType: NUMBER|STRING|BOOLEAN}`
  - `operator`: `EQ/NE/GT/GTE/LT/LTE/IN`
  - `right`: `{valueType, value?}` 或 `{valueType: STRING, values[]}`（`IN`）
- 可用 `path` 白名单：运行输入字段；`RETRIEVAL.hitCount`；`MODEL.classificationLabel`（必须来自 `labels` 枚举）；`APPROVAL.decision`；`MCP_TOOL.status`。缺值/类型不符/算子不匹配 → 节点失败 `WF_CONDITION_INVALID`，不默认走任一分支（AC-09）。
- 模板占位符只允许 `{{input.<field>}}`、`{{node.<nodeId>.summary}}`、`{{node.<nodeId>.classificationLabel}}`、`{{node.<nodeId>.hitCount}}`；服务端解析白名单，缺失字段 → 保存/发布/运行失败；不做字符串拼接表达式。
- 检索/工具/模型输出作为**不可信数据**插入用户内容区，不进入系统指令；系统提示由服务端固定模板生成（提示注入基线，TM-78）。

### 4.6 超时、取消、重试、幂等与部分完成

- **超时**：模型 300s、检索 120s、MCP 60s（部署配置）；运行执行 30min（审批等待不计）。节点超时 → 节点失败 + 下游 `SKIPPED`；运行超时 → 当前节点 `CANCELLED`、运行 `FAILED`（`WF_RUN_TIMEOUT`），已完成节点保留。
- **取消**：API 条件更新运行到 `CANCELLED`（仅 `PENDING/RUNNING/WAITING_APPROVAL`），取消待审批；Worker 在节点边界/心跳检查并尽力中断模型流；已产生节点结果保留，后续节点不再执行（AC-23）。MCP 外部调用无法强杀时等待其超时，结果丢弃并记录。
- **重试**：仅失败节点；`POST /workflow-runs/{id}/retry {nodeId}` 校验运行 `FAILED` 且节点 `FAILED`；新增 `attempt+1` 的 `workflow_node_run`（`PENDING`），运行回 `PENDING` 并设置 `current_node_key=failedNodeKey`；上游已成功节点不重跑；下游 `SKIPPED` 节点重新变为 `PENDING`。不做自动无限重试。
- **幂等**：
  - HTTP 创建/重试/审批决定带 `Idempotency-Key`；运行创建唯一键 `(tenant_id, workflow_id, requester_user_id, idempotency_key)`；重复请求返回既有运行。
  - 运行状态条件更新（`PENDING→RUNNING`、`RUNNING→SUCCEEDED/FAILED/CANCELLED`）+ 租约是最终幂等守卫；重复领取/重复恢复不得二次执行。
  - 节点尝试唯一键 `(run,node,attempt)`；审批唯一 `(run,node,attempt)`；工具调用唯一 `(tenant_id, approval_id)`。
  - 崩溃恢复：模型/检索可安全重执行（只产生新的调用记录与估算成本）；MCP 工具若已有 `STARTED` 无终态，按 `WF_SIDE_EFFECT_UNKNOWN` fail-closed，必须重新审批并显式重试，不自动重放。
- **`WF_INTERNAL` 终态**：`claim_attempts` 耗尽或不可恢复内部错误时，当前节点 `RUNNING→FAILED`（无节点行则按 `current_node_key` 创建一次失败尝试），运行 `FAILED` 并写审计；`POST /retry` 可重试该节点，运行不再永久卡在 `RUNNING`。
- **部分完成**：节点记录独立持久化；失败/取消运行保留已完成节点、输出摘要与引用；不生成部分最终结果（只有 `END` 成功才写 `result_json`）。

### 4.7 工具调用、写工具审批与 plan 绑定

- 工具节点执行前必须满足：工具在 `mcp_tool` 全局目录 `ACTIVE`、租户 `mcp_tool_grant.enabled=TRUE`、参数通过 schema/长度/敏感键校验；否则不发起调用，节点失败 `WF_TOOL_NOT_AUTHORIZED`（AC-18）。
- 实现上**不直接调用 J2 `McpGateway.invoke`**（其 `mcp_invocation.approval_id` FK `approval`）：抽取 worker 内部共享的 `McpCallExecutor`（授权/端点/超时/结果上限/审计），J2 与工作流各自持久化到 `mcp_invocation` / `workflow_tool_invocation`；不得复制两套策略分支。J2 行为由 `IT-HITL-001`、`MCP-001..008` 回归守住。

**执行器策略（F-006）**：`McpCallExecutor` 接收显式 `McpExecutionPolicy`，不在执行器内部猜测调用方：

| 模式 | 调用方 | 允许风险 | 审批要求 | 持久化/审计 |
| --- | --- | --- | --- | --- |
| `READ_ONLY` | J2（M02 research） | 仅 `risk_level=READ`；`WRITE` 直接拒绝（保持 `McpGateway.validatePolicy` 现状） | 现有 `approval` 必须 APPROVED 且绑定 | `mcp_invocation` + `mcp.tool.invoked` |
| `APPROVAL_BOUND_WRITE` | M06 工作流 | READ 按目录；WRITE 仅在 `workflow_approval` APPROVED 且 `plan_sha256` 匹配时允许 | 写工具必须前置审批、重试重新审批、一个审批最多一次 | `workflow_tool_invocation` + `mcp.tool.invoked`（带 workflowRunId） |

- 策略由调用方传入；**不得**因为 M06 需要 WRITE 而放宽 J2 的 `READ_ONLY`。未知风险级别 fail-closed。
- 验收：J2 的 `IT-HITL-001`/`MCP-001..008` 必须证明 WRITE 仍被拒；M06 的 `SEC-WF-004`/`IT-WF-104` 证明 WRITE 只在审批后执行、重试重新审批。
- `tool.requires_approval=TRUE` 或 `risk_level=WRITE` 时，工具的**直接前驱必须是 `APPROVAL` 节点**且 `targetNodeKey` 指向该工具节点；发布校验与运行前复查。M02 目录全部 `requires_approval=TRUE`，因此模板/流程默认包含审批节点。
- 审批记录绑定：`params_ciphertext` 保存规范化参数（AES-256-GCM，复用 ADR-0017，AAD=`tenantId|workflowApprovalId`）；`params_sha256` 供审计核对；`plan_sha256` 绑定 `workflow_version_id + node_key + 目标工具 + 上游输出摘要`。执行前重算：审批状态 `APPROVED`、`decided_by != requested_by`、参数哈希/plan 哈希一致；任一不符 → `WF_PLAN_DRIFT`，不调用工具。
- 写工具重试：新 attempt → 新审批；旧审批/旧调用不可复用；审批详情标注“重试”，提示可能重复外部副作用（AC-08）。系统无法证明外部幂等时不自动重试。
- 结果：单次工具结果 ≤1 MiB；超限 `WF_TOOL_RESULT_LIMIT`（不静默截断）；结果文本只作为下游不可信输入，日志/审计只记哈希/大小/耗时。

### 4.8 运行准入、并发与 kill switch

- 准入在 API `POST /workflow-runs` 同事务内：
  1. `QuotaAdmissionService.lockTenant(tenantId)`（与 M04 相同锁顺序），随后 `admit(tenantId, userId)`（DEC-0048=A：按运行准入 1 单位）；
  2. `SELECT COUNT(*) FROM workflow_run WHERE tenant_id=? AND status IN ('PENDING','RUNNING','WAITING_APPROVAL')` 与按 `workflow_id` 的计数；
  3. 超过租户 10 / 单流程 5 → `429 RICCON-WF-429`，返回限制值与重试建议，不创建运行；
  4. 插入 `workflow_run`（`PENDING`）+ 审计；任一步失败整体回滚。
- “运行准入并发”包含等待审批的运行；“执行并发”是 worker 运行队列并发（等待审批不占），两者在文档/UI 明确区分。
- 停用流程（`status=DISABLED`）阻止新运行；在途运行继续；重新启用恢复。停用/启用由所有者和租户管理员执行并审计（AC-15/16）。
- kill switch：`RICCON_WORKFLOW_ENABLED`（默认 `true`）。关闭时 API 拒绝创建/重试/审批决定/复制模板（503 `RICCON-WF-503`），但**取消仍可用**（否则无法清空在途）；编辑器只读，列表/历史/审批记录可读；J1/J2 不受影响；已受理的在途运行继续到终态。API 启动时若开关为关闭，写一条 `workflow.feature.changed` 审计（actor=SYSTEM，tenant=platform），details 带 `source=DEPLOYMENT_CONFIG`、`instanceId`、`observedAt`；语义是“启动观测”，不是变更日志，多实例/重启重复可接受。暴露 `riccon_workflow_feature_enabled` gauge，便于发现“API 已关但 Worker 仍在领”。Worker 侧 `RICCON_WORKFLOW_CLAIM_ENABLED` 控制停止领取与优雅停机（§4.3）。Ops 立场：保留部署配置，不升为用户决策（OPS-M06-009）。

### 4.9 为什么不是 outbox / 第二个队列

| 方案 | 结论 | 理由 |
| --- | --- | --- |
| 复用 `agent_run_outbox` + `workflow` 通道 | 拒绝 | `run_id NOT NULL` FK `agent_run`；`OutboxPoller` 绑定 `agent_run.kind/status` 并 `markSucceeded`，改造成本与 J2 回归面过大 |
| 新增 `workflow_outbox` 表 | 拒绝 | 需复制/重构 claimer、轮询器、看门狗与清理；run+event 双写使幂等与崩溃恢复更复杂 |
| `workflow_run` 作为持久队列 | **采用** | 单写、状态即队列；租约/条件更新/唯一键足够；J2 outbox 零改动；代价是新增一条受限跨租户领取例外（TM-85） |

- 不新增 `EventTypes`、outbox 事件 schema 或 internal API 端点；API/Worker 仍只通过 MySQL 状态与租约协作。
- 跨租户领取例外只允许 claim 元数据（`id/tenant_id/status/lease_until`），登记在 TM-85 与 ADR-0016 的后续修订建议中。

### 4.10 模型节点与 M04 的关系

- 复用 `ModelRouter`（`purpose=REPORT`）与 M04 降级/端点允许列表/配额；不新增 `WORKFLOW` 用途、不改 `model_routing` CHECK 与平台路由 UI。
- `model_invocation` 复用：`run_id=NULL`、`conversation_id/message_id=NULL`、`workflow_run_id`/`workflow_node_run_id` 写入；成本按现有租户/模型/日聚合自动包含工作流调用；`purpose` 显示为 `REPORT`（成本口径说明，§16）。
- 节点可选 `modelId` 必须属于当前 REPORT 路由链且 `ACTIVE`；否则保存/发布/运行拒绝（防止绕过平台路由）。
- 配额：按运行准入 1 单位计（DEC-0048=A），与 M04 按请求数计一致；模型成本按 `model_invocation` 聚合；资源上限约束单运行模型节点数量；R-36 为已接受 residual，不新增 token/成本配额。
- 外部模型端点：沿用 M04 允许列表；演示端点只处理合成数据；生产 PII 必须走客户内网网关（合同 `constraints.security`）。

---

## 5. 审批模型

### 5.1 扩展现有 `approval` vs 新建 `workflow_approval`

| 维度         | 扩展 `approval`                                                                                                      | 新建 `workflow_approval`                      |
| ------------ | -------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| 现有约束     | `research_task_id NOT NULL` + FK；`action_type CHECK=MCP_TOOL_CALL`；唯一键 `(tenant_id, run_id, stage, attempt_no)` | 无历史包袱；按工作流运行/节点/尝试建模        |
| J2 回归      | 需改 NOT NULL/CHECK/唯一键 + 新列，表可能已大，ALTER 阻塞；J2 查询需兼容                                             | 零改动；J2 不看新表                           |
| 审批人模型   | J2 只有“有权限的审批人”，无指定角色/人、无自批规则                                                                   | 指定角色/人、禁自批、无审批人阻断             |
| 工具参数绑定 | `mcp_invocation.approval_id` FK `approval`，工作流无法复用                                                           | 自有 `workflow_tool_invocation` FK            |
| 复用         | 可复用 `approval.decided` 动作与部分 UI                                                                              | 需新动作/端点；可复用 ADR-0017 加密与 UI 模式 |

**结论**：新建 `workflow_approval` + `workflow_approval_assignee`；不扩展 J2 `approval`。复用 ADR-0017 的信封加密实现（`purpose=approval-params`）、M02 的“审批详情只显示脱敏摘要”原则，以及 M10 审计机制。

### 5.2 审批人解析与禁自批

- 审批节点配置二选一：`assigneeType=USER` + `assigneeUserIds`（1..50）或 `assigneeType=ROLE` + `assigneeRole`（`TENANT_ADMIN`/`WORKFLOW_EDITOR`/`MEMBER`）。
- 解析顺序：本租户 → 账号 `ACTIVE` → 部门（如角色无部门限制，不额外过滤；部门级审批不在 M06）→ 排除运行发起人。解析结果为空 → 运行保持 `WAITING_APPROVAL`，**不新增 `blocked_reason` 列**，用 `WorkflowRun.errorCode=WF_NO_APPROVER` + 固定中文 `errorMessage`（“需先配置非发起人审批人”）表达；创建一条无可用 assignee 的 `workflow_approval` 记录用于时间线/审计；不自动放行、不跳过、不允许平台管理员跨租户代批（AC-06，`SEC-WF-001` 断言）。
- 发布校验：审批节点必须配置审批人；若按角色解析后租户内没有除发布者外的可用成员，发布返回 `400 RICCON-WF-400`（`reason=NO_APPROVER`）并提示配置入口（AC-06；单管理员租户需先增加第二名可审批成员）。
- 自批：`decided_by == workflow_run.requester_user_id` 一律 403 `RICCON-AUTH-403` 并写拒绝审计，运行保持等待；不因调用者是租户管理员而放行（AC-06）。发起人的待办项从 inbox 过滤，界面不提供入口。
- 审批人必须在节点指定范围内；范围外/已停用/他租户 → 404（不可见运行）或 403（可见但无能力），不泄露运行内容。

### 5.3 审批可见性与 ACL

- 待办列表只显示“分配给当前用户”的 PENDING 项；按 `created_at ASC`；每项显示流程名、节点名、发起人、等待时长、数据范围摘要；空态固定文案（AC-05）。`GET /workflow-approvals/{id}` 同样只对 assignee 开放（未分配 404）；租户管理员在运行详情只看到审批状态/决定/审批人与时间等元数据，不因管理员身份获得待审批参数摘要。
- 审批详情/待办显示：将要执行的动作、目标工具、`dataScopeSummary`（运行范围/部门/引用计数）、脱敏参数摘要；**不显示**完整工具参数、完整提示词、凭据、文档正文/OCR 文本。若审批人在 M05 文档 ACL 下无权访问引用文档，只显示脱敏摘要与来源标识（AC-05/18、OQ-M06-13）。`WorkflowDataScopeSummary` 只含范围/部门/计数，不含标题/片段/正文。
- 审批记录（审批人、时间、决定、理由）在运行详情与审计中可查；拒绝理由必填（≤500 字符），进入审批记录与审计（§8 隐私说明）。
- 无审批通知/代理/转交/超时自动放行（non-goal）；`expires_at` 不使用，审批不过期（M06 不自动放行）。

### 5.4 审批状态与运行推进

- 审批节点执行时把 `current_node_key` 置为该审批节点；`APPROVE` 时条件更新审批 `PENDING→APPROVED`，写 `decided_by/decided_at/comment`；审批节点的 `workflow_node_run` 置 `SUCCEEDED`；运行 `WAITING_APPROVAL→PENDING` 且 `current_node_key=targetNodeKey`；同事务写审计；审计失败整体回滚。
- `REJECT`：审批 `→REJECTED`；审批节点 `workflow_node_run→FAILED`（`WF_APPROVAL_REJECTED`）；运行 `→FAILED`，后续节点 `SKIPPED`，不写最终结果；`comment` 在请求 schema 条件必填（`decision=REJECT` 时 `minLength=1`），缺失/空返回 400 `RICCON-WF-400`；审批记录保留理由（AC-05，`SEC-WF-001` 断言）。
- 重复决定/并发决定：条件更新 0 行 → `409 RICCON-WF-409`；一个 200、一个 409。
- 取消运行：所有 PENDING 审批 `→CANCELLED`；已决定的审批保留。
- 审批详情/决定的 URL 级权限 `workflow.approve` + 服务层 assignee/自批校验；平台管理员 404。

---

## 6. 权限模型

### 6.1 新增权限码与角色

| 权限码             | 含义                                          | `PLATFORM_ADMIN` | `TENANT_ADMIN` | `WORKFLOW_EDITOR`                   | `MEMBER`                |
| ------------------ | --------------------------------------------- | ---------------- | -------------- | ----------------------------------- | ----------------------- |
| `workflow.read`    | 读可运行流程、自己的草稿/运行、模板列表、限制 | 否（404）        | 是（全部）     | 是（自己的草稿 + 可运行）           | 是（可运行 + 自己发起） |
| `workflow.edit`    | 创建/编辑草稿、校验、复制模板、另存副本       | 否               | 是（全部）     | 是（自己的）                        | 否                      |
| `workflow.publish` | 发布、停用/启用                               | 否               | 是（任意范围） | 是（自己的；DEPT 需部门负责人关系） | 否                      |
| `workflow.run`     | 创建运行、取消/重试自己的运行                 | 否               | 是             | 是                                  | 是                      |
| `workflow.approve` | 待办/审批详情/决定                            | 否               | 是（仅被指派） | 是（仅被指派）                      | 是（仅被指派）          |
| `workflow.manage`  | 移交流程所有权                                | 否               | 是             | 否                                  | 否                      |

- 新增系统角色 `WORKFLOW_EDITOR`（展示名“流程编辑者”）：`workflow.read/edit/publish/run/approve`。由租户管理员通过既有 `PUT /api/v1/users/{userId}/roles`（`tenant.role.manage`）授予/撤销；审计动作沿用 `permission.changed`（`details.role=WORKFLOW_EDITOR`），立即生效。撤销后已有草稿保留但只读（无 `workflow.edit`）。
- V15 为存量租户插入 `WORKFLOW_EDITOR` 角色行与角色权限；新租户由 `RoleDefinitions.tenantSystemRoles()` 创建。迁移生成的系统角色 ID 使用确定性 `BINARY(16)`（`SHA-256('role:WORKFLOW_EDITOR:'+tenant_id)` 前 16 字节），是 ADR-0012 UUIDv7 规则的系统数据例外（不对外暴露语义）；Builder 必须在迁移测试中断言幂等与唯一。
- 平台管理员不持有任何 `workflow.*` 权限；`/api/v1/workflows/**`、`/workflow-runs/**`、`/workflow-approvals/**`、`/workflow-templates/**` 对 `tenantId=platform` 统一 404（AC-15/24）。
- **平台租户 404 机制（F-005）**：新增 `WorkflowPlatformTenantFilter`（servlet filter，位于认证之后、`PermissionInterceptor`/RBAC 之前；`@Order` 显式排在权限拦截器前），对上述路径检查已认证主体租户；`tenantId=platform` 直接写 404 `RICCON-TENANT-404` 并结束，不进入 `@RequirePermission`，因此不会先返回 403。未认证仍 401；非 platform 继续走权限链。禁止只在 controller 内做 404（会被方法级权限拦截器抢先 403）。`TEN-M06-01` 必须断言平台管理员 404（不是 403），`T01` 实现 guard、`T05` 端点集成测试。

### 6.2 范围与资源级判定

- 定义可见性：
  - 草稿：所有者 + `TENANT_ADMIN` 可读/改；其他编辑者不可见（404）。AC-01 的“另一编辑者”并发用例由所有者 + 租户管理员（或同一管理员两个会话）覆盖；如 Product 要求同侪协同编辑，需新增显式草稿共享模型（§16）。
  - 已发布：按版本 `run_scope` 判定。`ONLY_SELF` 仅所有者可运行/可见；`DEPARTMENT` 该部门当前 `ACTIVE` 成员可运行；`TENANT` 本租户启用成员可运行。停用流程仍可读，不可新运行。
- 发布范围限制：`TENANT_ADMIN` 可选 `ONLY_SELF`/`TENANT`；`WORKFLOW_EDITOR` 可选 `ONLY_SELF`/`DEPARTMENT`，且 `DEPARTMENT` 必须是**本人当前负责的部门**（`department.manager_user_id = userId` 且部门 `ACTIVE`）。`manager_user_id` 只限制可发布范围，**不授予任何能力**；编辑能力来自 `WORKFLOW_EDITOR` 角色（回应 Product “不得把负责人关系当权限”）。
- 运行可见性：发起人 + `TENANT_ADMIN` 可看详情/结果；流程所有者只看自己流程的运行元数据列表（不看他人结果）；审批人只看分配给自己的审批项摘要；其他部门成员不可见（404）。租户管理员查看他人运行写 `workflow.run.admin_read`（`reason=MANAGEMENT_READ`）。
- 取消：仅发起人 + `TENANT_ADMIN`；部门负责人不默认可取消他人运行（OQ-M06-15）。重试：发起人 + `TENANT_ADMIN`（AC 未列重试主体；按发起人/管理员最小化）。
- 停用/启用：所有者 + `TENANT_ADMIN`；移交：仅 `TENANT_ADMIN`，目标必须是本租户 `ACTIVE` 且具备 `workflow.edit` 的成员；原所有者失去编辑权（管理员仍可管理）。
- 部门成员移出/部门停用：下一次请求按新范围判定；已发起运行保留，仅按原可见规则可查；账号禁用后不能发起/审批，下一次请求生效。
- 403/404：跨租户/跨部门/私有草稿/私有模板/他人运行详情/平台管理员 → 404 `RICCON-TENANT-404`；同租户可见但无能力（非所有者编辑、非发起人取消、非 assignee 审批）→ 403 `RICCON-AUTH-403`。列表不返回不可见对象的名称或数量。

### 6.3 权限与部门/空间的关系

- 不把编排权限绑定到 `workspace` 所有者/成员关系；流程不属于工作空间，避免与部门/租户范围叠加产生第二套可见性（Product §12.1）。
- 流程运行结果不自动写入工作空间产物（OQ-M06-14 P1：详情可查看；另存产物后续评估），因此不需要空间谓词；若未来另存产物，复用 `artifact` 与空间谓词。
- 知识库检索仍走 M05 文档 ACL；审批摘要按审批人 ACL 最小可见；M06 不新增文档权限。

---

## 7. 契约

### 7.1 版本与文件

- `contracts/openapi.yaml`：`1.5.0-m05b` → **`1.6.0-m06`**（additive）。
- `contracts/internal-api.yaml`：**不修改**（工作流通过 `workflow_run` 队列与轮询触发，Worker 不新增入站端点）。
- `contracts/events/`：**不新增事件**（M06 不使用 outbox；`envelope.schema.json` 不变）。
- `platform-domain`：新增 `PermissionCodes.WORKFLOW_*`、`AuditActions.WORKFLOW_*`、`AuditTargetType.WORKFLOW/WORKFLOW_RUN/WORKFLOW_TEMPLATE`、`RoleCodes.WORKFLOW_EDITOR`、工作流枚举。
- `contracts/README.md`：记录 `1.6.0-m06` 交付、端点、事件、幂等键、错误码、契约测试位置与唯一契约 writer。

### 7.2 端点（公开 REST，`/api/v1`）

| 方法 | 路径                                                | 权限（URL + 服务层） | 说明                                                                |
| ---- | --------------------------------------------------- | -------------------- | ------------------------------------------------------------------- |
| GET  | `/workflows`                                        | `workflow.read`      | `view=MINE/RUNNABLE/ALL`（`ALL` 仅租户管理员）；分页                |
| POST | `/workflows`                                        | `workflow.edit`      | 新建空白/从模板创建草稿；可选 `Idempotency-Key`                     |
| GET  | `/workflows/runtime-limits`                         | `workflow.read`      | 部署限制、保留、开关状态（租户只读；字面路径优先于 `{workflowId}`） |
| GET  | `/workflows/{workflowId}`                           | `workflow.read`      | 详情 + `draftGraph`（仅所有者/管理员）                              |
| PUT  | `/workflows/{workflowId}/draft`                     | `workflow.edit`      | 保存草稿；`version` 乐观锁，冲突 409 `RICCON-WF-409`                |
| POST | `/workflows/{workflowId}/duplicate`                 | `workflow.edit`      | 另存副本（AC-01 冲突后）                                            |
| POST | `/workflows/{workflowId}/validate`                  | `workflow.edit`      | 返回逐项问题（节点/原因/修正入口）                                  |
| POST | `/workflows/{workflowId}/publish`                   | `workflow.publish`   | 生成不可变版本；`Idempotency-Key` 必填                              |
| GET  | `/workflows/{workflowId}/versions`                  | `workflow.read`      | 版本列表（旧版本仍可运行，除非流程停用）                            |
| POST | `/workflows/{workflowId}/disable`                   | `workflow.publish`   | 停用；审计前后状态                                                  |
| POST | `/workflows/{workflowId}/enable`                    | `workflow.publish`   | 重新启用；审计                                                      |
| POST | `/workflows/{workflowId}/transfer`                  | `workflow.manage`    | 移交所有权；审计 from/to                                            |
| POST | `/workflows/{workflowId}/runs`                      | `workflow.run`       | 创建运行；`Idempotency-Key` 必填；429/503                           |
| GET  | `/workflows/{workflowId}/runs`                      | `workflow.read`      | 该流程运行列表（所有者/管理员；按可见性过滤）                       |
| GET  | `/workflow-runs`                                    | `workflow.read`      | 运行历史：流程/版本/状态/时间/发起人过滤；分页                      |
| GET  | `/workflow-runs/{workflowRunId}`                    | `workflow.read`      | 详情：节点时间线、审批、引用、结果；到期/不可见 404                 |
| POST | `/workflow-runs/{workflowRunId}/cancel`             | `workflow.run`       | 取消；`Idempotency-Key`                                             |
| POST | `/workflow-runs/{workflowRunId}/retry`              | `workflow.run`       | 重试失败节点；`Idempotency-Key`                                     |
| GET  | `/workflow-approvals`                               | `workflow.approve`   | 待办（仅分配给本人；`created_at ASC`）                              |
| GET  | `/workflow-approvals/{workflowApprovalId}`          | `workflow.approve`   | 审批详情（仅 assignee；未分配 404；ACL 最小可见）                  |
| POST | `/workflow-approvals/{workflowApprovalId}/decision` | `workflow.approve`   | 批准/拒绝；`Idempotency-Key`；禁自批 403                            |
| GET  | `/workflow-templates`                               | `workflow.read`      | 内置模板（M06 1 个；M06b 再加用户共享/升级字段）                    |
| POST | `/workflow-templates/{templateId}/copy`             | `workflow.edit`      | 复制为当前用户草稿（默认仅自己）；`Idempotency-Key`                 |

M06b 预留（M06 契约标 `x-riccon-deferred: M06b`，不实现）：`POST /workflows/{workflowId}/templates`（另存为模板）、`PATCH/DELETE /workflow-templates/{templateId}`（共享/删除）、模板升级提示字段。M06 的 `WorkflowTemplate` 已包含 `source/visibility/builtInVersion/latestBuiltInVersion/upgradeAvailable` 可选字段，M06b 只增加写端点。

### 7.3 错误码与语义

沿用既有：跨租户/不可见 `404 RICCON-TENANT-404`；同租户可见无能力 `403 RICCON-AUTH-403`；CSRF/认证不变。

新增：`RICCON-WF-400`（图/参数/条件/范围/模板非法、发布校验失败、无可用审批人）、`RICCON-WF-409`（草稿版本冲突、审批已决定、运行状态冲突）、`RICCON-WF-429`（租户/流程并发上限）、`RICCON-WF-503`（编排关闭/引擎不可用）。

运行/节点异步稳定码（响应字段/UI，不是 HTTP code）：`WF_INVALID_GRAPH`、`WF_NODE_LIMIT`、`WF_STEP_LIMIT`、`WF_RUN_TIMEOUT`、`WF_NODE_TIMEOUT`、`WF_MODEL_UNAVAILABLE`、`WF_MODEL_OUTPUT_INVALID`、`WF_RETRIEVAL_FAILED`、`WF_CONDITION_INVALID`、`WF_TOOL_NOT_AUTHORIZED`、`WF_TOOL_RESULT_LIMIT`、`WF_APPROVAL_REQUIRED`、`WF_NO_APPROVER`、`WF_APPROVAL_REJECTED`、`WF_SIDE_EFFECT_UNKNOWN`、`WF_PLAN_DRIFT`、`WF_RESULT_LIMIT`、`WF_FEATURE_DISABLED`、`WF_CANCELLED`、`WF_INTERNAL`。

### 7.4 幂等与轮询

- `Idempotency-Key`（8..128）：创建运行、发布、重试、审批决定必填；同一主体 + 资源 + 键返回既有结果；不跨用户复用。
- 运行详情/历史用**轮询**（默认 3s，`WorkflowRun.updatedAt` 变化才重绘；契约已补 `updatedAt`）；**终态即停止轮询**，`result` 只在终态读取一次；列表不返回 result。不新增 SSE。理由：M06 无逐 token 流、SSE 增加连接与网关成本；AC-04 的“刷新后一致”由 DB 权威状态满足。`ETag`/`If-None-Match` 或 `includeResult=false` 作为后续优化，不阻塞 M06（OPS-M06-010）。
- 列表分页 `page/size` 与全局规则一致（size ≤100）；运行历史过滤时间范围带偏移，最长 180 天。

### 7.5 合同与依赖影响

- `AGENTS.md` **不需要修改**：命令、技术栈、工具、DoD 与 M06/M26 路线图已在合同内；预算由 DEC-0046 的模块预算记录。若 Lead 认为需把「M06 不与 J2 outbox 共用」写入合同，只建议在 `constraints` 或 M06 路线图 goal 增补一句，不直接改本 ADR 范围。
- 依赖：不新增 Java/前端库（画布用原生 SVG/HTML；不加 React Flow/图数据库/工作流引擎）；不新增中间件；V15 是唯一迁移；M06b 不新增迁移（V15 预留 `workflow_user_template`）。
- 运行配置新增键见 §10.1；不含密钥。

### 7.5.1 契约测试

Builder 必须新增 `WorkflowContractM06Test`：每个 operationId 的请求/响应 schema、错误码、枚举、`x-riccon-permission`、M06b deferred 字段只读/不存在写入路径。M06 契约由 `T-M06-01` 独占写入；其他任务只读或提变更请求（`contracts/README.md` §7）。

---

## 8. 审计

### 8.1 动作清单

| 动作                                     | 目标类型       | 写入方     | details（白名单）                                                                                                                     |
| ---------------------------------------- | -------------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `workflow.created`                       | `WORKFLOW`     | api        | `workflowId`、`sourceTemplateId?`、`sourceTemplateType?`                                                                              |
| `workflow.draft.saved`                   | `WORKFLOW`     | api        | `workflowId`、`version`、`nodeCount`                                                                                                  |
| `workflow.published`                     | `WORKFLOW`     | api        | `workflowId`、`workflowVersionId`、`versionNo`、`runScope`、`departmentId?`、`nodeCount`                                              |
| `workflow.disabled` / `workflow.enabled` | `WORKFLOW`     | api        | `workflowId`、`previousStatus`、`status`                                                                                              |
| `workflow.owner.transferred`             | `WORKFLOW`     | api        | `workflowId`、`fromUserId`、`toUserId`                                                                                                |
| `workflow.run.requested`                 | `WORKFLOW_RUN` | api        | `workflowId`、`workflowVersionId`、`workflowRunId`、`versionNo`、`nodeCount`                                                          |
| `workflow.run.started`                   | `WORKFLOW_RUN` | worker     | `workflowRunId`、`workflowId`                                                                                                         |
| `workflow.run.completed`                 | `WORKFLOW_RUN` | worker     | `workflowRunId`、`status`、`durationMs`、`nodeCount`、`resultBytes`                                                                   |
| `workflow.run.failed`                    | `WORKFLOW_RUN` | worker     | `workflowRunId`、`errorCode`、`failedNodeId?`                                                                                         |
| `workflow.run.cancelled`                 | `WORKFLOW_RUN` | api/worker | `workflowRunId`、`previousStatus`、`reason?`                                                                                          |
| `workflow.run.retried`                   | `WORKFLOW_RUN` | api        | `workflowRunId`、`nodeId`、`attempt`                                                                                                  |
| `workflow.run.admin_read`                | `WORKFLOW_RUN` | api        | `workflowRunId`、`scope`(`LIST/DETAIL`)、`reason`（固定 `MANAGEMENT_READ`）                                                           |
| `workflow.approval.requested`            | `WORKFLOW_RUN` | worker     | `workflowRunId`、`workflowApprovalId`、`nodeId`、`assigneeCount`、`actionKind`、`targetToolName?`                                     |
| `workflow.approval.decided`              | `WORKFLOW_RUN` | api        | `workflowRunId`、`workflowApprovalId`、`nodeId`、`decision`、`approverUserId`、`targetToolName?`、`decisionComment?`（≤500，见 §8.3） |
| `workflow.feature.changed`               | `WORKFLOW`     | api        | `enabled`、`source`（`DEPLOYMENT_CONFIG`）、`reason?`                                                                                 |

- 复用 `mcp.tool.invoked` 记录工作流工具调用：`details` 追加可选 `workflowRunId`、`workflowNodeId`；`approvalId` 为工作流审批 ID；不新增第二套工具审计动作。
- 授权/撤销流程编辑权限沿用 `permission.changed`（`role=WORKFLOW_EDITOR`、`change=GRANT/REVOKE`）；不新增动作。
- M06b：`workflow.template.saved/shared/unshared` 在 M06b 契约与白名单中新增；M06 不写。

### 8.2 fail-closed 与范围

- 所有变更动作与业务状态同事务写审计；审计失败 → 业务回滚（ADR-0007）。Worker 的运行开始/完成/失败/审批请求同样 fail-closed。
- details 只允许 ID、枚举、计数、版本号、脱敏摘要；**禁止**图 JSON、prompt/query 模板、节点输出、工具参数、凭据、文档正文、OCR 文本、完整 PII。
- 租户管理员查看他人运行：LIST 每次一条 `admin_read`（`scope=LIST`）；DETAIL 仅在发起人不是本人时写一条（`scope=DETAIL`）；按正常权限查看自己的运行不写。
- M10 查询/导出机制不变：新增动作必须加入 `AuditDetailsPolicy` 写入与读取白名单；导出字段白名单不含新敏感内容；平台管理员仍不查租户明细。

### 8.3 隐私说明

`workflow.approval.decided.decisionComment` 是 Product AC-16 要求的“审批含理由”，属于用户输入的业务说明（≤500 字符）。风险：可能包含 PII/敏感信息。控制：审批 UI 明示“理由会进入审计并按审计保留期保存”；输入长度限制；导出沿用 M10 字段白名单；不写入 prompt/文档正文。若安全评审不接受，替代方案是审计只写 `commentSha256` 并在审批记录中保留理由（需 Product 确认 AC-16 口径，§16）。**状态：U-05 未解决**（owner=安全评审/Product，due=M06 gate 前）；当前是临时默认，不得在评审中声称已接受；M06 gate 前必须二选一并补测试（F-013）。

---

## 9. 安全与威胁模型增补（T4）

### 9.1 新增信任边界

`TB-13 工作流定义 ↔ 执行引擎`：流程定义、节点配置、运行输入、检索/模型/工具输出都是**用户可控或不可信数据**；执行引擎只按封闭节点/算子/模板占位符解释，不执行任意代码、脚本、HTTP，不把不可信内容拼进系统指令。Worker 仍是唯一执行方，API 不执行流程。

### 9.2 必须落地的缓解（TM-72..TM-85）

| ID    | 缓解                                                                                                                                              | 落点       | 验证                                              |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- | ------------------------------------------------- |
| TM-72 | 图/节点/算子/模板占位符均为服务端封闭 schema；未知类型/字段/表达式拒绝；节点 ≤50、边 ≤100、indegree ≤1、无环；保存/发布/运行三处校验              | api/worker | `IT-WF-101`、`SEC-WF-005`                         |
| TM-73 | 发布版本不可变（触发器 + 无更新路径）；运行绑定版本快照与 `graph_sha256`；草稿修改不影响在跑运行                                                  | api        | `IT-WF-102`、`E2E-M06-02`                         |
| TM-74 | 双重 RBAC + 范围：URL `workflow.*` + 服务层 owner/发起人/部门/租户；`WorkflowPlatformTenantFilter` 在权限拦截器前对 platform 租户返回 404（不是 403）；跨租户/跨部门/私有 404；可见无能力 403；列表不泄露名称/数量 | api | `SEC-WF-002/003/006`、`TEN-M06-01`、`DEPT-M06-01` | 4.13/§6 |
| TM-75 | 审批绑定：assignee 解析、禁自批、无审批人阻断不自动放行、工具参数密文 + `params_sha256` + `plan_sha256`、写工具重试重新审批、一个审批最多执行一次 | api/worker | `SEC-WF-001/004`、`IT-WF-104`                     |
| TM-76 | 审批/运行详情最小可见：不返回完整 prompt/工具参数/凭据/文档正文；审批摘要按审批人 M05 ACL 遮蔽；`sourceStatus` 遮蔽                               | api        | `SEC-WF-004`、`IT-WF-106`                         |
| TM-77 | 工具策略：J2 `READ_ONLY` 继续拒绝 WRITE；M06 `APPROVAL_BOUND_WRITE` 只在审批/plan 校验后允许 WRITE；工具状态/租户授权在保存/发布/执行三处复查；无任意 HTTP/脚本节点 | api/worker | `SEC-WF-004`、`IT-WF-104/105`、`IT-HITL-001`、`MCP-001..008` | 4.13/§4.7 |
| TM-78 | 提示注入：检索/模型/工具输出视为不可信数据，不进入系统指令；系统提示服务端固定；模型不能改变图/边/工具/审批；canary 凭据/内网地址不泄露           | worker     | `SEC-WF-004`、`INJ-*` 扩展                        |
| TM-79 | 资源/队列上限：节点/步数/超时/并发/结果大小服务端强制；`queue_depth`/`oldest_pending_age`/`lease_lost`/`claim_exhausted` 可观测；超限稳定码与可读提示，不静默截断、不留半成品 | api/worker | `IT-WF-109`、`E2E-M06-09`、`OPS-M06-01` | 4.13/§4.8/§10.2 |
| TM-80 | 数据最小化与保留：运行/审批/工具记录只存必要字段；默认 180 天到期不可见；不提供在线删除；日志/审计脱敏；不返回对象 key                            | api/worker | `IT-WF-106/107`、`IT-AUDIT-M06-01`                |
| TM-81 | 执行幂等/租约：每槽位 batch=1、领取后先启动心跳、续期间隔 ≤lease/3、调度/心跳池 ≥并发；`workflow_run` 条件领取、运行状态条件更新、节点/审批/工具唯一键；取消优先；崩溃恢复不重复外部副作用；未知副作用 fail-closed | worker | `IT-WF-103/104` | 4.13/§4.3/§4.6 |
| TM-82 | kill switch/回滚：关闭后新运行/重试/审批不受理但取消可用、历史只读、J1/J2 不受影响；`workflow.feature.changed` 观测审计 + feature gauge；不做 down migration；AC-23 判定 SQL/拒绝路径/演练进 RUNBOOK | api/ops | `IT-WF-110`、`OPS-M06-01` | 4.13/§10.4/§10.5 |
| TM-83 | 审计 fail-closed + action-aware 白名单；管理性读取审计；M10 查询/导出不变；新动作必须进读取白名单                                                 | api/worker | `IT-WF-108/112`、`IT-AUDIT-M06-01`                |
| TM-84 | 权限/部门边界：`WORKFLOW_EDITOR` 仅租户管理员授予；`manager_user_id` 只限制 DEPT 发布范围、不授权；禁用/移出部门下一次请求生效；移交流程审计      | api        | `SEC-WF-006`、`DEPT-M06-01`                       |
| TM-85 | `workflow_run` 跨租户领取例外：只读 `id/tenant_id/status/lease_until`（静态列集合断言），领取后立即打开租户上下文；不得通过领取路径读业务内容；非法 tenant/领取失败记 `claim_failures_total{reason}`/denied 审计；索引 `(status,lease_until,id)` 不以 tenant 开头，登记为例外 | worker | `IT-WF-103`、`TEN-M06-01` | 4.13/§4.3 |

### 9.3 明确不开放（负向控制，AC-24）

- 无 `CODE`/`SCRIPT`/`HTTP`/`PARALLEL`/`LOOP`/`SUBFLOW`/`CRON` 节点类型；契约枚举、服务端校验、UI 入口、帮助文案均不出现；接口对未知类型返回 400 `RICCON-WF-400`，不落库。
- 无表达式解析器、无 `eval`、无模板语言；条件只允许封闭算子；占位符只允许字段读取。
- 无定时/事件触发端点；只有手动 `POST /workflows/{id}/runs`。
- 开放代码执行前必须另立模块并重开威胁模型（TB-06/E-06-1/R-06-1 不复用为 M06 已开放）。

### 9.4 发布阻断项（M06 gate）

- **B-27**：代码执行/脚本/任意 HTTP/并行/循环/定时入口未关闭或可绕过；工具白名单/审批绑定失效。
- **B-28**：权限/租户/部门 403/404 矩阵不闭合；平台管理员可读取租户流程/运行/审批；自批可成功。
- **B-29**：队列参数/租约/心跳/调度池不闭合，资源上限/并发/结果大小/超时不可验证，或 kill switch/AC-23 判定 SQL/回滚演练/RUNBOOK 缺失。
- **B-30**：威胁模型 M06 增补未评审、审计动作/details 白名单缺失、提示注入/审批摘要 ACL 用例失败。

关闭条件与证据见 `docs/security/THREAT-MODEL.md` §8；任一未关闭不得通过 M06 gate。

### 9.5 残余风险（新增）

| ID   | 风险                                                                    | 影响               | 收敛/接受                                                                                 |
| ---- | ----------------------------------------------------------------------- | ------------------ | ----------------------------------------------------------------------------------------- |
| R-31 | 提示注入无法根治：模型节点可能被检索/工具内容诱导生成错误结果或工具参数 | 错误内容/越权尝试  | 不可信数据隔离 + 固定系统提示 + 工具白名单 + 写工具审批 + 引用校验；持续回归 `SEC-WF-004` |
| R-32 | 租户管理员可读取本租户全部运行/结果（管理性读取）                       | 管理员可见敏感内容 | 与 M05 U8 一致：审计 `admin_read`；平台管理员不可读；不声称对租户管理员保密               |
| R-33 | 审批是控制而非串谋证明；同租户管理员可指派自己人审批                    | 审批绕过           | 禁自批 + assignee 范围 + 审计；高风险客户可要求双人/平台审批（后续）                      |
| R-34 | 运行结果/节点输出存 MySQL 明文（租户隔离 + 备份）                       | DB 高权限者可读    | 与现有会话/知识库内容同级；生产 DB 最小权限/堡垒机（M09）；不新增字段级加密               |
| R-35 | 180 天保留只做到期不可见，物理清理归 M09                                | 合规时间差         | 与 M05 R-26 一致；M06 不声称已删除；M09 release blocker                                   |
| R-36 | M06 v1 按运行计 1 个配额单位，模型节点不逐次计（DEC-0048=A 接受） | 配额套利 | owner=Lead/Verifier；due=M06 gate 前；accepted_reason=DEC-0048=A 接受；节点 ≤50、步数 ≤200、运行 30min、租户并发 10 约束套利；如客户要求逐次计费需共享配额组件/新 ADR |
| R-37 | 单入边树状图不支持合并/并行，复杂流程需拆多个流程                       | 表达力限制         | 已在 non-goals；文案不声称并行/循环；后续模块另立 ADR                                     |

---

## 10. 运维与回滚

### 10.1 部署配置（租户只读）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `RICCON_WORKFLOW_ENABLED` | `true` | kill switch；关闭时新运行/重试/审批决定/复制模板 503，取消仍可用 |
| `RICCON_WORKFLOW_DISABLED_REASON` | 空 | 可选；关闭原因，API 回退固定文案；租户只读 |
| `RICCON_WORKFLOW_MAX_NODES` | `50` | 单流程节点上限 |
| `RICCON_WORKFLOW_MAX_STEPS` | `200` | 单运行节点尝试步数上限 |
| `RICCON_WORKFLOW_MODEL_TIMEOUT_SECONDS` | `300` | 模型节点超时 |
| `RICCON_WORKFLOW_RETRIEVAL_TIMEOUT_SECONDS` | `120` | 检索节点超时 |
| `RICCON_WORKFLOW_MCP_TIMEOUT_SECONDS` | `60` | MCP 工具节点超时 |
| `RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES` | `30` | 运行执行超时（审批等待不计） |
| `RICCON_WORKFLOW_TENANT_CONCURRENCY` | `10` | 租户准入并发（含等待审批） |
| `RICCON_WORKFLOW_FLOW_CONCURRENCY` | `5` | 单流程准入并发 |
| `RICCON_WORKFLOW_RESULT_MAX_BYTES` | `5242880` | 单次运行输出+结果上限（5 MiB） |
| `RICCON_WORKFLOW_WORKER_CONCURRENCY` | `10` | worker 运行队列槽位 |
| `RICCON_WORKFLOW_CLAIM_BATCH` | `1` | v1 固定 1（每槽位领 1 个 run）；>1 启动失败 |
| `RICCON_WORKFLOW_SCHEDULER_POOL_SIZE` | `10` | 独立调度池，必须 ≥ worker 并发 |
| `RICCON_WORKFLOW_HEARTBEAT_CONCURRENCY` | `10` | 心跳池线程数，必须 ≥ worker 并发 |
| `RICCON_WORKFLOW_HEARTBEAT_INTERVAL_SECONDS` | `30` | 续期间隔，必须 ≤ lease/3 |
| `RICCON_WORKFLOW_LEASE_SECONDS` | `90` | 执行租约 |
| `RICCON_WORKFLOW_CLAIM_ENABLED` | `true` | Worker 侧停止领取开关（优雅停机/紧急停止） |
| `RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS` | `120` | 停止领取后的在途等待上限 |
| `RICCON_WORKFLOW_RETENTION_DAYS` | 租户 `retention_days`（默认 180） | 运行到期不可见；M06 不新增租户覆盖入口 |
| `RICCON_DB_MAX_ALLOWED_PACKET` | `16777216` | JDBC `maxAllowedPacket`；预检服务器 `max_allowed_packet` ≥8 MiB 且 ≥结果上限 + 余量 |

启动校验（fail-closed，错误信息给出可操作键）：

- `WORKER_CONCURRENCY >= 1`；`CLAIM_BATCH == 1`；调度池 ≥ worker 并发；心跳池 ≥ worker 并发；`HEARTBEAT_INTERVAL <= LEASE_SECONDS / 3`。
- 超时/并发/上限为正；`RESULT_MAX_BYTES` 与 `max_allowed_packet`/JDBC `maxAllowedPacket` 满足 ≥8 MiB 且 ≥结果上限 + JSON/协议余量。
- 调度使用**独立** `ThreadPoolTaskScheduler`，不复用 `spring.task.scheduling.pool.size`；若误用共享池，启动校验必须把 outbox 既有任务计入所需池大小。
- `GET /workflows/runtime-limits` 返回 `featureEnabled`/`disabledReason`/限制/保留；租户只读，不能修改（OQ-M06-16）。

### 10.2 指标与告警（M06，Micrometer）

| 指标 | 类型/标签 | 用途 / 建议阈值（RUNBOOK，M06 负责处置） |
| --- | --- | --- |
| `riccon_workflow_queue_depth{status}` | Gauge | `PENDING`/`WAITING_APPROVAL` 深度；PENDING >50 持续 10min 告警 |
| `riccon_workflow_queue_oldest_pending_age_seconds` | Gauge | 最老 PENDING 年龄；>300s 告警，>900s 升级 |
| `riccon_workflow_lease_lost_total` | Counter | 任何增长都告警（重复执行/租约风险） |
| `riccon_workflow_claim_exhausted_total` | Counter | >0 告警（`WF_INTERNAL` 死记录） |
| `riccon_workflow_claim_failures_total{reason}` | Counter | 非法 tenant/DB 失败/领取异常；>0 告警 |
| `riccon_workflow_runs_total{status}` | Counter | 终态分布/失败率 |
| `riccon_workflow_run_duration_seconds` | Histogram | 执行时长（不含审批等待） |
| `riccon_workflow_node_duration_seconds{node_type,status}` | Histogram | 节点耗时/失败定位 |
| `riccon_workflow_runs_active` | Gauge | 当前执行槽位占用 |
| `riccon_workflow_approvals_pending` / `riccon_workflow_approval_oldest_wait_seconds` | Gauge | 待审批数/最老等待；>24h 提示租户管理员（OPS-M06-007） |
| `riccon_workflow_admission_rejected_total{scope}` | Counter | 租户/单流程 429 |
| `riccon_workflow_result_oversize_total` / `riccon_workflow_result_size_bytes` | Counter/Histogram | 5 MiB 超限与容量分位数（OPS-M06-011） |
| `riccon_workflow_heartbeat_renew_failures_total` | Counter | 续期失败/租约丢失前置信号 |
| `riccon_workflow_feature_enabled` | Gauge | API/Worker 开关不一致告警；按实例抓取 |

- 告警阈值与处置步骤写入 RUNBOOK（§10.5），M06 负责；M09 只做告警路由/长期容量，不把全部推给 M09。
- `/actuator/prometheus` 仍仅内网/受控（M04 CIDR）；不新增暴露面。

**性能预算（AC-21）**：沿用合同 `commands.perf` / `e2e/perf/mvc-smoke.js`，新增 `RICCON_PERF_SCENARIO=workflow`（或等价脚本）。50 并发为混合 J1/J2/M06 总负载（不要求 50 个 M06 同时执行，M06 并发上限单独记录）：J1 首字 p95 ≤3s；Studio 列表/详情/保存/发布/运行准入 API p95 ≤500ms（不含 SSE/TTFB）；审批等待不占执行并发；单流程 5 并发、租户 10 并发；超限 429。报告记录 `RICCON_FAKE_MODEL=1`、并发数、限制值、脚本参数与退出码，可复现。

### 10.3 发布顺序

1. 备份（记录 schema 14 / 迁移版本）→ 一次性 V15 迁移 Job（api 执行）。
2. 发布新 API（开关默认 `false` 或 `true`？**建议首次发布 `RICCON_WORKFLOW_ENABLED=false`**，完成 smoke 后再打开）→ 发布新 Worker（期望 schema 15，运行队列轮询启用）。
3. 打开开关（部署变更记录）→ 内置模板 smoke → M06 gate 证据。
4. J1/J2 回归在同一窗口执行，确认 default/heavy 通道未被工作流运行队列影响。

### 10.4 回滚（不做 down migration）

**受支持回滚**：

1. 关闭 `RICCON_WORKFLOW_ENABLED`；新运行/重试/审批决定 503，**取消仍可用**，历史/审计只读；在途运行等待终态或由发起人/管理员取消。
2. **回滚 Worker 前必须清空 `WAITING_APPROVAL`**：审批不过期，旧 Worker 不会接管；用保留的 M06 API 取消所有等待审批运行，确认 `SELECT COUNT(*) FROM workflow_run WHERE status='WAITING_APPROVAL'` = 0。不能只“等待终态”。
3. 回滚 Worker 到 M06 前版本：旧 Worker 不认识 `workflow_run` 表，不会领取；回滚窗口按 ADR-0021 的 schema 校验开关流程（仅人工核对 `MAX(version)=15`、登记工单）。
4. **保留 M06 API** 以继续提供运行历史/审批/审计读取；或对 API 前向修复。
5. V15 表/触发器/权限保留；对象存储无新增类别；J1/J2 不受影响。

**不支持**：在存在 workflow 运行/审批/审计行后把 API 回滚到 M06 前版本——旧 API 的 `AuditDetailsReader` 对未知 `workflow.*` 动作 fail-closed，审计查询/导出会失败；且旧 API 无 workflow 端点，**历史运行不可读**（与 AC-23 冲突）。因此 AC-23 的“应用回滚”按“关闭开关 + 回滚 Worker + 保留/前向修复 API”解释；DEC-0047=A 已确认本口径，AC-23 文本由 Product 同步。

**AC-23 全量 API 回滚/应急 drop 判定 SQL（任一 >0 必须拒绝）**：

```sql
SELECT
  (SELECT COUNT(*) FROM workflow_definition) AS definitions,
  (SELECT COUNT(*) FROM workflow_version) AS versions,
  (SELECT COUNT(*) FROM workflow_run) AS runs,
  (SELECT COUNT(*) FROM workflow_node_run) AS node_runs,
  (SELECT COUNT(*) FROM workflow_approval) AS approvals,
  (SELECT COUNT(*) FROM workflow_tool_invocation) AS tool_invocations,
  (SELECT COUNT(*) FROM workflow_user_template) AS user_templates,
  (SELECT COUNT(*) FROM audit_event WHERE action LIKE 'workflow.%') AS workflow_audit_events;
```

- 全部为 0 且用户书面同意，才允许全量 API 回滚/应急 drop；任一非 0 → 脚本/操作以非 0 退出并拒绝，只允许受支持回滚。`scripts/ops/workflow-rollback-drill.mjs`（T16b）必须断言“有数据时拒绝、无数据时允许”，并写演练报告（OPS-M06-001）。
- 全局 `workflow_template`（内置目录）与租户表分两段 drop：先租户表，最后全局模板；租户数据未清时不得删内置目录。

**应急 drop**（仅无数据 + 用户书面同意）：停 Worker → drop 触发器 → 按 FK 逆序 drop 租户子表/父表 → 删全局 `workflow_template` → 删 `model_invocation` 索引/列 → 删权限/角色回填 → 删 `flyway_schema_history` 15 → 旧 Worker。生产优先备份恢复（ADR-0010）。

### 10.5 RUNBOOK 最小章节（T15 写 `docs/RUNBOOK.md`；设计输入）

1. 配置表：全部 `RICCON_WORKFLOW_*`/`RICCON_DB_MAX_ALLOWED_PACKET`、默认值、启动校验（batch=1、调度/心跳池、lease/3、包大小）、生效方式（重启/滚动）。
2. 关闭/开启编排、Worker 停止领取（`RICCON_WORKFLOW_CLAIM_ENABLED`）与优雅停机；影响面；审计证据（`workflow.feature.changed` 的 instanceId/observedAt）。
3. 队列积压/最老 PENDING/租约丢失/`claim_exhausted`/心跳失败排障；重复领取与结果丢弃语义。
4. 审批等待占满 10 槽的发现与解除（最老等待 >24h、取消、审批人配置）；审批等待与执行并发的区别。
5. 失败节点重试/写工具重审批/`WF_SIDE_EFFECT_UNKNOWN`/`WF_INTERNAL` 终态与恢复。
6. V15 迁移预检（TRIGGER/binlog/MDL/包大小/备份）、单迁移失败处置、`flyway repair`/备份恢复。
7. 受支持回滚（DEC-0047=A）：取消 `WAITING_APPROVAL` → 关开关 → 回滚 Worker → 保留/前向修复 API；判定 SQL/拒绝路径；旧 Worker schema 15 窗口。
8. 全量 API 回滚/应急 drop 的无数据判定与拒绝路径；全局/租户 drop 分段。
9. 保留到期不可见与 M09 物理清理边界；“未物理删除”标准答复。
10. 5 MiB/`max_allowed_packet`/JDBC `maxAllowedPacket` 预检、容量公式（runs/day × 结果大小分位数 × 保留天数）、binlog/备份体积；指标/告警阈值与处置（§10.2）。
11. 发布/回滚演练（`OPS-M06-01`）：造运行/审批/审计 → 关闭 → 回滚 Worker → 历史可读/J1/J2 正常 → schema 15 核对 → 恢复。

## 11. 可访问性（a11y）

### 11.1 等价路径不是事后补

- 单一 `WorkflowGraph` 状态模型；画布与列表/表单是同一状态的两个视图，读写同一草稿 API。新增节点/连线/参数/校验能力必须同时定义两个视图的操作，否则任务不通过（AC-20）。
- **列表/表单为主路径**：节点表（名称/类型/状态/操作）+ 节点配置表单 + 连线表（源/目标/条件）+ 校验问题列表（定位到节点/字段）。键盘可完成创建→配置→连线→校验→保存→发布→运行。
- **画布为增强**：原生 SVG/HTML（不引入 React Flow 等新依赖）；节点为可聚焦按钮，`aria-label` 含名称/类型/状态；边有 `<title>` 与隐藏的有序列表（源→目标/条件）；Tab 顺序按节点创建顺序；方向键移动仅作可选增强，不替代表单连线。
- 375px：<768px 默认表单视图，画布隐藏并提示“小屏请使用列表/表单编辑”；页面无横向滚动；表格转卡片。
- 运行详情时间线用有序列表，状态文字 + 图标（不只颜色）；审批/重试按钮可聚焦、焦点可见；空/错/无权限/禁用态均有中文说明与下一步（AC-19）。
- axe：流程列表、编辑器、运行详情、审批待办、运行历史 + 模板库（M06b），无 serious/critical；`@a11y` 纳入合同 `commands.a11y`。

### 11.2 测试映射

`E2E-M06-09`（键盘全流程 + 375px + 状态文案）、`@a11y`（axe 五界面 + 模板库）、`E2E-M06-01..08/10` 覆盖各视图；画布与表单的校验结果一致性由 `E2E-M06-01` 与集成测试断言。

---

## 12. M06b/M26 边界与前向兼容

- **M06 交付**：1 个全局内置模板 `market-research`（J2 等价：检索 → 模型 → 审批 → 工具 → 模型 → 结束，带引用），模板列表/复制；内置模板 `visibility=BUILT_IN`（契约新增枚举，避免 `PRIVATE/TENANT` 误读，F-018）；不实现用户另存模板、共享、升级提示 UI。
- **M06b/M26 交付**：模板库（≥2 个内置模板，含知识库摘要）、用户模板另存、租户内共享（`PRIVATE/TENANT`）、升级提示（`latestBuiltInVersion`/`upgradeAvailable`）、复制新版本；AC-11/25 为 M06b gate P0。
- **V15 预留**：`workflow_user_template` 表、`workflow_definition.source_template_*`、`WorkflowTemplate` 的 `source/visibility/builtInVersion/latestBuiltInVersion/upgradeAvailable` 字段；M06 只读内置模板，用户模板表无读写路径。
- **契约**：M06 `1.6.0-m06` 定义只读模板字段；M06b `1.7.0-m06b` 增加写端点/共享/升级提示，全部 additive；M06 的 `x-riccon-deferred` 记录 `workflow-template-sharing`。
- **预算**：M06b 48h 不含在 M06 120h；M06 不为 M06b 预留实现工作量（只保留表/字段，约 1h 已计入 T01/T14）。
- **回滚继承（OPS-M06-014）**：M06b 无迁移、纯 API/Web 滚动，但新增 `workflow.template.saved/shared/unshared` 审计动作后，旧（M06）API 的 `AuditDetailsReader` 仍 fail-closed；因此“M06b → M06 API 回滚”只允许在无 M06b 审计行时执行。M06b 设计必须继承 DEC-0047=A 口径与 AC-23 判定 SQL，不得声称可任意回退。
- **不声称**：跨租户模板市场、模板计费、部门级模板共享（P2）、模板版本回滚。

---

## 13. 任务拆分（不创建 .riccon 任务；供 Lead 建模块/任务）

### 13.1 M06（23 条 AC，120h）

| ID | 内容 | 依赖 | 风险 | h | AC | 证据 | write_scope（glob） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-M06-01 | 契约 `1.6.0-m06`、常量/枚举、V15 迁移、权限/角色回填、模板种子、审计白名单、schema 15、V15 联动断言（CI/`ApiApplicationTests`/`SchemaVersionCheckerTest`/`restore-drill`）、平台租户 404 guard、契约测试骨架 | — | 高 | 9 | 02/03/06/16/17/22 | `IT-WF-101`、迁移测试、`WorkflowContractM06Test`、V15 断言测试 | `platform/platform-domain/src/main/java/com/riccon/platform/domain/workflow/**`、`platform/platform-domain/src/main/java/com/riccon/platform/domain/{identity,audit}/**`、`apps/api/src/main/resources/db/migration/V15__workflow_studio.sql`、`apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java`、`apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java`、`apps/api/src/main/java/com/riccon/api/workflow/web/**`、`apps/api/src/main/resources/application.yml`、`apps/worker/src/main/resources/application.yml`、`apps/api/src/test/java/com/riccon/api/contract/WorkflowContractM06Test.java`、`apps/api/src/test/java/com/riccon/api/workflow/web/**`、`apps/api/src/test/java/com/riccon/api/ApiApplicationTests.java`、`apps/worker/src/test/java/com/riccon/worker/persistence/SchemaVersionCheckerTest.java`、`apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java`、`.github/workflows/ci.yml`、`scripts/ops/restore-drill.mjs` |
| T-M06-02 | 定义/草稿领域与仓储、草稿 API、乐观锁、另存副本 | T01 | 高 | 6 | 01 | `IT-WF-101`、`E2E-M06-01` | `apps/api/src/main/java/com/riccon/api/workflow/definition/**`、`apps/api/src/main/java/com/riccon/api/workflow/domain/WorkflowGraph*.java`、`apps/api/src/main/java/com/riccon/api/workflow/persistence/WorkflowDefinition*.java`、`apps/api/src/test/java/com/riccon/api/workflow/definition/**` |
| T-M06-03 | 校验器：结构/必填/模型/工具/审批可达/条件/结果/限制 | T01,T02 | 高 | 6 | 02/06/09/18/24 | `IT-WF-101`、`SEC-WF-005` | `apps/api/src/main/java/com/riccon/api/workflow/validation/**`、`apps/api/src/test/java/com/riccon/api/workflow/validation/**` |
| T-M06-04 | 发布/版本/停用启用/移交/范围与审计 | T02,T03 | 高 | 6 | 03/15/16 | `IT-WF-102`、`IT-WF-112` | `apps/api/src/main/java/com/riccon/api/workflow/version/**`、`.../workflow/publish/**`、`apps/api/src/test/java/com/riccon/api/workflow/version/**`、`apps/api/src/test/java/com/riccon/api/workflow/publish/**` |
| T-M06-05 | 运行准入/创建/取消/重试 API、配额与并发计数 | T03,T04 | 高 | 6 | 03/04/15/17/23 | `IT-WF-109/110` | `apps/api/src/main/java/com/riccon/api/workflow/run/**`、`apps/api/src/test/java/com/riccon/api/workflow/run/**` |
| T-M06-06a | 运行队列/租约/调度池/心跳：claim batch=1、独立调度池、心跳池≥并发、interval≤lease/3、启动校验、重复领取防护、`WF_INTERNAL` 终态 | T05 | 高 | 6 | 04/17 | `IT-WF-103`、`OPS-M06-01` | `apps/worker/src/main/java/com/riccon/worker/workflow/queue/**`、`.../workflow/heartbeat/**`、`apps/worker/src/test/java/com/riccon/worker/workflow/queue/**`、`.../workflow/heartbeat/**` |
| T-M06-06b | 串行引擎/节点执行：开始/结束/检索/模型/条件、超时/取消、结果/引用、`inputSummary/outputSummary` | T06a | 高 | 8 | 04/07/09/13/17 | `IT-WF-105/109` | `apps/worker/src/main/java/com/riccon/worker/workflow/engine/**`、`.../workflow/node/**`、`apps/worker/src/test/java/com/riccon/worker/workflow/engine/**`、`.../workflow/node/**` |
| T-M06-07a | 审批模型/参数加密/assignee/决定：`workflow_approval`、不再自批、无审批人 `WF_NO_APPROVER`、决定幂等 | T06b | 高 | 5 | 05/06/15/18 | `IT-WF-108`、`SEC-WF-001` | `apps/worker/src/main/java/com/riccon/worker/workflow/approval/**`、`apps/worker/src/test/java/com/riccon/worker/workflow/approval/**` |
| T-M06-07b | 工具执行器策略/plan/重试/未知副作用：`McpCallExecutor` READ_ONLY vs APPROVAL_BOUND_WRITE、工具调用记录、重审批 | T07a | 高 | 5 | 07/08/18 | `IT-WF-104`、`SEC-WF-004`、`IT-HITL-001`、`MCP-001..008` | `apps/worker/src/main/java/com/riccon/worker/workflow/tool/**`、`apps/worker/src/main/java/com/riccon/worker/mcp/service/McpCallExecutor.java`、`.../mcp/service/McpGateway.java`、`apps/worker/src/test/java/com/riccon/worker/workflow/tool/**`、`apps/worker/src/test/java/com/riccon/worker/mcp/**` |
| T-M06-08 | 运行历史/详情/重试/取消/管理性读取/保留可见性/审批聚合/counts | T05,T07b | 高 | 6 | 12/13/14/15/16 | `IT-WF-106/107/108` | `apps/api/src/main/java/com/riccon/api/workflow/history/**`、`apps/api/src/test/java/com/riccon/api/workflow/history/**` |
| T-M06-09 | 审批待办/详情/决定 API（assignee/自批/无审批人/dataScopeSummary/拒绝理由） | T07a | 高 | 5 | 05/06/15/18 | `IT-WF-108`、`SEC-WF-001` | `apps/api/src/main/java/com/riccon/api/workflow/approval/**`、`apps/api/src/test/java/com/riccon/api/workflow/approval/**` |
| T-M06-10 | 前端：流程列表 + 表单编辑 + 节点配置 + 校验面板 + 空/错/禁用态 | T02,T03 | 高 | 10 | 01/02/19/20 | `E2E-M06-01`、`E2E-M06-09` | `apps/web/src/pages/workflow/list/**`、`apps/web/src/pages/workflow/edit/**` |
| T-M06-11 | 前端画布最小增强：SVG/节点焦点/aria/边列表/375px 降级（表单为主路径） | T10 | 中 | 2 | 19/20 | `E2E-M06-09`、`@a11y` | `apps/web/src/components/workflow/canvas/**` |
| T-M06-12a | 前端运行详情：节点时间线/输入输出摘要/引用/结果/审批记录 | T05,T06b | 高 | 5 | 04/13/19 | `E2E-M06-03/08` | `apps/web/src/pages/workflow/run/**` |
| T-M06-12b | 前端发布/版本/运行历史/重试/取消 | T05,T06b | 高 | 5 | 03/12/13/19 | `E2E-M06-02/05/08` | `apps/web/src/pages/workflow/history/**` |
| T-M06-13 | 前端审批待办/详情/决定、空态/错误态/键盘 | T09,T10 | 中 | 4 | 05/06/19/20 | `E2E-M06-04`、`@a11y` | `apps/web/src/pages/workflow/approvals/**` |
| T-M06-14 | 内置模板（J2 等价）+ 复制 + 模板列表（`visibility=BUILT_IN`） | T01,T02 | 中 | 4 | 10 | `E2E-M06-07` | `apps/api/src/main/java/com/riccon/api/workflow/template/**`、`apps/api/src/test/java/com/riccon/api/workflow/template/**`、`apps/api/src/main/resources/workflow-templates/**`、`apps/web/src/pages/workflow/templates/**` |
| T-M06-15 | 运行限制/开关/指标/启动校验/RUNBOOK：claim/heartbeat/scheduler 不变量、lease/3、包大小预检、队列/租约/审批等待指标与告警、Worker 停止领取、`docs/RUNBOOK.md` M06 章节 | T05,T06a | 中 | 4 | 16/17/23 | `IT-WF-109/110`、`OPS-M06-01` | `apps/api/src/main/java/com/riccon/api/workflow/runtime/**`、`apps/worker/src/main/java/com/riccon/worker/workflow/metrics/**`、`apps/worker/src/main/java/com/riccon/worker/workflow/config/**`、`apps/api/src/main/java/com/riccon/api/config/Workflow*.java`、`apps/api/src/test/java/com/riccon/api/workflow/runtime/**`、`apps/worker/src/test/java/com/riccon/worker/workflow/metrics/**`、`docs/RUNBOOK.md` |
| T-M06-16a | E2E/axe/键盘/50 并发 perf/越权/注入/重试 harness | T10..T15 | 高 | 6 | 19/20/21/22/24 | `E2E-M06-01..10`、`@a11y`、`PERF-M06-001`、`SEC-WF-*` | `e2e/**`、`scripts/real-stack/**`、`e2e/perf/**` |
| T-M06-16b | 回滚演练/5 MiB 容量/包大小预检：`scripts/ops/workflow-rollback-drill.mjs`、AC-23 判定 SQL/拒绝路径、容量报告 | T16a,T15 | 高 | 4 | 23 | `OPS-M06-01`、`IT-WF-110` | `scripts/ops/workflow-*.mjs` |
| T-M06-17 | 独立 Verifier 任务评审、模块 gate、全量回归、回滚/威胁模型核对 | 全部 | 高 | 8 | 22/23 + 全量 | review 文件、gate EV、`OPS-M06-01` | `.riccon/reviews/**`（verifier 写） |
- 合计 **120h**；关键路径 T01→T02→T03→T04→T05→T06a→T06b→T07a→T07b→T16a→T16b→T17 ≈ **75h**；前端链 T01→T02→T10→T11→T12a→T12b→T13 ≈ 41h，可与后端并行。2–3 builder 时墙钟预计 77–92h，留修复/评审余量。
- **OPS-M06-013 工时调剂**：T11 画布 5h→2h（-3h）、T15 3h→4h（+1h）、T16 8h→10h（+2h），总额仍 120h；画布只保留最小增强，列表/表单仍是等价主路径。若再超支，候选后移：运行历史高级过滤（-2h）、模板列表合并（-1h）；不得缩减 AC-15/16/17/18/21/23/24。
- 口径说明：上表是 Builder/Verifier 可执行任务工时（含迁移、契约测试、E2E/性能/安全 harness 与 gate）；Architect 的 ADR/设计评审时间按团队角色另计，不重复计入 builder 任务。若 Lead 要求把设计时间并入 120h 模块预算，需从 T10/T11 前端余量或 T17 gate 余量扣减 ~7h 并记录；不静默增加预算。
- 高风险任务统一为 **T01–T13、T16、T17**（含 T10 前端与拆分后的 T06a/b、T07a/b、T12a/b、T16a/b）；在 done 前必须有独立 Verifier 任务级评审；同一 owner 同时只一个 `in_progress`；写范围互不重叠，共享文件（`AuditDetailsPolicy`、`SchemaVersionChecker`、`contracts/**`、`docs/RUNBOOK.md`、`.github/workflows/ci.yml`、`scripts/ops/restore-drill.mjs`）由 T01/T15 独占。
- 若实际超过 120h 20%（>144h），按 DEC-0046 暂停并回用户决策；画布已降为最小增强，不再重复扣减。

### 13.2 M06b/M26（AC-11/25，48h）

| ID     | 内容                                                           | 依赖     | 风险 | h   | AC    | 证据                                   |
| ------ | -------------------------------------------------------------- | -------- | ---- | --- | ----- | -------------------------------------- |
| M26-B1 | 契约 `1.7.0-m06b`：模板写端点/共享/升级字段；移除对应 deferred | M06 gate | 中   | 4   | 11/25 | `WorkflowContractM06bTest`             |
| M26-B2 | 后端：用户模板另存/共享/删除、升级元数据、审计                 | B1       | 高   | 8   | 11/25 | `IT-WF-113/114`                        |
| M26-B3 | 前端：模板库列表/过滤/共享开关/升级提示/复制                   | B1,B2    | 中   | 10  | 11/25 | `E2E-M06-11/12`、`@a11y`               |
| M26-B4 | 第 2 个内置模板（知识库摘要）+ 版本升级种子/检测               | B1       | 中   | 8   | 25    | `E2E-M06-12`                           |
| M26-B5 | 权限/越权/审计/E2E/375/axe                                     | B2,B3,B4 | 高   | 10  | 11/25 | `SEC-WF-002`、`E2E-M06-11/12`、`@a11y` |
| M26-B6 | 独立评审/gate/修复                                             | B5       | 高   | 8   | 11/25 | review/gate EV                         |

- 合计 **48h**；M06b 不新增迁移（V15 已预留），不改 M06 核心行为；M06b gate P0 = AC-11 + AC-25；运行历史高级过滤/画布增强仍 P2/后续。

---

## 14. AC 映射（Product §12）

| AC        | 归属 | 设计/契约                                | 任务                        | 验证 ID                                                        | 备注                                    |
| --------- | ---- | ---------------------------------------- | --------------------------- | -------------------------------------------------------------- | --------------------------------------- |
| M06-AC-01 | M06  | §3.3.1/§4.1、`PUT /workflows/{id}/draft` | T02,T10 | `E2E-M06-01`、`IT-WF-101` | Product 9d30ac1 已改为所有者/管理员并发；同侪不在 v1（U-03 复验） |
| M06-AC-02 | M06  | §4.1/§4.5、`POST /validate`              | T01,T03,T10                 | `E2E-M06-01`、`IT-WF-101`                                      | 画布/表单同问题列表                     |
| M06-AC-03 | M06  | §3.3.2/§4.1、`publish/versions`          | T04,T05,T12b                 | `E2E-M06-02`、`IT-WF-102`                                      | 版本不可变（触发器）                    |
| M06-AC-04 | M06  | §4.2/§4.3、`GET /workflow-runs/{id}`     | T05,T06b,T12a                 | `E2E-M06-03`                                                   | 轮询，无 SSE                            |
| M06-AC-05 | M06  | §5.2/§5.4、`workflow-approvals` | T07a,T09,T13 | `E2E-M06-04`、`SEC-WF-001` | 拒绝理由必填；dataScopeSummary；ACL 最小可见 |
| M06-AC-06 | M06  | §5.2/§6.2 | T03,T07a,T09 | `SEC-WF-001`、`E2E-M06-04` | 自批 403+审计；无审批人 `WF_NO_APPROVER` |
| M06-AC-07 | M06  | §4.6、`retry`                            | T06b,T07b,T12a                 | `E2E-M06-05`、`IT-WF-103`                                      | 上游不重跑；下游跳过                    |
| M06-AC-08 | M06  | §4.6/§4.7                                | T07b,T16a                     | `IT-WF-104`、`E2E-M06-05`                                      | 写工具重审批；未知副作用 fail-closed    |
| M06-AC-09 | M06  | §4.5 | T03,T06b,T10 | `E2E-M06-06`、`IT-WF-105` | 条件失败不默认分支；branch 字符串枚举 |
| M06-AC-10 | M06  | §12、`workflow-templates`                | T14,T10                     | `E2E-M06-07`                                                   | 1 个内置模板；升级提示 M06b             |
| M06-AC-11 | M06b | §12、`x-riccon-deferred`                 | M26-B1..B5                  | `E2E-M06-11`、`SEC-WF-002`                                     | 私有默认；跨租户 404                    |
| M06-AC-12 | M06  | §6.2、`GET /workflow-runs` | T08,T12b | `E2E-M06-08`、`SEC-WF-003` | 所有者只看元数据；管理员全部；审批聚合/counts |
| M06-AC-13 | M06  | §3.4/§6.2/§7.3 | T06b,T08,T12a | `E2E-M06-08`、`IT-WF-106` | 引用 `sourceStatus`；input/outputSummary；不泄露 prompt/参数 |
| M06-AC-14 | M06  | §3.3.3/§10.1                             | T08                         | `IT-WF-107`                                                    | 到期不可见；无在线删除                  |
| M06-AC-15 | M06  | §6 | T01,T04,T05,T08,T09,T16a | `SEC-WF-002/003/006`、`TEN-M06-01`、`DEPT-M06-01`、`IT-WF-112` | 403/404 矩阵；平台管理员 404（filter） |
| M06-AC-16 | M06  | §8                                       | T01,T04,T05,T07a/b,T08,T09,T15 | `IT-WF-108/112`、`IT-AUDIT-M06-01`                             | 管理性读取审计；审计失败即失败；M06 只验收发布/运行/取消/审批/停用/授权/移交；模板共享子句已由 Product 在 9d30ac1 修订为 M06b AC-11/25 验收（F-003），M06 gate 不声称共享已实现 |
| M06-AC-17 | M06  | §4.8/§10.1                               | T05,T06a,T15,T16a             | `IT-WF-109`、`E2E-M06-09`                                      | 上限可读提示；租户只读                  |
| M06-AC-18 | M06  | §4.7/§9 | T03,T07b,T16a | `SEC-WF-004`、`E2E-M06-04` | READ_ONLY/APPROVAL_BOUND_WRITE；工具授权/审批/注入/canary |
| M06-AC-19 | M06  | §11                                      | T10,T12a,T12b,T13,T16a             | `E2E-M06-09`、`@a11y`                                          | 五界面状态；375px                       |
| M06-AC-20 | M06  | §11                                      | T10,T11,T13,T16a             | `E2E-M06-09`、`@a11y`                                          | 键盘全流程；等价路径                    |
| M06-AC-21 | M06  | §10.2、契约轮询                          | T16a,T16b,T17                     | `PERF-M06-001`                                                 | 50 并发混合；p95 口径                   |
| M06-AC-22 | M06  | §7、§10.3                                | T16a,T17                     | `E2E-M06-10`、`CONTRACT-M06`                                   | 无 404/deferred 冒充；J1/J2 回归        |
| M06-AC-23 | M06  | §10.4                                    | T15,T17                     | `IT-WF-110`、`OPS-M06-01`、THREAT §7                           | DEC-0047=A 回滚口径；全量 API 回滚仅限无数据/审计行                          |
| M06-AC-24 | M06  | §9.3/§7.3                                | T01,T03,T16a                 | `SEC-WF-005`、`IT-WF-111`                                      | 无代码/脚本/HTTP/并行/循环/定时入口     |
| M06-AC-25 | M06b | §12                                      | M26-B1..B5                  | `E2E-M06-12`、`SEC-WF-002`、`@a11y`                            | 2 个内置模板/升级提示                   |

- 补充：`TEN-M06-01`（流程/运行/审批/模板跨租户 404、平台管理员 404）、`DEPT-M06-01`（跨部门/移出部门/部门停用后 404）、`IT-WF-111`（负向节点类型/字段）、`IT-AUDIT-M06-01`（新动作可查询/导出、details 白名单）。
- 每条 AC 在评审包登记“AC → 测试 ID → 报告路径”；UI AC 不能只用 API 证据；安全 AC 必须含负向用例；性能 AC 只在合同 `commands.perf` 或等价脚本下判定。

---

## 15. 设计自检

### 15.1 AC → ADR/契约/威胁/测试

| 检查组         | 结论                                                                                                                                                                     |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| AC → 设计/契约 | 25 条均有 ADR 章节、端点/事件或“M06b deferred”归属；无跨模块重复声明（AC-11/25 只归 M06b）                                                                               |
| AC → 威胁模型  | AC-05/06/08/13/15/16/17/18/23/24 映射 TM-72..TM-85 与 B-27..B-30；AC-19/20 归 a11y；AC-21 归 perf                                                                        |
| AC → 测试 ID   | Product 提供的 `E2E-M06-01..12`、`IT-WF-101..112`、`SEC-WF-001..006`、`TEN-M06-01`、`DEPT-M06-01`、`PERF-M06-001`、`OPS-M06-01` 全部有落点；新增 `IT-WF-113/114` 归 M06b |
| 跨模块归属     | M07 只做 MCP 注册/审批/凭据；M09 只做调度/物理清理；J2 不迁移；M05 ACL/M10 审计/M04 网关语义不改；代码执行另立模块                                                       |

### 15.2 迁移可执行性

- V15 顺序满足 FK 依赖；新表 `CREATE TABLE` 可空默认；`model_invocation` 加列在末尾（INSTANT）+ 索引（INPLACE/LOCK=NONE）；`workflow_run` 增加领取索引；不改变既有 CHECK/列语义；无大表 COPY。
- 触发器需要迁移账号 `TRIGGER` 权限（ADR-0012 已有要求）；若客户环境不满足，V15 失败 → 迁移前置检查写入 RUNBOOK。
- 回滚 SQL 由 Builder 在迁移测试中验证“无数据时可 drop、有数据时保留”；生产不做 down migration。
- worker schema 14→15：旧 worker 在新库启动会因版本不符拒绝；回滚窗口按 ADR-0021 的显式开关与人工核对执行。

### 15.3 Gate 项与配额/成本/安全边界

- 不新增 `definition_of_done.module` key（遵循 2026-11-20 T4 决策）；security/a11y/perf/migration 继续作为 Verifier 评审与 release evidence。
- M06 gate 判 AC-01..10、12..24；AC-11/25 不进 M06 gate，M06b gate 判 AC-11/25。AC-19/20 虽 P1，仍须在 M06 gate 检查（合同质量基线），不得因预算后移不验。
- 配额：按运行准入 1 单位（DEC-0048=A）；模型成本按 `model_invocation` 聚合；资源上限约束单运行模型节点数量；不新增 token/成本配额。
- 安全：代码执行/脚本/HTTP/并行/循环/定时均无入口；工具白名单/审批/ACL/审计 fail-closed；平台管理员 404。

### 15.4 命名一致性

| 概念                                      | 唯一名称                                                                                                                             |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| 流程定义/版本/运行/节点运行/审批/工具调用 | `workflow_definition` / `workflow_version` / `workflow_run` / `workflow_node_run` / `workflow_approval` / `workflow_tool_invocation` |
| JSON/契约                                 | `workflowId`、`workflowVersionId`、`workflowRunId`、`workflowApprovalId`、`nodeId`（图内 key 对外统一 `nodeId`，DB 列 `node_key`）   |
| 权限码                                    | `workflow.read/edit/publish/run/approve/manage`                                                                                      |
| 审计动作                                  | `workflow.created/draft.saved/published/disabled/enabled/owner.transferred/run.*/approval.*/feature.changed`                         |
| 事件                                      | 无（M06 不使用 outbox；`workflow_run` 为持久队列）                                                                                  |
| 异步错误码                                | `WF_*`（HTTP 错误 `RICCON-WF-*`）                                                                                                    |
| 指标                                      | `riccon_workflow_*`                                                                                                                  |

文档、契约、实现、测试使用同一名称；`nodeId` 不写成 `nodeKey`（API），`workflowRunId` 不简写 `runId`（除事件 payload 内部按契约）。

### 15.5 高风险任务与独立评审

| 任务        | owner（建议实例）      | write_scope                                         | 可观察验收                        | 独立评审                       |
| ----------- | ---------------------- | --------------------------------------------------- | --------------------------------- | ------------------------------ |
| T01         | `riccon/builder-b1`    | platform-domain + V15 + AuditDetailsPolicy + schema | 迁移测试、契约测试、白名单测试    | Verifier 任务评审（迁移/审计） |
| T02/T03/T04 | `riccon/builder-b1`    | definition/validation/version                       | E2E-01/02、IT-WF-101/102          | Verifier（并发/校验/版本）     |
| T05         | `riccon/builder-b1`    | run API                                             | 429/503、幂等、审计               | Verifier（权限/配额）          |
| T06a/b/T07a/b | `riccon/builder-b2` | worker queue/engine/approval/tool                   | IT-WF-103/104/105、SEC-WF-001/004 | Verifier（执行/审批/注入）     |
| T08/T09     | `riccon/builder-b2`    | history/approval API                                | IT-WF-106/107/108、TEN/DEPT       | Verifier（越权/保留）          |
| T10–T13     | `riccon/builder-b3`    | web pages/components                                | E2E/axe/375                       | Verifier（a11y/E2E）           |
| T14/T15     | `riccon/builder-b2`/b3 | template/runtime                                    | E2E-07、OPS                       | Verifier（模板/运维）          |
| T16a/b/T17  | `riccon/verifier`      | e2e/scripts/reviews                                         | PERF/SEC/E2E 全量                 | Lead/用户（gate）              |

---

## 16. 风险与问题记录（含已解决）

U-01/U-02 已由 DEC-0047=A / DEC-0048=A 解决；U-03..U-13 仍未决，不得视为已解决。

| ID   | 问题                                                                                       | 默认/建议                                                                                         | 需谁决策              | 影响                |
| ---- | ------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------- | --------------------- | ------------------- |
| U-01（已解决：DEC-0047=A） | AC-23“应用回滚到 M06 前版本且历史运行与审计仍可读”与旧 API 无 workflow 端点/审计白名单冲突 | DEC-0047=A：关闭 + 回滚 Worker + 保留/前向修复 API；判定 SQL/拒绝路径见 §10.4（OPS-M06-001） | user（DEC-0047=A） | 回滚口径、gate 判定 |
| U-02（已解决：DEC-0048=A） | 配额单位：按运行 1 单位 vs 按模型节点逐次 | DEC-0048=A：按运行准入 1 单位；模型成本按 model_invocation 聚合；资源上限约束；R-36 accepted | user（DEC-0048=A） | 成本/配额公平 |
| U-03 | AC-01“另一编辑者”是否要求同侪协同编辑                                                      | 默认所有者 + 租户管理员；同侪需草稿共享模型                                                       | Product               | 权限/审计           |
| U-04 | kill switch 审计：部署配置 + 启动审计 vs 参考 M04 的 DB 开关 + 平台 API | 默认部署配置 + 启动**观测**审计（instanceId/observedAt）+ `riccon_workflow_feature_enabled` gauge + Worker claim 开关；不升用户决策（OPS-M06-009） | Ops/Verifier | 可运维/审计 |
| U-05 | 审批理由进入审计（AC-16）可能含 PII | **未解决**：临时默认允许 ≤500 字符并提示；替代为 `commentSha256` + 审批记录保留；M06 gate 前安全评审二选一（F-013） | 安全评审/Product | 隐私/合规 |
| U-06 | 模型节点是否允许选择任意员工可用模型                                                       | 默认必须属于 REPORT 路由链且 ACTIVE                                                               | Product/架构          | 路由/成本           |
| U-07 | 运行结果是否写对象存储 | 默认 MySQL ≤5 MiB；预检 `max_allowed_packet`/JDBC `maxAllowedPacket` ≥8 MiB + 容量公式；更大需 ADR-0009 对象 key 扩展（OPS-M06-011） | 架构/Ops | 存储/下载 |
| U-08 | `WORKFLOW_EDITOR` 角色 vs 专用授权表                                                       | 默认角色（复用角色管理/审计）；专用表增加端点与审计面                                             | Lead/Product          | 权限模型            |
| U-09 | 租户管理员是否可编辑所有草稿                                                               | 默认是（管理能力）；若不允许需草稿 ACL                                                            | 安全评审/Product      | 草稿保密            |
| U-10 | 部门范围快照：所有者调动后旧版本仍按发布时部门                                             | 默认版本快照 + 部门当前成员判定；停用/移交由管理员处理                                            | Product               | 可见性              |
| U-11 | M06b 内置模板升级：新行 vs version+1                                                       | M06b 决定；M06 只预留字段                                                                         | M06b 设计             | 模板升级            |
| U-12 | 审批等待是否计入租户准入并发 | 默认计入（防无限等待），但释放 worker 执行槽位；最老审批等待 >24h 告警 + 取消/配置解除步骤（OPS-M06-007） | Product/Verifier | 并发/性能 |
| U-13 | `workflow_run` 跨租户领取是新增 ADR-0009 例外 | 默认按 ADR-0016 模式：只读 claim 元数据、领取后立即租户上下文、索引例外登记；静态列集合断言 + `claim_failures`/denied；需独立安全评审（F-014/OPS-M06-015） | Verifier/安全 | 租户隔离/评审 |

---

## 17. OPS 评审响应（REV-M06-OPS-001）

评审基线 `751d384`、HEAD `a694979`，结论 `fail`（5 P1 open、10 P2（8 open + 2 accepted）、1 P3）。本节只记录**设计响应**，不声称 findings 已 fixed；由 Ops 复验后更新状态。

| Finding | 严重度 | 处理 | 修改位置/证据 | Owner / Due / 备注 |
| --- | --- | --- | --- | --- |
| OPS-M06-001 | P2 | 设计已补（待 Ops 复验）：AC-23 判定 SQL、拒绝路径、`WAITING_APPROVAL` 清空顺序、演练断言 | §10.4、§10.5、§13.1 T16b | T16b（脚本）/T15（RUNBOOK）；M06 gate 前 |
| OPS-M06-002 | P1 | 设计已补：每槽位 batch=1、领取后先心跳、独立调度池、心跳池≥并发、interval≤lease/3、启动校验、重复领取验收 | §4.3、§10.1、§13.1 T06a/T15 | T06a/T15；T06a 开工前 |
| OPS-M06-003 | P1 | 设计已补：queue_depth/oldest_pending_age/lease_lost/claim_exhausted/claim_failures/审批等待/feature gauge 与阈值；告警归 M06 | §10.2、§10.5、§13.1 T15 | T15；T15 完成前 |
| OPS-M06-004 | P1 | 设计已补：V15 联动断言（CI 触发器、ApiApplicationTests、SchemaVersionCheckerTest、restore-drill、RUNBOOK）写入 T01/T15/T16b write_scope | §3.5、§13.1 T01/T15/T16b | T01/T16b；V15 合入前 |
| OPS-M06-005 | P1 | 设计已补：TRIGGER/binlog/MDL/max_allowed_packet 预检、DDL 非事务失败矩阵、repair/备份恢复、DML 幂等 | §3.5、§10.5、§13.1 T01 | T01；T01 编码前 |
| OPS-M06-006 | P1 | 设计已补：`docs/RUNBOOK.md` 写入 T15；补 M06 配置/开关/队列/审批/迁移/回滚/保留/指标/演练章节 | §10.5、§13.1 T15 | T15；M06 gate 前 |
| OPS-M06-007 | P2 | **accepted**（Ops 已接受）+ 设计补充：最老审批等待告警、取消/配置解除步骤；不改变 U-12 语义 | §10.2、§10.5、§13.1 T15/T09 | T15/T09；M06 gate 前；accepted_reason=U-12 已由 DEC-0046=A 批准，仅补可观测/操作 |
| OPS-M06-008 | P2 | 设计已补：`WF_INTERNAL` 时节点 FAILED（或创建失败尝试）、运行 FAILED、retry 可用 | §4.3、§4.6、§13.1 T06a/T08 | T06a/T08；T06a 完成前 |
| OPS-M06-009 | P2 | 设计已补：kill switch 观测审计（instanceId/observedAt）、feature gauge、Worker `CLAIM_ENABLED`/优雅停机；保留部署配置，不升用户决策 | §4.8、§10.1、§10.5、§13.1 T15 | T15；T15 完成前；accepted_reason=部署配置适合私有化，无需平台 UI |
| OPS-M06-010 | P2 | 设计已补：契约 `WorkflowRun.updatedAt`；终态停止轮询、result 只读一次；ETag/includeResult 记为后续优化 | §7.4、`contracts/openapi.yaml`、§13.1 T01/T08/T12a/T12b | T01/T08/T12a/T12b；T08 契约冻结前 |
| OPS-M06-011 | P2 | 设计已补：JDBC `maxAllowedPacket`/服务器 `max_allowed_packet` ≥8 MiB 预检、容量公式、结果分位数、节点 256 KiB 与总 5 MiB 先后关系 | §10.1、§10.5、§13.1 T15/T16b | T15/T16b；T15/T16b 完成前 |
| OPS-M06-012 | P2 | **accepted**（Ops 已接受）：180 天只做到期不可见、物理清理 M09；补 RUNBOOK 标准答复/容量告警 | §10.5、§13.1 T15；M09 | Lead/M09；M09 release blocker；accepted_reason=R-35/M09 排期，M06 不新增在线删除 |
| OPS-M06-013 | P2 | 设计已补：T15 3→4h、T16 8→10h、T11 5→2h，总额 120h；回滚演练脚本落 T16b | §13.1 工时/写范围 | Lead；M06 建卡时（T16a/b 合计 10h） |
| OPS-M06-014 | P2 | 设计已补：M06b 继承 DEC-0047 回滚口径；无 M06b 审计行才允许 M06b→M06 API 回滚 | §12、M26-B1 设计输入 | Architect；M06b 设计启动时 |
| OPS-M06-015 | P2 | 设计已补（Ops 可接受 TM-85）：claim SELECT 列集合静态断言、非法 tenant/领取失败 `claim_failures`/denied；`IT-WF-103`/`TEN-M06-01` 覆盖 | §4.3、§9.2 TM-85、§13.1 T06a | T06a/Verifier；T06a 完成前 |
| OPS-M06-016 | P3 | 设计已补：`RICCON_WORKFLOW_DISABLED_REASON`（可选，API 回退固定文案）入 §10.1/RUNBOOK | §10.1、§10.5、§13.1 T15 | T01/T15；T15 完成前 |

### 17.1 REV-M06-ARCH-001（Verifier）

评审基线 `751d384`、本轮 HEAD `90ff68e`，结论 `fail`（7 P1 / 8 P2 / 4 P3）。本节记录设计响应，不声称 fixed。

| Finding | 严重度 | 处理 | 修改位置/证据 | Owner / Due / 备注 |
| --- | --- | --- | --- | --- |
| F-001 | P1 | 设计已补：`branch` enum 加引号为 `["TRUE","FALSE",null]`；契约测试断言字符串 | `contracts/openapi.yaml`、§4.1、§13.1 T01 | T01；T01 实现前 |
| F-002 | P1 | 设计已补：`WorkflowNodeConfig` 增加 `actionKind`/`targetNodeKey`，服务端校验指向直接后继 | `contracts/openapi.yaml`、§4.4、§13.1 T01/T03 | T01/T03；实现前 |
| F-003 | P1 | **Product 已修订（9d30ac1）**：AC-16 共享审计归 M06b AC-11/25；M06 只验收发布/运行/取消/审批/停用/授权/移交；待复验 | PRODUCT AC-16、§14 AC-16 备注、§8.1 | Product/Verifier；M06 gate 前 |
| F-004 | P1 | 设计已补：`WorkflowApproval.dataScopeSummary`（runScope/departmentId/引用计数），只含计数不含正文 | `contracts/openapi.yaml`、§3.3.5、§5.3、§13.1 T09 | T09；实现前 |
| F-005 | P1 | 设计已补：`WorkflowPlatformTenantFilter` 在认证后、权限拦截器前对 platform 租户返回 404；TEN-M06-01 断言 | §6.1、§13.1 T01/T05 | T01/T05；实现前 |
| F-006 | P1 | 设计已补：`McpCallExecutor` 策略 `READ_ONLY`（J2）/`APPROVAL_BOUND_WRITE`（M06）；J2 WRITE 继续拒绝 | §4.7、§13.1 T07b；`IT-HITL-001`/`MCP-001..008`/`SEC-WF-004` | T07b；实现前 |
| F-007 | P1 | 设计已补：T02–T09/T15 write_scope 增 `src/test`；IT-WF owner 明确 | §13.1 任务表 | Lead 建卡；实现前 |
| F-008 | P2 | **Product 已修订（9d30ac1）**：AC-01 Given 改为所有者/租户管理员并发，同侪编辑不在 v1；U-03 待复验后关闭 | PRODUCT AC-01、§6.2、§16 U-03 | Product/Verifier；M06 gate 前 |
| F-009 | P2 | 设计已补：不新增列，用 `errorCode=WF_NO_APPROVER` + 固定 errorMessage；`SEC-WF-001` 断言 | §5.2、§13.1 T09 | T09；实现前 |
| F-010 | P2 | 设计已补：`WorkflowRun.updatedAt` required；终态停止轮询、result 只读一次（与 OPS-M06-010 同一修改） | `contracts/openapi.yaml`、§7.4、§13.1 T01/T08 | T01/T08；T08 契约冻结前 |
| F-011 | P2 | 设计已补：`workflow_node_run.input_summary` + `WorkflowNodeRun.inputSummary` | §3.3.4、§4.4、`contracts/openapi.yaml`、T08 | T08；实现前 |
| F-012 | P2 | 设计已补：`approvalStatus` 聚合规则 + pending/approved/rejected counts（派生，不新增列） | §3.3.3、§5.4、`contracts/openapi.yaml`、T08 | T08；实现前 |
| F-013 | P2 | **未解决（U-05）**：保留理由入审计为临时默认；安全评审/Product 在 M06 gate 前二选一（原文+提示 vs hash+记录保留）；不得声称已接受 | §8.3、§16 U-05 | 安全评审/Product；M06 gate 前 |
| F-014 | P2 | 设计已补：claim SELECT 恰好 4 列、非法 tenant fail-closed、claim_failures/denied、静态断言与负向测试；需独立安全评审（与 OPS-M06-015 同一修改） | §4.3、§9.2 TM-85、T06a/TEN-M06-01 | T06a/Verifier；实现前 |
| F-015 | P2 | 设计已补：拆 T06a/b、T07a/b、T12a/b、T16a/b；总 120h 不变（与 OPS-M06-013 合并） | §13.1 | Lead 建卡；实现前 |
| F-017 | P3 | 设计已补：请求 schema `if decision=REJECT then comment required/minLength=1`；400 断言 | `contracts/openapi.yaml`、§5.4、T01/T09 | T01/T09；实现前 |
| F-018 | P3 | 设计已补：内置模板 `visibility=BUILT_IN`（新增枚举），用户模板 PRIVATE/TENANT | `contracts/openapi.yaml`、§3.3.7、§12、T14 | T14；实现前 |
| F-019 | P3 | 设计已补：THREAT-MODEL R-06-1 改为“M06 仍不执行用户代码；开放前另立模块并重开威胁模型” | `docs/security/THREAT-MODEL.md` | Architect；M06 gate 前 |
| F-020 | P3 | 设计已补：高风险任务统一 T01–T13、T16、T17，§15.5 与任务表一致 | §13.1、§15.5 | Lead 建卡；实现前 |

> 复验口径：Ops 重跑 REV-M06-OPS-001 时只审 delta；本表状态不代表 fixed。P1 必须在 M06 实现前由 Ops 复验；P2 accepted 项保留 owner/due。

## Consequences

- 正面：J2 运行/审批/工具链路零改动；工作流运行/审批语义独立、可测试；模型成本自然复用 M04；契约 additive；M06b 无迁移；权限/审计/回滚边界清晰。
- 负面：新增 9 张表与 2 个事件；运行结果存 MySQL 有 5 MiB 上限；单入边树状图表达力有限；API 回滚受审计白名单约束；`purpose=REPORT` 的成本口径需要解释；V15 触发器需要迁移账号权限。
- 约束：不得复用 `agent_run`/`approval`/`mcp_invocation` 承载工作流；不得引入脚本/表达式/HTTP/并行/循环/定时；不得把 `manager_user_id` 当权限；不得新增 down migration；不得在 M06 实现 M06b 共享/升级 UI。

## Alternatives

- **复用 `agent_run` + `approval`**：J2 回归面大、CHECK/唯一键冲突，拒绝。
- **通用 `approval` 加 `workflow_*` 可空列**：需改 NOT NULL/FK/唯一键并重建表，J2 风险，拒绝。
- **引入工作流引擎（Temporal/Camunda/Flowable）**：新增中间件/运维成本，不符合“无聊、成熟、私有化轻量”，拒绝。
- **图数据库**：无必要；MySQL JSON + 邻接校验足够，拒绝。
- **React Flow 等画布库**：许可可行但增加依赖与 a11y 成本；自研 SVG + 表单等价路径，拒绝新依赖。
- **SSE 运行进度**：M06 无逐 token 流，轮询足够；减少连接/网关成本，拒绝（可后续另立）。
- **对象存储结果**：引入 presigned URL/清理/M02 F-015 债务；5 MiB 内 MySQL 更简单，拒绝。
- **按模型节点逐次配额**：需把配额准入下沉到 worker 或共享模块；M06 预算内不做，拒绝（DEC-0048=A 已选择按运行计）。

## Evidence

- `docs/PRODUCT.md` §12、DEC-0046=A
- `apps/api/src/main/resources/db/migration/V4__research_runs_and_artifacts.sql`、`V5__model_gateway_and_mcp.sql`、`V9`、`V11`、`V12`、`V14`
- `apps/api/src/main/java/com/riccon/api/research/service/ApprovalService.java`、`apps/worker/src/main/java/com/riccon/worker/research/service/ApprovalService.java`
- `apps/worker/src/main/java/com/riccon/worker/run/domain/OutboxLane.java`、`OutboxPoller`、`ResearchRepository`
- `apps/worker/src/main/java/com/riccon/worker/agent/persistence/AgentContextJdbc.java`、`apps/api/src/main/java/com/riccon/api/knowledge/repository/KnowledgeRepository.java`
- `apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java`、`AuditDetailsReader.java`
- `platform/platform-domain/src/main/java/com/riccon/platform/domain/{identity,contract,audit,model}/**`
- `contracts/openapi.yaml`、`contracts/internal-api.yaml`、`contracts/events/**`、`contracts/README.md`
- `docs/security/THREAT-MODEL.md` §4–§10

## 修订记录

- 2026-11-25：初稿（Architect），对应 DEC-0046=A；契约 `1.6.0-m06`；状态 `proposed`。
