# ADR-0022：M04 配额执行与成本估算

Status: proposed
Date: 2026-11-20
Reviewers: `riccon/lead`（范围/合同一致性）、`riccon/verifier`（并发、窗口边界、跨租户与金额可测试性）、`riccon/ops`（迁移/重置/保留/可观测性）、安全评审（越权、成本推断、denial-of-wallet）
Approved-by: pending（准入计费语义、默认限额与成本口径需 DEC-0024；ADR 本身未评审前不标 `accepted`）
Review-date: M04 gate 前（预计 2026-12-05）
Supersedes: none
Scope: M04「模型网关纵深」的租户/用户配额与成本估算；路由/降级/多 key 见 ADR-0021
关联: DEC-0023（A）、`docs/PRODUCT.md` §10（AC-08..12、AC-17/18）、ADR-0007（审计）、ADR-0009（租户隔离）、ADR-0010（Flyway）、ADR-0012（数据模型）、ADR-0021（M04 路由）、`docs/security/THREAT-MODEL.md` §4.4/§7/§9/§10

## Context

M04 的产品范围（DEC-0023=A，OQ-18..20/25 推荐默认值）要求：

- 配额为**租户 + 用户**两级，按**请求数**计，**日 + 月**窗口，**硬限额**阻断新 run；平台管理员写、租户管理员只读；token/成本配额后续。
- 超限返回与“模型不可用”可区分的稳定错误码、中文文案与重置时间；在途 run 不被破坏。
- 限额边界 50 并发零超卖（AC-09）。
- 成本 = `prompt_tokens × input_price_snapshot + completion_tokens × output_price_snapshot`；6 位小数计算，展示按币种最小单位四舍五入，合计按未舍入值汇总；未定价/ token 缺失单列；价格快照不可被后续价格更新改写。
- 单一币种由部署配置；价格由部署种子/运维维护，M04 只读；成本是估算不是账单。
- 平台管理员看租户聚合（无用户明细）；租户管理员看本租户用户/模型/日明细；员工无入口；跨租户统一 404。
- 日/月窗口与成本日桶统一 `Asia/Shanghai`；审计保留 ≥180 天不变。

M02 的 `model_invocation` 只有 token 与状态，没有价格/币种/成本/日桶；`agent_run` 在 API 创建（`ChatSubmissionService`、`ResearchTaskService`）时入队，Worker 领取执行。M02 没有配额或成本查询端点。M04 必须在不破坏 M02/M03/M10 契约与 E2E 的前提下补齐。

## Decision

### 1. 配额模型

#### 1.1 维度、窗口与默认值

| 项         | 决定                                                                                                   |
| ---------- | ------------------------------------------------------------------------------------------------------ |
| 维度       | 租户级 + 用户级；两级都必须通过才准入（AND）                                                           |
| 计量       | 按请求数（1 个新 run = 1 次）；不按 token/成本                                                         |
| 窗口       | `DAILY`（`Asia/Shanghai` 自然日 00:00:00–23:59:59.999）+ `MONTHLY`（自然月）                           |
| 严格度     | 硬限额：超限拒绝新 run；在途 run 继续                                                                  |
| 写权限     | 平台管理员（`platform.quota.manage`）；租户管理员只读（`quota.read`）；员工无入口                      |
| 默认值     | 无显式行时取部署配置：租户日 10,000 / 月 200,000；用户日 1,000 / 月 20,000；`0` = 不限。显式行覆盖配置 |
| 重置时间   | 返回带偏移的绝对时间（`2026-12-01T00:00:00+08:00`）与“还有 X 小时/天”的展示文案由前端计算              |
| 时钟       | 服务端注入 `Clock`；窗口起点用 `Asia/Shanghai` 计算后转 UTC 存储；测试用固定时钟                       |
| 流式 token | 配额按请求数，**不依赖 token**；流式 token 未知只影响成本 `usage` 状态，不影响准入/计数                |

默认值理由：私有化默认不应让租户在升级后立刻被阻断，但也不能无限 denial-of-wallet；上述值是演示/小规模保守基线，部署可改。显式 `limit_count=NULL` 表示“明确不限”，与“无行取默认”区分，响应 `source=EXPLICIT_UNLIMITED|DEPLOYMENT_DEFAULT|EXPLICIT`。

#### 1.2 数据模型（V11，与 ADR-0021 同一迁移）

```sql
CREATE TABLE quota_limit (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type  VARCHAR(16) NOT NULL COMMENT 'TENANT|USER',
    scope_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TENANT 为 tenant_id；USER 为用户 UUID 字符串',
    user_id     BINARY(16) NULL COMMENT 'USER 维度必填；复合外键 (tenant_id,user_id)',
    window_kind VARCHAR(8) NOT NULL COMMENT 'DAILY|MONTHLY',
    limit_count INT NULL COMMENT 'NULL=明确不限；0 也视为不限（兼容配置 0）',
    updated_by  BINARY(16) NULL COMMENT '平台管理员账号 ID；逻辑引用',
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (tenant_id, scope_type, scope_id, window_kind),
    KEY idx_quota_limit_user (tenant_id, user_id),
    CONSTRAINT fk_quota_limit_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_quota_limit_user FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_quota_limit_scope CHECK (scope_type IN ('TENANT', 'USER')),
    CONSTRAINT chk_quota_limit_window CHECK (window_kind IN ('DAILY', 'MONTHLY')),
    CONSTRAINT chk_quota_limit_scope_user CHECK (
        (scope_type = 'TENANT' AND user_id IS NULL AND scope_id = tenant_id)
        OR (scope_type = 'USER' AND user_id IS NOT NULL)
    ),
    CONSTRAINT chk_quota_limit_count CHECK (limit_count IS NULL OR limit_count >= 0)
) ENGINE = InnoDB COMMENT = '配额上限（M04）';

CREATE TABLE quota_usage (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type  VARCHAR(16) NOT NULL COMMENT 'TENANT|USER',
    scope_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_kind VARCHAR(8) NOT NULL COMMENT 'DAILY|MONTHLY',
    window_start DATETIME(6) NOT NULL COMMENT '窗口起点（UTC）；Asia/Shanghai 自然日/月边界',
    used_count  INT NOT NULL DEFAULT 0 COMMENT '已准入请求数',
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (tenant_id, scope_type, scope_id, window_kind, window_start),
    KEY idx_quota_usage_cleanup (window_start),
    CONSTRAINT fk_quota_usage_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_quota_usage_scope CHECK (scope_type IN ('TENANT', 'USER')),
    CONSTRAINT chk_quota_usage_window CHECK (window_kind IN ('DAILY', 'MONTHLY')),
    CONSTRAINT chk_quota_usage_count CHECK (used_count >= 0)
) ENGINE = InnoDB COMMENT = '配额用量（M04）';
```

- `scope_id` 是字符串键（租户代码或用户 UUID），便于 PK 与展示；`user_id` 仅为复合外键与查询辅助；服务层保证一致。
- `quota_limit`/`quota_usage` 是租户表但不使用独立 `id`，主键由业务键组成；这是 ADR-0012 复合键规则的**计数表例外**，仍以 `tenant_id` 前缀并受租户外键约束。
- 不建 reservation 表：准入在 API 创建 `agent_run` 的同一事务内完成，事务回滚即回滚计数；同 run 的重试/恢复不再次准入。
- `quota_usage` 保留历史窗口（用于审计/排障），M09 维护任务按 `window_start` 清理 ≥180 天前的行。

#### 1.3 原子计数（零超卖）

准入算法（单事务，固定顺序；**不使用 `INSERT IGNORE`**）：

```text
if quotaEnforcementEnabled == false:
    # 只计量：仍补行并 +1，但不做 limit 比较、不抛 QuotaExceeded
    for scope/window: ensure row; UPDATE used_count = used_count + 1
    record metric riccon_quota_over_limit_observed_total（如果 used > limit）
    continue to INSERT agent_run
else:
  for (scope in [TENANT, USER]) for (window in [DAILY, MONTHLY]):
    effectiveLimit = explicit limit row ?? deployment default (0 => unlimited)
    # 1) 确保行存在；ON DUPLICATE 做 no-op 更新，取排他行锁，避免 INSERT IGNORE 吞错/共享锁升级
    INSERT INTO quota_usage (tenant_id, scope_type, scope_id, window_kind, window_start, used_count)
        VALUES (..., 0)
        ON DUPLICATE KEY UPDATE used_count = used_count
    # 2) 条件更新
    if effectiveLimit is unlimited:
        UPDATE quota_usage SET used_count = used_count + 1 WHERE <pk>
    else:
        UPDATE quota_usage SET used_count = used_count + 1
         WHERE <pk> AND used_count < :effectiveLimit
        if affectedRows == 0: throw QuotaExceeded(scope, window)
  -- 之后才 INSERT agent_run
```

- MySQL InnoDB 对同一 `quota_usage` 行的 `UPDATE ... WHERE used_count < limit` 是当前读 + 行锁；50 并发下当剩余 1 次时恰好 1 个事务成功，其余 `affectedRows=0`。不存在“先查后写”的竞态。
- 顺序固定 `TENANT/DAILY → TENANT/MONTHLY → USER/DAILY → USER/MONTHLY`，所有事务同序，避免交叉死锁；同一窗口是单行热点，50 并发串行在行锁上，`innodb_lock_wait_timeout` 必须小于 API 请求超时（建议 3s），连接池/事务超时同 RUNBOOK。
- **死锁/锁等待**：准入**不做自动重试**（自动重试可能重复扣或重复建 run）；捕获死锁/锁超时 → 返回 503 `RICCON-QUOTA-503` + `riccon_quota_store_failure_total{reason=deadlock|lock_wait}`，由调用方人工重试。50 并发用例要求 0 死锁；若出现即视为 B6 缺陷。
- 任一步失败抛出异常 → Spring 事务回滚 → 前序计数回滚；不会出现“日窗口扣了、月窗口拒绝”的半扣。
- 计数更新与 `agent_run` 插入在同一 `@Transactional` 内（`ChatSubmissionService`、`ResearchTaskService` 的既有事务）；不新增分布式事务。
- **拒绝审计独立事务**：准入失败回滚后，外层用 `QuotaAuditService`（`REQUIRES_NEW`，位于 `quota` 包）写 `quota.exceeded`；审计失败返回 500（不是 429），且不创建 run。成功准入不写拒绝审计。不能用同一事务写拒绝审计（会被回滚）。
- 失败/取消/部分流**不退还**：准入即计费；这防止失败重试风暴。同一 run 的 Worker 重试（`attempt+1`）不再次准入。
- 超限响应 `RICCON-QUOTA-429`，`QuotaExceededProblem` 包含 `scopeType`、`window`、`limit`、`used`、`resetsAt`；文案“已达{日/月}限额，{重置时间}后恢复。”由前端按字段渲染，服务端 `detail` 给同一中文句子。
- **`quotaEnforcementEnabled=false`**：仍记录用量（供成本/容量观察），但跳过拒绝；不写 `quota.exceeded`；写 `riccon_quota_over_limit_observed_total` 指标。开关变更由 B3/API 审计，M17-B13 RUNBOOK 记录（B10 只引用 M17 章节）。
- **配置默认值与显式行**：`limit_count=0` 与 `NULL` 都表示不限（历史/部署配置常用 0）；响应 `source` 区分 `EXPLICIT_UNLIMITED`、`DEPLOYMENT_DEFAULT`、`EXPLICIT`。

#### 1.4 执行点、在途与重试

| 场景                           | 行为                                                                                 |
| ------------------------------ | ------------------------------------------------------------------------------------ |
| J1 发消息创建 CHAT run         | 准入在 `ChatSubmissionService` 事务内；拒绝时返回 JSON `429`（不是 SSE），不创建 run |
| J2 创建报告任务                | 准入在 `ResearchTaskService` 事务内；拒绝时返回 JSON `429`，任务不创建               |
| 文件解析/知识库索引            | **不消耗配额**（不调用模型）                                                         |
| 已在运行的 run                 | 不受后续限额降低影响；Worker 不重复准入                                              |
| `Idempotency-Key` 命中既有任务 | 返回既有任务，不再次准入、不扣配额（先查幂等键，再扣配额）                           |
| Worker 重试同一 run            | 不再次准入（run 已存在）                                                             |
| 超限后被拒绝的请求             | 调高限额后重试 → 新建 run → 正常扣 1                                                 |
| 全部模型失败的 run             | 已扣 1，不退还（准入语义）                                                           |
| 用户取消                       | 已扣 1，不退还                                                                       |

#### 1.5 权限、审计与错误

- 权限：`quota.read`（`TENANT_ADMIN`）、`platform.quota.manage`（`PLATFORM_ADMIN`）。员工 403/无入口。
- 平台读：`GET /api/v1/admin/quotas` 只返回租户聚合（tenantId、tenantCode、tenantName、日/月 limit/used/resetsAt），**不含用户明细**。
- 租户读：`GET /api/v1/quotas` 返回本租户窗口；`GET /api/v1/quotas/users` 返回本租户用户分页明细。
- 写：`PUT /api/v1/admin/tenants/{tenantId}/quotas`（租户级）、`PUT /api/v1/admin/tenants/{tenantId}/users/{userId}/quotas`（用户级）。请求体 `limits:{daily,monthly}`，`null` = 明确不限；两键必填，PUT 为整体替换。
- 审计：`quota.limit.changed`（目标 `QUOTA`，details：`scopeType`、`scopeId`、`window`、`previousLimit`（首次设置允许 JSON null）、`limit`）、`quota.exceeded`（details：`scopeType`、`scopeId`、`window`、`limit`、`used`、`resetsAt`）。`QuotaAuditService` 使用 `REQUIRES_NEW` 写拒绝；审计失败 → 写操作失败；拒绝时审计失败 → 返回 500 而不是 429（保持“无审计不拒绝”的 fail-closed）。
- 跨租户：管理员路径的目标租户不存在/停用 → 404/409；租户管理员访问他租户 → 404；不返回金额或用量。

#### 1.6 时区与边界

- 日窗口：`Asia/Shanghai` 当日 00:00:00 至次日 00:00:00 前；月窗口：当月 1 日 00:00:00 至次月 1 日 00:00:00 前。
- `window_start` 存 UTC 瞬时（例：`2026-12-01T00:00:00+08:00` → `2026-11-30T16:00:00Z`）。
- 重置时间：日窗口为次日 00:00+08:00；月窗口为次月 1 日 00:00+08:00。
- 测试：`2026-11-30T23:59:59.999+08:00` 属于 11 月；`2026-12-01T00:00:00+08:00` 属于 12 月。Clock 注入，禁止依赖真实时间。
- 不做 DST 特判（`Asia/Shanghai` 当前无 DST），但使用 `ZonedDateTime` 而不是手工加 8 小时。

#### 1.7 配额存储不可用：fail-closed

- 配额读/写失败（DB 超时、表缺失）→ **拒绝新 run**，返回 503 `RICCON-QUOTA-503`（“用量服务暂不可用，请稍后重试。”），不创建 run；在途 run 继续；记录错误日志与指标 `riccon_quota_store_failure_total`。
- 理由：T4 成本边界优先于可用性；fail-open 会让成本失控且难以事后归因。DB 本来就是 run 创建的依赖，fail-closed 不引入新的单点。
- 不做“本地内存计数兜底”：多实例不一致且重启丢失，会比拒绝更危险。

### 2. 成本估算

#### 2.1 价格快照

- 价格源：`model.input_price_per_1m`、`model.output_price_per_1m`（DECIMAL(12,6)，每百万 token，币种由部署配置），由部署种子/运维维护；M04 **不提供价格编辑端点**。
- 快照时机：**最终成功尝试开始时**读取该模型的输入/输出价格并固定到本次调用上下文；失败尝试不计成本；同一 `model_invocation` 不会混合多个模型的价格。完成时写入 `model_invocation`，避免调用过程中价格变更导致同一回答使用两个价格。全链失败时没有成功模型，`cost_status=USAGE_UNKNOWN`、价格/金额为空。
- 快照列（ADR-0021 §3.3）：`input_price_per_1m`、`output_price_per_1m`、`price_currency`、`pricing_version='v1'`、`cost_amount`、`cost_status`、`cost_day`。
- 价格更新后历史不变：查询只读快照列，不 join 当前 `model` 价格；`IT-COST-101` 断言更新价格后历史金额不变。
- 单币种：`RICCON_BILLING_CURRENCY`（ISO 4217，默认 `CNY`）。币种变更不是 M04 支持的操作；若历史快照与当前配置不一致，成本报告返回 `currencyMismatchCalls` 并把它们排除在金额合计之外（正常情况下为 0）。

#### 2.2 计算与舍入

```text
cost = prompt_tokens * input_price_per_1m / 1_000_000
     + completion_tokens * output_price_per_1m / 1_000_000
```

- **口径固定**：“6 位小数”是**单笔计算/存储精度**；合计对已存储的 6 位 `cost_amount` 求和，**不按展示的 2 位先舍入**；仅在最终展示时按币种最小单位舍入一次。固定样例：`prompt_tokens=1000, input_price=3.000000` → `0.003000`；两笔 `0.0000004` 在 6 位 HALF_UP 后均为 `0.000000`，合计 `0.000000`（若 Product 要求更高精度，需改为 `DECIMAL(20,12)` 并另立契约；见 DEC-0024）。
- 用 `BigDecimal` 计算，`scale=6`，`RoundingMode.HALF_UP`；结果写入 `cost_amount DECIMAL(20,6)`。
- 展示：按币种最小单位四舍五入（CNY 2 位）；API 返回 `amount` 为 6 位小数字符串 + `currencyMinorUnit`，前端用同一 HALF_UP 规则展示。
- 不引入浮点 `double`；JSON 金额用字符串避免精度丢失。

#### 2.3 usage、未定价与失败调用

| `cost_status`   | 条件                                                      | 金额       | 报告                   |
| --------------- | --------------------------------------------------------- | ---------- | ---------------------- |
| `PRICED`        | 输入/输出价格均非空 **且** 两个 token 均非空              | 计算并写入 | 计入金额合计           |
| `UNPRICED`      | 输入或输出价格任一为空（模型未定价）                      | NULL       | 单列调用量，不计入金额 |
| `USAGE_UNKNOWN` | 价格齐全但 token 缺失（上游未返回 usage、失败/取消/断流） | NULL       | 单列调用量，不计入金额 |

- 优先级：先判断价格，再判断 token；两者都缺 → `UNPRICED`（因为即使有 token 也无法计价）。
- usage 以 provider 返回的 `prompt_tokens`/`completion_tokens` 为准；M04 **不做本地 tokenizer 估算**（依赖与误差不可控）。缺失即 `USAGE_UNKNOWN`。
- 失败/取消调用也写 `model_invocation`（M02 已有），`cost_status=USAGE_UNKNOWN`，计入“调用量”但不计金额；便于运维看到失败率，不伪造成本。
- `cost_day` 用 `started_at` 转 `Asia/Shanghai` 的自然日；`started_at` 仍 UTC。回滚窗口旧 Worker 不写 `cost_day`，这些行在报告中计入 `unbucketedCalls`（**不是** `cost_status` 值），单列且不计金额；RUNBOOK 标明混合版本窗口不可用于对账。

#### 2.4 聚合与可见边界

| 角色       | 端点                            | 维度                       | 边界                                                                 |
| ---------- | ------------------------------- | -------------------------- | -------------------------------------------------------------------- |
| 平台管理员 | `GET /api/v1/admin/costs`       | `groupBy=TENANT/MODEL/DAY` | 跨租户聚合，**不返回用户维度**；可选 `tenantId` 过滤（仍只返回聚合） |
| 租户管理员 | `GET /api/v1/costs`             | `groupBy=USER/MODEL/DAY`   | 只本租户；`userId`/`modelId` 可选过滤                                |
| 租户管理员 | `GET /api/v1/costs/invocations` | 明细                       | 只本租户；返回价格快照/金额/状态/用户显示名；分页                    |
| 员工       | —                               | —                          | 无入口；访问返回 403/404                                             |
| 平台管理员 | 明细                            | —                          | **不提供**按用户的调用明细；避免跨租户成本推断                       |

- 时间范围：`from`/`to` 为 `YYYY-MM-DD`（`Asia/Shanghai` 自然日，含端点），默认最近 30 天，最大 180 天；`from <= to`。
- 维度：平台 `TENANT/MODEL/DAY`、租户 `USER/MODEL/DAY` 均可作为**可选切换视图**；AC-11 的“按租户、模型、日聚合”由三个维度视图共同满足，AC-14 的“切换聚合维度”即此。M04 不做多维度同时分组的交叉表；若评审要求交叉表，另立契约增量。
- **AC-11 的“明细可展开”只适用于租户管理员**：平台管理员只到租户/模型/日聚合，不提供按用户明细（AC-12/OQ-19）。契约使用独立 `TenantCostReport`/`TenantCostBucket`（含 user 字段）与 `PlatformCostReport`/`PlatformCostBucket`（不含 user 字段，groupBy 不含 USER）。
- 分页：`page` 1..200、`size` 1..100；聚合结果按金额降序、再按维度键升序，固定排序。
- 响应：`currency`、`currencyMinorUnit`、`totals`（`amount`、`pricedCalls`、`unpricedCalls`、`usageUnknownCalls`、`currencyMismatchCalls`、`unbucketedCalls`）、`items[]`（维度键、金额、三类计数）。
- 固定文案由前端展示：“成本为按调用时价格快照估算，不是供应商账单。”API 在响应 `disclaimer` 返回同一句，避免前端硬编码漂移。
- 成本查看**不写审计**（M04 不要求，避免读放大）；记录指标 `riccon_cost_report_queries_total`。若未来需要“谁看过成本”，另立动作与授权模型。

#### 2.5 聚合实现与保留

- **不做预聚合表**：按 `model_invocation` 实时聚合，索引 `(tenant_id, started_at)`、`(tenant_id, user_id, started_at)`、`(tenant_id, cost_day)` 与显式 `(provider_id)` 覆盖范围过滤与分组；不再新增与既有 `(tenant_id, model_id, started_at)` 重叠的索引。
- 理由：私有化规模（50 并发、单租户月调用量万级）下实时聚合足够；预聚合会引入回填、重算与一致性风险。若 M09 观测到 p95 > 2 s，再评估按日 rollup（另立 ADR）。
- 保留：`model_invocation` 与 `model_invocation_attempt` 默认保留 ≥180 天，与成本可查范围一致；M09 维护任务按 `started_at`/`cost_day` 清理更早数据，先 attempt 再 invocation，`quota_usage` 历史窗口同批清理。清理不删除审计；成本历史不要求永久保留。
- 查询保护：最大 180 天、page ≤200、size ≤100；DB 超时 3 s，超时返回 503 `RICCON-COST-503`（不返回部分结果）。

### 3. 契约

#### 3.1 端点

| 方法 | 路径                                                     | 权限                    | 说明                                  |
| ---- | -------------------------------------------------------- | ----------------------- | ------------------------------------- |
| GET  | `/api/v1/quotas`                                         | `quota.read`            | 本租户日/月限额、用量、重置时间、来源 |
| GET  | `/api/v1/quotas/users`                                   | `quota.read`            | 本租户用户分页明细                    |
| GET  | `/api/v1/admin/quotas`                                   | `platform.quota.manage` | 跨租户聚合（无用户明细）              |
| PUT  | `/api/v1/admin/tenants/{tenantId}/quotas`                | `platform.quota.manage` | 租户级整体替换                        |
| PUT  | `/api/v1/admin/tenants/{tenantId}/users/{userId}/quotas` | `platform.quota.manage` | 用户级整体替换                        |
| GET  | `/api/v1/costs`                                          | `cost.read`             | 本租户聚合（USER/MODEL/DAY）          |
| GET  | `/api/v1/costs/invocations`                              | `cost.read`             | 本租户调用明细（价格快照）            |
| GET  | `/api/v1/admin/costs`                                    | `platform.cost.read`    | 跨租户聚合（TENANT/MODEL/DAY）        |

#### 3.2 Schema 摘要

- `QuotaWindowState`：`window`（`DAILY|MONTHLY`）、`limit`（int|null）、`used`、`resetsAt`、`source`（`EXPLICIT|EXPLICIT_UNLIMITED|DEPLOYMENT_DEFAULT`）。
- `TenantQuota`：`tenantId`、`tenantCode`、`tenantName?`、`windows[2]`。
- `UserQuota`：`userId`、`username`、`displayName`、`windows[2]`。
- `PlatformQuotaRow`：`tenantId`、`tenantCode`、`tenantName`、`windows[2]`（无用户字段）。
- `QuotaUpdateRequest`：`limits: {daily: int|null, monthly: int|null}`（两键必填；`null`=明确不限）。
- `TenantCostReport`：`from`、`to`、`groupBy`（USER/MODEL/DAY）、`currency`、`currencyMinorUnit`、`disclaimer`、`totals`、`items[]`（`TenantCostBucket` 含 userId/userDisplayName）。
- `PlatformCostReport`：`from`、`to`、`groupBy`（TENANT/MODEL/DAY）、`currency`、`currencyMinorUnit`、`disclaimer`、`totals`、`items[]`（`PlatformCostBucket` **不含** userId/userDisplayName）。
- `CostTotals`：`amount`、`pricedCalls`、`unpricedCalls`、`usageUnknownCalls`、`currencyMismatchCalls`、`unbucketedCalls`。
- `CostInvocation`：`id`、`modelId`、`modelDisplayName`、`userId`、`userDisplayName`、`startedAt`、`promptTokens`、`completionTokens`、`inputPricePer1M`、`outputPricePer1M`、`priceCurrency`（**可空**）、`amount`（可空）、`pricingStatus`；`UNPRICED`/`USAGE_UNKNOWN` 时价格/币种/金额可为 null。
- `QuotaExceededProblem`：`ProblemDetail` 字段 + `scopeType`、`window`、`limit`、`used`、`resetsAt`。
- `RateLimitedProblem`：`code` 枚举 `RICCON-RATE-429|RICCON-QUEUE-429`；与 `QuotaExceededProblem` 组成 429 `oneOf` + discriminator（契约测试按 `code` 判别）。

#### 3.3 错误码

| HTTP | code                                   | 场景                                                                       |
| ---- | -------------------------------------- | -------------------------------------------------------------------------- |
| 400  | `RICCON-QUOTA-400`                     | 限额非法（负数、缺键、窗口非法）；`RICCON-COST-400` 时间范围/维度/分页非法 |
| 403  | `RICCON-AUTH-403`                      | 员工/租户管理员访问无权端点                                                |
| 404  | `RICCON-TENANT-404`                    | 目标租户/用户不存在或跨租户；`/costs/invocations` 的 `userId` 不属于本租户 |
| 409  | `RICCON-QUOTA-409`                     | 并发修改同一配额（版本冲突，若启用版本；M04 用整体替换，暂不需要）         |
| 429  | `RICCON-QUOTA-429`                     | 配额超限（专用响应体）                                                     |
| 500  | `RICCON-COMMON-500`                    | 拒绝审计写入失败（fail-closed，不返回 429）                                |
| 503  | `RICCON-QUOTA-503` / `RICCON-COST-503` | 存储/查询不可用（fail-closed）                                             |

#### 3.4 审计

| 动作                  | 目标    | details 白名单                                                |
| --------------------- | ------- | ------------------------------------------------------------- |
| `quota.limit.changed` | `QUOTA` | `scopeType`、`scopeId`、`window`、`previousLimit`、`limit`    |
| `quota.exceeded`      | `QUOTA` | `scopeType`、`scopeId`、`window`、`limit`、`used`、`resetsAt` |

- 不含 prompt、文件、金额明细或用户名单；`scopeId` 是租户代码或用户 UUID。
- 平台修改租户配额：`tenant_id=platform`，`details.scopeId` 为目标租户/用户；目标租户在 M10 查询中不可见平台事件（M03 F-009 residual）。**M04 不改变该可见性模型**；租户管理员只能看到本租户的 `quota.exceeded`（actor 为本租户用户）与本租户配额读取，不看到平台的 `quota.limit.changed`。这是已接受残余，评审包登记。

### 4. 安全

| 风险                | 控制                                                                    | 验证                                 |
| ------------------- | ----------------------------------------------------------------------- | ------------------------------------ |
| 跨租户配额/成本泄漏 | 租户从主体派生；管理路径显式租户 + 角色；跨租户 404；平台聚合无用户明细 | `TEN-032`、`TEN-033`、`IT-QUOTA-103` |
| 越权修改配额        | `platform.quota.manage` 仅平台；租户 `quota.read` 只读；写接口 403      | `SEC-M04-003b`、`IT-QUOTA-103`      |
| 枚举/推断           | 统一 404/403；平台列表不返回用户维度；金额不返回给员工                  | `TEN-033`                            |
| 成本推断业务量      | 平台只看租户聚合；租户只看本租户；无公开成本端点                        | 评审 + `TEN-033`                     |
| 配额绕过            | 准入在 API 事务内；Worker 不暴露通用 run 创建；文件解析不计费           | `IT-QUOTA-101/102`                   |
| 数值精度            | `BigDecimal`/`DECIMAL`；金额字符串；禁止 `double`                       | `IT-COST-101`                        |
| 审计缺失            | 配置写/拒绝同事务或前置写；失败即失败                                   | `IT-QUOTA-101/103`                   |
| 读放大              | 180 天上限、page/size 上限、3 s 超时                                    | `PERF-M04-002`                       |

### 5. 迁移与回滚

- V11 与 ADR-0021 同一迁移：新增 `quota_limit`、`quota_usage`；不改 `agent_run`（准入不改表结构）。
- 权限种子：`quota.read`（TENANT_ADMIN）、`platform.quota.manage`（PLATFORM_ADMIN）、`cost.read`（TENANT_ADMIN）、`platform.cost.read`（PLATFORM_ADMIN）。
- 不回溯历史调用成本：M04 之前的历史 `model_invocation` 没有价格快照，`cost_status` 默认 `USAGE_UNKNOWN`（ADD COLUMN 的默认值已覆盖，**不执行全表 `UPDATE cost_status`**），在成本页单列；**不猜测历史价格**。这是有意的：宁可显示“用量未知”，也不伪造账单。
- **V11 不回溯历史 `cost_day`**：历史行保持 NULL，成本报告计入 `unbucketedCalls`；需要回填时由 B10 `scripts/ops/backfill-cost-day` 在 CHECK 后分批执行（每批 50,000、单独提交、记录进度），M04 gate 不依赖全量回填。迁移测试断言“历史 NULL 允许、M04 后新调用非空、金额未受影响”；`CONVERT_TZ` 用固定偏移 `'+00:00','+08:00'`，不依赖 tz 表。
- 回滚：V11 只加表/列；应用回滚到 M04 前版本时配额表被忽略、`agent_run` 创建不扣配额；历史 `model_invocation` 新列可空/有默认，旧应用可读。成本快照不删除、不改写（AC-18）。回滚窗口旧 Worker 不写 `cost_day` 的调用在报告中计入 `unbucketedCalls`，RUNBOOK 标明该窗口不可用于对账。
- 前向修复：限额误设 → 平台管理员 PUT；用量计数异常 → 人工对账后 `UPDATE quota_usage` 需运维工单（M04 不提供在线修正 API，避免越权改计数）；M09 维护任务清理旧窗口/attempt/调用（先 attempt 再 invocation，`LIMIT` 批量）。

### 6. 测试策略

| 类型            | 内容                                                                    | 编号/工具                      |
| --------------- | ----------------------------------------------------------------------- | ------------------------------ |
| 并发            | 剩余 1 次、50 并发，恰好 1 成功；日/月两级；两级同时边界                | `IT-QUOTA-102`、`PERF-M04-002` |
| 窗口边界        | `2026-11-30T23:59:59.999+08` vs `2026-12-01T00:00:00+08`；日/月重置时间 | 固定 Clock 单测                |
| 权限/跨租户     | 租户管理员写 403；他租户 404；平台无用户明细；员工无入口                | `IT-QUOTA-103`、`TEN-032/033`  |
| 失败关闭        | 配额表不可用 → 503，不创建 run；在途 run 继续                           | `IT-QUOTA-104`                 |
| 拒绝审计        | 拒绝审计独立事务；审计故障 → 500，无 run；审计存在                      | `IT-QUOTA-101`、`IT-QUOTA-103` |
| 在途/重试       | 限额降低不影响已运行 run；同 run 重试不重复扣；幂等命中不扣             | `IT-QUOTA-105`                 |
| 成本公式        | 6 位小数、展示舍入、合计未舍入；价格更新不改历史；固定样例              | `IT-COST-101`                  |
| 未定价/缺 usage | PRICED/UNPRICED/USAGE_UNKNOWN、`unbucketedCalls`、不计金额、计数正确    | `IT-COST-102`                  |
| 日桶            | `Asia/Shanghai` 边界；跨月                                              | `IT-COST-103`                  |
| 聚合边界        | 平台租户聚合无用户字段；租户用户明细；金额/调用量一致                   | `IT-COST-104`、`TEN-033`       |
| 历史数据        | M04 前调用为 `USAGE_UNKNOWN`，不被猜测                                  | 迁移测试                       |
| 性能            | 成本查询 30 天 p95；配额拒绝 50 并发                                    | `PERF-M04-002`                 |

- 并发测试使用真实 MySQL（Testcontainers/Compose），不用 H2；断言 `used_count` 与成功 run 数严格相等。
- 金额断言用 `BigDecimal` 比较，不用 `double`；CSV/JSON 金额字符串按 6 位小数断言。
- 时钟：`Clock.fixed` + `ZoneId.of("Asia/Shanghai")`；禁止 `LocalDate.now()`。

### 7. 任务图

任务、owner、写范围与依赖见 ADR-0021 §8：配额为 **M17-B6**（`riccon/builder-quota`），成本为 **M17-B7**（`riccon/builder-cost`）；Web 为 M17-B9b，契约为 M17-B12（`1.4.0-m04b`），E2E 为 M17-B11b，发布运维为 M17-B13，验证为 M17-V2。M04 任务只提供 schema/路由/failover 依赖；M17 为本 ADR 的模块归属（实际 ID `M17`，`split_from: M04`，别名 `M04b`）。

## Consequences

- 正面：50 并发零超卖可由单条条件 UPDATE 证明；超限与模型不可用可区分；在途 run 不被破坏；成本口径可重算、历史不可改写；平台/租户/员工三层可见性清晰；配额存储故障 fail-closed。
- 负面：准入即计费，失败/取消不退还，可能让用户“浪费”一次配额；平台托管默认限额需要部署配置；成本不做本地估算，缺 usage 时只能显示“用量未知”；平台看不到用户明细，排障粒度有限；实时聚合在超大数据量下可能变慢（M09 再评估 rollup）。
- 约束：所有配额写必须平台管理员 + 审计；所有成本查询必须租户/平台边界；金额禁止浮点；窗口必须 `Asia/Shanghai`；历史快照禁止 UPDATE。

## Alternatives

- **软限额/仅告警**：无法阻断 denial-of-wallet；DEC-0023 明确硬限额；拒绝。
- **按 token 或成本配额**：需要先有可靠 usage 与价格，且流式结束时才能知道；后移（OQ-18）；拒绝在 M04 做。
- **预扣 + 结束后退款/补扣**：复杂、易漂移，且失败/取消语义争议大；M04 采用准入计数；拒绝。
- **Redis 计数器**：引入第二套存储、持久化与一致性风险；MySQL 条件更新已足够；拒绝。
- **成本预聚合表**：回填/重算/一致性成本高，当前规模不需要；拒绝，保留 M09 评估。
- **本地 tokenizer 估算**：与供应商计费口径不一致，会制造“看似精确”的错误金额；拒绝。
- **平台查看用户级成本明细**：跨租户推断业务量与个人行为；DEC-0023/OQ-19 明确不做；拒绝。

## Evidence

- `contracts/openapi.yaml` `1.3.0-m04`：配额/成本端点与 schema。
- `docs/ARCHITECTURE.md` M04 实现地图、权限矩阵、迁移/回滚。
- `docs/security/THREAT-MODEL.md` TM-45..TM-48、ABUSE-09/11、R-24。
- 现有实现：`apps/api/.../chat/service/ChatSubmissionService.java`、`ResearchTaskService.java`、`apps/worker/.../model/service/ModelInvocationService.java`、`V5__model_gateway_and_mcp.sql`。
- 决策：DEC-0023；待 DEC-0024（准入计费、默认限额、单币种、回滚窗口）。

## 8. 不可逆/高成本项（需 DEC-0024）

| 项                                                           | 为什么需要用户批准                    | 建议                                                        |
| ------------------------------------------------------------ | ------------------------------------- | ----------------------------------------------------------- |
| 准入即计费、失败/取消不退还                                  | 直接影响员工配额消耗与支持口径        | 批准；若要求退款，需改为 reservation + 对账，范围与预算另议 |
| 默认限额（租户日 10,000/月 200,000；用户日 1,000/月 20,000） | 影响升级后是否被阻断                  | 批准为部署默认，可按客户调整                                |
| 平台不看用户明细、租户只看本租户                             | 已由 OQ-19 批准；需确认排障边界       | 批准；跨租户明细保持 deferred                               |
| 单币种、无价格编辑、历史不重算                               | 已由 OQ-20 批准；币种变更不支持需确认 | 批准                                                        |
| M04 前历史调用显示“用量未知”                                 | 不伪造历史账单                        | 批准                                                        |
| 6 位小数口径（单笔 6 位、合计按 6 位值、展示一次舍入）       | 影响“成本重算误差 0”的判定            | 批准；若要求更高精度，改 `DECIMAL(20,12)` 并另立契约        |
| 回滚窗口 `unbucketedCalls` 不计金额                          | 混合版本成本页不可用于对账            | 批准；RUNBOOK 记录窗口                                      |
| M17 首发配额开关先 false 后打开                              | 避免 M17 上线即拒绝                   | 批准；**M17-B13** RUNBOOK + M17 评审包（B10 只引用 M17 章节） |
| M04/M17 拆分与 AC 归属                                       | 改变模块范围/验收顺序                 | 批准；M17 实际 ID + `split_from`/aliases                    |
