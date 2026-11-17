-- =============================================================================
-- V8：审批参数加密重放与脱敏预览（ADR-0017）
--
-- 背景：审批通过后必须用“与审批时完全一致的参数”重放 MCP 工具调用。原设计把规范化
-- 完整参数写入 approval.params_preview，违反 ADR-0007 的“脱敏摘要”语义：预览会展示
-- 给审批人，也会进入数据库备份与只读副本，等于把工具入参原文长期明文保存。
--
-- 本迁移把两种用途拆开：
--   1. params_preview：只允许脱敏摘要（白名单键、截断值），供审批 UI 与时间线展示；
--   2. params_ciphertext + params_key_id：保存规范化完整参数的 AES-256-GCM 密文与密钥 ID，
--      只在审批通过后由 Worker 解密并重放，不通过任何 API 返回；
--   3. params_sha256：沿用既有列，保存规范化完整参数的 SHA-256（小写 hex），用于审计核对、
--      审批绑定与解密后完整性校验。
--
-- 密文格式（platform-common AesGcmEnvelope）：
--   version(1) || nonce(12) || AES-256-GCM ciphertext || tag(16)
-- AAD：UTF-8(tenantId + '|' + approvalId)；密钥来自 tenant_data_key（purpose=approval-params）。
-- 密文列上限 8192 字节，对应明文上限 8163 字节；应用层规范 JSON 上限 8000 字节。
--
-- 兼容与回滚：
--   - 新增列可空，保证历史行可读；V8 是 expand 阶段，不删除、不改写既有列数据；
--   - 旧 Worker 仍可读取 params_preview，但 M02 gate 前必须切换到密文重放（后续 Worker 任务）；
--   - 回滚采用前向修复：保留新列、部署旧版本即可，不回滚数据，也不删除密文。
-- =============================================================================

ALTER TABLE approval
    MODIFY COLUMN params_preview JSON NOT NULL
        COMMENT '脱敏摘要（白名单键与截断值，≤4096 字节）；禁止完整参数、文件/会话内容与凭据',
    MODIFY COLUMN params_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '规范化完整参数的 SHA-256（小写 hex），用于审计核对、审批绑定与重放校验',
    ADD COLUMN params_ciphertext VARBINARY(8192) NULL
        COMMENT '规范化完整参数的 AES-256-GCM 密文：version(1)||nonce(12)||ciphertext||tag(16)' AFTER params_preview,
    ADD COLUMN params_key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '参数加密密钥 ID（tenant_data_key.key_id，purpose=approval-params）' AFTER params_ciphertext,
    -- 预览必须是 JSON 对象，且限制在摘要预算内；SQL 无法识别业务敏感键，键级白名单由应用与测试保证。
    ADD CONSTRAINT chk_approval_params_preview_object CHECK (JSON_TYPE(params_preview) = 'OBJECT'),
    ADD CONSTRAINT chk_approval_params_preview_size CHECK (JSON_STORAGE_SIZE(params_preview) <= 4096),
    ADD CONSTRAINT chk_approval_params_sha256 CHECK (REGEXP_LIKE(params_sha256, '^[0-9a-f]{64}$')),
    -- 密文与密钥 ID 必须成对出现：只写密文会让轮换无从定位密钥，只写密钥 ID 则没有可重放内容。
    ADD CONSTRAINT chk_approval_params_envelope CHECK (
        (params_ciphertext IS NULL AND params_key_id IS NULL)
        OR (params_ciphertext IS NOT NULL AND params_key_id IS NOT NULL)
    ),
    ADD CONSTRAINT chk_approval_params_ciphertext_size CHECK (
        params_ciphertext IS NULL OR OCTET_LENGTH(params_ciphertext) >= 29
    ),
    ADD CONSTRAINT chk_approval_params_key_id CHECK (
        params_key_id IS NULL OR (params_key_id <> '' AND CHAR_LENGTH(params_key_id) <= 64)
    ),
    -- 密钥轮换或退役时按租户与 key id 定位待重加密的审批；索引以 tenant_id 开头（ADR-0012）。
    ADD KEY idx_approval_params_key (tenant_id, params_key_id);
