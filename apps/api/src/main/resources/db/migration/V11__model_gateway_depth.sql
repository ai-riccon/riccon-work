-- =============================================================================
-- V11：模型网关纵深（M04 唯一迁移；B1a）
--
-- 依据 docs/adr/ADR-0021-m04-model-routing-failover-multi-key.md §3/§4/§10.3 与
-- docs/adr/ADR-0022-m04-quota-and-cost.md §1.2/§5：
--   * 新增全局路由链 model_routing/model_routing_entry，并为 CHAT/REPORT 各预置一条链；
--     预置规则完全保留 M02 单模型行为：按 model.created_at, model.id 取最早的 ACTIVE 模型作为 position=1；
--   * 新增全局运行时开关 model_gateway_switch（routingMode/failoverEnabled/quotaEnforcementEnabled）；
--     发版种子 quotaEnforcementEnabled=false，B10 演练后显式 PUT true，M04 gate 以 true 取证；
--   * provider_credential 从“单 provider 单 key”改为平台托管 key 池：
--     RETIRED→DISABLED、fingerprint 回填、旧唯一键 uk_provider_credential 替换为 fingerprint 唯一键；
--   * model_invocation 增加 provider/user 快照、成本价格/币种/金额、Asia/Shanghai 日桶与尝试次数；
--     不建 provider 外键（调用时快照允许 provider 停用），不加新 CHECK（MySQL 8.4 ADD CHECK 只能 COPY，
--     会阻塞大表 DML；合法值由 Worker 应用层校验）；
--   * 新增 model_invocation_attempt 记录每次上游 HTTP 尝试（与降级审计一一对应）；
--   * 新增 quota_limit/quota_usage（租户 + 用户 × 日/月，按请求数准入；B6 的条件 UPDATE 零超卖）；
--   * 权限字典与既有角色回填 M04 权限码；RoleDefinitions 负责新租户开通与空库引导路径。
--
-- 执行顺序（不可随意调换，ADR-0021 §3.2）：
--   1) 建新表（空表/小表，可带 CHECK）；
--   2) provider_credential：加列(INSTANT) → DROP 旧 CHECK → RETIRED→DISABLED 与 fingerprint 回填 →
--      新唯一键/索引/CHECK → 最后 DROP 旧唯一键；
--   3) model_invocation：加列(INSTANT) 后逐条建索引(INPLACE, LOCK=NONE)，不与加列混用；
--   4) 权限字典 INSERT 与角色回填（NOT EXISTS 守卫，失败修复后可重跑）；
--   5) 路由链与开关种子。
--
-- 只向前兼容：不修改 V1–V10 已应用文件；V11 后旧 API 可读（新增列可空/有默认），
-- 旧 Worker 因 schema 版本严格校验不得在 V11 库上启动（回滚窗口见 ADR-0021 §10.3/§10.6）。
--
-- 回滚（仅非生产副本演练后由维护账号执行；不做 down migration）：
--   停新 API/Worker → 多 key 收敛（每组 (tenant_id, provider_id, credential_type) 只留 1 行：
--   有 ACTIVE 保留一个 ACTIVE，否则保留一行并置 RETIRED）→ 删除 V11 对象/列/权限 →
--   删除 flyway_schema_history 的 V11 行并校验 MAX(version)=10。
--   完整可执行 SQL 见 apps/api/src/test/java/com/riccon/api/migration/V11MigrationTest.java
--   的 rollbackV11Objects()；结构回滚会丢失 M04 新列数据，生产优先应用回滚与前向修复。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 模型路由链（全局表，ADR-0012 例外已由 ADR-0021 §3 显式扩展）
-- -----------------------------------------------------------------------------

-- 每个用途一条链；version 为乐观锁，保存整链时 +1。
CREATE TABLE model_routing (
    purpose    VARCHAR(16) NOT NULL COMMENT '用途：CHAT|REPORT',
    version    INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次保存 +1',
    updated_by BINARY(16) NULL COMMENT '最近修改的平台管理员账号 ID；逻辑引用',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (purpose),
    CONSTRAINT chk_model_routing_purpose CHECK (purpose IN ('CHAT', 'REPORT')),
    CONSTRAINT chk_model_routing_version CHECK (version >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模型路由链（全局表，M04）';

-- 链内条目：position 从 1 连续；同一用途下模型不可重复。
-- model_id 只做目录引用，M04 不提供模型删除端点。
CREATE TABLE model_routing_entry (
    purpose  VARCHAR(16) NOT NULL COMMENT '用途：CHAT|REPORT',
    position INT NOT NULL COMMENT '从 1 开始，唯一且连续',
    model_id BINARY(16) NOT NULL COMMENT '模型 ID',
    PRIMARY KEY (purpose, position),
    UNIQUE KEY uk_model_routing_entry_model (purpose, model_id),
    CONSTRAINT fk_model_routing_entry_routing FOREIGN KEY (purpose) REFERENCES model_routing (purpose) ON DELETE CASCADE,
    CONSTRAINT fk_model_routing_entry_model FOREIGN KEY (model_id) REFERENCES model (id),
    CONSTRAINT chk_model_routing_entry_position CHECK (position >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模型路由链条目（全局表，M04）';

-- -----------------------------------------------------------------------------
-- 2) 网关运行时开关（全局表单行键值；每次读取一次查询，不做跨请求缓存）
-- -----------------------------------------------------------------------------

-- 环境变量优先级高于 DB；环境变量存在时管理端写开关返回 409 ENV_OVERRIDE_ACTIVE。
CREATE TABLE model_gateway_switch (
    switch_key   VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'routingMode|failoverEnabled|quotaEnforcementEnabled',
    switch_value VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ROUTED|BASELINE / true|false',
    version      INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    updated_by   BINARY(16) NULL COMMENT '最近修改的平台管理员账号 ID；逻辑引用',
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT 'UTC',
    PRIMARY KEY (switch_key),
    CONSTRAINT chk_model_gateway_switch_key CHECK (
        switch_key IN ('routingMode', 'failoverEnabled', 'quotaEnforcementEnabled')
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模型网关运行时开关（全局表，M04）';

-- -----------------------------------------------------------------------------
-- 3) provider_credential：平台托管 key 池（租户表，M04 只写 tenant_id='platform'）
-- -----------------------------------------------------------------------------

-- 第 1 步：加列。provider_credential 是小表，但仍显式 INSTANT，避免重建与长时间元数据锁。
ALTER TABLE provider_credential
    ADD COLUMN label VARCHAR(64) NOT NULL DEFAULT '默认' COMMENT '管理员可读标签，不参与鉴权',
    ADD COLUMN fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'HMAC-SHA-256(pepper, provider_id||0x00||明文 key) 十六进制；仅去重，不可反推',
    ADD COLUMN masked_hint VARCHAR(16) NOT NULL DEFAULT '****' COMMENT '仅末 4 位，如 ****a1b2',
    ADD COLUMN priority INT NOT NULL DEFAULT 100 COMMENT '越小越先使用',
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0 COMMENT '连续可重试失败次数',
    ADD COLUMN cooldown_until DATETIME(6) NULL COMMENT '冷却截止（UTC）',
    ADD COLUMN disabled_at DATETIME(6) NULL COMMENT '管理员停用时间（UTC）',
    ADD COLUMN disabled_by BINARY(16) NULL COMMENT '停用操作者账号 ID；逻辑引用',
    ADD COLUMN invalidated_at DATETIME(6) NULL COMMENT '401/403 自动失效时间（UTC）',
    ADD COLUMN last_used_at DATETIME(6) NULL COMMENT '最近成功使用时间（UTC）',
    ALGORITHM = INSTANT;

-- 第 2 步：先删除旧 CHECK。此时数据仍是 ACTIVE|RETIRED，必须先解除约束才能写 DISABLED。
ALTER TABLE provider_credential DROP CHECK chk_provider_credential_status;

-- 第 3 步：状态归一与历史标识回填（必须在删除旧 CHECK 之后，否则 ERROR 3819）。
--   * RETIRED 是 M02 的“已轮换”语义，M04 统一为 DISABLED；
--   * 历史行没有明文，无法计算 HMAC fingerprint，只能用 id 派生历史标识；
--     该值只用于满足非空与唯一性，不参与新 key 去重（ADR-0021 §3.2 legacy 语义）。
UPDATE provider_credential SET status = 'DISABLED' WHERE status = 'RETIRED';

UPDATE provider_credential
   SET label = COALESCE(NULLIF(label, ''), '部署注入'),
       masked_hint = COALESCE(masked_hint, '****'),
       fingerprint = COALESCE(fingerprint, SHA2(CONCAT('legacy:', HEX(id)), 256))
 WHERE fingerprint IS NULL;

-- 第 4 步：新唯一键、池查询索引、新 CHECK 与状态注释。
--   * fingerprint 可为空仅为兼容历史行；服务层创建/轮换必须写非空 HMAC fingerprint；
--   * 新唯一键允许同一 provider 多把 key，但同租户同 provider 同类型下 fingerprint 不得重复；
--   * priority 1..1000，consecutive_failures 非负；状态枚举 ACTIVE|DISABLED|INVALID。
ALTER TABLE provider_credential
    ADD UNIQUE KEY uk_provider_credential_fingerprint (tenant_id, provider_id, credential_type, fingerprint),
    ADD KEY idx_provider_credential_pool (tenant_id, provider_id, credential_type, status, priority, created_at),
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED|INVALID',
    ADD CONSTRAINT chk_provider_credential_status CHECK (status IN ('ACTIVE', 'DISABLED', 'INVALID')),
    ADD CONSTRAINT chk_provider_credential_priority CHECK (priority >= 1 AND priority <= 1000),
    ADD CONSTRAINT chk_provider_credential_failures CHECK (consecutive_failures >= 0);

-- 第 5 步：最后删除旧的“单 provider 单 key”唯一键。顺序不可提前，
-- 否则第 4 步失败时无法回退到旧结构（旧键已丢失）。
ALTER TABLE provider_credential DROP INDEX uk_provider_credential;

-- -----------------------------------------------------------------------------
-- 4) model_invocation：调用级成本快照与尝试次数（大表，严格 INSTANT + INPLACE）
-- -----------------------------------------------------------------------------

-- 第 1 步：加列。全部可空或带常量默认；历史行由默认值覆盖，不做全表 UPDATE。
-- 本表不加 CHECK：MySQL 8.4 的 ADD CHECK 只能 COPY，会阻塞 DML 并重建大表。
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

-- 第 2 步：索引逐条执行（INPLACE, LOCK=NONE）。不要与 ADD COLUMN 混在一条 ALTER 中，
-- 否则 MySQL 会选择 COPY 并阻塞写。
ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_tenant_time (tenant_id, started_at),
    ALGORITHM = INPLACE,
    LOCK = NONE;
ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_user_time (tenant_id, user_id, started_at),
    ALGORITHM = INPLACE,
    LOCK = NONE;
ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_cost_day (tenant_id, cost_day),
    ALGORITHM = INPLACE,
    LOCK = NONE;
ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_provider (provider_id),
    ALGORITHM = INPLACE,
    LOCK = NONE;

-- 第 3 步：不建 provider 外键。provider_id 是调用时快照，历史 provider 允许停用；
-- 应用层在写入前校验 provider 存在。MySQL 8.4 的 ADD FK 需要 COPY 或
-- foreign_key_checks=OFF，M04 不承担该维护窗口（OPS-M04-019）。

-- 第 4 步：V11 不回溯历史 cost_day，也不执行全表 UPDATE cost_status。
-- 历史行保持 cost_day=NULL、cost_status=USAGE_UNKNOWN，成本报告计入 unbucketedCalls；
-- 需要回填时由 B10 的 scripts/ops/backfill-cost-day 在 CHECK 后独立分批执行。

-- -----------------------------------------------------------------------------
-- 5) 模型调用尝试明细（空表，允许 CHECK）
-- -----------------------------------------------------------------------------

-- 每次上游 HTTP 调用一行；key 的权威归属是 provider_credential (tenant_id='platform', id)，
-- 这里 tenant_id 是请求租户，因此 credential_id 只做逻辑引用、不建复合外键。
CREATE TABLE model_invocation_attempt (
    tenant_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求所属租户',
    invocation_id BINARY(16) NOT NULL COMMENT '所属调用 ID',
    attempt_no    INT NOT NULL COMMENT '从 1 开始',
    model_id      BINARY(16) NOT NULL COMMENT '本次尝试的模型',
    provider_id   BINARY(16) NOT NULL COMMENT '本次尝试的 provider',
    credential_id BINARY(16) NULL COMMENT '本次尝试的 key ID；仅内部排障，不返回员工',
    status        VARCHAR(16) NOT NULL COMMENT 'STARTED|SUCCEEDED|FAILED|CANCELLED',
    error_code    VARCHAR(64) NULL COMMENT '稳定错误码',
    first_token_ms INT NULL COMMENT '本次尝试首字延迟（毫秒）',
    started_at    DATETIME(6) NOT NULL COMMENT '开始时间（UTC）',
    finished_at   DATETIME(6) NULL COMMENT '结束时间（UTC）',
    PRIMARY KEY (tenant_id, invocation_id, attempt_no),
    KEY idx_model_attempt_model (tenant_id, model_id, started_at),
    CONSTRAINT fk_model_attempt_invocation FOREIGN KEY (tenant_id, invocation_id)
        REFERENCES model_invocation (tenant_id, id),
    CONSTRAINT fk_model_attempt_model FOREIGN KEY (model_id) REFERENCES model (id),
    CONSTRAINT fk_model_attempt_provider FOREIGN KEY (provider_id) REFERENCES model_provider (id),
    CONSTRAINT chk_model_attempt_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_model_attempt_no CHECK (attempt_no >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模型调用尝试明细（M04）';

-- -----------------------------------------------------------------------------
-- 6) 配额（租户表；计数表例外：使用业务复合主键，不生成 id）
-- -----------------------------------------------------------------------------

-- 配额上限：无显式行时取部署配置 RICCON_QUOTA_DEFAULT_*；limit_count NULL 与 0 都表示“明确不限”。
-- USER 维度必须带 user_id 并受 (tenant_id, user_id) 复合外键约束；TENANT 维度 user_id 必须为空、
-- scope_id 必须等于 tenant_id，避免服务层写错维度。
CREATE TABLE quota_limit (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    scope_type  VARCHAR(16) NOT NULL COMMENT 'TENANT|USER',
    scope_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TENANT 为 tenant_id；USER 为用户 UUID 字符串',
    user_id     BINARY(16) NULL COMMENT 'USER 维度必填；复合外键 (tenant_id,user_id)',
    window_kind VARCHAR(8) NOT NULL COMMENT 'DAILY|MONTHLY',
    limit_count INT NULL COMMENT 'NULL=明确不限；0 也视为不限（兼容部署配置 0）',
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
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '配额上限（M04）';

-- 配额用量：窗口起点存 UTC 瞬时（Asia/Shanghai 自然日/月边界换算），PK 正好匹配 B6 的
-- INSERT ... ON DUPLICATE KEY UPDATE used_count = used_count 与条件 UPDATE 形状。
-- 保留历史窗口供审计/排障，M09 按 window_start 清理 ≥180 天前数据。
CREATE TABLE quota_usage (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    scope_type  VARCHAR(16) NOT NULL COMMENT 'TENANT|USER',
    scope_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TENANT 为 tenant_id；USER 为用户 UUID 字符串',
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
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '配额用量（M04）';

-- -----------------------------------------------------------------------------
-- 7) 权限字典与角色回填
-- -----------------------------------------------------------------------------

-- 权限字典。新增权限码必须同步 platform-domain PermissionCodes 与 OpenAPI 的
-- x-riccon-permission；NOT EXISTS 只服务于失败后 repair 重跑的幂等恢复。
-- platform.model.read 在 V2 已写入；V11 再次幂等补齐，保证当前 PermissionCodes 全集在 V11 之后
-- 与 permission 字典一一对应（局部恢复/历史 schema 审计时不依赖 V2 单点）。
INSERT INTO permission (code, description)
SELECT 'platform.model.read', '平台模型目录只读'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'platform.model.read');

INSERT INTO permission (code, description)
SELECT 'platform.model.manage', '平台模型路由、模型启停、key 池与网关开关管理'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'platform.model.manage');

INSERT INTO permission (code, description)
SELECT 'quota.read', '读取本租户配额与用量'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'quota.read');

INSERT INTO permission (code, description)
SELECT 'platform.quota.manage', '平台管理员跨租户配额查看与整体替换'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'platform.quota.manage');

INSERT INTO permission (code, description)
SELECT 'cost.read', '读取本租户成本聚合与调用明细'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'cost.read');

INSERT INTO permission (code, description)
SELECT 'platform.cost.read', '平台管理员跨租户成本聚合（不含用户明细）'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'platform.cost.read');

-- 既有租户管理员回填：本租户配额/成本只读。新租户由 RoleDefinitions 开通时写入。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'quota.read'
  FROM role r
 WHERE r.code = 'TENANT_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'quota.read');

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'cost.read'
  FROM role r
 WHERE r.code = 'TENANT_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'cost.read');

-- 平台管理员只存在于 platform 保留租户；空库时角色可能尚未创建，由 BootstrapAdminRunner
-- 启动时按 RoleDefinitions 创建（含 M04 三项平台权限）。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'platform.model.manage'
  FROM role r
 WHERE r.tenant_id = 'platform'
   AND r.code = 'PLATFORM_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'platform.model.manage');

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'platform.quota.manage'
  FROM role r
 WHERE r.tenant_id = 'platform'
   AND r.code = 'PLATFORM_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'platform.quota.manage');

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'platform.cost.read'
  FROM role r
 WHERE r.tenant_id = 'platform'
   AND r.code = 'PLATFORM_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'platform.cost.read');

-- -----------------------------------------------------------------------------
-- 8) 路由链与开关种子
-- -----------------------------------------------------------------------------

-- 两个用途各一条链；空库（无模型）时只建链、不建条目。
INSERT INTO model_routing (purpose)
SELECT 'CHAT' WHERE NOT EXISTS (SELECT 1 FROM model_routing WHERE purpose = 'CHAT');
INSERT INTO model_routing (purpose)
SELECT 'REPORT' WHERE NOT EXISTS (SELECT 1 FROM model_routing WHERE purpose = 'REPORT');

-- M02 兼容种子：最早的 ACTIVE 模型作为 position=1。M04 上线后管理员可整链替换。
INSERT INTO model_routing_entry (purpose, position, model_id)
SELECT 'CHAT', 1, m.id
  FROM model m
 WHERE m.status = 'ACTIVE'
   AND NOT EXISTS (SELECT 1 FROM model_routing_entry e WHERE e.purpose = 'CHAT')
 ORDER BY m.created_at, m.id
 LIMIT 1;

INSERT INTO model_routing_entry (purpose, position, model_id)
SELECT 'REPORT', 1, m.id
  FROM model m
 WHERE m.status = 'ACTIVE'
   AND NOT EXISTS (SELECT 1 FROM model_routing_entry e WHERE e.purpose = 'REPORT')
 ORDER BY m.created_at, m.id
 LIMIT 1;

-- 发版种子：路由开启、降级开启、配额执行关闭（B10 演练后显式 PUT true）。
-- ON DUPLICATE 不覆盖运维已修改的值，仅用于 repair 重跑幂等。
INSERT INTO model_gateway_switch (switch_key, switch_value)
VALUES ('routingMode', 'ROUTED'),
       ('failoverEnabled', 'true'),
       ('quotaEnforcementEnabled', 'false')
ON DUPLICATE KEY UPDATE switch_key = switch_key;
