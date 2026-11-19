-- =============================================================================
-- V10：审计查询与导出（M10 唯一迁移；B3a）
--
-- 依据 docs/adr/ADR-0020-m10-audit-query-export.md §5/§5.2/§5.3/§5.4：
--   * 新增 audit_export_request：导出申请/审批状态机（PENDING_APPROVAL|APPROVED|REJECTED）与租户键；
--     不存导出文件、对象 key 或预签名 URL，下载由服务层流式生成（ADR-0020 §2）；
--   * audit_event 增加两个查询索引：租户+动作+时间、租户+操作者+时间；
--   * 权限字典新增 audit.export.approve / platform.audit.export.approve，并回填既有角色；
--     新租户开通与空库引导由 RoleDefinitions 写入（ADR-0020 §8.1 的 B3a 窄例外）。
--   * 纯 additive：不删列、不改类型、不重建 M02/M03 表；旧版本代码可忽略新表/索引/权限，
--     因此代码回滚到 V9 版本不需要结构回滚也能启动（ADR-0020 §5.3）。
--
-- 生产执行前置（OPS-002/004/005/006/007，ADR-0020 §8.1）：
--   * OPS-004 只能协调切换，不能纯滚动：停旧 worker → 停 API 写 → 本迁移（一次性迁移 Job）→
--     起新 API → 起新 worker。DB 升到 10 后，旧 worker（expected=9）重启会以 RICCON-WORKER-001
--     启动失败；Flyway 默认忽略 future 迁移，旧 API 仍可在 V10 库上启动，但滚动期重启仍是可用性风险。
--   * OPS-005 索引 DDL：两条索引必须写在同一条 ALTER，并显式 ALGORITHM=INPLACE, LOCK=NONE。
--     MySQL 8 原子 DDL 保证两条索引同成败，失败不会留下半成品索引；生产仍走维护窗口停写，
--     online DDL 只作为演练确认后的加速手段，不作为免窗口依据。
--   * OPS-007 回滚：结构回滚后必须由维护账号删除 flyway_schema_history 的 V10 行并校验
--     MAX(success version)=9；应用账号不得删除 history（具体 SQL 见文件末尾）。
--
-- 语句顺序与 OPS-006 迁移失败恢复矩阵（MySQL DDL 非事务，失败会保留已提交前缀）：
--   1) CREATE TABLE：失败时无残留 → flyway repair → 重跑。
--   2) 权限字典 INSERT：表已建；修因 → DROP TABLE audit_export_request（V10 新建且为空，
--      丢弃成本为零）→ flyway repair → 重跑；权限 INSERT 带 NOT EXISTS 守卫，可安全重跑。
--   3) 角色回填 INSERT：同第 2 条；NOT EXISTS 守卫避免 B1 已给新租户授予 audit.read 时主键冲突。
--   4) 索引 ALTER 放在最后：失败时表/权限已就绪但没有 V10 新索引；先消除冲突对象（例如重名索引），
--      再按第 2 条 DROP TABLE → repair → 重跑。禁止手工只建其中一条索引，否则原子性缺失且下次重跑
--      会因重名继续失败。
--   禁止修改已应用的 V10 文件（checksum）；失败只能按上表修复后 repair 重跑。
--
-- 时间口径：DATETIME(6) UTC（ADR-0008/0012）；租户表复合键与索引均以 tenant_id 开头。
-- =============================================================================

-- 审计导出请求：申请人只能在本租户内；审批人可能是 platform 租户账号，因此 approved_by 只做逻辑引用。
-- 状态机与审批字段由 CHECK 强一致：PENDING 无审批字段；APPROVED 必须有 decided_at 与 expires_at；
-- REJECTED 必须有 decided_at 且不设过期；申请人不得自批（approved_by 为全局唯一 UUID，跨租户比较安全）。
CREATE TABLE audit_export_request (
    tenant_id             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '申请人所属租户',
    id                    BINARY(16) NOT NULL COMMENT '导出请求 ID（UUIDv7）',
    requested_by          BINARY(16) NOT NULL COMMENT '申请人账号 ID',
    status                VARCHAR(32) NOT NULL COMMENT 'PENDING_APPROVAL|APPROVED|REJECTED',
    filters               JSON NOT NULL COMMENT '规范化查询条件；不含 tenantId，服务层限制 4 KiB',
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

-- 权限字典：两项审批权限在 M10 首次启用。
-- NOT EXISTS 只服务于“失败后 repair 重跑”的幂等恢复，不表示可以容忍预先占用；
-- 生产执行前仍按 ADR-0020 §5.4 预检这两个 code 不存在（OPS 前置）。
INSERT INTO permission (code, description)
SELECT 'audit.export.approve', '同租户审批审计导出；申请人不得自批'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'audit.export.approve');

INSERT INTO permission (code, description)
SELECT 'platform.audit.export.approve', '平台管理员审批单管理员租户的审计导出（仅元数据）'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'platform.audit.export.approve');

-- 既有租户角色回填。M02/M03 期间 audit.read 未授予任何角色；B1 之后新建的租户可能已由
-- RoleDefinitions 写入 audit.read，因此用 NOT EXISTS 幂等补齐，避免主键冲突导致迁移失败。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'audit.read'
  FROM role r
 WHERE r.code = 'TENANT_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'audit.read');

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'audit.export.approve'
  FROM role r
 WHERE r.code = 'TENANT_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'audit.export.approve');

-- 平台管理员只存在于 platform 保留租户；空库时角色可能尚未创建，
-- 由 BootstrapAdminRunner 在启动时按 RoleDefinitions 创建（含 platform.audit.export.approve）。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'audit.read'
  FROM role r
 WHERE r.tenant_id = 'platform'
   AND r.code = 'PLATFORM_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'audit.read');

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'platform.audit.export.approve'
  FROM role r
 WHERE r.tenant_id = 'platform'
   AND r.code = 'PLATFORM_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'platform.audit.export.approve');

-- M10 审计查询索引（ADR-0020 §4/§5.2，OPS-005）。
-- 两条索引同语句原子成败：查询只按主体租户过滤，tenant_id 必须在首位；
-- action/actor_id 等值过滤后按 occurred_at 范围与排序，避免全分区扫描。
-- 对象过滤继续复用 V6 的 idx_audit_target_time；outcome 低基数，过滤后置不建索引。
ALTER TABLE audit_event
    ADD KEY idx_audit_tenant_action_time (tenant_id, action, occurred_at),
    ADD KEY idx_audit_tenant_actor_time (tenant_id, actor_id, occurred_at),
    ALGORITHM = INPLACE,
    LOCK = NONE;

-- -----------------------------------------------------------------------------
-- 回滚（ADR-0020 §5.4，OPS-007；只在非生产副本演练后由维护账号执行）：
--
--   -- 1) 先停新 worker 与新 API，排空写入并做全量备份；回滚会丢失导出请求（操作数据）。
--   DROP TABLE audit_export_request;
--   ALTER TABLE audit_event
--       DROP INDEX idx_audit_tenant_action_time,
--       DROP INDEX idx_audit_tenant_actor_time;
--   DELETE FROM role_permission
--    WHERE permission_code IN ('audit.read', 'audit.export.approve', 'platform.audit.export.approve');
--   DELETE FROM permission
--    WHERE code IN ('audit.export.approve', 'platform.audit.export.approve');
--
--   -- 2) 维护账号删除 V10 历史行，并校验 schema 版本回到 9；应用账号不得删除 history。
--   DELETE FROM flyway_schema_history WHERE version = '10' AND success = 1;
--   SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1;  -- 必须为 9
--
-- 前提（F-022）：audit.read 在 M10 前没有授予任何角色；若发布后存在手工授予，回滚前必须先
-- 导出/记录这些映射，不能盲目删除。audit_event 与 M02/M03 数据不受回滚影响。
-- 回退 worker：必须先回滚 API 代码到 V9 版本再滚动 worker，否则新版 worker 的 expected=10
-- 会拒绝 V9 schema（RICCON-WORKER-001）；顺序与 OPS-004 的正向切换严格相反。
-- -----------------------------------------------------------------------------
