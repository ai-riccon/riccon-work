# ADR-0020：M10 审计查询、导出审批与平台边界

Status: proposed
Date: 2026-11-19
Reviewers: `riccon/lead`（完整性/合同一致性）、`riccon/verifier`（可测试性/越权与导出安全）、`riccon/ops`（索引/备份/保留/回滚）；REV-M10-ARCH-001（2026-11-19，fail；本轮修订 F-003/004/005/008/023 等）；独立评审记录写入 `docs/ARCHITECTURE.md` 评审状态表
Approved-by: pending（DEC-0013/DEC-0014 已确认后移与审批口径；ADR 本身未经复审，不自行标 `accepted`）
Review-date: 2026-11-19T23:59:59+08:00
Supersedes: none
关联：ADR-0007（审计事件模型）、ADR-0009（租户隔离）、ADR-0010（Flyway）、ADR-0012（数据模型/租户键）、ADR-0018（M03 部门与平台来源事件）、ADR-0019（M10 OIDC）、DEC-0011、DEC-0013、DEC-0014、DEC-0015、DEC-0017、PRODUCT §8 M10-AC-15..21

## Context

M02 已建立 `audit_event`（append-only、按月分区、触发器拒绝 UPDATE/DELETE）与最小事件清单（ADR-0007）；M03 新增部门/空间事件，但跨租户部门事件写入 `tenant_id=platform` + `details.targetTenantId`，目标租户不可见（M03 F-009，REV-M03-001）。M10 必须交付：

- 管理员按时间/动作/操作者/对象过滤并分页查看审计；只读；查询本身产生审计（AC-15）；
- 界面无修改/删除入口、接口拒绝写操作、普通员工无权限（AC-16）；
- 导出申请/审批：同租户另一管理员审批、申请人不得自批、单管理员租户由平台管理员审批；批准后下载有时效；导出、审批人、范围与行数写审计（AC-17）；
- 保留 ≥ 180 天；界面显示保留策略与可查范围；导出不含密码哈希、会话令牌、模型/MCP 凭据、文件原文；过期不可下载（AC-18）；
- 扩展越权套件到导出对象（AC-19 导出段、AC-20 新对象）与 OIDC/审计页面 a11y（AC-21）。

DEC-0013 明确：MFA、审计哈希链、平台管理员跨租户审计明细**后移**，M10 不得声称已实现。DEC-0014 明确导出审批人模型与导出时效。M03 F-009 要求 M10 显式写出平台来源事件的可见性结论。

M02/M03 只写审计、不提供查询 API；`audit.read` 权限码已在 V2 字典但未授予任何角色。`AdditionalAuditActions` 中的若干动作（`tenant.updated`、`session.deleted`、`message.sent` 等）尚未同步回 `platform-domain` 与 ADR-0007 白名单，而 M10 一旦开放查询，这些动作必须成为稳定词汇。

## Decision

### 1. 查询模型（只读）

**端点**：`GET /api/v1/audit-events`，权限 `audit.read`。

- 租户范围：只从已认证主体派生；查询条件不接受 `tenantId`。平台管理员查询时主体租户为 `platform`，只能看到 `tenant_id=platform` 的事件（平台自身的租户/账号/部门元数据操作），**不能**按路径或参数读取目标租户事件。
- 过滤器（全部可选，服务端组合）：
  - `occurredFrom`、`occurredTo`：ISO 8601 且必须带偏移（如 `2026-11-01T00:00:00+08:00`）；缺省为最近 24 小时；最大跨度 180 天；`occurredTo >= occurredFrom`；`occurredTo` 不得超过服务器时间 + 5 分钟；不接受无偏移时间。
  - `action`：精确匹配稳定动作名（大小写敏感）。
  - `actorId`：UUID 字符串，精确匹配 `actor_id`。
  - `targetType`：`AuditTargetType` 枚举值。
  - `targetId`：字符串（UUID 或服务标识），精确匹配。
  - `outcome`：`SUCCESS|FAILURE|DENIED`。
- 排序固定 `occurred_at DESC, event_id DESC`；不接受客户端排序字段，避免无索引排序。
- 分页：`page` 从 1 开始，`size` 默认 20、最大 100；`page` 最大 200；超过返回 400 `RICCON-AUDIT-400`，提示缩小时间范围或改用导出。深层 offset 的慢查询风险在 M09 前由该上限与 180 天范围约束。
- 响应 `AuditEventPage` = `PageMeta` + `items`；每行字段：`eventId`、`occurredAt`（带偏移的 ISO 8601）、`tenantId`、`actorType`、`actorId`、可选 `actorDisplayName`（读取时按当前 `app_user.display_name` 解析，仅 `USER`，非历史快照，不含邮箱）、`action`、`targetType`、`targetId`、`outcome`、`traceId`、`details`。**不返回 `source_ip`**（ADR-0007 §5：仅安全审计可见；M10 不提供字段选择）、不返回 `request_id`、不返回 `schema_version`。
- **actorDisplayName 批量解析（F-009）**：每页最多 100 行，必须用一次 `LEFT JOIN app_user` 或一次批量 `WHERE tenant_id=:tenantId AND id IN (:userIds)` 解析，禁止逐行查询；`IT-AUDIT-101` 用 100 行断言查询次数/耗时，不得引入 N+1。
- `details` 读取时执行**与写入相同的 action-aware 白名单**（API 与 Worker 的合法键合并为共享策略，见 §6.1），只允许该动作白名单内的键；白名单外的键、未知动作的非空 details、以及精确敏感键（`password`/`passwordHash`/`passwd`/`token`/`accessToken`/`refreshToken`/`idToken`/`clientSecret`/`secret`/`credential`/`cookie`/`authorization`/`prompt`/`content`/`email`/`phone`/`sourceIp`）命中即拒绝该次查询/导出并告警（500 `RICCON-AUDIT-500`，fail-closed），不静默剔除后继续。**禁止用 `token`/`secret`/`content` 子串匹配**：`promptTokens`、`completionTokens`、`sessionIdHash`、`paramsSha256`、`contentType` 是合法白名单键，模型事件不得因此失败（F-003）。
- 查询成功后在同一租户写 `audit.queried`，`details={filtersHash, rowCount}`；`filtersHash` 为规范化过滤条件的 SHA-256。查询事件在读取之后写入，不会出现在本次结果中。审计写失败使查询失败（fail-closed）。
- 只读保证：无创建/修改/删除审计的 API；数据库触发器继续拒绝 UPDATE/DELETE；`..audit..` 包外不得直接访问 `audit_event`（ArchUnit）。AC-16 的“接口拒绝写操作”以“无路由 + 404/405”验证，不新增 PATCH/DELETE。
- **策略端点**：`GET /api/v1/audit-events/policy` 返回 `retentionDays`（平台审计保留，最少 180，配置 `riccon.audit.retention-days` 低于 180 启动失败）、`maxRangeDays=180`、`pageSizeMax=100`、`maxPage=200`、`actions`（当前已知动作名列表，含 API 与 Worker 动作，供 UI 下拉）、`exportLimits`（`ttlHours`、`rowLimit`）。界面显示 `policy.retentionDays` 与可查范围，不写死“180 天”；固定文案为“审计至少保留 {retentionDays} 天，可查最近 180 天；无删除入口”。

### 2. 导出申请、审批与下载

**权限**：

- `audit.read`：查询；租户管理员与平台管理员（平台范围）。发起/下载导出仅租户管理员；平台租户导出在 M10 显式不支持（见下）。
- `audit.export.approve`：同租户审批；仅租户管理员。
- `platform.audit.export.approve`：平台管理员审批**单管理员租户**的导出；仅平台管理员。

**平台租户自身导出（F-023）**：`tenant_id=platform` 的 `POST /audit-exports`、列表/详情/下载在 M10 一律返回 403 `RICCON-AUDIT-403`；平台管理员可以查询 `platform` 审计，但不能申请/审批/下载平台租户导出。原因：平台租户只有平台管理员，没有独立的同租户审批人，自批会违反 DEC-0014。平台租户导出列为 M10+ deferred（需第二个平台管理员或独立审批模型），见 §10。

**申请**：`POST /api/v1/audit-exports`，body `{filter: {...}}`（`filter` 与查询过滤器同构；审批意见只在决策请求中出现）。

- 服务端计算 `estimatedRowCount`（与查询同一 SQL 条件）；超过 `rowLimit`（默认 10,000）返回 400 `RICCON-AUDIT-400`，提示缩小时间范围或过滤器；不接受客户端自报行数。
- 存规范化 `filters` JSON、`filters_hash`、`estimated_row_count`；状态 `PENDING_APPROVAL`；写 `audit.export.requested`。
- 请求体大小与 filters 字段长度受限；`occurredFrom/occurredTo` 规则同查询（最大 180 天、必须带偏移）。

**审批**：

- 租户端点：`POST /api/v1/audit-exports/{exportId}/decision`，body `{decision: APPROVE|REJECT, comment?}`，权限 `audit.export.approve`；审批人必须是本租户当前启用账号，且 `approverUserId != requestedBy`（自批 403 `RICCON-AUDIT-403`）。
- 平台端点（**仅单管理员租户**）：`GET /api/v1/admin/tenants/{tenantId}/audit-exports?status=PENDING_APPROVAL`、`POST /api/v1/admin/tenants/{tenantId}/audit-exports/{exportId}/decision`，权限 `platform.audit.export.approve`；只返回/决策导出请求元数据（申请人、过滤器、预计行数、申请时间），**不返回审计内容、不提供下载**。平台管理员不得成为申请人。
- **单管理员租户校验（F-004）**：平台列/决策前统计目标租户 `app_user.status='ACTIVE'` 且拥有 `TENANT_ADMIN` 角色的账号数；`>=2` 时列表与决策均返回 409 `RICCON-AUDIT-409`（提示在租户内由另一管理员审批），不得由平台审批；`<=1` 才允许。统计在目标租户作用域内实时查询，不做跨请求缓存。该规则同时写入 admin 端点契约描述与 B3 验收。
- 状态机：`PENDING_APPROVAL -> APPROVED | REJECTED`，条件更新 `WHERE status='PENDING_APPROVAL'`；并发审批只有一个成功，另一个 409 `RICCON-AUDIT-409`。批准时写 `approved_by`、`approved_by_tenant_id`、`decided_at`、`expires_at = decided_at + ttl`（默认 24h，配置上限 7 天）；拒绝时只写决定与时间，不设过期。
- **TTL 与 Clock（F-008）**：服务注入 `java.time.Clock`，`decided_at`/`expires_at`/默认查询时间都用同一 Clock；测试通过 `riccon.audit.export.ttl=PT2S`（或更短）与 `MutableClock` 前进时间验证过期，不允许 `Thread.sleep` 或直接依赖 24h；生产配置上限 7 天，小于等于 0 启动失败。
- 不能自批、不能跨租户审批、不能审批已决定/已过期请求；拒绝后可重新申请（新 ID）。
- 审计：`audit.export.approved` / `audit.export.rejected`，`details={exportId, decision, estimatedRowCount, targetTenantId?, occurredFrom, occurredTo, action?, actorId?, targetType?, targetId?, outcome?, filtersHash}`（只写非空过滤字段；值 ≤200，不写 `decisionComment` 原文；F-016）。租户审批写本租户；平台审批沿用 M03 口径写 `tenant_id=platform` + `details.targetTenantId`，**不写两条重复事件**。申请人从导出记录读取审批人与 `decisionComment`，而不是依赖目标租户审计（见 §3）。

**下载**：`GET /api/v1/audit-exports/{exportId}/download`。

- 只有**申请人本人**可下载；其他租户管理员（包括审批人）403 `RICCON-AUDIT-403`；跨租户/不存在统一 404 `RICCON-TENANT-404`。平台管理员调用租户下载端点时主体租户为 `platform`，按 404 处理（不泄露导出存在性，F-015）；admin 路径不提供下载。
- 无 `audit.read` 权限的既有拦截器返回 403 `RICCON-AUTH-403`；`RICCON-AUDIT-403` 仅用于“有权限但业务拒绝”（自批、非申请人、平台租户导出）。契约与测试按此区分。
- 状态必须 `APPROVED` 且 `expires_at > now`；`PENDING_APPROVAL`/`REJECTED` 409 `RICCON-AUDIT-409`；过期 410 `RICCON-AUDIT-410`（状态不改写，按时间派生；`status=EXPIRED` 过滤等价于 `status='APPROVED' AND expires_at < now`）。
- 下载前用服务端保存的 filters 重新计数并查询；实际行数超过 `rowLimit` 时拒绝 409 `RICCON-AUDIT-409`（要求收窄条件），不会输出截断文件。查询与行数都只按申请人租户过滤。
- **不落盘、不写对象存储**：以 `text/csv; charset=utf-8` 流式响应，`Content-Disposition: attachment; filename="audit-export-{exportId}.csv"`、`Cache-Control: no-store`、`X-Content-Type-Options: nosniff`。文件内容：
  1. UTF-8 BOM；
  2. 4 行 `#` 前缀元数据：`exportId`、`generatedAt`（带 `+08:00` 偏移）、`filters`（规范化 JSON）、`rowCount`；
  3. 空行；
  4. 表头：`eventId,occurredAt,tenantId,actorType,actorId,action,targetType,targetId,outcome,traceId,details`；
  5. 数据行，RFC 4180 转义，`details` 为 JSON 字符串。**CSV 公式注入防护（F-020）**：任何以 `=`、`+`、`-`、`@`、制表符或回车开头的字符串单元前置 `'`；`details` 以 `{` 开头但内部字段不受影响；`IT-AUDIT-106` 断言危险前缀被转义。
- 字段白名单固定；**不导出** `source_ip`、`request_id`、`schema_version`、密码哈希、会话令牌、模型/MCP 凭据、文件原文、提示词、邮箱；`details` 读取时执行与查询相同的 action-aware 白名单（§1），不是子串匹配。
- 每次成功下载写 `data.exported`，`details={exportId, scope:"audit", rowCount, filtersHash}`；导出失败/过期不写成功事件（可写失败审计或指标）。重复下载不设 M10 专用配额，沿用通用 API 限流并逐次审计；专用配额/缓存列 M10+（F-020）。
- **为什么不在对象存储保留导出文件**：M10 没有清理作业调度器；持久文件会引入对象 key、预签名 URL、过期删除与残留泄露面。流式导出让“过期”成为单纯的数据库时间判断，没有可被遗留读取的副本；代价是同一导出在有效期内重复下载会重新生成（结果受 append-only 新增事件影响，`occurredTo` 固定）。若客户要求“保留导出件/审计归档”，另立模块，用对象存储 + 生命周期 + 审批绑定 object key。

### 3. 平台管理员与跨租户审计边界

- **M10 不提供平台管理员跨租户审计明细**（DEC-0013）。平台管理员只能查询 `platform` 租户自身事件；不能通过 `/api/v1/audit-events`、导出、admin 端点或参数读取目标租户审计内容。
- **M03 F-009 的结论**：平台来源的部门/用户/租户事件继续写 `tenant_id=platform` + `details.targetTenantId`；目标租户的审计查询只按主体租户过滤，因此**目标租户管理员在 M10 看不到这些平台来源事件**。M10 不引入 `UNION` 读模型、不把 `targetTenantId` 作为查询参数、不复制事件到目标租户。目标租户仍可通过部门/空间的业务结果感知变更；平台侧事件由平台管理员在 `platform` 审计中查看。
- 未来若需要“目标租户可见的平台来源事件”，必须另立 ADR：显式授权模型（谁可见、哪些动作、是否脱敏）、二次确认、独立审计、查询索引与导出规则；在此之前任何 UI 或文档不得声称已可见。
- 单管理员租户的导出由平台管理员审批是**元数据审批**，不构成跨租户审计内容访问；审批记录与导出记录可显示审批人，但平台管理员不能下载。多管理员租户由平台端点返回 409，见 §2。
- **M03 F-009 状态**：这是 M10 的**承接结论与残余风险**，不是修复。M10 评审包、`docs/ACCEPTANCE.md` 与模块评审必须记 `residual/deferred`，不得记 `fixed`；后续显式授权 ADR 才是修复。F-009 的目标租户“看不到平台来源事件”在 M10 保持不变。
- 多租户审计查询的索引/分区不因平台审批改变；平台审批查询只走 `audit_export_request (tenant_id, status, created_at)`。

### 4. 保留、分区与索引

- 审计保留为平台级策略，至少 180 天（合同 `data.retention`、`constraints.compliance`），不随租户 `retention_days` 缩短。M10 无在线删除；到期清理由 M09 维护账号按分区 `DROP PARTITION`，支持 legal hold（M09）。
- `audit_event` 继续按月分区；现有索引 `(tenant_id, occurred_at)`、`(action, occurred_at)`、`(tenant_id, target_type, target_id, occurred_at)`、`(trace_id)`。
- M10 评估并新增两个查询索引（V10）：
  - `idx_audit_tenant_action_time (tenant_id, action, occurred_at)`：租户 + 动作 + 时间过滤；
  - `idx_audit_tenant_actor_time (tenant_id, actor_id, occurred_at)`：租户 + 操作者 + 时间过滤。
- 对象过滤复用 `(tenant_id, target_type, target_id, occurred_at)`；`outcome` 低基数，过滤后置。查询必须带时间范围，避免全分区扫描；最大 180 天与分区边界对齐。
- 分区表加索引会重建各分区；V10 在测试库用 V1–V9 种子数据验证；生产在 M09 维护窗口执行（或确认 MySQL 8.4 online DDL 可用）。迁移失败回滚见 §5.4。
- **Ops 评审前提（F-012）**：V10 生产执行前必须由 Ops 评审并记录：分区索引构建窗口/online DDL 结论、回滚 SQL 演练、worker `SchemaVersionChecker` 9→10 与 API/Worker 滚动顺序、`RICCON_OIDC_IDPS`/`RICCON_PUBLIC_BASE_URL` Secret 注入与轮换、`publicBaseUrl` 代理头、`audit_export_request` 的 M09 清理。Ops 结论不阻塞 B1/B2（无迁移），但阻塞 V10 生产执行与 M10 gate。
- 导出请求记录保留：随审计保留策略由 M09 清理（至少 180 天），不参与审计 append-only；删除导出请求不删除 `audit_event`。

### 5. 数据模型与迁移（V10，单迁移）

M10 只新增一个迁移 `V10__audit_query_export.sql`（编号接 V9；OIDC 不需要 schema 变更）。DDL 设计如下，Builder 落地前必须与 `TenantJdbc`/`PlatformJdbc` 约定一致：

#### 5.1 `audit_export_request`

```sql
CREATE TABLE audit_export_request (
    tenant_id             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请人所属租户',
    id                    BINARY(16) NOT NULL COMMENT '导出请求 ID（UUIDv7）',
    requested_by          BINARY(16) NOT NULL COMMENT '申请人账号 ID',
    status                VARCHAR(32) NOT NULL COMMENT 'PENDING_APPROVAL|APPROVED|REJECTED',
    filters               JSON NOT NULL COMMENT '规范化查询条件；不含 tenantId',
    filters_hash          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化 filters 的 SHA-256',
    estimated_row_count   INT NOT NULL COMMENT '申请时按 filters 计算的行数',
    approved_by           BINARY(16) NULL COMMENT '审批人账号 ID；可能是 platform 租户账号，逻辑引用',
    approved_by_tenant_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审批人所属租户；平台审批为 platform',
    decided_at            DATETIME(6) NULL COMMENT '审批时间（UTC）',
    decision_comment      VARCHAR(500) NULL COMMENT '审批意见；不进审计 details',
    expires_at            DATETIME(6) NULL COMMENT '批准后的下载截止时间（UTC）',
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '申请时间（UTC）',
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    KEY idx_audit_export_status (tenant_id, status, created_at),
    KEY idx_audit_export_expiry (tenant_id, status, expires_at),
    CONSTRAINT fk_audit_export_requester
        FOREIGN KEY (tenant_id, requested_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_audit_export_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_audit_export_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_audit_export_rows CHECK (estimated_row_count >= 0),
    -- 申请人不得自批；approved_by 是全局唯一 UUID，跨租户比较安全。
    CONSTRAINT chk_audit_export_self_approval CHECK (approved_by IS NULL OR approved_by <> requested_by),
    -- 状态与审批字段必须一致，避免“已批准但没有过期时间”。
    CONSTRAINT chk_audit_export_decision CHECK (
        (status = 'PENDING_APPROVAL'
            AND approved_by IS NULL AND approved_by_tenant_id IS NULL
            AND decided_at IS NULL AND expires_at IS NULL)
        OR (status = 'APPROVED'
            AND approved_by IS NOT NULL AND approved_by_tenant_id IS NOT NULL
            AND decided_at IS NOT NULL AND expires_at IS NOT NULL)
        OR (status = 'REJECTED'
            AND approved_by IS NOT NULL AND approved_by_tenant_id IS NOT NULL
            AND decided_at IS NOT NULL AND expires_at IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '审计导出请求与审批（M10）';
```

- `requested_by` 用复合外键（同租户）；`approved_by` 不用复合外键，因为平台管理员属于 `platform` 租户，无法满足目标租户的 `(tenant_id, approved_by)` 外键；权威操作者仍在 `audit_event.actor_id`。
- 不存导出文件、对象 key、预签名 URL；下载是流式生成。
- `filters` 只存结构化条件（时间、动作、actor、对象、结果），最多 4 KiB；`filters_hash` 用于审计关联。

#### 5.2 索引与权限

```sql
ALTER TABLE audit_event
    ADD KEY idx_audit_tenant_action_time (tenant_id, action, occurred_at),
    ADD KEY idx_audit_tenant_actor_time (tenant_id, actor_id, occurred_at);

INSERT INTO permission (code, description)
VALUES ('audit.export.approve', '同租户审批审计导出；申请人不得自批'),
       ('platform.audit.export.approve', '平台管理员审批单管理员租户的审计导出（仅元数据）');

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'audit.read' FROM role r
 WHERE r.code = 'TENANT_ADMIN';

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'audit.export.approve' FROM role r
 WHERE r.code = 'TENANT_ADMIN';

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'audit.read' FROM role r
 WHERE r.tenant_id = 'platform' AND r.code = 'PLATFORM_ADMIN';

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'platform.audit.export.approve' FROM role r
 WHERE r.tenant_id = 'platform' AND r.code = 'PLATFORM_ADMIN';
```

`RoleDefinitions` 同步：`TENANT_ADMIN` 增加 `audit.read`、`audit.export.approve`；`PLATFORM_ADMIN` 增加 `audit.read`、`platform.audit.export.approve`。`MEMBER` 不获得审计权限。

#### 5.3 向前兼容

- V10 只新增表、索引、权限与角色映射；不删列、不改类型、不重建 M02/M03 表。
- 旧版本代码忽略新表/索引/权限，可安全回滚；新版本代码依赖 V10，Flyway 在启动前完成迁移。
- Worker 不访问审计查询/导出；`SchemaVersionChecker.EXPECTED_SCHEMA_VERSION` 由 Builder 从 9 升到 10。
- 迁移测试必须在 V1–V9 种子数据上应用 V10（空库通过不算完成），断言表/索引/权限回填/CHECK；导出请求插入与并发条件更新至少各一条集成测试。

#### 5.4 回滚

首选“前向修复 + 备份恢复”。若必须回到 V9：

```sql
DROP TABLE audit_export_request;
ALTER TABLE audit_event
    DROP INDEX idx_audit_tenant_action_time,
    DROP INDEX idx_audit_tenant_actor_time;
DELETE FROM role_permission
 WHERE permission_code IN ('audit.read', 'audit.export.approve', 'platform.audit.export.approve');
DELETE FROM permission
 WHERE code IN ('audit.export.approve', 'platform.audit.export.approve');
```

执行前停止 API 写入并做全量备份；`flyway_schema_history` 的 V10 行由维护账号按 ADR-0010 处理，应用账号不得删除。回滚会丢失导出请求记录（操作数据），但 `audit_event` 不受影响；回滚演练记录进入 M10 评审包。**前提（F-022）**：M10 前 `audit.read` 没有授予任何角色，回滚删除其映射是安全的；若发布后存在手工授予，回滚前必须导出/记录这些映射，不能盲目删除。worker schema 期望版本从 10 回退到 9 时，必须先回滚 API 代码再滚动 worker，避免版本校验失败。

### 6. 审计动作、权限与错误码

#### 6.1 动作词汇同步与新增

M10 开放查询前，必须把 `apps/api/.../audit/domain/AdditionalAuditActions.java` 与 worker `WorkerAuditActions` 中已在写入的动作提升为 `platform/platform-domain` 的 `AuditActions` 常量，并同步 ADR-0007 白名单与 API `AuditDetailsPolicy`：

| 动作                    | 说明                     | details 合法键                                                    |
| ----------------------- | ------------------------ | ----------------------------------------------------------------- |
| `tenant.updated`        | 平台更新租户状态/保留期  | `status`、`retentionDays`                                         |
| `tenant.access.denied`  | 伪造租户标识被拒绝       | `attemptedTenant`、`reason`                                       |
| `session.deleted`       | 删除会话（软删除）       | `sessionId`                                                       |
| `message.sent`          | 发送消息并创建运行       | `userMessageId`、`assistantMessageId`、`runId`、`attachmentCount` |
| `message.stopped`       | 停止生成或客户端断开取消 | `messageId`、`runId`、`reason`                                    |
| `research.task.created` | 创建报告任务             | `researchTaskId`、`workspaceId`、`runId`、`mcpToolId`             |
| `artifact.downloaded`   | 获取产物下载地址         | `artifactId`、`version`                                           |
| `user.access.denied`    | Worker 运行发起人已禁用  | `userId`、`reason`（F-005）                                       |

新增 M10 动作：

| 动作                     | 目标类型       | details 白名单（ADR-0007 扩展）                                                                                                                              |
| ------------------------ | -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `audit.queried`          | `AUDIT`        | `filtersHash`、`rowCount`                                                                                                                                    |
| `audit.export.requested` | `AUDIT_EXPORT` | `exportId`、`filtersHash`、`estimatedRowCount`、`status`、`occurredFrom`、`occurredTo`、`action`、`actorId`、`targetType`、`targetId`、`outcome`（只写非空） |
| `audit.export.approved`  | `AUDIT_EXPORT` | `exportId`、`decision`、`estimatedRowCount`、`filtersHash`、`targetTenantId`（平台）、过滤条件（同 requested）                                               |
| `audit.export.rejected`  | `AUDIT_EXPORT` | `exportId`、`decision`、`estimatedRowCount`、`filtersHash`、`targetTenantId`（平台）、过滤条件（同 requested）                                               |
| `data.exported`          | `AUDIT_EXPORT` | `exportId`、`scope`、`rowCount`、`filtersHash`                                                                                                               |
| `user.oidc.bound`        | `USER`         | `userId`、`issuerHash`、`subjectHash`、`previousAuthSource`                                                                                                  |
| `user.oidc.unbound`      | `USER`         | `userId`、`issuerHash`                                                                                                                                       |

- `details` 值仍限基本类型、单值 ≤ 200 字符；`filtersHash`/`subjectHash` 为 64 位 hex。过滤条件字段用于 AC-17 的“范围”可读性（F-016），不是完整 filters JSON；完整条件存 `audit_export_request.filters`。
- `AuditActions`/`AuditDetailsPolicy`/`AdditionalAuditActions` 在 B1 中**唯一 owner**；B3 只消费，不修改这些文件（F-006）。`AuditPolicy.actions` 必须包含上述全部动作，并包含 worker `user.access.denied`。
- `AUDIT_EXPORT` 加入 `AuditTargetType`；`target_id` 为导出请求 ID。
- 导出文件内容、`decisionComment`、邮箱、姓名、IP 不进入审计 details；拒绝原因保存在 `audit_export_request.decision_comment`，评审包可读。
- `audit.read` 的既有语义从“保留”变为“启用”，不新增权限码；新增 `audit.export.approve`、`platform.audit.export.approve`。
- **读取策略共享**：API 读取审计时使用合并后的 action-aware 白名单（API + Worker），禁止用子串规则；`promptTokens`/`completionTokens` 等合法键必须通过，未知 action + 非空 details 或白名单外键 fail-closed `RICCON-AUDIT-500`。

#### 6.2 错误码

| 错误码             | HTTP | 场景                                                                                        |
| ------------------ | ---- | ------------------------------------------------------------------------------------------- |
| `RICCON-AUDIT-400` | 400  | 时间范围/过滤器/分页非法；预计行数超过上限                                                  |
| `RICCON-AUTH-403`  | 403  | 无 `audit.read`/`audit.export.approve`/`platform.audit.export.approve`（既有拦截器，F-015） |
| `RICCON-AUDIT-403` | 403  | 有权限但业务拒绝：申请人自批、非申请人下载、平台租户导出、平台审批多管理员租户              |
| `RICCON-AUDIT-409` | 409  | 导出状态不允许该操作；并发审批冲突；下载时实际行数超限；平台审批多管理员租户                |
| `RICCON-AUDIT-410` | 410  | 导出已过期                                                                                  |
| `RICCON-AUDIT-500` | 500  | 读取时发现白名单外/敏感的 details 键（fail-closed），响应不含内容                           |

`RICCON-AUDIT-*` 为 M10 新增稳定码；不改变既有错误码语义。跨租户/不存在导出统一 `RICCON-TENANT-404`；平台管理员下载租户导出按 404 处理，避免存在性泄露（F-015）。

### 7. API 与契约

| 方法 | 路径                                                                 | 权限                            | 说明                                                   |
| ---- | -------------------------------------------------------------------- | ------------------------------- | ------------------------------------------------------ |
| GET  | `/api/v1/audit-events`                                               | `audit.read`                    | 查询；强制时间范围与分页                               |
| GET  | `/api/v1/audit-events/policy`                                        | `audit.read`                    | 保留/范围/动作/导出策略                                |
| POST | `/api/v1/audit-exports`                                              | `audit.read`（仅租户管理员）    | 发起导出申请；平台租户 403                             |
| GET  | `/api/v1/audit-exports`                                              | `audit.read`（仅租户管理员）    | 列出本租户导出请求；平台租户 403；`EXPIRED` 为派生过滤 |
| GET  | `/api/v1/audit-exports/{exportId}`                                   | `audit.read`（仅租户管理员）    | 导出请求详情；平台租户 403                             |
| POST | `/api/v1/audit-exports/{exportId}/decision`                          | `audit.export.approve`          | 同租户审批；自批 403；平台租户无此路径                 |
| GET  | `/api/v1/audit-exports/{exportId}/download`                          | `audit.read` + 申请人本人       | CSV 流；过期 410；平台管理员 404                       |
| GET  | `/api/v1/admin/tenants/{tenantId}/audit-exports`                     | `platform.audit.export.approve` | 仅单管理员租户；多管理员 409；只返回元数据             |
| POST | `/api/v1/admin/tenants/{tenantId}/audit-exports/{exportId}/decision` | `platform.audit.export.approve` | 仅单管理员租户；多管理员 409；不返回/下载审计内容      |

- 所有 POST 需要 `X-CSRF-TOKEN`；GET 不需要。
- 列表分页沿用 `PageMeta`；导出列表按 `created_at DESC`。审计查询使用 `AuditPage` 参数（`page ≤ 200`），契约可校验。
- OpenAPI 版本升 `1.2.0-m10`；`x-riccon-deferred` 移除 `oidc-sso`、`audit-query`、`audit-export`，保留并更新 `platform-cross-tenant-audit`，新增 `mfa`、`audit-hash-chain`、`platform-tenant-audit-export` 后置项。
- `contracts/internal-api.yaml` 无变更（M10 不改变 API→Worker 契约）。

### 8. Builder 任务边界与依赖

M10 共 **5 个 Builder 任务 + 1 个 Verifier 任务 + 1 个 Ops 评审**；每个任务 `write_scope ≤ 8`、`acceptance ≤ 6`。写范围按文件/目录级别互不重叠；`AdditionalAuditActions.java`、`AuditDetailsPolicy.java`、`AuditActions.java`、`RoleDefinitions.java`、`ErrorCodes.java` 只在 B1 出现一次。

```text
B1 OIDC 登录/登出 + 共享词汇 ──> B2 账号开通/绑定 ──> B4 Web（OIDC/绑定）
        │                                      │
        └──────────────> B3 审计查询/导出 + V10 ──> B5 Web（审计/导出）
B4 + B5 ─────────────────────────────────────────> V1 独立验证（含测试 IdP/E2E 夹具）
OPS-M10（评审） ──> B3 的 V10 生产执行 / M10 gate
```

| 任务                             | 内容                                                                                                                                                                                                                                               | 写范围（≤8，文件级不重叠）                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | 验收（≤6）                                                                                                                                                                                             |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| M10-B1 OIDC 登录/登出 + 共享词汇 | 配置模型/校验（secret 脱敏）、动态 `ClientRegistrationRepository`、发现缓存、无 userinfo、`oauth2Login` 过滤器链、OIDC-aware `SecurityContextRepository`、失败清理、自动联合登出、限流、审计；全部新动作/权限/错误码/AuditDetailsPolicy 唯一 owner | `apps/api/src/main/java/com/riccon/api/oidc/**`；`apps/api/src/main/java/com/riccon/api/auth/controller/**`；`apps/api/src/main/java/com/riccon/api/config/SecurityConfig.java`；`apps/api/src/main/java/com/riccon/api/identity/domain/RoleDefinitions.java`；`apps/api/src/main/java/com/riccon/api/audit/domain/**`；`apps/api/src/main/java/com/riccon/api/error/**`；`platform/platform-domain/src/main/java/com/riccon/platform/domain/**`；`apps/api/src/test/java/com/riccon/api/m10/oidc/**` | 配置校验/secret 脱敏/issuer 唯一；PKCE+nonce+state 一次性；无 userinfo/无 token 落库；失败清理断言；一次点击登出自动 end-session；绑定/禁用四类失败；`IT-OIDC-001..007` 骨架；共享动作/权限/错误码齐全 |
| M10-B2 账号开通/绑定             | `UserCreateRequest` OIDC 分支、绑定/解绑、凭据删除/重建、`UserSummary.authSource`、冲突与审计                                                                                                                                                      | `apps/api/src/main/java/com/riccon/api/admin/identity/**`；`apps/api/src/main/java/com/riccon/api/admin/controller/**`；`apps/api/src/main/java/com/riccon/api/admin/service/**`；`apps/api/src/main/java/com/riccon/api/identity/dto/**`；`apps/api/src/main/java/com/riccon/api/auth/repository/**`；`apps/api/src/main/java/com/riccon/api/auth/persistence/**`；`apps/api/src/test/java/com/riccon/api/m10/identity/**`                                                                           | 不按邮箱合并；绑定命中同一账号/角色；同身份/跨身份冲突 409；绑定删除本地凭据；解绑恢复本地密码；审计 details 合规；LOCAL 分支拒绝空密码                                                                |
| M10-B3 审计查询/导出 + V10       | 查询/策略端点、导出申请/审批/下载、V10、索引、权限回填、平台单管理员校验、平台租户导出 403、Clock/TTL、CSV 注入防护、审计                                                                                                                          | `apps/api/src/main/java/com/riccon/api/audit/controller/**`；`apps/api/src/main/java/com/riccon/api/audit/service/**`；`apps/api/src/main/java/com/riccon/api/audit/repository/**`；`apps/api/src/main/java/com/riccon/api/audit/persistence/**`；`apps/api/src/main/java/com/riccon/api/admin/audit/**`；`apps/api/src/main/resources/db/migration/V10__*.sql`；`apps/api/src/test/java/com/riccon/api/m10/audit/**`；`apps/api/src/test/java/com/riccon/api/migration/**`                           | 过滤/排序/分页/180 天；无写入口；查询审计；读策略不误杀 token 计数；导出申请/自批 403/并发 409/过期 410；平台仅单管理员；平台租户 403；V1–V9 种子迁移/回滚；`IT-AUDIT-101..106` 骨架                   |
| M10-B4 Web OIDC/绑定             | 登录页企业账号入口、provider 查询、错误态、自动联合登出落地页、用户管理绑定/解绑、`authSource`、375 px/键盘/axe                                                                                                                                    | `apps/web/src/pages/login/**`；`apps/web/src/pages/admin/users/**`；`apps/web/src/services/auth/**`；`apps/web/src/services/users/**`；`apps/web/tests/m10-oidc/**`                                                                                                                                                                                                                                                                                                                                   | 企业登录入口与七类错误态；未知租户不泄露；一次点击登出导航到 IdP；IdP 不可达提示本地退出；绑定/解绑校验；`authSource` 展示；375 px/键盘/axe                                                            |
| M10-B5 Web 审计/导出             | 审计查询页、策略/空态/错误态、导出申请/列表/审批/下载、平台审批页、a11y                                                                                                                                                                            | `apps/web/src/pages/admin/audit/**`；`apps/web/src/pages/admin/tenant-exports/**`；`apps/web/src/services/audit/**`；`apps/web/tests/m10-audit/**`；`apps/web/config/routes.ts`                                                                                                                                                                                                                                                                                                                       | 过滤/分页/排序；`policy.retentionDays` 文案；无删除入口；申请/审批/下载状态机；自批无入口；平台审批仅单管理员/元数据；375 px/键盘/axe                                                                  |
| M10-V1 独立验证（含测试 IdP）    | 确定性测试 IdP、OIDC 安全套件、导出权限/并发/过期/字段、跨租户扩展、E2E、a11y、security、回滚演练、模块评审                                                                                                                                        | `apps/api/src/test/java/com/riccon/api/security/**`；`e2e/oidp/**`；`e2e/support/oidc/**`；`e2e/tests/m10/**`；`e2e/playwright.config.ts`；`deploy/compose/docker-compose.test.yml`；`.riccon/reviews/M10-review.md`                                                                                                                                                                                                                                                                                  | 测试 IdP discovery/authorize/token/JWKS/end-session/故障注入；`SEC-OIDC-001..003`/`IT-OIDC-*`；`IT-AUDIT-*`；`DEPT-010` 导出段；`TEN-028..030`；`E2E-M03-03..07`；axe；security；回滚演练              |

**Ops 评审（OPS-M10，非编码）**：在 B3 的 V10 生产执行前与 M10 gate 前完成，记录到模块评审包：`RICCON_OIDC_IDPS`/`RICCON_PUBLIC_BASE_URL` Secret 注入与轮换、代理头、V10 分区索引构建窗口/online DDL、回滚 SQL 演练、worker schema 9→10 滚动顺序、`audit_export_request` 的 M09 清理。Ops 结论不阻塞 B1/B2，但阻塞 V10 生产执行与 gate（F-012）。

补充约束：

- **唯一 owner**：B1 独占 `AdditionalAuditActions.java`、`AuditDetailsPolicy.java`、`AuditActions.java`、`RoleDefinitions.java`、`ErrorCodes.java`；B2/B3 只消费，不得修改；B3 独占 V10；B4/B5 不写后端；V1 不写生产代码/迁移。
- B1 与 B2 的 `auth/**` 已按 `auth/controller` 与 `auth/repository|persistence` 拆分；B2/B3 的 `admin/**` 已按 `admin/identity|controller|service` 与 `admin/audit` 拆分；不存在 glob 覆盖重叠（F-006）。
- 测试 IdP 只属于 V1 的 `e2e/oidp/**`/`e2e/support/oidc/**` 与 compose 测试配置，不进入 B1 生产范围（F-007）。
- B4/B5 可并行，但必须先对齐 B1/B3 的 OpenAPI schema；任何契约变更回到 architect，不在前端私改字段。
- 验收“≤6”指任务卡 acceptance 条数，不是测试条数；每个任务至少一条负向安全用例。
- 不做全量 `verify` 作为任务收尾的替代；按合同 L1/L2 分层，模块 gate 由 M10-V1 跑全量。

### 9. 验证策略

| 层           | 内容                                                                                                                                                                                                                                 | 命令/入口                                                                                    |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------- |
| L1 迭代      | 定向测试：`-Dtest=Oidc*Test`、`-Dtest=Audit*Test`、`-Dtest=TenantUserAdmin*Test`；OpenAPI lint                                                                                                                                       | `./mvnw -q -pl apps/api -am test -Dtest=...`、`npx @redocly/cli lint contracts/openapi.yaml` |
| L2 任务收尾  | 受影响模块全量一次、迁移测试、前端单测、契约 lint                                                                                                                                                                                    | `./mvnw -q -T1C verify`、`pnpm -w test`                                                      |
| L3 模块 gate | 合同 `commands.test`、`coverage`、`test_e2e`、`a11y`、`security`；M10-V1 独立套件                                                                                                                                                    | 合同命令；覆盖率 line 80 / branch 70；Trivy 0 未抑制                                         |
| OIDC 安全    | `SEC-OIDC-001`（无 JWT/token 泄漏、malformed config 日志脱敏、无 userinfo）、`SEC-OIDC-002`（禁用即时失效）、`SEC-OIDC-003`（Cookie/CSRF/超时一致性）；回放 state、拒绝授权、IdP 不可达、issuer 混淆、同邮箱不合并；**失败会话清理** | `IT-OIDC-001..007`、`E2E-M03-03/04`                                                          |
| 审计查询     | 过滤组合、排序、分页上限、空态、180 天边界、查询自身审计、普通员工 403、无写路由；**模型事件 promptTokens/completionTokens 可查**；`actorDisplayName` 批量/100 行性能                                                                | `IT-AUDIT-101..103`、`E2E-M03-05`、`TEN-028`                                                 |
| 导出         | 申请/审批/拒绝/自批/并发/过期/非申请人/跨租户/行数上限/字段白名单/CSV 元数据/公式注入；平台审批仅单管理员；平台租户导出 403；Clock+短 TTL 过期                                                                                       | `IT-AUDIT-104..106`、`SEC-AUDIT-001`、`E2E-M03-06`、`TEN-029..030`                           |
| a11y         | 登录页 OIDC 段、审计查询页、导出申请/审批页；加载/空/错误/无权限/成功；375 px；仅键盘完成“企业登录 → 进入部门空间 → 筛选审计 → 发起导出”                                                                                             | `E2E-M03-07`、`pnpm -w a11y`                                                                 |
| 安全         | Trivy vuln/secret/misconfig；B-06 例外不扩大；OIDC secret 不出现在报告、日志、镜像                                                                                                                                                   | `commands.security`                                                                          |
| 回滚         | V10 回滚 SQL 在非生产副本执行；OIDC 配置移除后企业登录关闭、本地登录可用；记录步骤与结果                                                                                                                                             | M10-V1 演练记录；ADR-0010                                                                    |

测试 ID 约定：`IT-OIDC-NNN`、`IT-AUDIT-1NN`、`SEC-OIDC-NNN`、`SEC-AUDIT-NNN` 为 Builder/Verifier 共用；`TEN-028..030` 并入 `@Tag("tenant-isolation")`；`DEPT-010` 在 M10 扩展导出请求段，不重排 M03 已发布编号。PRODUCT AC-16 的测试线索写作 `TEN-021`，与 M03 已占用的部门用例编号冲突；M10 使用 `TEN-028` 承接审计接口跨租户/无权限断言，并在评审包登记该编号冲突，不重排既有编号。**PRODUCT 文档漂移（F-018）**：`docs/PRODUCT.md` §8.3 表仍将 08..21 标为 `M03-AC-*`，§8.5 才把 08..18 归 M10；AC-20 线索仍写 `TEN-001..026`，而实际 M03 已扩到 027、M10 扩到 030。M10 评审包按 §8.5 映射并登记文本漂移，不改 PRODUCT 正文。

### 10. 明确不做 / 后置

- **MFA**：不实现、不验证 `acr`/`amr`、不声称强认证（DEC-0013，R-12 保留）。
- **审计哈希链/完整性证明**：不实现 `prev_hash`/`event_hash`/外部锚定；继续依赖 append-only 触发器、无在线删除与最小权限（R-13 保留）。
- **平台管理员跨租户审计明细**：M10 不提供；平台来源事件对目标租户不可见（R-14、M03 F-009，residual 不是 fixed）。
- **平台租户自身审计导出**：M10 不支持（平台租户只有平台管理员，无人可承担同租户审批）；`POST/GET/download /audit-exports` 对 `tenant_id=platform` 返回 403 `RICCON-AUDIT-403`。需第二个平台管理员或独立审批模型时另立 ADR（F-023）。
- 审计在线删除、legal hold UI、导出常驻链接、导出文件内预览、导出归档到对象存储、定时导出、导出专用重复下载配额。
- 审计查询的全文搜索、任意字段排序、跨租户聚合看板、告警规则（M09）。
- 导出审批的通知/邮件（合同 v1 不发邮件）；审批人通过列表轮询发现待办。
- 自定义保留策略（租户级缩短/延长审计保留）与跨模块审计关联分析。

## Consequences

- 正面：审计从“只写”变为可查、可审批导出，且不引入新存储/中间件；append-only 与无写路由保持；导出无持久副本，过期即不可读；平台管理员审批只接触元数据，跨租户业务内容边界不扩大；查询索引与范围上限把慢查询风险限制在可控范围。
- 负面：流式导出在有效期内重复下载会重新生成，结果可能包含 `occurredTo` 之前新增的事件（append-only 导致行数变化）；深层分页有 200 页上限；目标租户仍看不到平台来源事件；平台管理员不能查看目标租户审计，排障依赖平台审计与日志。
- 约束：任何新增审计动作必须同时更新 `AuditActions`、`AuditDetailsPolicy`、OpenAPI/文档与测试；任何新增导出字段必须更新白名单与 `SEC-AUDIT-001`；不得为“方便排查”增加审计 UPDATE/DELETE 或跨租户查询参数；不得把 `decision_comment` 写入审计 details。

## Alternatives

- **导出文件落对象存储 + 预签名 URL**：可重复下载且可归档，但引入对象 key、TTL 清理、残留副本与生命周期依赖；M10 无清理调度器，拒绝。
- **审批后在同步请求中生成文件并长期保存**：批准事务变长，失败重试复杂；拒绝。
- **申请即导出（无审批）**：违反 AC-17 与合同“导出需审批”；拒绝。
- **审批人可下载/平台管理员可下载**：扩大内容访问面；拒绝，只有申请人可下载。
- **按 `details.targetTenantId` 让目标租户看到平台事件**：会绕过“平台管理员只管理元数据”的审计边界，且 JSON 过滤无索引；拒绝，后置显式授权模型。
- **keyset 分页替代 page/size**：深层性能更好，但与 M02 分页契约不一致，前端和测试成本高；M10 用范围/页码上限控制，M09 再评估。
- **哈希链**：更强的防篡改证据，但与分区删除/外部锚定耦合；DEC-0013 后移，拒绝在 M10 夹带。
- **审计查询走 OpenSearch**：检索灵活，但审计是权威记录且需强一致/租户过滤，复制到检索会增加泄露面；拒绝。

## Evidence

- 现状：`apps/api/src/main/resources/db/migration/V6__audit_event.sql`（表、分区、触发器）、`V2__identity_and_workspace.sql`（`audit.read` 权限码、OIDC 预留列）、`apps/api/src/main/java/com/riccon/api/audit/**`、`platform/platform-domain/.../AuditActions.java`、`AuditTargetType.java`。
- 审计模型：ADR-0007；部门事件与平台口径：ADR-0018 §5。
- M03 承接项：REV-M03-001 F-009；`docs/ACCEPTANCE.md` 残余项表。
- 决策：DEC-0011、DEC-0013、DEC-0014、DEC-0015、DEC-0016。
- 产品：`docs/PRODUCT.md` §8 M10-AC-15..21、§8.5。
- 威胁模型：`docs/security/THREAT-MODEL.md` TB-07/TB-12、TM-34..TM-38、R-13/R-14、B-17。
- 测试：M10-V1 的 `IT-AUDIT-101..106`、`SEC-AUDIT-001`、`TEN-028..030`、`E2E-M03-05/06/07`。
