-- M04-V2 独立回滚重放（演练专用；生产回滚优先备份恢复，不执行 down migration）。
-- 依据 docs/adr/ADR-0021 §10.3；在一次性演练库 riccon_rollback_v2（mysql:8.4.13 快照）上执行，
-- 覆盖多 key 收敛：每组保留 1 行，有 ACTIVE 保留 ACTIVE；其余先归档再删除。

-- 0) 归档表（演练专用）
DROP TABLE IF EXISTS provider_credential_rollback_archive;
CREATE TABLE provider_credential_rollback_archive LIKE provider_credential;
ALTER TABLE provider_credential_rollback_archive
    ADD COLUMN archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

-- 1) 多 key 收敛：把每组 rn>1 的行写入归档表
INSERT INTO provider_credential_rollback_archive
SELECT pc.*, CURRENT_TIMESTAMP(6)
  FROM provider_credential pc
  JOIN (
        SELECT tenant_id, id
          FROM (
                SELECT tenant_id, id,
                       ROW_NUMBER() OVER (
                           PARTITION BY tenant_id, provider_id, credential_type
                           ORDER BY (status = 'ACTIVE') DESC, priority ASC, created_at DESC, id DESC
                       ) AS rn
                  FROM provider_credential
               ) ranked
         WHERE rn > 1
       ) extra
    ON extra.tenant_id = pc.tenant_id AND extra.id = pc.id;

-- 2) 删除多余行（必须先收敛，否则恢复旧唯一键会 ERROR 1062）
DELETE pc
  FROM provider_credential pc
  JOIN (
        SELECT tenant_id, id
          FROM (
                SELECT tenant_id, id,
                       ROW_NUMBER() OVER (
                           PARTITION BY tenant_id, provider_id, credential_type
                           ORDER BY (status = 'ACTIVE') DESC, priority ASC, created_at DESC, id DESC
                       ) AS rn
                  FROM provider_credential
               ) ranked
         WHERE rn > 1
       ) extra
    ON extra.tenant_id = pc.tenant_id AND extra.id = pc.id;

-- 3) 删除新 CHECK（顺序不可反；否则 RETIRED 归一失败）
ALTER TABLE provider_credential
    DROP CHECK chk_provider_credential_status,
    DROP CHECK chk_provider_credential_priority,
    DROP CHECK chk_provider_credential_failures;

-- 4) 非 ACTIVE 的保留行改为旧枚举；保留的 ACTIVE 不动
UPDATE provider_credential SET status = 'RETIRED' WHERE status IN ('DISABLED', 'INVALID');

-- 5) 恢复旧唯一键/CHECK，删除新唯一键/索引
ALTER TABLE provider_credential
    ADD UNIQUE KEY uk_provider_credential (tenant_id, provider_id, credential_type),
    ADD CONSTRAINT chk_provider_credential_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    DROP INDEX uk_provider_credential_fingerprint,
    DROP INDEX idx_provider_credential_pool;

-- 6) 删除新增列（小表；旧应用不读取）
ALTER TABLE provider_credential
    DROP COLUMN label, DROP COLUMN fingerprint, DROP COLUMN masked_hint, DROP COLUMN priority,
    DROP COLUMN consecutive_failures, DROP COLUMN cooldown_until, DROP COLUMN disabled_at,
    DROP COLUMN disabled_by, DROP COLUMN invalidated_at, DROP COLUMN last_used_at;

-- 7) 删除 M04 新增表（成本列保留在 model_invocation）
DROP TABLE IF EXISTS quota_usage;
DROP TABLE IF EXISTS quota_limit;
DROP TABLE IF EXISTS model_invocation_attempt;
DROP TABLE IF EXISTS model_gateway_switch;
DROP TABLE IF EXISTS model_routing_entry;
DROP TABLE IF EXISTS model_routing;

-- 8) 删除 Flyway V11 行（先备份 flyway_schema_history；演练库可回滚）
DELETE FROM flyway_schema_history WHERE version = '11';
