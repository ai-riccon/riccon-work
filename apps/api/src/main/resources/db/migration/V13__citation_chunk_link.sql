-- V13 引用分块关联（M05 B9 修复，REV-TASK-0171-001 F-001）
--
-- 目的：artifact_citation 缺少 chunk_id，J2 产物引用无法按分块读取 bbox/ocr_confidence，
-- 导致 AC-03 页内区域与 AC-04 低置信提示在报告引用中不可验。本迁移只增加可空逻辑引用列与租户索引；
-- 不建外键（分块可重建、chunk_id 是引用快照，索引重建后允许悬空，读取时按缺失处理），
-- 不回填历史引用（历史 chunk_id 为 NULL，bbox/ocrConfidence 返回 null，不报错）。
--
-- 回滚 SQL（仅回滚窗口；先删索引再删列，最后删除 history 13，与 ADR-0023 §7.2 的回滚顺序一致）：
--   ALTER TABLE artifact_citation DROP INDEX idx_artifact_citation_chunk, ALGORITHM=INPLACE, LOCK=NONE;
--   ALTER TABLE artifact_citation DROP COLUMN chunk_id, ALGORITHM=INSTANT;
--   DELETE FROM flyway_schema_history WHERE version = '13';
-- 回滚后旧 Worker（EXPECTED_SCHEMA_VERSION=12）方可启动；新 Worker（expected=13）必须先停。

ALTER TABLE artifact_citation
    ADD COLUMN chunk_id BINARY(16) NULL
        COMMENT '引用时的 knowledge_chunk.id 逻辑引用；仅 KNOWLEDGE 引用写入',
    ALGORITHM = INSTANT;

-- 按 chunk_id 批量读取 bbox/ocr_confidence；索引含 tenant_id，满足 ADR-0012 的租户索引约定。
ALTER TABLE artifact_citation
    ADD KEY idx_artifact_citation_chunk (tenant_id, chunk_id),
    ALGORITHM = INPLACE,
    LOCK = NONE;
