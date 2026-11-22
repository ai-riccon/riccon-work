# ADR-0021：M04 模型路由、降级状态机与平台托管 key 池

Status: accepted
Date: 2026-11-20
Reviewers: `riccon/lead`（范围/合同一致性）、`riccon/verifier`（可测试性、降级与越权）、`riccon/ops`（迁移/回滚/出网/可观测性）、安全评审（SSRF、凭据、denial-of-wallet）
Approved-by: DEC-0024=B（2026-11-20）：不拆，M04 单模块 48h，18 条 AC 全保留；§12 高成本项按 B 答复批准。评审：REV-M04-ARCH-005 pass、REV-M04-OPS-006 pass（2026-11-20）
Review-date: 2026-11-20（REV-M04-ARCH-005 pass、REV-M04-OPS-006 pass；M04 gate 前复核完成）
Supersedes: none
Scope: M04「模型网关纵深」的路由、降级、多 key、受管端点与契约收口；配额与成本见 ADR-0022
关联: DEC-0023（A：范围/AC/OQ 默认值）、DEC-0024（B：单模块 48h、18 条 AC、17 个实施任务）、`docs/PRODUCT.md` §10、ADR-0006（信封加密）、ADR-0007（审计）、ADR-0009（租户隔离）、ADR-0010（Flyway）、ADR-0012（数据模型）、ADR-0014（API/SSE 契约）、ADR-0015（模块边界）、ADR-0018（部门数据范围）、ADR-0019/0020（M10 身份/审计）、`docs/security/THREAT-MODEL.md` §4.4/§7/§9/§10

> 状态口径：`accepted` 仅表示设计评审通过（DEC-0024=B + REV-M04-ARCH-005 + REV-M04-OPS-006）。M04 模块 gate、用户最终验收与生产发布仍需 17 个实施任务、全量合同证据和 release 批准；本 ADR 不对它们作任何声明。

## Context

### M02 基线

- 模型目录是部署级全局表 `model_provider`、`model`（V5），`base_url` 由部署固定；Worker 的 `ModelCatalogJdbc.findActive()` 只取“最早创建的一个 ACTIVE 模型”，`ModelRegistry.resolve(tenantId)` 再取 `provider_credential` 中该 provider 的唯一 ACTIVE 凭据并解密。**没有路由、没有降级、没有多 key、没有配额、没有成本快照。**
- `provider_credential` 的唯一键是 `(tenant_id, provider_id, credential_type)`，结构上只能保存一把 key；`tenant_id='platform'` 表示平台托管凭据。凭据按 ADR-0006 信封加密，明文只在 Worker 内存中出现。
- `model_invocation` 已有 `model_id`、`model_key_snapshot`、`purpose`、`status`、`first_token_ms`、`duration_ms`、`prompt_tokens`、`completion_tokens`、`error_code`、`trace_id`，但**没有 provider/价格/币种/成本/日桶/尝试明细**。
- 现状调用点：`ChatStreamService`（J1）、`AgentRunRequestedHandler`/`AgentRunResumeHandler`（J2）各调用一次 `ModelRegistry.resolve`，再由 `ChatAgentService`/`ResearchAgentService` 用 `ModelRegistry.createClient` 构造 `OpenAiCompatibleChatModel`。`OpenAiCompatibleChatModel` 只在“尚未输出任何增量”时对 429/5xx/超时做同模型重试，一旦发出增量就终止。
- M02 的 `/api/v1/models`、`/api/v1/admin/model-providers`、`/api/v1/admin/models` 在契约中存在但实现 404（F-011）；`e2e/perf/mvc-smoke.js` 不存在（F-007）。

### M04 用户可见结果（DEC-0023=A 已批准）

- 平台管理员按用途（CHAT/REPORT）配置有序模型链、启停模型；保存后下一次请求生效；变更可审计。
- 主模型超时、5xx、429 或凭据失效时，按链切换到下一个模型或同一 provider 的下一把 key；员工侧回答正常完成且**不提示切换**。
- 同一 provider 至少 2 把 key，新增/轮换/停用；单 key 失效不拖垮 provider；保存后不回显明文；key 全生命周期审计。
- 受管端点只来自部署允许列表，UI 不自由输入 `base_url`；端点有“生产可用/仅演示”标签；非 HTTPS、带用户信息/查询串/元数据地址被拒绝。
- 三个模型端点 M04 必做并通过契约测试（F-011）；MCP 目录端点留 M07 并标 deferred。
- 首字后失败保留已生成内容、可重试、不重复；全链失败统一中文文案；成功降级零提示。
- 关闭路由/降级开关后回到基线策略，J1/J2 仍可用，变更写审计；应用可回滚到 M04 前版本（schema 前向兼容，不做 down migration）。

### 硬约束

- `AGENTS.md`：T4；租户行级隔离与越权测试；密钥不得写入仓库/镜像/日志/证据；模型、工具、MCP、权限变更必须留痕；审计写失败即业务失败。
- `data.migrations`：Flyway 只向前；仅 `apps/api` 执行迁移；worker 关闭 Flyway 并校验版本；破坏性变更需 ADR 与回滚脚本。
- `constraints.performance`：假模型基准首字 p95 ≤ 3 s，API p95 ≤ 500 ms，50 并发 Agent run。
- `constraints.security`：外部模型端点只处理合成数据；生产 PII 必须走客户内网网关；路由失败即失败，不静默换到外部端点。

## Decision

> **对 ADR-0012 的限制/覆盖**：ADR-0012 §4 的历史文字“M04 再引入租户级路由”“M04 可写租户级记录”不再适用。M04 明确为**平台全局路由、平台托管 key（tenant_id='platform'）、租户 BYO 后移**；旧 ADR 正文不改，执行以本 ADR 为准。

### 1. 术语与不变量

| 术语      | 含义                                                                                   |
| --------- | -------------------------------------------------------------------------------------- |
| 受管端点  | `model_provider` 一行，`base_url` 由部署配置允许列表约束；M04 不提供自由输入 URL       |
| 模型链    | `model_routing` + `model_routing_entry`，按 `purpose` 有序；全局、非租户级             |
| key 池    | `provider_credential` 中 `tenant_id='platform'` 的 API_KEY 行；平台托管，租户 BYO 后移 |
| 尝试      | 一次上游 HTTP 调用，记录在 `model_invocation_attempt`                                  |
| 降级/切换 | 首字前失败后换同一模型的下一个 key，或换链中的下一个模型                               |
| 首字      | 第一个非空 `TextBlockDeltaEvent` 被发送给客户端；SSE `chat.delta` 的第一帧             |

不变量：

1. **端点不可由请求方或租户指定**；`base_url` 只来自部署允许列表 + 全局目录。
2. **key 明文永不离开进程内存**；任何 API 响应、页面、审计、日志、错误、证据都不含明文或 `Authorization` 头。
3. **首字后不自动重试、不重放、不拼接**；已生成内容保留，客户端以新消息重试。
4. **一次 run 只在创建时消耗一次配额**（ADR-0022）；模型尝试次数不影响配额计数。
5. **配置变更下一次请求生效**；M04 不引入跨请求路由/key/配额缓存。
6. **审计 fail-closed**：配置写入与降级事件写审计失败时，对应写操作失败或模型请求失败。

### 2. 受管端点允许列表

#### 2.1 配置来源与优先级

部署配置 `RICCON_MODEL_ENDPOINTS`（JSON 数组，非密钥）是唯一可新增端点的来源：

```json
[
  {
    "code": "corp-gw",
    "baseUrl": "https://llm-gw.corp.example/v1",
    "environmentLabel": "PRODUCTION"
  },
  {
    "code": "demo-gw",
    "baseUrl": "https://demo.example.test/v1",
    "environmentLabel": "DEMO_ONLY"
  }
]
```

- `code` ∈ `[a-z0-9][a-z0-9-]{1,63}`，全局唯一；`baseUrl` 必须是绝对 URL；`environmentLabel` ∈ `PRODUCTION|DEMO_ONLY`。
- `RICCON_MODEL_DEPLOYMENT_MODE` ∈ `PRODUCTION|DEMO`，默认 `PRODUCTION`（安全默认）。`DEMO` 仅用于本地/CI/演示。
- `RICCON_MODEL_ENDPOINTS` **未配置**时：`DEMO` 模式允许“仅 DB 目录”的遗留兼容并记录启动警告；`PRODUCTION` 模式启动失败（除非显式 `RICCON_MODEL_ALLOW_LEGACY_ENDPOINTS=true` 做书面风险接受，默认 false）。
- 本地/CI 测试 profile 必须显式设置 `RICCON_MODEL_DEPLOYMENT_MODE=DEMO`；不得为了通过测试把生产默认改成 `DEMO`。
- `scripts/real-stack/run.mjs` 必须把 `RICCON_MODEL_DEPLOYMENT_MODE=DEMO`、`RICCON_MODEL_ENDPOINTS`（fake provider 回环 + HTTP）、`RICCON_MODEL_ALLOW_HTTP=true` **同一份 env 对象**注入 API 与 Worker；RUNBOOK preflight 比对两侧 `/actuator/info` 指纹（mode、endpoint code、baseUrl host、currency），不一致即停止发布。
- `/actuator/info` 指纹键（B1a `application.yml`，`management.info.env.enabled=true`）：`info.riccon.model.deploymentMode`、`info.riccon.model.endpointSetHash`（规范化 endpoint JSON 的 SHA-256）、`info.riccon.masterKeyId`、`info.riccon.billing.currency`、`info.riccon.model.routingMode`、`info.riccon.model.failoverEnabled`、`info.riccon.model.quotaEnforcementEnabled`、`info.riccon.quota.tenantDaily`、`info.riccon.quota.tenantMonthly`、`info.riccon.quota.userDaily`、`info.riccon.quota.userMonthly`（后四者为默认配额生效整数，非密钥）；不暴露 endpoint URL、key 或密文。B10 preflight 比对两侧这些键。
- 数据库 `model_provider` 只保存目录元数据（`code`、`name`、`protocol`、`base_url`）。API/Worker 启动时把 DB 行与部署允许列表按 `code` 对齐：
  - 配置有、DB 无：启动警告，不自动建行（部署种子负责建目录）。
  - DB 有、配置无：标记 `allowed=false`，不参与路由选择；**调用时再次拒绝**（双重校验）。
  - `baseUrl` 不一致：DB 行标记 `allowed=false` 并告警；以部署配置为准，不在运行时改写 DB。

#### 2.2 URL 校验（API 与 Worker 共用）

`platform-common` 新增纯 JDK 的 `ManagedEndpointPolicy`，API（保存/目录）与 Worker（调用前）共用同一实现：

| 规则                                                                                                                                                                                                                                                                           | 拒绝原因码（稳定）                                         |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------- |
| 必须是绝对 URL、有 host、无空白                                                                                                                                                                                                                                                | `ENDPOINT_MALFORMED`                                       |
| 必须 HTTPS；仅 `DEMO` + `RICCON_MODEL_ALLOW_HTTP=true` 允许 HTTP                                                                                                                                                                                                               | `ENDPOINT_SCHEME_NOT_ALLOWED`                              |
| 禁止 userinfo（`user:pass@`）                                                                                                                                                                                                                                                  | `ENDPOINT_USERINFO_FORBIDDEN`                              |
| 禁止 query、fragment                                                                                                                                                                                                                                                           | `ENDPOINT_QUERY_FORBIDDEN` / `ENDPOINT_FRAGMENT_FORBIDDEN` |
| 禁止 `localhost`、`127.0.0.0/8`、`::1`（生产）                                                                                                                                                                                                                                 | `ENDPOINT_LOOPBACK_FORBIDDEN`                              |
| 永久禁止云元数据/链路本地地址：`169.254.0.0/16`（含 `169.254.169.254`、`169.254.170.2`）、`100.100.100.200`（阿里云）、`192.0.0.192`（Oracle）、`fd00:ec2::254`、`metadata.google.internal`；先做 IPv4/IPv6 归一化（十进制/十六进制/八进制 IP、IPv4-mapped IPv6、末尾点 FQDN） | `ENDPOINT_METADATA_FORBIDDEN`                              |
| `DEMO_ONLY` 端点在 `PRODUCTION` 模式不可被路由选择                                                                                                                                                                                                                             | `ENDPOINT_DEMO_ONLY`                                       |
| 不在允许列表（按 `code`）                                                                                                                                                                                                                                                      | `ENDPOINT_NOT_ALLOWED`                                     |
| 同一 `code` 重复或同一 `baseUrl` 重复                                                                                                                                                                                                                                          | `ENDPOINT_DUPLICATE`                                       |

- **禁止重定向**：JDK `HttpClient` 固定 `Redirect.NEVER`（已有实现保持），3xx 视为 `INVALID_RESPONSE`，不跟随。
- **DNS/私网**：客户内网网关通常就是私网地址，因此**允许私网 IP**，但启动时解析 host 并拒绝解析到元数据/链路本地地址；不实现“解析后固定 IP”（JDK HttpClient 无自定义解析钩子），由 TLS 主机名校验 + 禁止重定向 + 部署出网策略共同约束。DNS rebinding 的残余风险记入威胁模型 R-23。
- **可注入解析 seam**：`ManagedEndpointPolicy` 注入 `HostResolver` 接口（生产 `InetAddress::getAllByName`，测试固定 map），负向用例覆盖十进制/十六进制/八进制 IP、IPv4-mapped IPv6、末尾点 FQDN、阿里云/Oracle/ECS 元数据地址；测试不依赖外部 DNS。
- **出网允许列表**：生产由 Ops 在 M09 用网络策略限制 Worker 只能访问 `RICCON_MODEL_ENDPOINTS` 的 host/port；M04 在应用层提供配置校验与请求前校验，测试用 Compose 私有网络。运行手册由 Ops 更新（任务 M04-B10）。
- **Actuator 来源控制**：`RICCON_OPS_ALLOWED_CIDRS`（逗号分隔 CIDR）同时限制 `/actuator/prometheus` 与 `/actuator/info`，两者共用同一来源门禁，不新增 `permitAll`；空值 = 仅 loopback，`PRODUCTION` 空值启动 WARN；非允许来源拒绝（API 匿名来源按过滤器链返回 401，Worker 返回 403；匿名 401 沿用 REV-TASK-0103-002 F-003 已接受语义）。M04 应用层只做来源过滤，完整 ingress/网络策略归 M09。B2 实现，B1c 负向测试；`info` 的允许/拒绝来源回归由 TASK-0128 覆盖。
- **测试用 HTTP 开关**：仅 `DEMO` 模式允许 `RICCON_MODEL_ALLOW_HTTP=true`；`PRODUCTION` 模式启动时拒绝该组合。

#### 2.3 “生产/演示”标签的建模

- 标签**不落库**，由部署配置派生；`GET /admin/model-providers` 返回 `environmentLabel`、`allowed`、`baseUrl`、`activeCredentialCount`、`modelCount`。
- `PRODUCTION` 标签是部署对“客户内网/合规网关”的声明；应用不做私网探测（客户可能用公网网关 + mTLS），由 Ops 的出网策略与合同 `integrations.external_apis` 约束保证生产 PII 不出客户内网。
- API 与 Worker 每次解析模型时都检查标签；`PRODUCTION` 模式跳过 `DEMO_ONLY` 端点并在指标中计数，不静默调用。
- 标签不参与租户可见的员工目录：员工只看到可路由模型的目录字段，不看到 `baseUrl`/provider code。

#### 2.4 为什么没有“端点写 API”

- M04 不提供创建新端点的 API（没有 `POST /admin/model-providers`）；新增端点走部署变更 + 重启。`PUT /admin/model-providers/{providerId}` 只允许改展示名。
- 为满足 AC-15 的“通过接口尝试保存端点”，`PUT /api/v1/admin/model-providers/{providerId}` 只允许改 `name`；请求体中若出现 `baseUrl`，先按 §2.2 校验 URL：非 HTTPS、userinfo、query、fragment、元数据/回环地址返回对应的 `ENDPOINT_*` 原因；URL 合法但属于部署管理字段时返回 `ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED`。两种情况都是 400 `RICCON-MODEL-400`，`field` 指向具体字段，且**不发起任何出网请求**。`environmentLabel`/`protocol` 出现即返回不可变原因。
- 路由保存（§4）也会校验每个 `modelId` 对应端点：不在允许列表、演示端点用于生产、协议非 `OPENAI_COMPATIBLE` 都返回 400 + 字段级原因；这是 AC-15 的另一条可测试路径。

### 3. 数据模型（V11，单一迁移）

所有表遵守 ADR-0012：租户表 `tenant_id` 前缀；时间 `DATETIME(6)` UTC；状态 `VARCHAR + CHECK`；主键 `BINARY(16)` UUIDv7。全局目录表是 ADR-0012 已记录的例外，本 ADR **显式扩展**全局表清单：`model_routing`、`model_routing_entry`、`model_gateway_switch`。

#### 3.1 `model_routing` / `model_routing_entry`（全局）

```sql
CREATE TABLE model_routing (
    purpose    VARCHAR(16) NOT NULL COMMENT 'CHAT|REPORT',
    version    INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次保存 +1',
    updated_by BINARY(16) NULL COMMENT '最近修改的平台管理员账号 ID；逻辑引用',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (purpose),
    CONSTRAINT chk_model_routing_purpose CHECK (purpose IN ('CHAT', 'REPORT')),
    CONSTRAINT chk_model_routing_version CHECK (version >= 1)
) ENGINE = InnoDB COMMENT = '模型路由链（全局，M04）';

CREATE TABLE model_routing_entry (
    purpose  VARCHAR(16) NOT NULL COMMENT 'CHAT|REPORT',
    position INT NOT NULL COMMENT '从 1 开始，唯一且连续',
    model_id BINARY(16) NOT NULL COMMENT '模型 ID',
    PRIMARY KEY (purpose, position),
    UNIQUE KEY uk_model_routing_entry_model (purpose, model_id),
    CONSTRAINT fk_model_routing_entry_routing FOREIGN KEY (purpose) REFERENCES model_routing (purpose) ON DELETE CASCADE,
    CONSTRAINT fk_model_routing_entry_model FOREIGN KEY (model_id) REFERENCES model (id),
    CONSTRAINT chk_model_routing_entry_position CHECK (position >= 1)
) ENGINE = InnoDB COMMENT = '模型路由链条目（全局，M04）';
```

- `model` 只做目录引用，不删除（M04 无删除端点）。
- 迁移为 CHAT/REPORT 各插入 1 条 `model_routing`，并按 `model.created_at, model.id` 取最早的 ACTIVE 模型作为 `position=1`，**完全保留 M02 单模型行为**；其他模型由管理员显式加入链。
- 保存时整链替换（`DELETE` + `INSERT`），在单事务内完成；`position` 连续。

#### 3.2 `provider_credential` 扩展（租户表，M04 仅 `platform`）

```sql
-- 第 1 步：加列（provider_credential 是小表；逐条/合并 ALTER，显式 ALGORITHM=INSTANT）
ALTER TABLE provider_credential
    ADD COLUMN label VARCHAR(64) NOT NULL DEFAULT '默认' COMMENT '管理员可读标签，不参与鉴权',
    ADD COLUMN fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'HMAC-SHA-256(pepper, provider_id||0x00||明文 key) 十六进制；仅去重，不可反推',
    ADD COLUMN masked_hint VARCHAR(16) NOT NULL DEFAULT '****' COMMENT '仅末 4 位，如 ****a1b2',
    ADD COLUMN priority INT NOT NULL DEFAULT 100 COMMENT '越小越先使用',
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0 COMMENT '连续可重试失败次数',
    ADD COLUMN cooldown_until DATETIME(6) NULL COMMENT '冷却截止（UTC）',
    ADD COLUMN disabled_at DATETIME(6) NULL COMMENT '管理员停用时间（UTC）',
    ADD COLUMN disabled_by BINARY(16) NULL COMMENT '停用操作者；逻辑引用',
    ADD COLUMN invalidated_at DATETIME(6) NULL COMMENT '401/403 自动失效时间（UTC）',
    ADD COLUMN last_used_at DATETIME(6) NULL COMMENT '最近成功使用时间（UTC）',
    ALGORITHM = INSTANT;

-- 第 2 步：先删除旧 CHECK；此时仍是 ACTIVE|RETIRED
ALTER TABLE provider_credential DROP CHECK chk_provider_credential_status;

-- 第 3 步：数据归一（必须在删旧 CHECK 之后）
UPDATE provider_credential SET status = 'DISABLED' WHERE status = 'RETIRED';
UPDATE provider_credential
   SET label = COALESCE(NULLIF(label, ''), '部署注入'),
       masked_hint = COALESCE(masked_hint, '****'),
       fingerprint = COALESCE(fingerprint, SHA2(CONCAT('legacy:', HEX(id)), 256))
 WHERE fingerprint IS NULL;

-- 第 4 步：唯一键/索引/新 CHECK
ALTER TABLE provider_credential
    ADD UNIQUE KEY uk_provider_credential_fingerprint (tenant_id, provider_id, credential_type, fingerprint),
    ADD KEY idx_provider_credential_pool (tenant_id, provider_id, credential_type, status, priority, created_at),
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED|INVALID',
    ADD CONSTRAINT chk_provider_credential_status CHECK (status IN ('ACTIVE', 'DISABLED', 'INVALID')),
    ADD CONSTRAINT chk_provider_credential_priority CHECK (priority >= 1 AND priority <= 1000),
    ADD CONSTRAINT chk_provider_credential_failures CHECK (consecutive_failures >= 0);

-- 第 5 步：最后删除旧唯一键
ALTER TABLE provider_credential DROP INDEX uk_provider_credential;
```

- **正确顺序不可调换**：①加列 → ②`DROP CHECK` 旧状态约束 → ③`RETIRED→DISABLED` 与 fingerprint/masked_hint 回填 → ④新唯一键/索引/新 CHECK → ⑤删除旧唯一键。旧 CHECK 仍在时执行第 3 步会 `ERROR 3819`。
- `fingerprint` 使用 **HMAC-SHA-256**，pepper 来自独立 Secret `RICCON_CREDENTIAL_FINGERPRINT_PEPPER`（Base64 32 字节，API 与 Worker 都必须注入；`PRODUCTION` 缺失即启动失败）。pepper 不随主密钥轮换，避免去重键变化；数据库备份单独泄露时不构成弱 key 的离线校验 oracle。
- `fingerprint` 可空仅为兼容历史行；**服务层创建/轮换必须写入非空 fingerprint**，重复返回 409。API key 校验最小 16 字符并拒绝全空白/已知占位值；不存任何可逆摘要。
- **legacy fingerprint 语义**：历史行回填的 `SHA2('legacy:'||HEX(id))` 只是历史标识，**不参与新 key 去重**；同一把历史 key 再次录入会得到新的 HMAC fingerprint，需管理员先停用旧行/人工核对。M04 不迁移历史 fingerprint（无明文无法 HMAC）。
- M04 服务层强制 `tenant_id='platform'` 与 `credential_type='API_KEY'`；租户 BYO 不实现（不写 CHECK，避免未来 BYO 需要破坏性迁移）。
- **V11 迁移测试种子分两阶段**：
  1. **V10 阶段**只能造 V10 schema 合法的数据：1 把 `ACTIVE` 与 1 把 `RETIRED`（必须属于不同 provider 或不同租户，旧唯一键禁止同 provider 多行）、1 万行历史 `model_invocation`。
  2. **V11 之后**用测试 helper/直接 SQL 给同一 provider 再插 1 把 `ACTIVE` + 1 把 `INVALID`，覆盖新枚举与多 key；回滚演练从这里开始。
     不得要求在 V10 schema 上插入同 provider 第二把 ACTIVE 或 INVALID（会 1062/3819）。

#### 3.3 `model_invocation` 扩展 + `model_invocation_attempt`

```sql
-- 第 1 步：加列（全部可空/有默认，显式 ALGORITHM=INSTANT；本表不加 CHECK）
ALTER TABLE model_invocation
    ADD COLUMN provider_id BINARY(16) NULL COMMENT '实际成功/最后尝试的 provider 快照',
    ADD COLUMN user_id BINARY(16) NULL COMMENT '发起人快照，便于按用户聚合；来自 agent_run',
    ADD COLUMN cost_day DATE NULL COMMENT 'Asia/Shanghai 自然日，成本日桶',
    ADD COLUMN input_price_per_1m DECIMAL(12, 6) NULL COMMENT '最终成功尝试开始时的输入价格快照',
    ADD COLUMN output_price_per_1m DECIMAL(12, 6) NULL COMMENT '最终成功尝试开始时的输出价格快照',
    ADD COLUMN price_currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'ISO 4217，最终成功尝试时的部署币种快照',
    ADD COLUMN pricing_version VARCHAR(16) NULL COMMENT '计价公式版本，M04 固定 v1',
    ADD COLUMN cost_amount DECIMAL(20, 6) NULL COMMENT '6 位小数成本；PRICED 时非空',
    ADD COLUMN cost_status VARCHAR(16) NOT NULL DEFAULT 'USAGE_UNKNOWN' COMMENT 'PRICED|UNPRICED|USAGE_UNKNOWN（应用层校验，不加 CHECK）',
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 1 COMMENT '本调用尝试次数',
    ALGORITHM = INSTANT;

-- 第 2 步：索引逐条执行（INPLACE, LOCK=NONE；不要与 ADD COLUMN 混在一条 ALTER）
ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_tenant_time (tenant_id, started_at),
    ALGORITHM = INPLACE, LOCK = NONE;
ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_user_time (tenant_id, user_id, started_at),
    ALGORITHM = INPLACE, LOCK = NONE;
ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_cost_day (tenant_id, cost_day),
    ALGORITHM = INPLACE, LOCK = NONE;
ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_provider (provider_id),
    ALGORITHM = INPLACE, LOCK = NONE;

-- 第 3 步：不建 provider FK。provider_id 是调用时快照，允许历史 provider 被停用；
-- 逻辑引用由应用层校验，数据库 FK 在 MySQL 8.4 需 COPY 或 foreign_key_checks=OFF，M04 不承担该窗口。
-- （若未来坚持 FK，必须单独 ADR + 维护窗口 + COPY/foreign_key_checks=OFF，不得声明 INPLACE。）

-- 第 4 步：V11 不回溯历史 cost_day。历史行保持 NULL，成本报告计入 unbucketedCalls；
-- 需要回填时由 B10 的 scripts/ops/backfill-cost-day（CHECK 后的独立 Job）分批执行，M04 gate 不依赖全量回填。

CREATE TABLE model_invocation_attempt (
    tenant_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    invocation_id BINARY(16) NOT NULL COMMENT '所属调用 ID',
    attempt_no    INT NOT NULL COMMENT '从 1 开始',
    model_id      BINARY(16) NOT NULL COMMENT '本次尝试的模型',
    provider_id   BINARY(16) NOT NULL COMMENT '本次尝试的 provider',
    credential_id BINARY(16) NULL COMMENT '本次尝试的 key ID；只用于内部排障，不返回员工',
    status        VARCHAR(16) NOT NULL COMMENT 'STARTED|SUCCEEDED|FAILED|CANCELLED',
    error_code    VARCHAR(64) NULL COMMENT '稳定错误码',
    first_token_ms INT NULL COMMENT '本次尝试首字延迟',
    started_at    DATETIME(6) NOT NULL COMMENT 'UTC',
    finished_at   DATETIME(6) NULL COMMENT 'UTC',
    PRIMARY KEY (tenant_id, invocation_id, attempt_no),
    KEY idx_model_attempt_model (tenant_id, model_id, started_at),
    CONSTRAINT fk_model_attempt_invocation FOREIGN KEY (tenant_id, invocation_id)
        REFERENCES model_invocation (tenant_id, id),
    CONSTRAINT fk_model_attempt_model FOREIGN KEY (model_id) REFERENCES model (id),
    CONSTRAINT fk_model_attempt_provider FOREIGN KEY (provider_id) REFERENCES model_provider (id),
    CONSTRAINT chk_model_attempt_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_model_attempt_no CHECK (attempt_no >= 1)
) ENGINE = InnoDB COMMENT = '模型调用尝试明细（M04）';
```

> **价格快照时点**：上方 `input_price_per_1m`/`output_price_per_1m`/`price_currency` 列注释的“最终成功尝试开始时”是已应用迁移 V11 的历史设计文案，不得据此推断当前实现；价格快照时点以 ADR-0022 §2.1（2026-11-22 修订）的“成功终态读取一次”为准。

- **`model_invocation` 不加 CHECK**：MySQL 8.4 的 `ADD CONSTRAINT ... CHECK` 只能 `ALGORITHM=COPY`（阻塞 DML + 全表重建），大表上不可接受。`cost_status` 用 `NOT NULL DEFAULT`，`cost_status/cost_amount/attempt_count` 的合法值由 Worker 应用层校验 + `IT-COST-101/102`、`IT-MODEL-103` 覆盖；**AC 不要求数据库 CHECK**。新增小表（`quota_*`、`attempt`、`routing`、`switch`）继续用 CHECK，因为它们是空表/小表。
- 不加与既有 `idx_model_invocation_model (tenant_id, model_id, started_at)` 重叠的 `(started_at, tenant_id, model_id)`；平台跨租户按日查询改用 `(tenant_id, cost_day)` + `(tenant_id, started_at)` 组合，避免重复索引。
- `provider_id` 只建索引，**不建数据库 FK**：它是调用时快照，历史 provider 可以停用；应用层在写入前校验 provider 存在。MySQL 8.4 的 ADD FK 需要 COPY 或 `foreign_key_checks=OFF`，M04 不承担该窗口（OPS-M04-019）。
- `cost_day` **V11 不回溯历史**：历史行保持 NULL，成本报告计入 `unbucketedCalls`；需要回填时由 B10 `scripts/ops/backfill-cost-day` 在 CHECK 后的独立 Job 分批执行，M04 gate 不依赖全量回填（OPS-M04-012）。**不执行 `UPDATE cost_status`**（默认值已覆盖）。
- `model_invocation.attempt_count` / `cost_status` 的历史行由默认值覆盖；M04 前调用保持 `USAGE_UNKNOWN`，不猜测价格。
- 保留：`model_invocation` 与 `model_invocation_attempt` 默认保留 ≥180 天；清理顺序先 `attempt` 再 `invocation`（FK），每批 `LIMIT`，由 M09 维护任务执行；`quota_usage` 历史窗口同批清理。容量估算写入 RUNBOOK（调用数 × 平均 attempt 数 × 行宽）。
- `model_invocation.user_id` 用逻辑引用（不建复合外键）以允许 `run_id` 为空的历史行；写入时从 `agent_run` 复制。
- `cost_day` 由 Worker 用 `Asia/Shanghai` 计算；`started_at` 仍是 UTC。范围查询用 `cost_day`，避免 `CONVERT_TZ` 阻止索引。
- `attempt` 表不存 token（只有最终调用有 usage），避免重复计数。
- `model_invocation_attempt.credential_id` 是**逻辑引用**，不建复合外键：attempt 行的 `tenant_id` 是请求租户，而 key 属于 `platform`，复合外键会失败；key 的权威归属在 `provider_credential (tenant_id='platform', id)`，M04 仅内部排障使用，不返回员工。
- `model_invocation` 的 `model_id` 在调用开始时写入首个尝试模型；成功降级后在同一事务内更新为成功模型，`model_key_snapshot` 同步更新。`attempt` 表保留全部尝试。`model.invocation.started` 审计记录首个尝试模型；`model.invocation.completed` 使用最终成功模型，二者通过 `traceId`/`invocationId` 关联。

#### 3.4 `model_gateway_switch`（全局）

```sql
CREATE TABLE model_gateway_switch (
    switch_key   VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    switch_value VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version      INT NOT NULL DEFAULT 1,
    updated_by   BINARY(16) NULL,
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (switch_key),
    CONSTRAINT chk_model_gateway_switch_key CHECK (
        switch_key IN ('routingMode', 'failoverEnabled', 'quotaEnforcementEnabled')
    )
) ENGINE = InnoDB COMMENT = '模型网关运行时开关（M04）';

INSERT INTO model_gateway_switch (switch_key, switch_value) VALUES
  ('routingMode', 'ROUTED'),
  ('failoverEnabled', 'true'),
  ('quotaEnforcementEnabled', 'false'); -- 发版种子保持 false；B10 演练后显式 PUT true，M04 gate 以 true 取证
```

- 取值：`routingMode=ROUTED|BASELINE`；`failoverEnabled=true|false`；`quotaEnforcementEnabled=true|false`。
- **优先级**：环境变量（若设置）> DB > 默认。环境变量存在时，`PUT /admin/model-gateway/switches` 返回 409 `RICCON-MODEL-409`，`reason=ENV_OVERRIDE_ACTIVE`，避免 UI 显示“已关闭”但实际未生效。
- 每次读取开关是一次单行查询；不缓存，满足“下一次请求生效”。

### 4. 路由解析

#### 4.1 解析顺序

1. 读开关（`routingMode`、`failoverEnabled`）。
2. `routingMode=BASELINE`：使用 M02 语义选择“最早创建的 ACTIVE 模型”，链长为 1；**仍必须复用 `ManagedEndpointPolicy`（允许列表、生产/演示标签、URL 校验）与平台 `tenant_id='platform'` key 池过滤**；`failoverEnabled` 仍可让该模型在 key 池内轮换（没有跨模型）。
3. `routingMode=ROUTED`：按 `purpose` 读取 `model_routing_entry`，`ORDER BY position`，联表 `model`/`model_provider`；过滤：
   - `model.status='ACTIVE'` 且 `provider.status='ACTIVE'`；
   - provider 在部署允许列表且 `environmentLabel` 与部署模式兼容；
   - `provider.protocol='OPENAI_COMPATIBLE'`（M04 唯一协议）。
4. 每个模型读取可用 key：`status='ACTIVE'` 且（`cooldown_until IS NULL OR cooldown_until <= now`），`ORDER BY priority, created_at, id`。
5. 全链为空 → `RICCON-MODEL-503`（“模型服务暂不可用，请稍后重试。”），不发起任何出网；记 `model.invocation.failed`（`details.reason=CHAIN_EMPTY`）并告警。

**停用模型仍保留在链中**：`GET /admin/model-routing` 显示 `status=DISABLED` 与位置；运行时跳过。这样 AC-01 的“停用 B 后链不变、下一次请求跳过 B，重新启用即恢复”不需要重新保存链。

#### 4.2 用途、启停与未知模型

- `purpose` 只有 `CHAT|REPORT`；J1 用 CHAT，J2 生成阶段用 REPORT。文件解析/知识库索引不调用模型。
- 保存链时：
  - `modelIds` 1..20 个、去重、每个必须是存在的模型；
  - 每个模型 provider 必须通过端点校验；
  - 允许包含 `DISABLED` 模型（保留位置）；
  - 空数组 → 400 `RICCON-MODEL-400`，`reason=CHAIN_EMPTY`；要停用用途，应停用链中的模型而不是保存空链。
- 未知 `modelId` → 400（字段级），不是 404：请求体引用的对象不参与资源存在性语义。
- 运行时链中模型全部被删/停用 → 同 §4.1 第 5 步。

#### 4.3 变更生效与并发

- 读路径**不缓存**；保存成功后下一次模型请求即读新链（AC-01）。
- `PUT /admin/model-routing/{purpose}` 请求体带 `version`；服务端 `UPDATE model_routing SET version=version+1 ... WHERE purpose=? AND version=?`，0 行 → 409 `RICCON-MODEL-409`，`reason=VERSION_CONFLICT`，返回当前 `version`。
- 整链替换与审计在同一事务；审计失败回滚保存（ADR-0007）。
- 权限：`platform.model.manage`（仅 `PLATFORM_ADMIN`）。租户管理员/员工 403；路径中不接受 `tenantId`。

#### 4.4 审计

- 动作 `model.routing.updated`，目标类型 `MODEL_ROUTING`，目标 ID 为 `purpose`。
- `details` 只允许：`purpose`、`modelIds`（有序 UUID 数组）、`version`、`previousVersion`、`result`。
- 不含 `baseUrl`、provider code、key、提示词。审计写入失败 → 保存失败（500），不返回“已保存”。

### 5. 降级状态机

> 成本快照时点不在此节定义：以 ADR-0022 §2.1（2026-11-22 修订）的“成功终态读取一次”为准；本节的首字前/后语义不改变价格快照时点。

#### 5.1 触发分类

| 上游表现                        | 分类                         | 是否重试同 key        | 是否换 key/模型 | key 状态变更                                               |
| ------------------------------- | ---------------------------- | --------------------- | --------------- | ---------------------------------------------------------- |
| 连接超时/连接拒绝/DNS 失败      | `UNAVAILABLE`                | 否                    | 是              | `consecutive_failures+1`；≥3 次冷却 30 s                   |
| 等待响应头超时（首字前）        | `TIMEOUT`                    | 否                    | 是              | 同上                                                       |
| 流空闲超时（首字前）            | `TIMEOUT`                    | 否                    | 是              | 同上                                                       |
| 429                             | `RATE_LIMITED`               | 是，最多 1 次退避重试 | 是              | 设置 `cooldown_until=now+min(Retry-After,300s)`，默认 60 s |
| 5xx                             | `UNAVAILABLE`                | 是，最多 1 次退避重试 | 是              | `consecutive_failures+1`；≥3 次冷却 30 s                   |
| 401/403                         | `UNAUTHORIZED` / `FORBIDDEN` | 否                    | 是（换 key）    | **立即 `INVALID`** + `model.credential.invalidated` 审计   |
| 无效 JSON/协议结构/错误 payload | `INVALID_RESPONSE`           | 否                    | 是              | `consecutive_failures+1`                                   |
| 凭据解密失败/DEK 缺失           | `CONFIGURATION`              | 否                    | 是              | 不改 key 状态；记错误并告警（可能是主密钥/DEK 问题）       |
| 3xx                             | `INVALID_RESPONSE`           | 否                    | 是              | 不跟随重定向                                               |
| 首字后断流/超时/5xx             | `INTERRUPTED`                | **否**                | **否**          | 不改 key 状态（可能是网络瞬断）                            |

#### 5.2 尝试顺序与预算

```text
for model in chain (position 1..N):
  for key in keys(model) (priority 1..M):
    attempt = 1 HTTP call (inner client maxAttempts=1)
    if first visible delta: stream to end; no failover
    if failure:
      classify; audit model.failover.triggered (if another attempt will run)
      explicit same-key retry only for 429/5xx (counts as a new attempt)
      continue
if no success: RICCON-MODEL-503
```

- **内层客户端固定 `riccon.worker.model.max-attempts=1`**：`OpenAiCompatibleChatModel` 不再自行重试；所有重试/换 key/换模型由 `FailoverChatModel` 拥有，保证 HTTP 调用数 = `model_invocation_attempt` 行数 = 降级审计条数。
- **两套超时 profile**（都以 env 覆盖，写入 RUNBOOK 与 k6 报告）：
  - 生产默认：`connect-timeout=3s`、`first-token-timeout=30s`、`pre-first-token-budget=60s`、`routing.max-attempts=3`、`retry-delays=200ms,500ms,1s`。
  - AC-17 假模型 perf profile：`connect-timeout=500ms`、`first-token-timeout=1000ms`、`pre-first-token-budget=2500ms`、`routing.max-attempts=3`、`retry-delays=50ms,100ms`。这样主模型首字前超时 1s + 备用成功（假 provider 毫秒级）仍可稳定 <3s。
- 每次尝试的响应头/首字超时取 `min(first-token-timeout, 剩余预算)`；连接超时取 `min(connect-timeout, 剩余预算)`；预算耗尽返回 `RICCON-MODEL-504`（员工文案仍为统一失败文案）。
- `failoverEnabled=false`：只允许 1 次尝试（首个可用模型的首个 key），失败即 `RICCON-MODEL-503`；成功降级审计不产生。
- **同 key 重试**只对 429/5xx 且最多 1 次，作为**新的 attempt** 计数，并写 `model.failover.triggered`（`toModelId` 相同、`reason=RETRY`）；退避按序号取并乘 `0.5..1.0` 抖动（`ThreadLocalRandom`）。测试可配置 `retry-delays=0ms`。
- `routing.max-attempts` 默认 3、硬上限 5；链长和 key 数超过上限时按优先级截断，不无限重试。
- 无跨请求熔断器；健康状态只在 DB（§5.5）。

#### 5.3 首字前/首字后语义与 SSE

| 阶段               | 行为                           | SSE                                                                                                                                                                              | 记录                                                                                                                |
| ------------------ | ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| 首字前失败         | 自动换 key/模型                | 尚未发送 `chat.started`/`delta`；切换对客户端不可见                                                                                                                              | `model_invocation_attempt` 一行/次；`model.failover.triggered` 一行/次                                              |
| 首字后失败         | 不重试、不重放；保留已输出内容 | 发送 `riccon.chat.failed.v1`，`code=RICCON-MODEL-502`（断流）或 `RICCON-MODEL-504`（空闲超时），`retryable=true`，`message="回答已中断，已保留已生成内容，可重试。"`，随后流结束 | `model_invocation` FAILED，`first_token_ms` 非空；审计 `model.invocation.failed` 带 `emitted=true`；run 标记 FAILED |
| 全链失败（首字前） | 停止                           | 首字前失败返回 ProblemDetail（或既有 SSE 失败帧），`code=RICCON-MODEL-503`，`message="模型服务暂不可用，请稍后重试。"`                                                           | `model_invocation` FAILED，`attempt_count` 为实际尝试数                                                             |
| 用户取消           | 关闭当前响应体、停止读循环     | `chat.stopped`                                                                                                                                                                   | `model_invocation` CANCELLED；不写失败审计                                                                          |

- **不重复**：首字后失败不创建第二个 assistant 消息；客户端“重试”走既有 M02 重试入口，生成**新消息**，旧消息保留部分内容。新回答不追加到旧消息。
- 员工可见文案固定为 §5.3 的两句；不出现 provider、`modelKey` 之外的内部名称、地址、堆栈。`chat.started` 仍按 M02 契约携带 `modelId`/`modelKey`（`contracts/events/sse/chat-event.v1.schema.json` 已定义），M04 不改变该事件。
- **降级成功零提示**：SSE 不新增“已切换”事件；`chat.started` 使用最终成功模型（见 §7.8 实现注记）。

#### 5.4 取消与资源释放

- 取消/客户端断开时关闭当前 `InputStream`（现有 `OpenAiCompatibleChatModel` 的 `sink.onCancel/onDispose` 保持），停止退避循环。
- 取消不触发降级；不写 `model.failover.triggered`。
- 取消不退还配额（ADR-0022）。

#### 5.5 是否使用熔断/健康状态

**不使用进程内熔断器。** 使用 DB 中的 key 级健康状态：

- `cooldown_until`、`consecutive_failures` 跨 Worker 实例共享，重启不丢失，可审计、可回滚。
- 进程内熔断器是单实例状态，滚动发布时行为不一致，且难以做确定性测试；M04 拒绝。
- 模型级健康不单独存储：模型的可用性由“是否还有可用 key”派生；全部 key 不可用即跳过该模型。
- **跨租户隔离**：key 健康是部署级（平台托管池），一个租户的流量可能让 key 进入冷却，影响其他租户的请求。这是平台托管池的固有属性；通过租户配额、Worker 并发上限与 provider 侧限流约束，不按租户拆分健康状态（拆分会让“同一 provider 故障”被重复探测）。残余风险 R-21。
- **失效与回滚**：401/403 自动 `INVALID`，需管理员 `enable` 恢复（审计 `model.credential.recovered`）；429/5xx 冷却自动过期；没有进程内熔断器需要回滚，开关 `failoverEnabled` 可整体止血。

#### 5.6 降级风暴控制

- 尝试上限 + 总预算 + 每 key 冷却 + 退避抖动四项共同限流。
- 指标 `riccon_model_failover_total{fromModel,toModel,reason}` 突增时告警；同一 trace 的尝试数上限 5（默认 3）。
- 当链中所有模型都不可用时，**不自动切换到链外模型**（防止降级到未授权端点）；返回 503。
- 开关 `failoverEnabled=false` 可在故障风暴中快速止血，下一次请求生效（§3.4）。

### 6. 多 key 平台托管池

#### 6.1 归属与跨租户

- M04 的模型凭据**只能是平台托管**：`provider_credential.tenant_id='platform'`，服务层拒绝其他租户值；API 不提供租户 BYO 端点。Worker `ModelRepository.findActiveCredential` **删除“先查当前租户、再回退 platform”的旧逻辑**，只查 `platform`；租户级遗留凭据被忽略并有负向测试（`IT-MODEL-105`）。
- 租户请求使用平台 key 时，按请求租户记账/审计，但 key 本身不按租户隔离。**绝对禁止**把 A 租户的凭据用于 B 租户；M04 没有租户凭据，未来 BYO 必须另立 ADR 并做租户键强制。
- `provider_credential` 仍是租户表，平台行受 `(tenant_id, id)` 主键与审计约束；查询必须带 `tenant_id='platform'`，不得跨租户扫描。

#### 6.2 选择策略

- 过滤：`status='ACTIVE'`、`cooldown_until IS NULL OR <= now`。
- 排序：`priority ASC, created_at ASC, id ASC`（稳定、可预测、无随机）。
- 不做加权随机：M04 目标是可测试的故障切换，不是负载均衡。
- 上次成功的 key 不粘滞：每次请求从池头开始；避免“热点 key”与状态复杂化。

#### 6.3 生命周期

| 操作     | 端点                                                   | 语义                                                                                                                | 审计                                                                                      |
| -------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| 新增     | `POST /admin/model-providers/{providerId}/credentials` | 校验 provider 在允许列表；`apiKey` 只出现在请求体；生成 DEK（如无）并信封加密；`fingerprint` 去重；`status=ACTIVE`  | `model.credential.created`                                                                |
| 轮换     | `POST .../credentials/{credentialId}/rotate`           | 单事务：插入新 key（继承 priority/label 或使用请求值），旧 key `DISABLED`+`disabled_at`；旧 key 的在途流不打断      | `model.credential.rotated`（含新旧 credentialId）                                         |
| 停用     | `POST .../credentials/{credentialId}/disable`          | `ACTIVE/INVALID → DISABLED`；新请求不再使用；在途继续                                                               | `model.credential.disabled`                                                               |
| 恢复     | `POST .../credentials/{credentialId}/enable`           | `DISABLED/INVALID → ACTIVE`；清空 `consecutive_failures`/`cooldown_until`/`invalidated_at`；用于 401 误判或密钥续期 | `model.credential.enabled`（来自 DISABLED）/ `model.credential.recovered`（来自 INVALID） |
| 自动失效 | Worker 收到 401/403                                    | `ACTIVE → INVALID`；不清空密文；不自动恢复                                                                          | `model.credential.invalidated`（actor=SYSTEM）                                            |
| 冷却     | Worker 收到 429/连续 5xx                               | 只写 `cooldown_until`/`consecutive_failures`，不改 `status`                                                         | 不逐次审计，记指标                                                                        |

- 停用/恢复/轮换幂等：状态机条件更新，重复操作返回 200 与当前状态（幂等），不重复写审计？重复写会造成噪声；用条件更新，0 行时返回 200 但 `changed=false`，不写审计。契约返回 `changed` 字段。
- 轮换时若新 key 与旧 key 明文相同（fingerprint 相同）→ 409 `DUPLICATE_CREDENTIAL`。
- 禁用最后一个 ACTIVE key 是允许的（管理员意图可能是下线 provider）；运行时会跳到下一个模型或 503。

#### 6.4 在途请求

- 请求开始时把 `credentialId` 与解密后的 key 固定在该次尝试的上下文中；管理员停用/轮换不取消已经建立的 HTTP 连接。
- 在途流可能因上游主动断开而失败；按 §5.3 首字后语义保留内容、可重试、不重复。
- UI 在“停用/轮换”确认框固定说明：“停用后新请求不再使用该 key；正在进行的回答可能中断，可重试，已生成内容不会重复。”

#### 6.5 加密、pepper 与主密钥轮换

- 沿用 ADR-0006：DEK 按 `tenant_id='platform' + purpose='model-credential'`，AES-256-GCM，AAD=`tenantId|purpose|keyId|schemaVersion`；密文格式 `enc:v1:<keyId>:<Base64(nonce||ciphertext||tag)>`。
- **API 首次具备加密能力**：M04 在 `platform-common` 提取 JDK-only `MasterKeyRing`（wrap/unwrap，行为与 Worker 现有 `MasterKeyProvider` 一致），Worker 保留 `MasterKeyProvider` 委托同一实现，API 新增 `MasterKeyProvider` 组件与启动校验（长度、canary、已知测试密钥拒绝）。API 新增/轮换 key 时在自己的事务内创建 DEK（若不存在）、加密 key、写 `tenant_data_key` 与 `provider_credential`。
- API 与 Worker 都必须注入 `RICCON_MASTER_KEY`、可选 `RICCON_MASTER_KEY_PREVIOUS`、`RICCON_MASTER_KEY_ID`；API 从 M04 起新增启动依赖。`apps/api/src/test/resources/application-test.yml` 必须加测试主密钥占位；`scripts/real-stack/run.mjs` 给 API 与 Worker 注入同一 masterKey；RUNBOOK“本地运行”给出 `export RICCON_MASTER_KEY=<injected-at-deploy>`。
- **fingerprint pepper**：独立 Secret `RICCON_CREDENTIAL_FINGERPRINT_PEPPER`（Base64 32B）；API 与 Worker 在 `PRODUCTION` 缺失即启动失败（统一语义）。pepper 不随主密钥轮换；丢失后无法新建/去重 key，但恢复 pepper 后历史密文解密不受影响；禁止自动生成新 pepper。
- 主密钥轮换仍只 rewrap DEK（ADR-0006），不重加密凭据；M04 必须交付 `scripts/ops/rewrap-dek`（列出 `master_key_id` 分布 → 逐条 unwrap/rewrap → 校验）与 RUNBOOK 四步：新密钥作为 current + 旧密钥 previous → 滚动重启 API/Worker → rewrap 全部 `tenant_data_key` → 移除 previous。没有工具/演练时不得声称“主密钥轮换已完成”。
- **备份生命周期**：数据库备份必须记录 `master_key_id` 与密钥版本；密钥本体留在 Secret store，禁止入备份包；密钥保留期 ≥ 最长备份保留期（默认 180 天）。B10 提供 `scripts/ops/restore-drill`（恢复到一次性容器 → 校验表数/触发器/一行 credential 可解密/J1 冒烟）与 RPO/RTO：**预览环境默认 RPO ≤24h、RTO ≤4h**；客户生产 SOW 可覆盖该目标，RUNBOOK 必须写明实际值与恢复判据。
- **明文缓存边界：不缓存。** 每次尝试按需解密；`SecretValue` 只在构造 `Authorization` 头时 `reveal()`，不进入静态字段、MDC、日志、异常消息、审计或证据。解密成本（AES-GCM 微秒级）远低于模型调用。

#### 6.6 脱敏展示

- API/UI 只返回 `id`、`label`、`maskedHint`（末 4 位）、`status`、`priority`、`version`、时间字段。
- 审计 `details` 只允许 `providerId`、`credentialId`、`maskedHint`、`status`、`version`、`reason`；禁止 `apiKey`、`ciphertext`、`keyId`？`keyId` 是 DEK 标识，可记录但无必要；M04 不记录。
- 日志只允许 `providerCode`、`credentialId`（内部）、`errorCode`；禁止 `Authorization`、明文、密文、nonce。
- 错误响应不区分“key 不存在/已停用/解密失败”；管理端用稳定 reason 码，员工端统一 503。

#### 6.7 并发与幂等

- 新增 key 的 fingerprint 唯一键是最终防线；并发重复创建只有一个成功，另一个 409。
- 轮换用单事务 + 条件更新旧 key 状态；并发轮换只有一个成功，另一个 409（`CREDENTIAL_STATE_CONFLICT`）。
- 状态变更带 `version` 条件更新；响应返回新 `version`。
- 创建/轮换请求支持 `Idempotency-Key`（可选）：同主体 + 同 key 重复请求返回既有结果，不重复写审计。实现为 `provider_credential` 上的 `idempotency_key`？为避免新列，M04 **不承诺**凭据写接口的幂等键；重复请求由 fingerprint 唯一键与状态机兜底。契约中明确该端点不接受 `Idempotency-Key` 语义（或接受但不承诺）。见 §7.3。

### 7. 契约

#### 7.1 端点

| 方法  | 路径                                                     | 权限                    | 说明                                                       |
| ----- | -------------------------------------------------------- | ----------------------- | ---------------------------------------------------------- |
| GET   | `/api/v1/models`                                         | `model.read`            | 员工模型目录；过滤不可路由/演示端点；不含 provider/baseUrl |
| GET   | `/api/v1/admin/model-providers`                          | `platform.model.read`   | 端点目录 + 标签 + 允许状态 + key 数量                      |
| PUT   | `/api/v1/admin/model-providers/{providerId}`             | `platform.model.manage` | 只改 `name`；`baseUrl` 等只读字段出现即 400                |
| GET   | `/api/v1/admin/models`                                   | `platform.model.read`   | 平台模型目录（含 provider、价格、定价状态）                |
| PATCH | `/api/v1/admin/models/{modelId}`                         | `platform.model.manage` | 启停模型；审计 `model.updated`                             |
| GET   | `/api/v1/admin/model-routing`                            | `platform.model.read`   | 返回 CHAT/REPORT 两条链                                    |
| PUT   | `/api/v1/admin/model-routing/{purpose}`                  | `platform.model.manage` | 整链保存，`version` 乐观锁                                 |
| GET   | `/api/v1/admin/model-providers/{providerId}/credentials` | `platform.model.read`   | 掩码列表                                                   |
| POST  | `/api/v1/admin/model-providers/{providerId}/credentials` | `platform.model.manage` | 新增 key（明文只进不出）                                   |
| POST  | `.../credentials/{credentialId}/rotate`                  | `platform.model.manage` | 新增 + 停用旧 key                                          |
| POST  | `.../credentials/{credentialId}/disable`                 | `platform.model.manage` | 停用                                                       |
| POST  | `.../credentials/{credentialId}/enable`                  | `platform.model.manage` | 恢复                                                       |
| GET   | `/api/v1/admin/model-gateway/switches`                   | `platform.model.read`   | 开关 + 环境覆盖                                            |
| PUT   | `/api/v1/admin/model-gateway/switches`                   | `platform.model.manage` | 开关变更，`version` 乐观锁                                 |

配额/成本端点见 ADR-0022 §3。

#### 7.2 Schema 摘要（完整定义在 `contracts/openapi.yaml`）

- `ModelProvider` 增加：`baseUrl`、`environmentLabel`、`allowed`、`activeCredentialCount`、`modelCount`。
- `AdminModel`：`id`、`modelKey`、`displayName`、`providerId`、`providerCode`、`environmentLabel`、`contextWindow`、`maxOutputTokens`、`capabilities`、`inputPricePer1M`、`outputPricePer1M`、`pricingStatus`（`PRICED|UNPRICED`）、`status`。
- `ModelRouting`：`purpose`、`version`、`entries[]`（`position`、`modelId`、`displayName`、`status`、`environmentLabel`）、`updatedAt`、`updatedBy`。
- `ModelRoutingUpdateRequest`：`version`、`modelIds[]`（1..20，去重）。
- `ModelCredential`：`id`、`providerId`、`label`、`maskedHint`、`status`（`ACTIVE|DISABLED|INVALID`）、`priority`、`version`、`createdAt`、`updatedAt`、`rotatedAt`、`lastUsedAt`、`cooldownUntil`、`consecutiveFailures`。
- `ModelCredentialCreateRequest`：`label`、`apiKey`（`writeOnly`，16..512）、`priority`；`RotateRequest`：`apiKey`、`label?`。
- `ModelGatewaySwitches`：`routingMode`、`failoverEnabled`、`quotaEnforcementEnabled`、`version`、`envOverride`、`updatedAt`、`updatedBy`。
- 错误响应用 `ProblemDetail`；配额超限用专用 `QuotaExceededProblem`（ADR-0022）。

#### 7.3 错误码与状态

| HTTP    | code                       | 场景                                                              |
| ------- | -------------------------- | ----------------------------------------------------------------- |
| 400     | `RICCON-MODEL-400`         | 端点不允许/演示端点用于生产/只读字段被写/链为空/未知模型/字段非法 |
| 403     | `RICCON-AUTH-403`          | 非平台管理员调用管理端点                                          |
| 404     | `RICCON-TENANT-404`        | provider/credential/model 不存在；跨租户统一 404                  |
| 409     | `RICCON-MODEL-409`         | 路由版本冲突、凭据重复、凭据状态冲突、环境覆盖生效时改开关        |
| 429     | `RICCON-QUOTA-429`         | 配额超限（ADR-0022；员工可区分）                                  |
| 503     | `RICCON-MODEL-503`         | 全链不可用（员工文案统一）                                        |
| 502/504 | `RICCON-MODEL-502` / `504` | 首字后断流/空闲超时                                               |

- `PUT /admin/model-providers/{providerId}` 的只读字段错误返回 400 且 `field` 指向 `baseUrl`/`environmentLabel`/`protocol`。
- 凭据写接口不承诺 `Idempotency-Key`：契约注明“重复请求由 fingerprint/状态机兜底，不保证同键返回同响应”。M04 以安全与简单为先，避免新增幂等表。

#### 7.4 审计动作、目标类型与 details 白名单

| 动作                                     | 目标类型           | details 允许键                                                                   |
| ---------------------------------------- | ------------------ | -------------------------------------------------------------------------------- |
| `model.routing.updated`                  | `MODEL_ROUTING`    | `purpose`、`modelIds`、`version`、`previousVersion`、`result`                    |
| `model.updated`                          | `MODEL`            | `modelId`、`status`、`providerId`                                                |
| `model.failover.triggered`               | `MODEL`            | `purpose`、`fromModelId`、`toModelId`、`reason`、`attempt`（不含 credential ID） |
| `model.credential.created`               | `MODEL_CREDENTIAL` | `providerId`、`credentialId`、`maskedHint`、`status`                             |
| `model.credential.rotated`               | `MODEL_CREDENTIAL` | `providerId`、`credentialId`、`previousCredentialId`、`maskedHint`、`status`     |
| `model.credential.disabled`              | `MODEL_CREDENTIAL` | `providerId`、`credentialId`、`maskedHint`、`status`、`reason`                   |
| `model.credential.enabled`               | `MODEL_CREDENTIAL` | 同上                                                                             |
| `model.credential.invalidated`           | `MODEL_CREDENTIAL` | `providerId`、`credentialId`、`maskedHint`、`reason`                             |
| `model.credential.recovered`             | `MODEL_CREDENTIAL` | 同上                                                                             |
| `model.gateway.switch.changed`           | `MODEL`            | `switch`、`value`、`previousValue`、`source`                                     |
| `quota.limit.changed` / `quota.exceeded` | `QUOTA`            | 见 ADR-0022                                                                      |

- `AuditDetailsPolicy` 必须对新动作 fail-closed；M10 的审计查询/导出读取同一白名单。禁止 `apiKey`、`ciphertext`、`authorization`、`baseUrl`、`prompt`、`content`、`token` 子串（`promptTokens`/`completionTokens` 是既有允许键）。
- **数组与 null 支持**：`AuditDetailsPolicy` 扩展为受限数组（最多 20 个 UUID 字符串、总长度 ≤2 KiB）与动作级 nullable 键；`model.routing.updated.modelIds` 使用数组，`quota.limit.changed.previousLimit` 首次设置允许 JSON null。不得放宽为任意 JSON；非法类型继续 fail-closed，并补 `IT-MODEL-102`/`IT-QUOTA-101` 负向断言。
- **`emitted`**：`model.invocation.failed` 的 Worker 白名单增加 `emitted`（Boolean），用于 AC-04 区分首字后失败；API/Worker 两份 `AuditDetailsPolicy` 同步。
- **可见性/actor**：`model.credential.*` 由 API 或 Worker 以 `tenant_id=platform`、`actor_type=SYSTEM`（自动失效）写入，只有平台管理员可见；Worker 增加显式 `appendSystem(TenantId platform, ...)` 内部方法（或等价 seam），不得沿用调用方租户。租户侧 `model.failover.triggered` 只含模型 ID/展示名与原因，不含 key/credential/provider 地址。租户管理员看不到平台 key 生命周期事件（M03 F-009 的既有可见性模型，M04 不改变）。
- **查询**：AC-03 的“按模型与时间筛选降级”复用 M10 审计查询的 `targetType=MODEL` + `targetId=<fromModelId>` + `occurredFrom/To` + `action=model.failover.triggered`；M04 不新增模型专用查询端点或索引。B9a 在审计页增加 `model.failover.triggered` 的 **7 天默认预设**（M10 端点默认仍是 24h，避免改变通用契约），B11a 断言预设与过滤。
- 跨租户管理事件沿用 M02/M03 口径：`tenant_id=platform`，目标租户不写 `details.targetTenantId`（模型目录是平台全局对象，不属于某个目标租户）。租户管理员在 M10 查询中看不到 platform 事件（M03 F-009 residual）。

#### 7.5 员工 catalog 与 admin catalog 差异

| 字段                            | 员工 `/models`                   | 平台 `/admin/models` | 平台 `/admin/model-providers` |
| ------------------------------- | -------------------------------- | -------------------- | ----------------------------- |
| `id` / `displayName` / `status` | 是                               | 是                   | —                             |
| `modelKey`                      | 是（M02 契约已有）               | 是                   | —                             |
| `providerId` / `providerCode`   | 否                               | 是                   | 是                            |
| `baseUrl`                       | 否                               | 否                   | 是                            |
| `environmentLabel`              | 否                               | 是                   | 是                            |
| 价格 / `pricingStatus`          | 否（员工 schema 不定义价格字段） | 是                   | 否                            |
| key 掩码/数量                   | 否                               | 否                   | 是                            |

- 员工 `/models` 使用独立 `EmployeeModel` schema，**不包含** `inputPricePer1M`/`outputPricePer1M`，避免“实现正确但 schema 允许泄露”；价格只在 `AdminModel` 返回。
- 员工目录只返回“当前部署模式下可路由”的模型（provider 允许 + 非演示端点 + ACTIVE）；否则员工会看到永远不可用的模型。

#### 7.6 F-011 与 MCP deferred

- 三个模型端点在 M04 实现并通过契约测试（`IT-MODEL-108a`）：`GET /api/v1/models`、`GET /api/v1/admin/model-providers`、`GET /api/v1/admin/models`。
- `/api/v1/admin/mcp-servers`、`/api/v1/admin/mcp-tools` 保留路径，operation 标 `x-riccon-deferred: M07`，实现可继续 404；契约不再暗示 M04 交付。
- `x-riccon-deferred` 移除 `model-routing-quota`；`workspace-archive` 改为 `M09`；新增 `byo-model-credentials`（M10+）、`model-price-editing`（M09）、`multi-currency`（unplanned）、`billing-reconciliation`（unplanned）。

#### 7.7 版本与兼容

- `contracts/openapi.yaml` 保持 `1.3.0-m04` 的**全量设计**（含配额/成本端点）。DEC-0024=B 已撤销拆分，M04 单模块一次交付，不存在补丁级契约版本或 deferred 分段。
- 同一 `/api/v1` 内只做向后兼容增量：新增端点、可选字段、枚举值；员工 `/models` 使用 `EmployeeModel`（不含价格），平台使用 `AdminModel`。
- `ModelProvider` 新增字段全部可选；`Model` 保留既有字段（价格字段按 §7.5 处理）。
- 错误码只新增，不复用旧码语义；`RICCON-MODEL-401/403/429` 是 Worker 内部上游错误，不直接暴露给员工。
- 契约 writer：`M04-B2` 独占 M04 的 `contracts/openapi.yaml` 与 `contracts/README.md`，并在同一任务交付 `IT-MODEL-108a`（模型/路由/凭据/开关）与 `IT-MODEL-108b`（配额/成本）两组契约测试；其他任务只读或提变更请求。

#### 7.8 口径澄清（需 Verifier/Product 在评审中确认）

1. **“内部模型名”**：AC-05 要求“响应、页面与审计不出现 provider 地址、key、堆栈或内部模型名”。M02 的 `chat.started` 事件与运行详情按既有契约返回 `modelKey`（上游模型标识），AC-03 又要求“运行详情中的模型标识按 M02 既有要求保留”。M04 的解释是：**员工可见的模型标识仅限 M02 已发布的 `modelId`/`modelKey`**；`provider code`、`baseUrl`、key、内部别名不得出现。若 Verifier 认为 `modelKey` 属于“内部模型名”，需要 Product 明确；M04 不擅自删除 M02 字段。
2. **“成功降级零提示”**：SSE `chat.started` 使用最终成功模型；不为切换新增事件。若在首字前切换，客户端只看到一次 `started`。
3. **AC-15 的“保存端点”**：M04 没有端点创建 API，使用 §2.4 的只读字段 400 + 路由保存校验 + 启动校验三条可测试路径。

### 8. 交付计划（单模块任务图，供 Lead 创建）

#### 8.1 总览（17 个实施任务，全部属于 M04）

| 任务     | 建议 owner                   | 交付物（单一可验收结果）                                                                                      | 依赖                             | 风险               |
| -------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------- | ------------------ |
| M04-B1a  | `riccon/builder-core`        | V11 迁移、权限/审计词汇、错误码、RoleDefinitions、schema 11、迁移测试                                         | —                                | 高（迁移）         |
| M04-B1b  | `riccon/builder-crypto`      | 共享 `MasterKeyRing`、API 加密能力、Worker 委托、启动校验、往返测试                                           | —                                | 高（加密）         |
| M04-B1c  | `riccon/ops`                 | Micrometer/Prometheus 依赖、7 个路由/降级/key 指标约定、`metrics-check`、默认拒绝回归测试                     | —                                | 中（观测）         |
| M04-B2   | `riccon/builder-route`       | 端点允许列表 + 三模型端点 + 模型启停 + Actuator 暴露 + 契约唯一 writer（含配额/成本契约）                     | B1a、B1c                         | 高（SSRF/契约）    |
| M04-B3   | `riccon/builder-route`       | 路由配置 API + 乐观锁 + 审计 + 生效读模型                                                                     | B1a、B2                          | 高（权限/AI 路由） |
| M04-B4   | `riccon/builder-failover`    | Worker 路由/降级/尝试记录/首字后语义/开关/移除 BYO 回退 + 注册 7 个指标                                       | B1a、B3                          | 高（AI 调用/并发） |
| M04-B5   | `riccon/builder-key`         | key 池管理 API + 掩码 + 轮换/停用/恢复 + 审计                                                                 | B1a、B1b、B2                     | 高（凭据）         |
| M04-B6   | `riccon/builder-quota`       | 配额表/原子准入/读写端点/审计/并发测试 + 注册 2 个配额指标                                                    | B1a                              | 高（并发/配额）    |
| M04-B7   | `riccon/builder-cost`        | 成本快照写入 + 平台/租户聚合/明细 API + 边界测试 + 成本查询指标                                               | B1a、B4                          | 高（跨租户/金额）  |
| M04-B8   | `riccon/builder-gateway-e2e` | fake provider 场景 + real-stack 种子/run.mjs + `e2e/perf/mvc-smoke.js` + stub                                 | B1a                              | 中                 |
| M04-B9a  | `riccon/builder-web`         | 路由/key 两页 + 路由/菜单 + Web API client + audit target/7d 预设                                             | B2、B3、B5                       | 中                 |
| M04-B9b  | `riccon/builder-web`         | 配额/成本两页 + 菜单增量 + 四页联动                                                                           | B6、B7、B2、B9a                  | 中                 |
| M04-B10  | `riccon/ops`                 | 单模块 RUNBOOK（发布/回滚/quota 开关演练/漂移检查/成本对账）、脚本、CI perf job                               | B1a、B1b、B2、B3、B4、B5、B6、B7 | 中                 |
| M04-B11a | `riccon/builder-gateway-e2e` | 路由/key E2E + J1/J2/M03/M10 回归 + 审计 7 天 + `@a11y`                                                       | B4、B8、B9a                      | 中                 |
| M04-B11b | `riccon/builder-gateway-e2e` | 配额/成本 E2E + 四页 `@a11y` + 配额争用/成本边界断言                                                          | B6、B7、B9b                      | 中                 |
| M04-V1   | `riccon/verifier`            | AC-01、AC-03..16（15 条）+ AC-02 模型/key/开关段证据、路由/key 安全、`IT-MODEL-101..109`、两页/四页 a11y 证据 | B4、B5、B6、B7、B9a、B11a、B11b  | 高                 |
| M04-V2   | `riccon/verifier`            | AC-02 整条 + AC-17/18、配额/成本安全与并发、perf、迁移/回滚/备份、7+2 指标、最终提交全量独立复验              | B6、B7、B10、B11b、B8、V1        | 高                 |

共 **17 个实施任务**，全部属于 M04：B1a/B1b/B1c/B2/B3/B4/B5/B6/B7/B8/B9a/B9b/B10/B11a/B11b/V1/V2。B1a 的 V11 同时建路由/key/配额/成本/尝试明细表；B2 是唯一契约 writer（`IT-MODEL-108a` 与 `IT-MODEL-108b` 同任务交付）；B10 是唯一发布/回滚/演练任务（吸收 quota 开关 false→true 与成本对账窗口）。不再有独立契约分段任务，也不再有独立配额发布任务。

#### 8.2 依赖图

```text
Lane A（契约/路由/Worker）:   B1a -> B2 -> B3 -> B4 -> B7 -> B9b -> B11b -> V1 -> V2
Lane B（加密/key/Web）:       B1b -> B5 -> B9a -> B11a -> V1
Lane C（观测/配额/资产/ops）: B1c -> B2（暴露）
                              B6  -> B9b
                              B8  -> B11a / V2
                              B10 -> V2（B10 另依赖 B3/B4/B5/B6/B7 完成后的路由/降级/key 池/配额）
```

- 关键路径（3 builder）：B1a 2h → B2 4h → B3 2h → B4 5h → B7 3h → B9b 3h → B11b 2h → V1 3h → V2 4h ≈ **28h**；B10（≈6h）、B11a 与关键路径并行。
- B1b 与 B1a 可并行（文件不重叠）；B5 依赖两者；B2 依赖 B1a（契约/端点配置）与 B1c（指标 registry 约定先于 Actuator 暴露验收），不需要 crypto。
- B1c 可并行，但必须在 B4/B6/B7 写指标前完成 registry 依赖与指标约定；Actuator 暴露由 B2 实现，B10 消费指标清单。
- B5 与 B3 可并行（不同 API 子包），但都依赖 B2；B5 不修改 Worker（自动失效逻辑归 B4）。
- B6 只依赖 B1a，与 B2/B3 并行（不同 API 子包），不改变路由关键路径。
- B7 依赖 B4（同一 Worker 模型包，避免并行写冲突）；这是 M04 内部唯一的 Worker 串行点。
- **B10 的隐藏依赖已显式化**：B10 依赖 B3（路由 API）、B4（Worker 降级/开关）、B5（key 池）、B6（配额）与 B7（成本）；发布/回滚/quota false→true/漂移演练必须等这些实现完成。B7 在 16h 完成、V2 在 24h 开始，B10 只有约 8h 窗口，估算 ≈6h（§8.4）。
- **配置唯一 writer**：`application.yml` 由 B1a 一次性加齐 M04 配置键；B1b/B1c/B2 不再修改 yml；B4/B7 都改 `worker/model/**`（B7 依赖 B4）；B9a/B9b 都改 `routes.ts`/`access.ts`（B9b 依赖 B9a）。这三组不得并行派单。
- **契约唯一 writer**：B2 独占 `contracts/openapi.yaml` 与 `contracts/README.md`（含配额/成本端点），并同时交付 `IT-MODEL-108a/108b`；其他任务只读或提变更请求。B9b 依赖 B2 的 schema 定稿。
- **测试资产**：B8 独占 `scripts/real-stack/**`、`e2e/perf/**`、`e2e/stub/server.mjs`；B11a/B11b 只写 `e2e/tests|real/tests` 规格，不修改 stub。`.github/workflows/ci.yml` 由 B10 独占；B8 只保证脚本本地/CI 可运行。`scripts/ops` 按文件前缀分工：B1c 只写 `metrics-check*`，B10 写 `seed-*/update-*/rewrap-*/restore-*/backfill-*/quota-*/cost-*`。
- **Ops 串行**：B1c 与 B10 的 owner 都是 `riccon/ops`，同一 owner 同时只能有一个 in_progress；B10 是唯一 RUNBOOK 与 `docker-compose.test.yml` writer，不存在第二个 Ops 任务并行写。
- **同 owner 串行**：B2/B3 同 `builder-route`、B9a/B9b 同 `builder-web`、B11a/B11b 同 `builder-gateway-e2e`、V1/V2 同 `verifier`；依赖关系已按串行排布，不需要额外派单约束。

#### 8.3 写范围与验收（≤6 条/任务）

| 任务 | `write_scope`（目录写 `dir/**`）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | 验收（≤6）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B1a  | `apps/api/src/main/resources/db/migration/V11__model_gateway_depth.sql`、`apps/api/src/test/java/com/riccon/api/migration/**`、`platform/platform-domain/src/main/java/com/riccon/platform/domain/audit/**`、`platform/platform-domain/src/main/java/com/riccon/platform/domain/identity/PermissionCodes.java`、`apps/api/src/main/java/com/riccon/api/error/ErrorCodes.java`、`apps/api/src/main/java/com/riccon/api/audit/domain/AuditDetailsPolicy.java`、`apps/worker/src/main/java/com/riccon/worker/audit/domain/AuditDetailsPolicy.java`、`apps/worker/src/main/java/com/riccon/worker/audit/domain/WorkerAuditActions.java`、`apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java`、`apps/worker/src/main/java/com/riccon/worker/persistence/SchemaVersionChecker.java`、`apps/api/src/main/resources/application.yml`、`apps/worker/src/main/resources/application.yml`、`apps/api/src/test/resources/application-test.yml`、`apps/worker/src/test/resources/**`、`apps/worker/src/test/java/com/riccon/worker/WorkerIntegrationTestBase.java`（M04 全部配置键/测试 profile 占位） | V10 种子（1 ACTIVE + 1 RETIRED 跨 provider + 1 万历史调用）后 V11 通过；V11 后 helper 造 2 ACTIVE + 1 INVALID 并完成多 key 回滚（每组 1 行；有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED；不写死总行数）；`information_schema` 无 `fk_model_invocation_provider`、`model_invocation` 无新增 CHECK、加列 INSTANT/索引 INPLACE；权限回填 + M04 权限码/审计动作/目标类型/details 数组+nullable 白名单预置；两应用 M04 配置键占位（crypto/endpoint/management/routing/currency + 默认配额 `RICCON_QUOTA_DEFAULT_TENANT_DAILY`/`RICCON_QUOTA_DEFAULT_TENANT_MONTHLY`/`RICCON_QUOTA_DEFAULT_USER_DAILY`/`RICCON_QUOTA_DEFAULT_USER_MONTHLY`）+ Worker 集成测试上下文 DEMO+端点+主密钥可启动 + schema=11；quota/cost/attempt 表结构+FK/CHECK+空表 INSERT/ROLLBACK+PK 顺序/默认值断言（按 B6 upsert 形状）                                                                                                                                                                                                                                                                                                                                 |
| B1b  | `platform/platform-common/src/main/java/com/riccon/platform/common/crypto/**`、`platform/platform-common/src/test/java/**`、`apps/worker/src/main/java/com/riccon/worker/crypto/service/MasterKeyProvider.java`、`apps/api/src/main/java/com/riccon/api/crypto/**`、`apps/api/src/main/java/com/riccon/api/config/CryptoProperties.java`、`platform/platform-common/src/test/java/com/riccon/platform/common/crypto/**`、`apps/api/src/test/java/com/riccon/api/crypto/**`、`apps/worker/src/test/java/com/riccon/worker/crypto/**`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | API 加密→Worker 解密往返；AAD/篡改/跨租户失败；日志 canary 不含 key；API current+previous+master-key-id+canary；测试主密钥占位；无明文缓存                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| B1c  | `apps/api/pom.xml`、`apps/worker/pom.xml`、`scripts/ops/metrics-check*`、`apps/api/src/test/java/com/riccon/api/actuator/**`、`apps/worker/src/test/java/com/riccon/worker/actuator/**`（Actuator 暴露的 SecurityConfig 归 B2）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | Prometheus registry 依赖（API/Worker）；7 个路由/降级/key 指标名与标签约定（Worker 注册，B4 落地）；`scripts/ops/metrics-check` 以 `--base-url` 指向 Worker，只断言这 7 个；暴露实现归 B2，B1c 只做 registry 依赖与负向测试；不改变默认拒绝基线                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| B2   | `apps/api/src/main/java/com/riccon/api/model/catalog/**`、`apps/api/src/main/java/com/riccon/api/admin/model/catalog/**`、`apps/api/src/main/java/com/riccon/api/admin/model/provider/**`、`platform/platform-common/src/main/java/com/riccon/platform/common/model/**`、`apps/worker/src/main/java/com/riccon/worker/config/ModelEndpointProperties.java`、`apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java`、`apps/worker/src/main/java/com/riccon/worker/config/SecurityConfig.java`、`contracts/openapi.yaml`、`contracts/README.md`、`apps/api/src/test/java/com/riccon/api/model/**`、`apps/api/src/test/java/com/riccon/api/admin/model/**`、`apps/api/src/test/java/com/riccon/api/config/**`、`apps/worker/src/test/java/com/riccon/worker/config/**`、`apps/api/src/test/java/com/riccon/api/contract/**`                                                                                                                                                                                                                                                                               | `IT-MODEL-108a`（三个模型端点 + 路由/凭据/开关契约测试）；`IT-MODEL-108b`（配额/成本端点契约测试，同一 `1.3.0-m04` 全量、无 deferred 分段）；端点标签/允许状态、只读字段 400、生产模式拒绝演示端点、URL 负向矩阵（含元数据/IP 归一化）、405 无写路由；内层 `model.max-attempts=1`；Actuator prometheus 按 `RICCON_OPS_ALLOWED_CIDRS` 控制（空=loopback）且不放宽默认拒绝；**M04 契约唯一 writer（含 `contracts/README.md`）**，M02/M03/M10 契约零回归                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| B3   | `apps/api/src/main/java/com/riccon/api/model/routing/**`（含 repository/service）、`apps/api/src/main/java/com/riccon/api/admin/model/routing/**`、`apps/api/src/test/java/com/riccon/api/model/routing/**`、`apps/api/src/test/java/com/riccon/api/admin/model/routing/**`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | GET/PUT 两链；版本冲突 409；空链/未知模型 400；停用模型保留；审计数组 details 通过；下一次请求读新链                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| B4   | `apps/worker/src/main/java/com/riccon/worker/model/**`（含 `ModelRepository.java` 删除租户回退）、`apps/worker/src/main/java/com/riccon/worker/chat/**`、`apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java`、`apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java`、`apps/worker/src/main/java/com/riccon/worker/agent/**`、`apps/worker/src/main/java/com/riccon/worker/audit/service/AuditService.java`、`apps/worker/src/test/java/com/riccon/worker/model/**`、`apps/worker/src/test/java/com/riccon/worker/chat/**`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | 链路解析 + 内层 `maxAttempts=1` + 尝试顺序/上限/预算；8 类失败矩阵、首字后不重试不重放；尝试表/审计（`emitted`、SYSTEM/platform）；开关回退 BASELINE 且仍走 allowlist、无链外回退；BYO 回退移除（租户级凭据被忽略）+ 注册 7 个路由/降级/key 指标；J1/J2 回归                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| B5   | `apps/api/src/main/java/com/riccon/api/admin/model/credential/**`、`apps/api/src/test/java/com/riccon/api/admin/model/credential/**`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | 掩码列表；新增/轮换/停用/恢复；HMAC fingerprint 409、最后一把 key 可停用；审计无明文、DTO toString/错误不回显 key；在途影响说明；权限                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| B6   | `apps/api/src/main/java/com/riccon/api/quota/**`、`apps/api/src/main/java/com/riccon/api/admin/quota/**`、`apps/api/src/main/java/com/riccon/api/chat/service/ChatSubmissionService.java`、`apps/api/src/main/java/com/riccon/api/research/service/ResearchTaskService.java`、`apps/api/src/test/java/com/riccon/api/quota/**`、`apps/api/src/test/java/com/riccon/api/admin/quota/**`、`apps/api/src/test/java/com/riccon/api/chat/**`、`apps/api/src/test/java/com/riccon/api/research/**`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | V11 的 `quota_usage` PK 顺序/默认值匹配 upsert + 安全 upsert + 条件 UPDATE；50 并发 1 成功/49 个 429/无 500 + 拒绝审计 REQUIRES_NEW（失败 500）；`quotaEnforcement=false` 只计量并注册 `riccon_quota_rejections_total{scope,window}`、`riccon_quota_usage_ratio{scope,window}`（无 tenant 标签）；日/月边界；权限/跨租户；存储故障 → 503 不创建 run                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| B7   | `apps/worker/src/main/java/com/riccon/worker/model/service/ModelInvocationService.java`、`apps/worker/src/main/java/com/riccon/worker/model/persistence/ModelInvocationJdbc.java`、`apps/worker/src/main/java/com/riccon/worker/model/repository/ModelInvocationRepository.java`、`apps/worker/src/main/java/com/riccon/worker/chat/service/ChatStreamService.java`、`apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunRequestedHandler.java`、`apps/worker/src/main/java/com/riccon/worker/research/service/AgentRunResumeHandler.java`、`apps/api/src/main/java/com/riccon/api/cost/**`、`apps/api/src/main/java/com/riccon/api/admin/cost/**`、`apps/api/src/test/java/com/riccon/api/cost/**`、`apps/api/src/test/java/com/riccon/api/admin/cost/**`、`apps/worker/src/test/java/com/riccon/worker/model/**`                                                                                                                                                                                                                                                                               | 价格快照 + 6 位小数 + 未定价/用量未知/unbucketed 分桶；历史不改写（价格更新后金额不变）；日桶 `Asia/Shanghai` 边界；平台无用户字段、租户明细、跨租户 404；注册 `riccon_cost_report_queries_total{group_by}` + 查询保护（180 天/page/size/3s 超时 → 503）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| B8   | `scripts/real-stack/fake-model.mjs`、`scripts/real-stack/seed.mjs`、`scripts/real-stack/checks.mjs`、`scripts/real-stack/run.mjs`、`e2e/perf/**`、`e2e/support/fake-model/**`、`e2e/stub/server.mjs`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | 302/401/429/5xx/超时/断流/缺 usage/key 维度，场景确定性（固定内容/usage/错误码）；API/Worker 同一份 DEMO/端点/主密钥 env；k6 三场景+成本查询+退出码+env 记录（`RICCON_CHAT_MAX_STREAMS=50`、`RICCON_DB_POOL_SIZE>=25`、`RICCON_FAKE_MODEL=1`）；脚本可本地/CI 运行；报告记录 host CPU/内存、镜像 tag、k6 版本、脚本 sha、启动命令；perf 场景不启 OpenSearch 或另计主机规格（2C4Gi sandbox 不足）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| B9a  | `apps/web/src/pages/admin/model-routing/**`、`apps/web/src/pages/admin/model-keys/**`、`apps/web/src/pages/admin/audit/**`（仅 7 天预设与 target 类型）、`apps/web/src/services/model/**`、`apps/web/src/services/api/client.ts`、`apps/web/src/services/api/types.ts`、`apps/web/src/services/audit/types.ts`、`apps/web/config/routes.ts`、`apps/web/src/access.ts`、对应 `*.test.tsx`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | 路由/key 两页、key 掩码；端点标签；AC-07 影响说明；AC-15 UI 拒绝文案；audit target 新值 + 7 天预设；375px/键盘                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| B9b  | `apps/web/src/pages/admin/quotas/**`、`apps/web/src/pages/admin/costs/**`、`apps/web/src/services/quota/**`、`apps/web/src/services/cost/**`、`apps/web/config/routes.ts`（仅追加配额/成本入口）、对应 `*.test.tsx`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | 配额/成本两页；平台/租户可见性；AC-11 租户明细展开；AC-13 四页空态/403；与 B9a 菜单联动                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| B10  | `docs/RUNBOOK.md`、`deploy/compose/docker-compose.test.yml`、`scripts/ops/seed-*`、`scripts/ops/update-*`、`scripts/ops/rewrap-*`、`scripts/ops/restore-*`、`scripts/ops/backfill-*`、`.github/workflows/ci.yml`、`scripts/ops/quota-*`、`scripts/ops/cost-*`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | preflight（Secret/端点/模式/币种/默认配额键（`RICCON_QUOTA_DEFAULT_*` 4 键）/表体积）+ preflight+六步发布（0–6；drain 旧 Worker 上限 5 分钟，在途 run 记 `runId` 清单并由新 Worker 按租约恢复）+ 回滚（先 DROP CHECK、收敛 key、分组断言（每组 1 行、优先 ACTIVE）、`RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动旧 Worker、固定 WARN `RICCON-WORKER-002`、工单登记、Flyway 属性）；rewrap/幂等种子/价格/备份恢复演练（预览环境 RPO ≤24h、RTO ≤4h，客户生产 SOW 可覆盖并写入 RUNBOOK；restore-drill 通过判据：表数/触发器/一行 credential 可解密/J1 冒烟/`MAX(version)=11`）+ `backfill-cost-day` 干跑/进度/幂等；quota 开关 false→true 演练（**真实超限**：临时把测试租户日限额设为已用量+1 → 验证 429、`quota.exceeded` 审计、`riccon_quota_rejections_total` → `PUT false` 只计量不阻断 → 恢复限额 → `PUT true`）；API/Worker 端点/模式/币种/开关/默认配额键（`RICCON_QUOTA_DEFAULT_*`）漂移检查（`/actuator/info` 指纹）；成本对账窗口与告警阈值表（容差：金额差 0.00 CNY、调用量差 ≤0.1% 且差异需解释）；CI perf job（nightly 定期 + 发布前必跑；manual gate 仅限本地预检，不得替代 CI/nightly）+ 预算/主机规格记录 |
| B11a | `e2e/tests/m04-routing*.spec.ts`、`e2e/real/tests/m04-routing*.spec.ts`、`e2e/support/m04-routing*`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | E2E-M04-01/02/03/04/08 的路由/key 段 + `E2E-M04-07a` 两页子集（五态：加载/空/错误/403/成功；375px 无横向滚动；键盘；axe 无 serious/critical）；J1/J2/M03/M10 回归；audit 7 天；`@a11y` 两页；AC-02 界面无写入口（路由/key 两页，租户管理员/员工）；零 skip                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| B11b | `e2e/tests/m04-quota-cost*.spec.ts`、`e2e/real/tests/m04-quota-cost*.spec.ts`、`e2e/support/m04-quota-cost*`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | E2E-M04-05/06 + `E2E-M04-07b` 四页：AC-13 五态（加载/空/错误/403/成功）+ 375px 无横向滚动；AC-14 键盘路径逐条（路由页调整顺序→保存、配额页修改限额→保存、成本页切换聚合维度）；四页 `@a11y` 且 axe 无 serious/critical；AC-11 明细展开；AC-13 四入口；AC-02 界面无写入口（配额/成本两页，租户管理员/员工）；零 skip                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| V1   | `apps/api/src/test/java/com/riccon/api/security/m04/**`（Verifier M04 独立类）、`e2e/**` 只读运行                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | **AC-01、AC-03..16（15 条）逐条判定，不声称 AC-02**；为 AC-02 提供模型/key/开关段证据 `SEC-M04-003a`、`TEN-031`、`IT-MODEL-102`（AC-08..12 复用 B6/B7 集成报告与 B11b 的 `E2E-M04-05/06`）；`SEC-M04-001/002/004/005/006/007/008/009`；`IT-MODEL-101..109`（含 108a/108b 契约报告）；`E2E-M04-01..04/08` + `E2E-M04-07a` 两页 + `E2E-M04-07b` 四页（AC-13/14 五态、键盘路径、375px、axe）；V11 结构断言（无 `fk_model_invocation_provider`、无新增 CHECK、空表 INSERT/ROLLBACK）+ TM-39..44/49/50/53、B-20 的 Worker 7 指标部分核对；零 skip                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| V2   | `apps/api/src/test/java/com/riccon/api/security/m04verify/**`（Verifier 模块级复验独立类）、`e2e/**` 只读、`e2e/perf/**` 只读、`.riccon/evidence/**`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | **AC-02 整条**（模型/key/开关写越权：复用 V1 的 `SEC-M04-003a`/`TEN-031`/`IT-MODEL-102` + 独立抽检；配额写越权：`SEC-M04-003b`、`IT-QUOTA-103`；界面无写入口：`E2E-M04-07a/07b` 角色断言；路由审计与审计 fail-closed：`IT-MODEL-102`）；**AC-17/18** + `PERF-M04-001/002`；`TEN-032/033`、`IT-QUOTA-101..105`、`IT-COST-101..104`；迁移/回滚/备份恢复（`OPS-M04-01`：在一次性 MySQL 8.4.13 上独立重放并核对 WARN/工单）；7+2 指标核对（`OPS-M04-02a` Worker 7 + `OPS-M04-02b` API 2 主 + 3 辅助）；**在最终提交上独立运行一次全量合同 test/coverage/E2E/a11y/security（与 V1 分开取证）**；独立抽样重跑 AC-01、AC-03、AC-05、AC-08、AC-09、AC-11、AC-12、AC-16（引用 V1 证据但不替代）；零 skip                                                                                                                                                                                                                                                                                                                                                                                                                                 |

#### 8.4 关键路径、单模块排期与停止条件

| 项                    | 单模块 M04                                                                                                   |
| --------------------- | ------------------------------------------------------------------------------------------------------------ |
| 任务数                | 17 个实施任务（含 V1/V2）                                                                                    |
| 人时                  | ≈ 52–56                                                                                                      |
| 关键路径（3 builder） | B1a 2 → B2 4 → B3 2 → B4 5 → B7 3 → B9b 3 → B11b 2 → V1 3 → V2 4 ≈ **28h**                                   |
| 并行                  | B1b/B1c/B5/B6/B8/B9a/B10/B11a                                                                                |
| 串行组                | B1a→B2→B3→B4→B7（契约与 Worker 模型包）；B9a→B9b（routes/access）；B11b→V1→V2（证据链与同一 verifier owner） |
| B10 估算              | ≈6h；B7 在 16h 完成后启动、V2 在 24h 前完成；8h 窗口内完成才不拉长 ≈28h 关键路径，48h 仍有约 20h 余量        |

**为什么 48h 可行（3 builder）**

1. 关键路径 ≈28h，距 48h 上限约 1.7×；余额用于 7 个高风险任务的独立 Verifier 任务级评审（B1a/B1b/B2/B3/B4/B5/B6）、V1/V2 的模块级复核、失败重跑与干净提交后的证据录制。
2. 配额/成本不再等前一个模块用户验收：B6 只依赖 B1a，与 B2/B3 并行；B7 只额外等 B4，串在路由关键路径末端。并入后关键路径比纯路由段只增加约 8h（B7 3 + B9b 3 + B11b 2），不需要第二条模块排期。
3. 三条并行车道覆盖 17 个任务：A=契约/路由/Worker/四页收口，B=加密/key/路由页，C=观测/配额/测试资产/ops；V1 在 B11a 与 B11b 之后判定 AC-01、AC-03..16（15 条）并为 AC-02 提供模型/key/开关段证据，V2 收口 AC-02、AC-17/18，Verifier 串行不抢实现车道。
4. 必须串行的只有 5 组文件冲突（`contracts/**`、`application.yml`、`worker/model/**`、`routes.ts`/`access.ts`、`docs/RUNBOOK.md`+`docker-compose.test.yml`），其余均可并行；这是“48h 可行”的依据，不是靠压缩验证换时间。
5. 停止条件：墙钟 48h 未达 gate 必须暂停，向 Lead/用户报告偏差并重估；超过 2 倍（96h）暂停询问用户，未获新预算前停止。不得自行扩预算、静默降级 DoD、后移 AC 或重启拆分（拆分需要新的用户决策）。

**AC → V1/V2 判定归属（18 条，无重叠、无遗漏）**

| AC    | 判定              | 证据（V1 提供 / V2 判定）                                                                                                                                                                                               |
| ----- | ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-01 | V1                | `E2E-M04-01`、`IT-MODEL-101`                                                                                                                                                                                            |
| AC-02 | **V2 整条**       | 模型/key/开关写越权：复用 V1 的 `SEC-M04-003a`/`TEN-031`/`IT-MODEL-102` + V2 独立抽检；配额写越权：`SEC-M04-003b`、`IT-QUOTA-103`；界面无写入口：`E2E-M04-07a/07b` 角色断言；路由审计与审计 fail-closed：`IT-MODEL-102` |
| AC-03 | V1（V2 抽样重跑） | `E2E-M04-02`、`IT-MODEL-103`                                                                                                                                                                                            |
| AC-04 | V1                | `E2E-M04-03`、`IT-MODEL-104`                                                                                                                                                                                            |
| AC-05 | V1（V2 抽样重跑） | `SEC-M04-007`、`IT-MODEL-105/106`                                                                                                                                                                                       |
| AC-06 | V1                | `IT-MODEL-105`、`PERF-M04-001`                                                                                                                                                                                          |
| AC-07 | V1                | `E2E-M04-04`、`SEC-M04-008`、`IT-MODEL-106`                                                                                                                                                                             |
| AC-08 | V1（V2 抽样重跑） | `E2E-M04-05`、`IT-QUOTA-101`                                                                                                                                                                                            |
| AC-09 | V1（V2 抽样重跑） | `PERF-M04-002`、`IT-QUOTA-102`                                                                                                                                                                                          |
| AC-10 | V1                | `E2E-M04-05`、`IT-QUOTA-103`（V2 的 `TEN-032` 为独立补充证据）                                                                                                                                                          |
| AC-11 | V1（V2 抽样重跑） | `E2E-M04-06`、`IT-COST-101`                                                                                                                                                                                             |
| AC-12 | V1（V2 抽样重跑） | `IT-COST-104`（B7：跨租户 404、平台无用户字段）+ `E2E-M04-06` 员工无入口断言；V2 的 `TEN-033` 为独立补充证据，不参与 V1 判定                                                                                            |
| AC-13 | V1                | `E2E-M04-07a` 两页 + `E2E-M04-07b` 四页五态（加载/空/错误/403/成功）+ 375px 无横向滚动 + axe 无 serious/critical                                                                                                        |
| AC-14 | V1                | `E2E-M04-07b` 键盘路径（路由页调整顺序→保存、配额页修改限额→保存、成本页切换聚合维度）+ 四页 axe                                                                                                                        |
| AC-15 | V1                | `SEC-M04-001/002/009`、`IT-MODEL-107`                                                                                                                                                                                   |
| AC-16 | V1（V2 抽样重跑） | `E2E-M04-08`、`IT-MODEL-108a`                                                                                                                                                                                           |
| AC-17 | **V2 整条**       | `PERF-M04-001/002`、`OPS-M04-02a/02b`                                                                                                                                                                                   |
| AC-18 | **V2 整条**       | `OPS-M04-01`（V2 在一次性 MySQL 上独立重放并核对 WARN/工单）                                                                                                                                                            |

- V1 只判 **15 条（AC-01、AC-03..16）**，不声称 AC-02；V2 判 **AC-02、AC-17、AC-18 三条整条**，并独立抽样重跑 AC-01、AC-03、AC-05、AC-08、AC-09、AC-11、AC-12、AC-16。15 + 3 = 18，覆盖完整。
- V2 依赖 V1 只表示“读取 V1 已录证据 + 在最终提交上全量独立复验”，不产生 V1 等待 V2 证据的时序：V1 在自身时点即可完成 15 条判定；AC-02 的配额段与 UI 段本就在 B11b/V2 时点之后，不要求 V1 提前判定。

**模块 acceptance（供 Lead 使用，不在本任务实际创建）：**

`docs/PRODUCT.md §10.3 的 M04-AC-01..18（18 条）全部通过（V1 判 AC-01、AC-03..16，V2 判 AC-02/17/18 并在最终提交独立全量复验）；路由/key/配额/成本四页任务级验收与四页 a11y 通过；J1/J2 与 M02/M03/M10 回归零失败；F-007 perf 脚本可运行、F-011 三个模型端点收口；V11 迁移/回滚/备份恢复与主密钥 rewrap 演练通过；T4 威胁模型 B-18/B-19/B-20/B-21 与 TM-39..TM-54 全部核对；契约保持 1.3.0-m04 全量且 108a/108b 通过；R-11、M13 F-001 不承接。`

实施注意：

- **V11 一次建全表**：路由/key/配额/成本/尝试明细同属 V11；若 gate 发现表结构错误，用 V12 前向修复，不得重开 V11。gate 必须对 `quota_limit`/`quota_usage`/`model_invocation_attempt` 做结构 + 最小读写冒烟（空表 INSERT/ROLLBACK、FK/CHECK 断言、`information_schema` 无 `fk_model_invocation_provider`）。
- **契约一次交付**：`1.3.0-m04` 保持全量，B2 同时交付 `IT-MODEL-108a` 与 `IT-MODEL-108b`；不存在“先声明后实现”的分段，也不得把配额/成本端点标 `x-riccon-deferred`。
- **配额开关（强制）**：V11 种 `quotaEnforcementEnabled=false`；B10 在发布窗口验证“只计量不阻断”、默认限额与配置漂移后显式 `PUT true`，M04 gate 以 true 状态取证 AC-08/09；该 false→true 步骤、失败回滚与对账窗口写入 B10 RUNBOOK 与模块评审包，不得以“建议”带过。
- **DEC-0024 已答复**：B 选项已撤销拆分；Lead 只需按本表创建 17 个任务，不需要创建分段契约任务或第二个模块。ADR 不修改 `.riccon/`。

#### 8.5 高风险任务的独立评审与测试资产边界

- B1a（迁移）、B1b（加密/主密钥）、B2（SSRF/契约）、B3（权限/路由）、B4（AI 路由/并发）、B5（凭据）、B6（配额）、B7（成本/跨租户金额）在 done 前必须有独立 Verifier 的任务级评审（`REV-TASK-*`），不得由 Builder 自评；V1/V2 是模块级复核，不替代任务级评审。
- B8 独占测试资产写入；V2 只读 `e2e/perf/**` 并写 `.riccon/evidence/**`，不得修改 B8 文件。
- **V1/V2 判定边界**：V1 只判 AC-01、AC-03..16（15 条），并为 AC-02 提供模型/key/开关段证据；AC-02 整条由 V2 判定（含配额写越权与界面无写入口）。V2 必须在最终提交上独立运行一次全量合同 test/coverage/E2E/a11y/security 并与 V1 分开取证，同时独立抽样重跑 AC-01、AC-03、AC-05、AC-08、AC-09、AC-11、AC-12、AC-16；引用 V1 证据但不替代。V1/V2 合计覆盖 18 条，零 skip。
- B11a/B11b 必须在验收中显式包含 `@a11y`（两页/四页）；`E2E-M04-07a/07b` 必须断言真实页面状态、文案与入口，不得只用 API 证据代替 UI；两者是同一模块内的测试分组名，不表示分模块交付。AC-13/14 的五态、三条键盘路径与 375px 无横向滚动必须在 07b（四页）与 B11b 验收中逐条出现。

### 9. 安全

| 风险               | 控制                                                                                                                  | 验证                                                |
| ------------------ | --------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| SSRF/DNS rebinding | 部署允许列表；URL 结构校验；禁止 userinfo/query/fragment/重定向；拒绝元数据/链路本地；TLS 主机名校验；出网策略（M09） | `SEC-M04-001..004`、`IT-MODEL-107`                  |
| 凭据泄漏           | 信封加密；API/Worker 主密钥注入与启动校验；无明文缓存；掩码；日志/审计/错误白名单                                     | `SEC-M04-005`、`IT-MODEL-105/106`、`KEY-006`        |
| 跨租户             | 管理端点仅平台角色；租户端点从主体派生；跨租户 404；key 池无租户键                                                    | `TEN-031..033`、`IT-MODEL-102`                      |
| 越权配置           | `platform.model.manage` 分离；租户管理员只读；审计 fail-closed                                                        | `SEC-M04-003a/003b`、`IT-MODEL-102`、`IT-QUOTA-103` |
| denial-of-wallet   | 租户/用户配额（ADR-0022）；尝试上限/预算；key 冷却；每租户并发上限（M02）                                             | `PERF-M04-002`、`IT-QUOTA-102`                      |
| 降级到未授权端点   | 运行时再次校验允许列表与标签；链只引用目录模型；无链外回退                                                            | `IT-MODEL-107`、`SEC-M04-004`                       |
| 降级风暴           | 尝试上限/预算/退避/冷却/开关                                                                                          | `PERF-M04-001`、运维演练                            |
| 审计缺失           | 配置写与降级事件同事务/前置写入；失败即失败                                                                           | `IT-MODEL-102/103/105`                              |
| 密钥轮换失败       | 双密钥窗口 rewrap；轮换后校验；失败不启动；RUNBOOK                                                                    | `KEY-005`、`OPS-M04-01`                             |

### 10. 迁移与回滚

#### 10.1 V11 迁移步骤与 DDL 语义

- 单一迁移 `V11__model_gateway_depth.sql`，接 V10；只增不删（除 `provider_credential` 唯一键替换与 `RETIRED→DISABLED` 数据归一）。
- **DDL 语义（MySQL 8.4.13 实测）**：
  - 加列 `ALGORITHM=INSTANT`（单独语句，不与索引混写）；
  - 加索引 `ALGORITHM=INPLACE, LOCK=NONE`（逐条）；
  - `model_invocation` **不加 CHECK**（`ADD CONSTRAINT ... CHECK` 只能 COPY、阻塞 DML）；`cost_status`/`cost_amount`/`attempt_count` 由应用层校验 + 测试覆盖；
  - 小表（`quota_*`、`attempt`、`routing`、`switch`）可保留 CHECK；
  - `provider_credential` 加列（小表）→ DROP 旧 CHECK → 数据归一 → 新唯一键/索引/CHECK → 最后删旧唯一键（详见 §3.2）。
- **大表判据**：迁移前测量 `SELECT table_rows, data_length, index_length FROM information_schema.tables WHERE table_name='model_invocation'`；`>200 万行` 或 `data+index >2 GiB` 时，索引必须在单独维护窗口停写执行，磁盘余量 ≥2×表体积。V11 不回溯 `cost_day`；需要时由 B10 `backfill-cost-day` 在 CHECK 后分批执行，M04 gate 不依赖全量回填；不执行 `cost_status` 全表更新。
- 迁移账号需 `TRIGGER` 权限（审计触发器已有）；历史 `model_invocation` 可读。
- 迁移测试（`V11MigrationTest`）必须覆盖：V1→V11 fresh、V10→V11 升级（V10 合法种子 1 ACTIVE + 1 RETIRED 跨 provider + 1 万历史调用；V11 后 helper 造 2 ACTIVE + 1 INVALID）、回滚 SQL 单 key/多 key 两种、失败注入（唯一键提前）、旧应用在 V11 库读取历史调用。

#### 10.2 发布顺序（preflight + 六步，0–6）

0. **preflight（无副作用）**：Secret（`RICCON_MASTER_KEY`/`PREVIOUS`/`FINGERPRINT_PEPPER`/service token/DB）、`RICCON_MODEL_DEPLOYMENT_MODE`/`ENDPOINTS`/`BILLING_CURRENCY`/默认配额键（`RICCON_QUOTA_DEFAULT_*`）合法且 API 与 Worker 一致、DB 连通、`model_invocation` 行数/体积/磁盘、无长事务/MDL。失败即停，不动 DB。
1. **备份**：全量 MySQL（含 `flyway_schema_history`、`tenant_data_key`、`provider_credential`、`SPRING_SESSION`），记录 `master_key_id`；在副本上校验一行 credential 可解密；密钥保留期 ≥ 备份保留期。
2. **一次性迁移 Job**：新 API 镜像只执行 Flyway（不启 Web 端口），失败即停，未动业务；**不要**由常驻 API 启动触发迁移。
3. **结构校验**：`MAX(version)=11`、6 张新表、索引、`RETIRED=0`、`model_invocation.provider_id` 无 FK、小表 CHECK 生效；历史 `cost_day` 允许 NULL（计入 `unbucketedCalls`），M04 后新调用抽样非空。
4. **新 API**：`/actuator/health` UP；`/admin/model-providers` 返回 `allowed=true` 且标签正确。
5. **drain 旧 Worker → 新 Worker**：新 Worker 期望 schema=11，`/actuator/health` + outbox 恢复领取；旧 Worker 不需要在迁移前停（旧代码显式列清单、V11 新列可空/有默认，读写兼容），但**V11 之后不得重启旧 Worker**（严格等值校验会失败）。停旧 Worker 是缩短混合窗口的可选步骤，不是迁移前置。**drain 判据**：先停止领取新 run（优雅停机），drain 上限 **5 分钟**；在途 run 记 `runId` 清单，由新 Worker 按租约到期恢复（同 run 不重复准入、attempt 不重复计数）；超时未完成的 run 不得强杀，按失败/取消语义结束并核对审计。
6. **冒烟与开关**：J1 首字、J2 报告（含审批）、路由读、key 掩码、开关读；默认 `ROUTED/true/false`（`quotaEnforcementEnabled=false`）；B10 先验证“只计量不阻断”、默认限额与漂移检查，再显式 `PUT true`，M04 gate 以 true 状态取证 AC-08/09；观测 30 分钟覆盖路由/降级/key/链空/5xx 与配额拒绝。按发版策略可先 BASELINE 再打开。

- **不能纯滚动**：旧 Worker 期望 10，DB 到 11 后重启会失败；必须维护窗口。RUNBOOK 记录。
- **生产目录/价格变更**：新增端点 = 更新 `RICCON_MODEL_ENDPOINTS` + 跑 `scripts/ops/seed-model-catalog.mjs`（按 `code`/`provider+model_key` 幂等 upsert，不 DELETE、不含密钥）+ 重启 API/Worker；新增模型/改价格同理。`scripts/ops/update-model-price.mjs` 只改 `model.input/output_price_per_1m`，打印 before/after，禁止改历史 `model_invocation` 快照。B10 RUNBOOK 写“加端点/加模型/改价格/换币种不可行”四条流程。
- **M04 gate 环境**：`commands.deploy_preview`/`deploy_prod` 指向的 M09 交付物当前不存在，M04 不以此取证；迁移/回滚/真实栈证据走一次性 MySQL 8.4.13 + `scripts/real-stack/run.mjs`，评审包标 `deploy_preview not-applicable`（M09）。

#### 10.3 回滚（应用回滚，不做 down migration）

- V11 是**加列/加表 + 唯一键替换**，旧应用（M02/M10）不读取新表；`model_invocation` 新列可空/有默认，旧查询按列名读取仍工作。
- 回滚到 M04 前应用版本时：
  - API：Boot 4.1.2 的 Flyway 默认 `spring.flyway.ignore-migration-patterns=*:future`（`ignore-future-migrations` 在 Flyway 12 已移除，设置无效）；旧 API 镜像在 V11 库上启动必须演练 `/actuator/health` UP + 历史调用可读。若客户显式置空该配置，需在回滚窗口设为 `*:future`。
  - Worker：旧版本 `SchemaVersionChecker` 固定期望 10；回滚窗口必须以 `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动旧 Worker，并在 RUNBOOK 记录“仅回滚窗口、人工核对 `MAX(version)`、只允许启动 M04 前最后一个 worker 版本”的工单与风险接受。关闭校验时必须输出固定 WARN 码 `RICCON-WORKER-002`，文案固定为 `schema validation disabled`；演练与 V2 独立重放按该码逐字核对，不得输出其他码。
- 回滚后行为回到 M02 单模型：旧 Worker 的 `findActiveCredential` 取其中一把 ACTIVE key（多 key 下按 version DESC LIMIT 1），仍可服务；但**回滚前必须收敛到一把 ACTIVE key**（见下），否则可用性取决于任意 key。路由/配额/成本表被忽略。**成本快照不删除/不改写**；历史调用记录仍可读（AC-18）。
- **不提供 down migration**。以下“应急 SQL”只用于测试环境验证 V11 可逆性或经用户批准后的灾难恢复演练，不是生产回滚路径；生产回滚优先备份恢复。执行前必须停止 API/Worker、备份 `provider_credential`、确认不再需要 M04 数据：

```sql
-- 仅演练/灾难恢复；生产回滚不做 down migration，优先备份恢复。
-- 0) 先备份 provider_credential；多 key 数据必须归档/删除多余行后再恢复旧唯一键
--    归档表（演练前创建；生产灾难恢复优先备份，不强制保留）
CREATE TABLE IF NOT EXISTS provider_credential_rollback_archive LIKE provider_credential;
ALTER TABLE provider_credential_rollback_archive
    ADD COLUMN archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
-- 1) 删除新 CHECK（必须先删，否则状态归一失败）
ALTER TABLE provider_credential
    DROP CHECK chk_provider_credential_status,
    DROP CHECK chk_provider_credential_priority,
    DROP CHECK chk_provider_credential_failures;
-- 2) 每个 (tenant_id, provider_id, credential_type) 只保留一把：
--    优先 ACTIVE；同状态按 priority ASC, created_at DESC, id DESC 选一把。
--    其余行先写入 provider_credential_rollback_archive（同结构 + archived_at），再 DELETE；
--    演练种子可含 2 ACTIVE + 1 INVALID（同组）或跨 provider 的 1 ACTIVE + 1 RETIRED；收敛后每组只保留 1 行。
-- 3) 只把非 ACTIVE 的保留行改为旧枚举；保留的 ACTIVE 不动（旧 Worker 仍可服务）
UPDATE provider_credential SET status = 'RETIRED' WHERE status IN ('DISABLED', 'INVALID');
-- 4) 恢复旧唯一键/CHECK，删除新唯一键
ALTER TABLE provider_credential
    ADD UNIQUE KEY uk_provider_credential (tenant_id, provider_id, credential_type),
    ADD CONSTRAINT chk_provider_credential_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    DROP INDEX uk_provider_credential_fingerprint,
    DROP INDEX idx_provider_credential_pool;
-- 5) 删除新增列（小表；旧应用不读取）
ALTER TABLE provider_credential
    DROP COLUMN label, DROP COLUMN fingerprint, DROP COLUMN masked_hint, DROP COLUMN priority,
    DROP COLUMN consecutive_failures, DROP COLUMN cooldown_until, DROP COLUMN disabled_at,
    DROP COLUMN disabled_by, DROP COLUMN invalidated_at, DROP COLUMN last_used_at;
-- 6) 删除 M04 新增表（会丢失路由/key 健康/配额/尝试明细；成本列保留）
DROP TABLE IF EXISTS quota_usage;
DROP TABLE IF EXISTS quota_limit;
DROP TABLE IF EXISTS model_invocation_attempt;
DROP TABLE IF EXISTS model_gateway_switch;
DROP TABLE IF EXISTS model_routing_entry;
DROP TABLE IF EXISTS model_routing;
-- 7) model_invocation 新增列可保留（旧应用忽略）；不删除历史成本快照。
-- 8) 手工回滚 schema 后删除 Flyway V11 行（先备份 flyway_schema_history）：
DELETE FROM flyway_schema_history WHERE version = '11';
-- 然后 flyway repair；断言 MAX(version)=10，旧 API/Worker 可启动。
```

- 回滚演练断言（不写死全表 COUNT）：按 `(tenant_id, provider_id, credential_type)` 分组，每组恰好 1 行；组内若有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED。fixture 可跨 provider 为 1 ACTIVE + 1 RETIRED（总行数=2、ACTIVE=1），也可同组收敛为 1 行；不得把总行数写死为 1。旧 Worker 取到 ACTIVE key 并完成一次 J1。不能只断言行数与唯一键。
- 上述 SQL 必须在迁移测试中分**单 key**与**多 key**两种种子验证：单 key 可直接执行；多 key 必须先收敛/归档，否则恢复旧唯一键会 `ERROR 1062`。生产只走“应用回滚 + 备份恢复 + 前向修复”。
- 回滚窗口的混合版本成本页不可用于对账：旧 Worker 不写 `cost_day`，这些行进入 `unbucketedCalls`（见 ADR-0022 §2.4），RUNBOOK 登记窗口。
- **回滚/备份恢复的数值判据**：预览环境默认 RPO ≤24h、RTO ≤4h；客户生产 SOW 可覆盖，B10 RUNBOOK 记录实际值。`OPS-M04-01` 通过条件 = V11 迁移/回滚/备份恢复/rewrap 演练全部完成，且 V2 在一次性 MySQL 8.4.13 上独立重放回滚并核对固定 WARN 与工单。

#### 10.4 前向修复与 V11 失败恢复矩阵

| 失败点                                         | 残留                                         | 恢复                                                                    |
| ---------------------------------------------- | -------------------------------------------- | ----------------------------------------------------------------------- |
| 建新表阶段                                     | 已建的空表                                   | `DROP TABLE` 空表 + `flyway repair` + 重跑                              |
| `provider_credential` 加列后、DROP 旧 CHECK 前 | 新列可空/默认，旧 CHECK/唯一键仍在           | 补 DROP CHECK，继续后续步骤；旧应用仍可读写                             |
| DROP 旧 CHECK 后、新 CHECK 前                  | 旧 CHECK 已删，`RETIRED→DISABLED` 可能已执行 | 旧应用不依赖 CHECK；直接补新唯一键/新 CHECK，再删旧唯一键               |
| 新唯一键已建、旧唯一键未删                     | 两套唯一键并存                               | 完成数据归一后 DROP 旧唯一键；不要重跑 ADD COLUMN                       |
| `model_invocation` 加列/索引中途               | 列或部分索引已提交（DDL 非事务）             | 按 `information_schema` 残留清单补缺失索引；不要重复 ADD COLUMN         |
| `cost_day` 未回填                              | 历史 NULL（允许）                            | M04 不阻塞；B10 `backfill-cost-day` 分批执行                            |
| 迁移 Job 被杀 / history success=0              | 结构前缀已提交                               | `flyway repair` → 结构校验 → 按上表补齐；禁止修改已应用 V11（checksum） |

- 失败后**不要**直接起旧 Worker；先 repair + 结构校验。`provider_credential` 半迁移（旧 CHECK 已删、新 CHECK 未建）时旧应用仍可读写（旧代码不依赖该 CHECK），但必须补齐再启动新代码。
- V11 有 10+ 条非事务 DDL，必须按本表逐项演练；参考 V10 OPS-006。

| 故障             | 前向修复                                                         |
| ---------------- | ---------------------------------------------------------------- |
| 路由配错导致 503 | `routingMode=BASELINE` 开关或 API 修改链；下一次请求生效         |
| 新 key 无效      | 管理端停用/恢复；`failoverEnabled` 保持开启时自动跳过            |
| 配额误设         | 平台管理员调高限额；被拒绝 run 可重试                            |
| 成本价格错       | 部署种子更新价格；历史快照不变；不重算历史                       |
| V11 索引/列问题  | 新迁移 V12 修正；不回滚 V11                                      |
| 迁移 Job 中断    | `flyway repair` + 按残留清单补齐；禁止修改已应用 V11（checksum） |

#### 10.5 开关（env 名称、优先级与默认值）

- 环境变量（紧急，需重启）与 DB 开关（在线）；优先级 **ENV > DB > 默认**。
  - `RICCON_MODEL_ROUTING_MODE=ROUTED|BASELINE`
  - `RICCON_MODEL_FAILOVER_ENABLED=true|false`
  - `RICCON_MODEL_QUOTA_ENFORCEMENT_ENABLED=true|false`
- 默认值：DB 种子 `ROUTED/true/false`（发版保持 false；B10 演练后 PUT true，M04 gate 以 true 取证）；非法值启动失败。`quotaEnforcementEnabled=false` 时仍记录用量，但跳过拒绝（ADR-0022 §1.3）。
- 开关变更写 `model.gateway.switch.changed`；环境变量覆盖只在值**实际变化**时写一条 `source=DEPLOYMENT` 审计（actor=SYSTEM，tenant=platform），并在 `/actuator/info` 暴露生效值，避免多副本滚动重启产生成倍审计噪音。
- `/actuator/info` 在 S-12 键之外增加 `info.riccon.quota.tenantDaily`/`tenantMonthly`/`userDaily`/`userMonthly`（默认配额生效整数，非密钥）；B10 preflight 比对 API 与 Worker 一致，作为默认配额键的漂移检查。
- `RICCON_BILLING_CURRENCY` 部署后不可变；变更 = 新部署 + 历史报告按币种隔离，不提供在线改币种。

#### 10.6 观测与告警

- 指标（B1c 落地 registry/指标约定；**暴露由 B2**；B4/B6/B7 注册）：
  - **M04（7 个，B4 注册）**：`riccon_model_requests_total{provider,model,outcome}`、`riccon_model_failover_total{from,to,reason}`、`riccon_model_attempt_duration_seconds`（histogram）、`riccon_model_credential_invalid_total{provider}`、`riccon_model_credential_cooldown_total{provider}`、`riccon_model_chain_empty_total{purpose}`、`riccon_agent_runs_inflight`（gauge）。
  - **配额/成本（2 个主指标 + 3 个辅助，B6/B7 在 API 注册）**：`riccon_quota_rejections_total{scope,window}`、`riccon_quota_usage_ratio{scope,window}`（gauge）；辅助 `riccon_cost_report_queries_total{group_by}`、`riccon_quota_store_failure_total`、`riccon_quota_over_limit_observed_total`（如适用）。
  - 框架：`http.server.requests`；B1c `metrics-check` 只断言 Worker 的 7 个路由/降级/key 指标，V2 断言 API 的 2 个主指标 + 3 个辅助指标。
- 标签纪律：指标禁止 `tenant`/`userId`/`runId` 等高基数标签（ADR-0008）；按租户排查走日志/审计/DB 查询。`riccon_quota_usage_ratio` 只保留 `scope`/`window`。
- **ADR-0008 同名指标**：`riccon_model_requests_total` 复用 ADR-0008 名称，M04 扩展标签集为 `{provider,model,outcome}` 并作为唯一注册点（B4）；ADR-0008 原 `{outcome}` 视为被 M04 扩展，不定义第二个同名不同义指标。
- 暴露：`/actuator/info` 与 `/actuator/prometheus` 共用 `RICCON_OPS_ALLOWED_CIDRS` 来源门禁（逗号分隔 CIDR；空=loopback；非允许来源拒绝，API 匿名 401、Worker 403），SecurityConfig（B2）不扩大公网默认拒绝、不新增 `permitAll`；完整 ingress 归 M09。`info` 仅暴露 S-12/配额等非敏感指纹键，不含 endpoint URL、key 或密文（§2.1/§10.5）。
- 日志：只记录 `providerCode`、`modelId`、`credentialId`、`errorCode`、`attempt`、`traceId`；禁止 prompt、key、`Authorization`、`baseUrl`、上游响应正文。
- 告警（B10 阈值表，M09 接入）：路由/降级：降级率 >20%/5min、`riccon_model_credential_invalid_total` 增长、`riccon_model_chain_empty_total >0`、`riccon_agent_runs_inflight` 接近上限；配额/成本：配额拒绝率、`riccon_quota_store_failure_total >0`、成本查询 p95 >2s。

### 11. 测试策略

| 层     | 内容                                                                                 | 工具/位置                              |
| ------ | ------------------------------------------------------------------------------------ | -------------------------------------- |
| 单元   | 端点 URL 矩阵、路由解析、尝试顺序、分类映射、成本公式、窗口边界                      | JUnit，`platform-common`/`apps/*`      |
| 集成   | `IT-MODEL-101..109`、`IT-QUOTA-*`、`IT-COST-*`；真实 MySQL（Testcontainers/Compose） | `./mvnw -q -T1C verify`                |
| 契约   | 三个模型端点、路由/凭据/开关/配额/成本端点；OpenAPI 校验                             | `apps/api` 契约测试                    |
| 真实栈 | fake provider 场景（429/5xx/401/超时/断流/缺 usage）、key 轮换、降级审计             | `scripts/real-stack/**`、`e2e/real/**` |
| E2E    | `E2E-M04-01..08`；J1/J2/M03/M10 回归；a11y                                           | Playwright                             |
| 安全   | `SEC-M04-001..009`、`TEN-031..033`、`SEC-MODEL-*`                                    | Verifier 独立类                        |
| 负载   | `PERF-M04-001/002`：50 并发、故障矩阵、配额争用                                      | `k6 run e2e/perf/mvc-smoke.js`         |
| 迁移   | V1→V11、V10→V11、回滚到 V10 + 旧应用读；`OPS-M04-01`                                 | 迁移测试 + Ops 演练                    |
| 运维   | Worker 7 指标、API 2 主 + 3 辅助指标、标签纪律、`metrics-check`；`OPS-M04-02a/02b`   | Micrometer + 脚本                      |

- fake provider 通过控制端点设置场景；**确定性**：固定内容、固定 usage、固定错误码，不使用随机失败（除抖动测试固定种子）。
- 不得用 `--no-file-parallelism` 或其他并行度开关掩盖 flake；失败用例必须修根因或登记 P2 + owner + 到期。
- 新增 `IT-M04-*` 用于模块自测（不在 PRODUCT 清单中的补充用例）；PRODUCT 已给出的 `IT-MODEL-*`/`IT-QUOTA-*`/`IT-COST-*` 编号不重排。

#### 11.1 测试 ID 目录（场景 / 断言 / owner / 证据）

| ID              | 层   | 场景与关键断言                                                                                                                         | owner     | 证据                  |
| --------------- | ---- | -------------------------------------------------------------------------------------------------------------------------------------- | --------- | --------------------- |
| `IT-MODEL-101`  | 集成 | 路由保存/停用/启用/下一次请求；版本冲突 409；空链/未知 400                                                                             | B3/B4     | surefire              |
| `IT-MODEL-102`  | 集成 | 越权写 403/404；路由审计 details 数组；审计失败回滚                                                                                    | B3/B5     | surefire              |
| `IT-MODEL-103`  | 集成 | 首字前 429/5xx/401/超时矩阵；attempt 数 = HTTP 调用数；切换审计                                                                        | B4        | surefire              |
| `IT-MODEL-104`  | 集成 | 首字后断流/超时；内容保留；`emitted=true`；不重放                                                                                      | B4        | surefire              |
| `IT-MODEL-105`  | 集成 | 多 key 停用/401 失效/恢复/全失效；平台租户审计；租户级凭据被忽略                                                                       | B4/B5     | surefire              |
| `IT-MODEL-106`  | 集成 | 轮换在途；旧 key 掩码；fingerprint HMAC 去重                                                                                           | B5        | surefire              |
| `IT-MODEL-107`  | 集成 | URL/IP/元数据/演示端点/非允许 provider；无出网；启动失败                                                                               | B2        | surefire + 配置日志   |
| `IT-MODEL-108a` | 契约 | 三个模型端点 + 路由/凭据/开关 schema（配额/成本见 108b，不标 deferred）                                                                | B2        | 契约测试报告          |
| `IT-MODEL-108b` | 契约 | 配额/成本端点 schema；同一 `1.3.0-m04` 全量契约，无 deferred 分段                                                                      | B2        | 契约测试报告          |
| `IT-MODEL-109`  | 集成 | 三种开关关闭；BASELINE 仍走 allowlist；回滚后历史可读                                                                                  | B4/B10    | surefire + 演练日志   |
| `IT-QUOTA-101`  | 集成 | 租户日/用户月超限；429 形状；不产生模型调用；拒绝审计                                                                                  | B6        | surefire              |
| `IT-QUOTA-102`  | 集成 | 剩余 1 次 + 50 并发：1 成功/49 个 429/无 500；`used_count=1`                                                                           | B6        | surefire              |
| `IT-QUOTA-103`  | 集成 | 跨租户/权限/平台无用户明细/租户只读                                                                                                    | B6        | surefire              |
| `IT-QUOTA-104`  | 集成 | 配额存储不可用 → 503；在途继续；失败注入                                                                                               | B6        | surefire              |
| `IT-QUOTA-105`  | 集成 | 限额降低不影响在途；同 run 重试不重复扣；幂等键命中不扣                                                                                | B6        | surefire              |
| `IT-COST-101`   | 集成 | 固定样例 6 位小数/展示舍入/合计；价格更新不改历史                                                                                      | B7        | surefire              |
| `IT-COST-102`   | 集成 | PRICED/UNPRICED/USAGE_UNKNOWN 三类；unbucketed                                                                                         | B7        | surefire              |
| `IT-COST-103`   | 集成 | `Asia/Shanghai` 日/月边界；历史 `cost_day NULL` 允许、M04 后新调用非空；`unbucketedCalls` 计数                                         | B7/B1a    | surefire              |
| `IT-COST-104`   | 集成 | 平台无用户字段；租户明细；跨租户 404                                                                                                   | B7        | surefire              |
| `SEC-M04-001`   | 安全 | HTTP/userinfo/query/fragment/重定向/元数据/回环矩阵；HostResolver 固定 map                                                             | B2/V1     | surefire              |
| `SEC-M04-002`   | 安全 | 生产模式 + 演示端点；非允许 provider；无出网                                                                                           | B2/V1     | surefire              |
| `SEC-M04-003a`  | 安全 | 员工/租户管理员写模型、key、开关；403/404；无配置内容                                                                                  | V1        | surefire              |
| `SEC-M04-003b`  | 安全 | 员工/租户管理员写配额；403/404；无配额内容                                                                                             | V2        | surefire              |
| `SEC-M04-004`   | 安全 | 路由引用未授权端点；BASELINE 无链外请求                                                                                                | V1        | surefire              |
| `SEC-M04-005`   | 安全 | canary key 扫描响应/日志/审计/DB/证据/trace；DTO toString/异常不回显                                                                   | V1        | 扫描报告              |
| `SEC-M04-006`   | 安全 | key 生命周期审计（created/rotated/disabled/invalidated/enabled/recovered）+ 审计失败路径；SYSTEM/platform                              | V1        | surefire              |
| `SEC-M04-007`   | 安全 | 全链不可用：稳定 `RICCON-MODEL-503`、固定中文文案、响应/页面/审计无 provider/key/host/堆栈                                             | V1/B2     | surefire              |
| `SEC-M04-008`   | 安全 | 轮换在途：旧 key 只掩码、无明文；部分内容保留、不重复                                                                                  | V1/B2     | surefire              |
| `SEC-M04-009`   | 安全 | 端点目录/路由选择器标签可见；生产拒绝演示端点；页面/日志/审计/错误无 key/内部地址                                                      | V1/B2/B9a | Playwright + surefire |
| `TEN-031`       | 安全 | 租户管理员/员工调用模型路由/模型/key/开关写接口；跨租户对象 404                                                                        | V1        | surefire              |
| `TEN-032`       | 安全 | 租户读他租户配额/成本；平台请求用户明细；平台无用户字段                                                                                | V2        | surefire              |
| `TEN-033`       | 安全 | 成本/配额传他租户/用户；员工访问；平台 `groupBy=USER` 400                                                                              | V2        | surefire              |
| `E2E-M04-01`    | E2E  | 路由页配置/停用/启用/保存；端点标签                                                                                                    | B11a      | Playwright            |
| `E2E-M04-02`    | E2E  | 注入主模型失败；员工无感；无 provider 名称                                                                                             | B11a      | Playwright            |
| `E2E-M04-03`    | E2E  | 首字后中断/全链失败；保留内容/重试/统一文案                                                                                            | B11a      | Playwright            |
| `E2E-M04-04`    | E2E  | key 新增/轮换/停用；掩码；影响说明；审计                                                                                               | B11a      | Playwright            |
| `E2E-M04-05`    | E2E  | 配额配置/超限/重置时间；租户只读                                                                                                       | B11b      | Playwright            |
| `E2E-M04-06`    | E2E  | 成本页平台/租户视图；未定价/unknown/unbucketed；租户明细展开                                                                           | B11b      | Playwright            |
| `E2E-M04-07a`   | E2E  | 路由/key 两页五态（加载/空/错误/403/成功）/375px 无横向滚动/键盘/axe 无 serious/critical/租户管理员与员工无写入口                      | B11a      | Playwright + axe      |
| `E2E-M04-07b`   | E2E  | 四页五态（加载/空/错误/403/成功）/375px/键盘（调整顺序→保存、修改限额→保存、切换聚合维度）/axe/audit 7 天预设/租户管理员与员工无写入口 | B11b      | Playwright + axe      |
| `E2E-M04-08`    | E2E  | J1/J2/M03/M10 回归                                                                                                                     | B11a      | Playwright            |
| `PERF-M04-001`  | 负载 | 50 并发 + 首字前故障矩阵；首字 p95 ≤3s、API p95 ≤500ms、降级成功率 100%                                                                | B8/V2     | k6 JSON + summary     |
| `PERF-M04-002`  | 负载 | 50 并发配额争用零超卖；成本查询 30/180 天 p95 ≤2s                                                                                      | B6/B8/V2  | k6 JSON               |
| `OPS-M04-01`    | 运维 | V11 迁移/回滚/主密钥 rewrap/备份恢复演练                                                                                               | B10/V2    | 演练日志 + 证据       |
| `OPS-M04-02a`   | 运维 | Worker 7 个 `riccon_*` 指标存在/标签正确/无 tenant 高基数；`metrics-check --base-url` 指向 Worker 通过；默认拒绝未回归                 | B1c/V1    | metrics 抓取报告      |
| `OPS-M04-02b`   | 运维 | API 配额/成本 2 个主指标 + 3 个辅助指标存在/标签正确；V2 记录抓取样本                                                                  | V2        | metrics 抓取报告      |

- `SEC-MODEL-003` 是 M02 既有 ID，只覆盖旧模型端点；M04 新端点由 `SEC-M04-003a`（模型/key/开关写越权）与 `SEC-M04-003b`（配额写越权）覆盖。PRODUCT 中的历史线索 `SEC-MODEL-004/005/006` 分别由 `SEC-M04-007`（全链不可用无泄露）、`SEC-M04-008`（轮换在途）、`SEC-M04-009`（端点标签/生产拒绝/内部信息）承接；M04 文档不再把它们当证据。
- AC-15 的 PRODUCT 历史线索 `SEC-MODEL-002` 在 M04 由 `SEC-M04-001/002/009` + `IT-MODEL-107` 承接（M02 旧 ID 不再作为 M04 证据）；PRODUCT 文本同步归 Product/Lead，不在本 ADR 写入范围。
- `E2E-M04-07a` 是路由/key 两页的子集分组，`E2E-M04-07b` 是四页与 AC-14 完整断言的分组；AC-13/14 由 B11b 的四页 E2E + `@a11y` 在同一模块内判定，两个分组合起来才构成 AC-13/14 的完整证据。`OPS-M04-02a` 只覆盖 Worker 7 个指标，`OPS-M04-02b` 覆盖 API 2 主 + 3 辅助；两者不得互相替代。
- 每个 ID 只能在**一个**测试方法/用例中定义；评审包按“AC → ID → 报告路径”登记。

## Consequences

- 正面：路由/降级成为可配置、可审计、可回滚的确定性状态机；key 池跨实例共享健康；端点不可由用户输入；首字后失败不重复内容；关闭开关可回 M02 行为；成本/配额有独立数据模型（ADR-0022）。
- 负面：每次模型请求多 2–3 次目录/开关查询；多 key 增加凭据管理面；API 从 M04 起需要主密钥；V11 需要维护窗口；baseline 回滚需要临时关闭 Worker schema 校验；平台托管池的健康状态是全局的，单租户流量可能影响其他租户。
- 约束：新增模型目录/路由/凭据写入必须走平台管理员权限与审计；任何新端点必须走部署配置；不得在响应/日志/审计中出现 key、Authorization、baseUrl；不得引入进程内熔断或跨请求缓存。

## Alternatives

- **每租户独立模型链/key**：租户 BYO 与租户级路由后移（DEC-0023/OQ-17）；M04 只做平台全局，避免租户隔离与计费复杂度。
- **进程内熔断器（Resilience4j 等）**：单实例状态、滚动发布不一致、难以确定性测试；拒绝，改用 DB key 健康。
- **随机/加权 key 选择**：不可预测、难以复现故障；拒绝。
- **失败后自动重试同一模型直到成功**：放大会话延迟与上游压力；拒绝，改为有界尝试 + 预算。
- **首字后继续换模型续写**：会产生重复/拼接内容；拒绝，保留内容 + 客户端新消息重试。
- **UI 自由输入 base_url**：SSRF 与凭据外发；DEC-0023 明确禁止；拒绝。
- **把端点写 API 与部署配置双写**：漂移与越权；拒绝，部署配置唯一来源。
- **在 API 内复制一套加密逻辑**：格式漂移风险；改为 `platform-common` 共享 `MasterKeyRing`，Worker 保留兼容包装。

## Evidence

- `contracts/openapi.yaml` `1.3.0-m04`：模型/路由/凭据/开关端点与 schema。
- `contracts/README.md` 版本与安全规则。
- `docs/ARCHITECTURE.md` M04 实现地图、评审状态、风险表。
- `docs/security/THREAT-MODEL.md` TB-04 扩展、TM-39..TM-54、ABUSE-07..11、R-21..R-25。
- 现有实现：`apps/worker/.../model/**`、`apps/worker/.../crypto/**`、`apps/api/.../admin/**`、`V5__model_gateway_and_mcp.sql`。
- 决策：DEC-0023；待 DEC-0024（§12）。

## 12. 不可逆/高成本项（DEC-0024=B 已答复）

> DEC-0024=B 已于 2026-11-20 答复：不拆，M04 单模块 48h、18 条 AC 全保留；下表各项按 B 答复批准，未批准项不存在。

| 项                                                                | 为什么需要用户批准                                                             | 建议                                                       |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------ | ---------------------------------------------------------- |
| V11 数据模型（新表/列/唯一键替换/全局表扩展）                     | 数据模型与全局表例外是高成本、难回退（虽只向前）                               | 批准 V11 方案；回滚按 §10.3 应用回滚 + 前向修复            |
| API 新增主密钥启动依赖                                            | 改变部署 Secret 要求，漏注入会导致 API 不可启动                                | 批准；Ops 在 RUNBOOK 与部署清单中加 `RICCON_MASTER_KEY`    |
| 生产部署必须提供端点允许列表                                      | 未配置 `RICCON_MODEL_ENDPOINTS` 时 `PRODUCTION` 启动失败；M02 升级需要部署变更 | 批准；RUNBOOK 给出配置样例与回退说明（`DEMO` 仅测试/演示） |
| 首字后失败语义（保留内容、不自动重试、新消息重试）                | 产品可见行为，影响“重试不重复”的契约                                           | 批准 AC-04 口径                                            |
| 平台托管 key 池、无租户 BYO                                       | 已由 OQ-17 批准，但“平台 key 用于所有租户”的安全/成本含义需确认                | 批准；BYO 保持 deferred                                    |
| 回滚窗口关闭 Worker schema 校验                                   | 运维风险接受，需明确                                                           | 批准；仅回滚窗口，RUNBOOK 记录                             |
| 单币种、无价格编辑                                                | 已由 OQ-20 批准；币种变更不支持需确认                                          | 批准                                                       |
| 单模块 48h 与 18 条 AC 全归 M04（DEC-0024=B）                     | 改变模块范围、验收顺序与交付节奏，属产品/交付决策                              | 已批准：不拆、17 个实施任务、契约保持 `1.3.0-m04`          |
| 独立 fingerprint pepper（`RICCON_CREDENTIAL_FINGERPRINT_PEPPER`） | 新增部署 Secret；缺失时 API 启动失败                                           | 批准；B1b/B10 注入与轮换说明                               |
| `/actuator/prometheus` 内网暴露与 Micrometer 依赖                 | 新增运行时依赖与观测端口，需确认安全边界                                       | 批准；仅内网/受控，B1c 依赖/指标、B2 暴露                  |
| AC-17 perf profile（50 并发/短超时/池大小）                       | 与生产默认不同，需要在报告中记录环境；否则 AC-17 不可判定                      | 批准 perf profile；生产默认不承诺 3s 失败注入              |
| M04 首发配额开关先 false 再打开                                   | 避免上线即拒绝；改变默认执行时点                                               | 批准；B10 RUNBOOK 与 M04 评审包记录                        |

若用户不批准上述任一项，M04 需回到范围决策；**不得**以“实现时再说”绕过。

## 13. 评审响应矩阵（REV-M04-OPS-001..006、REV-M04-ARCH-001..005；DEC-0024=B 收口）

> 本矩阵记录历次修订；ADR 已于 2026-11-20 转 `accepted`（REV-M04-ARCH-005 pass、REV-M04-OPS-006 pass）。**REV-M04-ARCH-003/004 与 REV-M04-OPS-004/005 是历史轮次记录（其中拆分/分段方案已被 DEC-0024=B 撤销），不再作为当前方案依据**；当前口径以 DEC-0024=B 单模块 48h 为准。`fixed` 表示设计/契约已改，验证仍由后续任务执行；`superseded` 表示该行描述的分模块/分段方案已被 DEC-0024=B 撤销，正文以单模块口径为准；`closed` 表示前置决策已完成；`deferred` 表示需要用户/Lead/Product 决策或后续模块；`rejected` 附证据与影响。

| finding     | verdict    | 修改位置                                                                                                                                                                                                                                     | 验证方式                                                                                                                       |
| ----------- | ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| OPS-M04-001 | fixed      | §3.2 五步顺序；§10.3 回滚先 DROP 新 CHECK；B1a 迁移测试种子                                                                                                                                                                                  | V10→V11 升级 + 多 key 回滚测试（含 RETIRED）                                                                                   |
| OPS-M04-002 | fixed      | 新增 B1c（Micrometer/Prometheus、指标/`metrics-check`），Actuator 暴露归 B2；§10.6                                                                                                                                                           | `scripts/ops/metrics-check` 断言 Worker 7 个路由/降级/key 指标；V2 抓取配额/成本 2 个主指标 + 3 个辅助指标                     |
| OPS-M04-003 | fixed      | §3.3 移除 `model_invocation` CHECK；§10.1 DDL 算法/大表判据                                                                                                                                                                                  | 迁移测试 + 大表预检；应用层校验用例                                                                                            |
| OPS-M04-004 | fixed      | §10.3 先收敛/归档多 key 再恢复旧唯一键；多 key 种子                                                                                                                                                                                          | 单 key/多 key 回滚演练（2 ACTIVE + 1 INVALID）                                                                                 |
| OPS-M04-005 | fixed      | §10.3 改 `ignore-migration-patterns=*:future`（默认）；旧 API 镜像演练                                                                                                                                                                       | 旧 API 连 V11 库启动 + health UP + 历史可读                                                                                    |
| OPS-M04-006 | fixed      | §10.2 五段式：preflight→备份→迁移 Job→新 API→新 Worker→开关；drain 语义                                                                                                                                                                      | RUNBOOK 演练 + 迁移 Job 失败注入                                                                                               |
| OPS-M04-007 | fixed      | §6.5 API current/previous/id/canary；B1a 配置（含 test profile）、B1b 测试；B8 run.mjs；B10 rewrap/四步                                                                                                                                      | API/Worker 启动失败用例；rewrap 演练                                                                                           |
| OPS-M04-008 | fixed      | §2.1 测试 profile/run.mjs 同一 env；B1a 配置/B2/B8/B10；preflight 指纹                                                                                                                                                                       | 无 env 启动失败；API/Worker 指纹一致                                                                                           |
| OPS-M04-009 | fixed      | B8 创建 k6/CI perf job/env；§11；AC-17 perf profile                                                                                                                                                                                          | `k6 run e2e/perf/mvc-smoke.js` 退出码 + env 记录                                                                               |
| OPS-M04-010 | fixed      | B10 `seed-model-catalog`/`update-model-price`；RUNBOOK 流程                                                                                                                                                                                  | dry-run diff；历史快照不变                                                                                                     |
| OPS-M04-011 | fixed      | §6.5 备份/密钥同生命周期；B10 restore-drill/RPO/RTO                                                                                                                                                                                          | 恢复演练 + 一行 credential 可解密                                                                                              |
| OPS-M04-012 | fixed      | §3.3/§10.1 删除 cost_status 更新；V11 不回溯 cost_day，历史 NULL 计入 `unbucketedCalls`；B10 可选 backfill Job                                                                                                                               | 历史 NULL 允许；M04 后新调用非空；B10 回填进度记录                                                                             |
| OPS-M04-013 | fixed      | ADR-0022 §2.4 `unbucketedCalls`；成本查询口径；RUNBOOK 混合窗口                                                                                                                                                                              | 回滚窗口调用计数不消失                                                                                                         |
| OPS-M04-014 | fixed      | ADR-0022 §1.3 改 `ON DUPLICATE KEY UPDATE`；无自动重试；指标                                                                                                                                                                                 | 50 并发 1/49/0；锁等待/死锁指标                                                                                                |
| OPS-M04-015 | fixed      | §3.3 删除重叠索引；显式 provider 索引；保留/清理顺序                                                                                                                                                                                         | information_schema 索引断言；容量估算                                                                                          |
| OPS-M04-016 | fixed      | §10.3 回滚前收敛 key；B10 WARN/工单；B5 种子按 fingerprint 幂等                                                                                                                                                                              | 回滚演练；重复 seed 不新增 key                                                                                                 |
| OPS-M04-017 | deferred   | M09 交付 deploy_preview/deploy_prod；M04 评审包标 not-applicable                                                                                                                                                                             | Lead 在 gate 说明；M04 用一次性容器/真实栈                                                                                     |
| OPS-M04-018 | fixed      | §10.5 env 实际变化才审计、`/actuator/info`；统一 `routing.*`；币种不可变                                                                                                                                                                     | 启动审计条数；RUNBOOK 开关表                                                                                                   |
| F-001       | fixed      | 契约 provider PUT 路径 `{providerId}`；10 写端点补 `CsrfToken`；AuditTargetType 三值；B2 契约唯一 writer                                                                                                                                     | path/CSRF 脚本检查 + 契约测试                                                                                                  |
| F-002       | fixed      | §7.4 受限数组 + action nullable；B1a 两份 AuditDetailsPolicy                                                                                                                                                                                 | 20 模型链保存；首次配额 `previousLimit=null`；fail-closed                                                                      |
| F-003       | fixed      | 契约拆 `TenantCostReport`/`PlatformCostReport`；平台无用户字段；ADR-0022 §2.4                                                                                                                                                                | `TEN-033`；平台 schema 断言；租户明细展开                                                                                      |
| F-004       | fixed      | 契约 `priceCurrency` 可空；三类状态                                                                                                                                                                                                          | `IT-COST-102` 三类调用 schema 通过                                                                                             |
| F-005       | fixed      | §5.2 内层 maxAttempts=1；两套 timeout profile；attempt 计数                                                                                                                                                                                  | fake provider 精确 HTTP/attempt 数；k6 p95                                                                                     |
| F-006       | fixed      | B1c + B8 perf env（50 streams/pool 25）；AC-17 报告                                                                                                                                                                                          | `/actuator/prometheus` 抓取；k6 无 QUEUE-429                                                                                   |
| F-007       | fixed      | §8.3 补 run.mjs/RoleDefinitions/client.ts/types.ts/test profile/contracts 的 owner                                                                                                                                                           | write_scope 对照；真实栈无手工 env 启动                                                                                        |
| F-008       | fixed      | ADR-0022 §1.3 安全 upsert/独立事务审计/死锁处理；B6 验收                                                                                                                                                                                     | 真实 MySQL 1 成功/49 个 429/无 500；审计故障 500                                                                               |
| F-009       | superseded | DEC-0024=B 撤销拆分：§8.4 改为单模块 48h、18 条 AC、17 个实施任务；契约保持 `1.3.0-m04` 全量                                                                                                                                                 | Lead 模块/任务 JSON 校验；模拟 gate proof                                                                                      |
| F-010       | fixed      | §3.2 HMAC pepper（`RICCON_CREDENTIAL_FINGERPRINT_PEPPER`）；B1b/B10                                                                                                                                                                          | pepper 缺失启动失败；DB dump 无法离线验证                                                                                      |
| F-011       | fixed      | §10.5 env 名称/默认值；ADR-0022 §1.3 检查开关                                                                                                                                                                                                | env 启动 `GET switches` source；关闭配额 50 并发全准入                                                                         |
| F-012       | fixed      | §8.3 B9a/B11a/B11b 逐条绑定 AC-07/11/13/14/15；§11.1 目录                                                                                                                                                                                    | Playwright 四页断言 + axe + 截图                                                                                               |
| F-013       | fixed      | §2.2 denylist 扩展 + HostResolver seam                                                                                                                                                                                                       | 固定 map 单测覆盖编码 IP/IPv6-mapped/末尾点                                                                                    |
| F-014       | fixed      | §4.1 BASELINE 复用 allowlist/key 池                                                                                                                                                                                                          | `IT-MODEL-109` 未允许端点 + BASELINE → 503                                                                                     |
| F-015       | fixed      | ADR-0022 §2.2 明确 6 位计算精度/合计未按展示单位舍入；固定样例                                                                                                                                                                               | `IT-COST-101` 固定 token/价格；Product 复审确认                                                                                |
| F-016       | fixed      | §11.1 测试 ID 目录；SEC-M04-006 定义；SEC-MODEL-003 边界                                                                                                                                                                                     | 目录逐条绑定；ID 唯一性 grep                                                                                                   |
| F-017       | fixed      | §7.4 `emitted`；Worker `appendSystem`/SYSTEM/platform                                                                                                                                                                                        | `IT-MODEL-104/105` 审计 actor/tenant                                                                                           |
| F-018       | fixed      | §6.1/B4 删除 `ModelRepository` 租户回退                                                                                                                                                                                                      | 租户级凭据被忽略的负向测试                                                                                                     |
| F-019       | deferred   | R-21/R-22 与“定稿”措辞已在 ADR/ARCHITECTURE 修正；ADR-0012 旧 ADR 不改（写入边界），PRODUCT/ACCEPTANCE 归 Lead/Product                                                                                                                       | ADR-0021 §3 全局表例外 + ARCHITECTURE；Lead/Product 更新文档                                                                   |
| F-020       | fixed      | 契约 `RateLimitedProblem` 判别 429、`/costs/invocations` 404、`EmployeeModel` 无价格；B9a audit UI                                                                                                                                           | 契约测试；`TEN-033`；员工目录无价格键                                                                                          |
| F-021       | superseded | §8.4 按 3 builder 重算：关键路径 ≈28h、人时 ≈52–56                                                                                                                                                                                           | Lead 排期与 module wall clock 对齐                                                                                             |
| F-022       | fixed      | B9a audit 7 天预设；B11a E2E；§11.1                                                                                                                                                                                                          | E2E 默认 7 天 + `action`/`targetType` 过滤                                                                                     |
| F-023       | fixed      | §10.3 多 key 先收敛/归档；单/多 key 演练                                                                                                                                                                                                     | 多 key 回滚预期失败并记录；收敛后成功                                                                                          |
| F-024       | fixed      | B5 DTO toString/错误不回显；SEC-M04-005 canary                                                                                                                                                                                               | 成功/失败/校验失败路径扫描                                                                                                     |
| F-025       | fixed      | §3.3 保留/清理顺序；ADR-0022 §2.5；B10 容量估算                                                                                                                                                                                              | 故障注入行数/查询 p95；M09 清理计划                                                                                            |
| F-026       | fixed      | §8.5 高风险任务独立 Verifier；V2 只读 perf；B11 `@a11y`                                                                                                                                                                                      | task review 引用；V2 不修改 B8；a11y 四页                                                                                      |
| R-01        | fixed      | §8.3 V1/V2 测试归属；§11.1 拆 `SEC-M04-003a/b`、`E2E-M04-07a/b`；V2 write_scope 为 `security/m04verify/**`                                                                                                                                   | V1 跑 AC-01、AC-03..16 并为 AC-02 取证；V2 判 AC-02 与 AC-17/18、003b、TEN-032/033、IT-QUOTA/COST、07b；ID 唯一性 grep         |
| R-02        | fixed      | B1c 移除 SecurityConfig（归 B2，理由：B2 已负责端点/SSRF 安全边界）；B8 移除 `ci.yml`（归 B10，理由：CI/CD 属 Ops）；B1c 保留 pom/metrics-check/负向测试；B10 加 CI perf job                                                                 | B1c 不改生产安全代码；B2 验收默认拒绝不放宽；B8 脚本本地/CI 可运行；B10 负责 CI job（nightly + 发布前必跑，manual 仅本地预检） |
| R-03        | superseded | DEC-0024=B 后取消分段任务：B2 独占契约并同时交付 108a/108b；实施任务 17 个                                                                                                                                                                   | `IT-MODEL-108a` + `IT-MODEL-108b`；M02/M03/M10 零回归                                                                          |
| R-04        | fixed      | B11a 用 `E2E-M04-07a`（路由/key 两页）；B11b 用 `E2E-M04-07b`（四页）；AC-13/14 由四页在同一模块内判定                                                                                                                                       | Playwright 07a/07b 分别执行；axe 两页/四页；AC-14 只在 07b 完整断言                                                            |
| S-01        | fixed      | §3.3/§10.1 删除 `fk_model_invocation_provider`，`provider_id` 改为逻辑引用+索引；迁移测试改为“无 FK”断言                                                                                                                                     | V11 迁移测试：`information_schema` 无该 FK；M04 无 ERROR 1846                                                                  |
| S-02        | fixed      | §10.3 `UPDATE ... WHERE status IN ('DISABLED','INVALID')` 保留 ACTIVE；演练按 `(tenant_id, provider_id, credential_type)` **每组 1 行；组内若有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED；不写死总行数**                                       | 多 key 回滚探针：每组 1 行且优先 ACTIVE；旧 Worker 取到 key                                                                    |
| S-03        | fixed      | §3.2/B1a 种子两阶段：V10 合法（1 ACTIVE+1 RETIRED 跨 provider）→ V11 后 helper 造 2 ACTIVE+1 INVALID                                                                                                                                         | V10→V11 升级测试无 1062/3819；多 key 回滚从 V11 后开始                                                                         |
| S-04        | fixed      | ADR-0021 §3.3/§10.1、ADR-0022 §5：V11 不回溯 `cost_day`；历史 NULL 计入 `unbucketedCalls`；B10 可选 backfill                                                                                                                                 | 结构校验允许历史 NULL；M04 后新调用非空；B10 进度记录                                                                          |
| S-05        | superseded | 发布/演练并入 B10：V11 种子 `quotaEnforcementEnabled=false`，B10 演练后 `PUT true`，gate 以 true 取证                                                                                                                                        | B10 RUNBOOK；开关演练                                                                                                          |
| S-06        | fixed      | §10.6 指标统一 `riccon_*`；`riccon_quota_usage_ratio{scope,window}` 删除 tenant 标签；B1c/metrics-check/B-20（原 B-20a）                                                                                                                     | metrics-check 断言 M04 7 个 `riccon_*`；无 tenant 标签                                                                         |
| S-07        | fixed      | B1a write_scope/验收加入 `WorkerIntegrationTestBase`；DEMO+端点+主密钥；B1a 验收上下文启动                                                                                                                                                   | Worker 集成测试上下文启动通过                                                                                                  |
| S-08        | superseded | THREAT §8 合并为 B-18/B-19/B-20；§10 单模块核对                                                                                                                                                                                              | M04 gate 引用 B-18/B-19/B-20                                                                                                   |
| S-09        | superseded | DEC-0024=B 后 18 条 AC 全归 M04；V1/V2、§11.1、B11a/B11b 按单模块同步                                                                                                                                                                        | M04 acceptance 含 AC-01..18                                                                                                    |
| S-10        | fixed      | B1a 验收+V1 scope 增加 quota/cost/attempt 结构+空表 INSERT/ROLLBACK+PK 顺序；B6 验收绑定 upsert 形状                                                                                                                                         | B1a/V1 结构冒烟；B6 upsert/条件 UPDATE 断言                                                                                    |
| S-11        | fixed      | §2.2/§10.6 增加 `RICCON_OPS_ALLOWED_CIDRS`（空=loopback）；B2 实现、B1c 负向测试                                                                                                                                                             | 非允许来源 403/404；loopback 可抓取                                                                                            |
| S-12        | fixed      | §2.1/§10.5 定义 `/actuator/info` 键：deploymentMode/endpointSetHash/masterKeyId/currency/routingMode/failoverEnabled/quotaEnforcementEnabled；ARCH-004 增补 `info.riccon.quota.*` 四键；B1a+B10                                              | preflight 比对两侧 info 键                                                                                                     |
| S-13        | fixed      | §10.3 增加 `provider_credential_rollback_archive` DDL、`DELETE flyway_schema_history WHERE version=11`、repair                                                                                                                               | 回滚演练 MAX=10；归档表存在                                                                                                    |
| S-14        | fixed      | §3.2 legacy fingerprint 仅历史标识、不参与新去重；同一历史 key 重新录入需人工核对                                                                                                                                                            | 文档语义 + B5 负向测试                                                                                                         |
| S-15        | fixed      | B1a/V1 结构冒烟绑定 B6 的 `quota_usage` PK/upsert 形状；B6 验收显式断言                                                                                                                                                                      | B6 50 并发 + V11 结构断言                                                                                                      |
| S-16        | fixed      | B1c 测试收窄 `actuator/**`；B2 收窄 model/config；B4/B7 收窄 model/chat/cost；B2 依赖 B1c                                                                                                                                                    | write_scope 无 `src/test/**` 泛化；B2 等 B1c                                                                                   |
| S-17        | fixed      | B8/B10 验收记录 host CPU/内存、镜像 tag、k6 版本、脚本 sha、启动命令；perf 不启 OpenSearch 或另计主机                                                                                                                                        | PERF 报告含环境快照                                                                                                            |
| S-18        | closed     | DEC-0024=B 已答复（2026-11-20）；§8.4 不再有分段/拆分验收前置                                                                                                                                                                                | Lead 调度；M04 gate 使用 B 答复                                                                                                |
| S-19        | fixed      | B10 写范围收窄为 `deploy/compose/docker-compose.test.yml` + 明确 scripts/ops 前缀；deploy_preview/prod 归 M09                                                                                                                                | write_scope 无 `deploy/**`                                                                                                     |
| S-20        | fixed      | §6.5 pepper 统一：生产 API/Worker 缺失即启动失败；丢失不能新建/去重，恢复后解密不受影响                                                                                                                                                      | 启动失败用例；pepper 恢复用例                                                                                                  |
| S-21        | fixed      | §10.4 增加 V11 失败恢复矩阵（失败点/残留/恢复）                                                                                                                                                                                              | 迁移失败注入演练                                                                                                               |
| S-22        | superseded | §11.1 的 `IT-MODEL-108a`/`108b` 同为 B2 交付的契约测试分组                                                                                                                                                                                   | 契约测试 108a/108b；M02/M03/M10 零回归                                                                                         |
| S-23        | fixed      | ARCH/THREAT 统一超时口径：生产 60s、perf 2.5s、默认 3/硬上限 5                                                                                                                                                                               | 文档 grep 无 20s                                                                                                               |
| S-24        | fixed      | §8.5 高风险评审清单加入 B3                                                                                                                                                                                                                   | B3 done 前独立 REV-TASK                                                                                                        |
| S-25        | superseded | V2 依赖改为 B6/B7/B10/B11b/B8/V1；契约无独立任务                                                                                                                                                                                             | 任务表与依赖图一致                                                                                                             |
| S-26        | fixed      | B1c/B10 的 `scripts/ops` 按前缀分工；同 owner 串行                                                                                                                                                                                           | write_scope 无重叠                                                                                                             |
| S-27        | fixed      | M04 文档旧 `SEC-MODEL-003/004/005/006` 改为 `SEC-M04-003a/007/008/009`；新增 007/008/009 定义                                                                                                                                                | §11.1/THREAT ID 唯一且 AC 可映射                                                                                               |
| S-28        | fixed      | §7.4 `model.routing.updated` details 增加 `result`                                                                                                                                                                                           | 白名单测试；审计保存 200                                                                                                       |
| S-29        | superseded | V11 种子 false；B10 验收强制 false→true 演练，gate 以 true 取证                                                                                                                                                                              | false→true 演练                                                                                                                |
| S-30        | fixed      | ADR-0021 对 ADR-0012 §4 的 M04 预期作限制/覆盖说明；ARCHITECTURE 同步；旧 ADR 正文不改                                                                                                                                                       | 文档 review；Builder 按 ADR-0021                                                                                               |
| S-31        | superseded | 单模块：B6 只依赖 B1a、B7 依赖 B4，无需跨模块等待                                                                                                                                                                                            | 模块/任务依赖检查                                                                                                              |
| S-32        | closed     | DEC-0024=B 已答复；Lead 创建 17 个任务即可，`.riccon/modules/M04.json` 保持 18 AC/48h                                                                                                                                                        | Lead 动作；本 ADR 提供 acceptance 文本                                                                                         |
| S-33        | superseded | B10 独占 RUNBOOK 与演练；V2 依赖 B10                                                                                                                                                                                                         | V2 评审包引用 B10 RUNBOOK                                                                                                      |
| T-01        | fixed      | §10.3 回滚断言改为按 `(tenant_id, provider_id, credential_type)` **每组 1 行；组内若有 ACTIVE 则保留 ACTIVE，否则保留 RETIRED；不写死总行数**；允许 fixture 为 1 ACTIVE + 1 RETIRED；B1a/B10 验收同步                                        | 回滚演练按分组统计；旧 Worker 取到 ACTIVE key 并完成 J1                                                                        |
| T-02        | fixed      | §10.2 第 6 步默认 `ROUTED/true/false`；§10.5 种子/默认值；观测覆盖路由/降级/key/链空/5xx 与配额拒绝；B10 演练后 `PUT true`；ADR-0022 §8、B10 验收同步                                                                                        | 文档中无配置性 `true/true`；RUNBOOK 有配额拒绝观测；B10 验证后显式 `PUT true`                                                  |
| T-03        | fixed      | §10.6 指标拆分：路由/降级/key 7 个（B4 注册）+ `http.server.requests`；配额/成本 2 个主指标（B6/B7 注册）+ 3 个辅助；B1c `metrics-check` 只断言 7 个，V2 断言 2+3；新增 `OPS-M04-02a`（Worker 7）/`OPS-M04-02b`（API 2+3）；THREAT B-20 同步 | `metrics-check` 7 个通过；V2 抓取 2+3；无“gate 9 指标”要求                                                                     |
| T-04        | superseded | §8.1 17 行与“17 个实施任务”一致；发布/演练并入 B10                                                                                                                                                                                           | 表格行数=17                                                                                                                    |
| T-05        | fixed      | ARCHITECTURE “迁移与回滚”删除 V11 分批回填，改为“V11 不回溯 `cost_day`；历史 NULL 计 `unbucketedCalls`；B10 在 CHECK 后独立分批回填”                                                                                                         | ARCH 与 ADR-0021/0022 文字一致；B10 Job 验收                                                                                   |
| T-06        | fixed      | 第三轮 F-019（与首轮同号不同事）：§11.1 补 `SEC-M04-007/008/009` 定义、owner（V1/B2，009 含 B9a）与证据列；确认目录无重复                                                                                                                    | §11.1 ID 唯一性检查；V1 验收可映射                                                                                             |
| T-07        | superseded | THREAT §10 单模块核对 TM-39..TM-54                                                                                                                                                                                                           | 核对清单逐条归属 M04                                                                                                           |
| T-08        | superseded | 配额开关演练 owner 统一为 B10                                                                                                                                                                                                                | ADR-0022 §8 与 B10 验收一致                                                                                                    |
| T-09        | fixed      | ARCHITECTURE 评审状态表按当前单模块状态更新：Lead `pass`；Verifier `pending（待 REV-M04-ARCH-005；REV-004 fail，1 P1 修复中）`；Ops `pass（REV-M04-OPS-005，6 P2 open）`；用户 `pass（DEC-0024=B）`；安全 `pending（随 ARCH-005 复核）`      | 状态表逐项核对                                                                                                                 |
| T-10        | fixed      | B1a 验收逐字加“`information_schema` 无 `fk_model_invocation_provider`；`model_invocation` 无新增 CHECK；加列 INSTANT/索引 INPLACE”；V1 增加无 FK 断言                                                                                        | 迁移测试断言；V1 scope 含无 FK                                                                                                 |
| T-11        | fixed      | B10 验收加 `backfill-cost-day` 干跑/进度/幂等；§10.6/B-20 补 `riccon_quota_over_limit_observed_total`（如适用）                                                                                                                              | B10 工具验收；配额/成本指标抓取                                                                                                |
| T-12        | superseded | §8.4 给出 B10 吸收演练后不在关键路径；单模块 ≈52–56 人时/48h 可覆盖                                                                                                                                                                          | 任务表/关键路径一致                                                                                                            |
| T-13        | superseded | B10 独占 compose 与 RUNBOOK；不再有第二个 Ops writer；§10.6 统一“registry 归 B1c、暴露归 B2”                                                                                                                                                 | write_scope 无同文件冲突；措辞一致                                                                                             |
| T-14        | fixed      | §10.6 明确 `riccon_model_requests_total` 复用 ADR-0008 名称并扩展为 `{provider,model,outcome}`，B4 唯一注册；ADR README 记录，不定义第二个同名指标                                                                                           | 单注册点；metrics-check 标签断言                                                                                               |
| DEC-0024    | fixed      | DEC-0024=B（2026-11-20）撤销拆分：单模块 48h、18 条 AC 全归 M04、17 个实施任务；§8/§10.2/§10.5/§10.6/§11.1/§12 与 ADR-0022/ARCHITECTURE/THREAT-MODEL/README 同步；契约保持 `1.3.0-m04` 无分段                                                | 目标文档 grep 无分模块/分段残留；Lead 创建 17 个任务                                                                           |

### 13.1 REV-M04-ARCH-004 / REV-M04-OPS-005 响应（2026-11-20）

> ARCH-004 的 F-001..F-013 与本表前段的 F-* 编号不同轮次，故加 `A4-` 前缀避免歧义。`fixed` 只表示设计文本已改；是否通过由 REV-M04-ARCH-005 独立判定。

| finding     | 状态     | 修改位置                                                                                                                           | 验证方式                             |
| ----------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| A4-F-001    | fixed    | §8.1/§8.3/§8.4/§8.5：AC-02 整条归 V2；V1 只判 15 条并提供 `SEC-M04-003a`/`TEN-031`/`IT-MODEL-102`；§8.4 新增 AC→V1/V2 映射表       | REV-005 核对映射；V1/V2 任务验收     |
| A4-F-002    | fixed    | §8.3 B11a/B11b、§8.5、§11.1 07a/07b：五态 + 三条键盘路径 + 375px + axe；V1 同时列 07a 与 07b                                       | REV-005 文本核对；B11b/E2E 验收      |
| A4-F-003    | fixed    | §8.3 V2：最终提交全量独立 test/coverage/E2E/a11y/security + 抽样重跑 8 条 AC                                                       | V2 证据与 V1 分开取证                |
| A4-F-004    | fixed    | THREAT §8 新增 B-21（`PERF-M04-001/002` + `OPS-M04-01`）；M04 gate 要求 B-18/B-19/B-20/B-21                                        | REV-005；V2 取证                     |
| A4-F-005    | fixed    | THREAT §10.4：M04 gate acceptance = PRODUCT §10.3 AC-01..18；安全 gate = B-18..B-21；TM/指标/UI 为补充核对                         | REV-005                              |
| A4-F-006    | deferred | PRODUCT.md:31 的已撤销拆分 ID 字面；PRODUCT 不在本任务写入范围，由 Product 任务（TASK-0098）改口径                                 | Product/Lead 后续文档任务            |
| A4-F-007    | fixed    | ARCH 评审表：ARCH-004 fail（1 P1，修复中）/ OPS-005 pass（6 P2 open）；Verifier 待 ARCH-005；§13 T-09 同步                         | REV-005 核对                         |
| A4-F-008    | fixed    | §8.1 B10 依赖补 B3/B4/B5；§8.2 依赖图与隐藏依赖说明                                                                                | Lead 派单前核对                      |
| A4-F-009    | deferred | EV-0522/0523 覆盖不足属历史证据；由后续证据任务重录                                                                                | Lead/任务 owner                      |
| A4-F-010    | fixed    | §13 S-02/T-01 统一为“每组 1 行、优先 ACTIVE、否则 RETIRED、不写死总行数”                                                           | 回滚演练                             |
| A4-F-011    | fixed    | §11.1 增加 `SEC-MODEL-002` → `SEC-M04-001/002/009` + `IT-MODEL-107` 映射；PRODUCT 文本归 Product/Lead                              | Product 同步                         |
| A4-F-012    | fixed    | §8.3 B1a write_scope 补 `apps/worker/src/test/resources/**`                                                                        | write_scope 核对                     |
| A4-F-013    | deferred | EV-0522/0523 recorder=lead 属历史证据；需 Lead 在 note 说明或由 owner 重录                                                         | Lead                                 |
| OPS-M04-042 | fixed    | §13 S-02/T-01 与 §10.3 统一分组断言                                                                                                | 回滚演练                             |
| OPS-M04-047 | fixed    | §11.1 拆 `OPS-M04-02a`（Worker 7）/`OPS-M04-02b`（API 2+3）；§8.3 V2 与 THREAT B-20 引用更新                                       | V2 抓取报告；REV-005                 |
| OPS-M04-048 | fixed    | ADR-0022 §1.1/§1.3 命名 4 个默认配额键；ADR-0021 B1a/B10/§10.2 preflight 与 `/actuator/info` 漂移检查                              | B1a 配置键；B10 演练                 |
| OPS-M04-049 | fixed    | §8.3 B10 回滚绑定 `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false`、固定 WARN、工单；V2 独立重放                                    | B10 演练；V2 证据                    |
| OPS-M04-050 | fixed    | §6.5/§10.3/§8.3 B10：RPO ≤24h、RTO ≤4h；对账容差金额差 0.00 CNY、调用量差 ≤0.1%                                                    | B10 restore-drill；对账演练          |
| OPS-M04-051 | fixed    | §8.4 B10 ≈6h 与 8h 窗口说明；48h 仍有约 20h 余量                                                                                   | Lead 派单前确认                      |
| OPS-M04-052 | fixed    | §8.3 B10 quota 演练补真实超限（限额=已用量+1 → 429/审计/指标 → false 只计量 → 恢复 → true）                                        | B10 演练记录                         |
| OPS-M04-053 | fixed    | §10.2 标题/正文统一“preflight + 六步（0–6）”；drain 上限 5 分钟与在途 run 判据                                                     | RUNBOOK 演练                         |
| OPS-M04-054 | fixed    | §8.3 B10：CI perf nightly + 发布前必跑；manual gate 仅本地预检                                                                     | CI 配置与 perf 报告                  |
| OPS-M04-055 | fixed    | ARCH 评审表更新；ADR 已用 07a/07b、108a/108b（PRODUCT 文本归 Product/Lead）                                                        | REV-005                              |
| OPS-M04-056 | fixed    | `docs/ACCEPTANCE.md:59/61` 已由 TASK-0098（`18226e3`）改为“R-11、M13 F-001 不承接，归 M09/M10”；ADR §10.3 与 ARCHITECTURE:169 一致 | ACCEPTANCE 文本核对；REV-M04-OPS-006 |

### 13.2 REV-M04-ARCH-005 / REV-M04-OPS-006 收口（2026-11-20）

> 两轮评审 verdict 均为 `pass`，ADR-0021/0022 据此转 `accepted`。下表是评审后新增的 P3 文档同步项，由 TASK-0099 关闭；不改变 18 条 AC、DEC-0024 范围或契约 `1.3.0-m04`。

| finding     | 状态  | 修改位置                                                                                                                                                       | 验证方式                        |
| ----------- | ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------- |
| OPS-M04-057 | fixed | §10.3 去掉“建议”，固定 WARN 码 `RICCON-WORKER-002`（文案固定 `schema validation disabled`），与 B10/§10.3 通过条件、ARCHITECTURE:317、THREAT B-21:627 逐字一致 | 文本 grep；V2 回滚重放核对 WARN |
| OPS-M04-058 | fixed | §13 OPS-056 行改 `fixed`；ADR-0021/0022 的 Review-date 登记 REV-M04-ARCH-005 / REV-M04-OPS-006；`docs/adr/README.md` 与 `docs/ARCHITECTURE.md` 评审表同步      | 文档核对；REV-M04-OPS-006       |

> 未在本轮处理（仍是 P3 观察项，归 Lead/Architect 在 V1/V2 派单前核对）：ARCH-005 §5 的 PRODUCT AC-05/07 旧线索、V1 write_scope 缺 `.riccon/evidence/**`、AC-06 的 `PERF-M04-001` 归属、§8.3 标题“≤6 条/任务”。
