-- =============================================================================
-- V6：审计事件（M02）
--
-- 模型见 ADR-0007：每条事件包含租户、操作者、动作、目标、结果、traceId 与时间；
-- append-only：应用层没有 update/delete，数据库触发器拒绝 UPDATE/DELETE；
-- 按 occurred_at 月分区，M09 由维护账号按分区 DROP 清理，禁止在线 DELETE。
-- event_id 为 UUIDv7（时间有序），按 ADR-0012 统一；分区要求 occurred_at 进入主键。
-- 本表不建外键：审计不得因业务删除而级联，也不得被业务事务以外的方式修改。
-- =============================================================================

CREATE TABLE audit_event (
    event_id       BINARY(16) NOT NULL COMMENT '事件 ID（UUIDv7，时间有序）',
    occurred_at    DATETIME(6) NOT NULL COMMENT '事件发生时间（UTC），分区键',
    tenant_id      VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户；平台级事件为 platform',
    actor_type     VARCHAR(16) NOT NULL COMMENT 'USER|SERVICE|SYSTEM',
    actor_id       VARCHAR(64) NOT NULL COMMENT '操作者 ID；用户 UUID、服务名或 system，不含 PII',
    action         VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定动作名，如 file.uploaded',
    target_type    VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'SESSION|FILE|ARTIFACT|MODEL|MCP_TOOL|USER|ROLE|TENANT|AUDIT 等',
    target_id      VARCHAR(64) NULL COMMENT '目标 ID；无具体目标时为空',
    outcome        VARCHAR(16) NOT NULL COMMENT 'SUCCESS|FAILURE|DENIED',
    trace_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '贯穿日志、错误响应与运行记录的 traceId',
    request_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '单次 HTTP 或任务请求 ID',
    source_ip      VARCHAR(45) NULL COMMENT '仅登录事件记录来源 IP，视为安全审计数据',
    details        JSON NULL COMMENT '白名单字段；禁止提示词、原文、密钥与完整 PII',
    schema_version SMALLINT NOT NULL DEFAULT 1 COMMENT '事件信封 schema 版本',
    PRIMARY KEY (event_id, occurred_at),
    KEY idx_audit_tenant_time (tenant_id, occurred_at),
    KEY idx_audit_action_time (action, occurred_at),
    KEY idx_audit_target_time (tenant_id, target_type, target_id, occurred_at),
    KEY idx_audit_trace (trace_id),
    CONSTRAINT chk_audit_event_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_audit_event_actor CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    CONSTRAINT chk_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT chk_audit_event_version CHECK (schema_version >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '审计事件（append-only，按月分区）'
  PARTITION BY RANGE COLUMNS (occurred_at) (
    PARTITION p2026_11 VALUES LESS THAN ('2026-12-01'),
    PARTITION p2026_12 VALUES LESS THAN ('2027-01-01'),
    PARTITION p2027_01 VALUES LESS THAN ('2027-02-01'),
    PARTITION p2027_02 VALUES LESS THAN ('2027-03-01'),
    PARTITION p2027_03 VALUES LESS THAN ('2027-04-01'),
    PARTITION p2027_04 VALUES LESS THAN ('2027-05-01'),
    PARTITION p2027_05 VALUES LESS THAN ('2027-06-01'),
    PARTITION p2027_06 VALUES LESS THAN ('2027-07-01'),
    PARTITION p2027_07 VALUES LESS THAN ('2027-08-01'),
    PARTITION p2027_08 VALUES LESS THAN ('2027-09-01'),
    PARTITION p2027_09 VALUES LESS THAN ('2027-10-01'),
    PARTITION p2027_10 VALUES LESS THAN ('2027-11-01'),
    PARTITION p2027_11 VALUES LESS THAN ('2027-12-01'),
    PARTITION p2027_12 VALUES LESS THAN ('2028-01-01'),
    PARTITION pmax VALUES LESS THAN (MAXVALUE)
  );

-- 数据库层 append-only：任何 UPDATE 直接失败；M02 至少落地触发器与“无修改/删除 API”。
CREATE TRIGGER trg_audit_event_no_update
    BEFORE UPDATE
    ON audit_event
    FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event is append-only';

-- 数据库层 append-only：任何 DELETE 直接失败；保留清理由 M09 按分区 DROP。
CREATE TRIGGER trg_audit_event_no_delete
    BEFORE DELETE
    ON audit_event
    FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event is append-only';
