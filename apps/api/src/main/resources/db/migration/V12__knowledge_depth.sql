-- =============================================================================
-- V12：知识库纵深（M05 唯一迁移；B1）
--
-- 依据 docs/adr/ADR-0023-m05-knowledge-depth.md：
--   * §5.1 文档级 ACL（knowledge_document.visibility/department_id、knowledge_document_acl）；
--   * §5.1 解析元数据（parse_engine/parse_warnings/parse_summary/acl_version/retention_expires_at）；
--   * §5.1 引用版本快照（message_citation/artifact_citation.source_version）；
--   * §4.3 图片/扫描件定位列（file_chunk/knowledge_chunk 的 bbox_x/y/w/h 与 ocr_confidence，
--     可空、不加新 CHECK；ADR §7.2 的“内容”清单未列出这 5 列，属于脚本与正文的漂移，
--     已按 §4.3 落地并报告 Lead，不修改 docs/adr）；
--   * §6.2 外部连接器表（kb_connector/kb_connector_item；连接器行为由 M05b 实现）；
--   * §7.3 outbox 双通道所需的事件类型过滤索引（status, event_type, available_at, lease_until）；
--   * 新增权限 knowledge.acl.manage、knowledge.connector.read/manage 及 TENANT_ADMIN 回填。
--
-- 只向前兼容：全部新增列可空或带默认，历史 knowledge_document 行保持
-- visibility='TENANT'、department_id=NULL、acl_version=1，不做全表 UPDATE；
-- 新建表为空表；旧 API 可忽略新列/新表，旧 Worker 因 schema 版本严格校验不得在 V12 库启动。
--
-- 执行顺序：先扩展 knowledge_document（小表），再建 ACL/连接器表与引用/分块列，
-- 最后写权限字典与角色回填（NOT EXISTS 守卫，失败修复后可重跑）。
--
-- 锁与在线 DDL：加列/索引使用 INSTANT 或 INPLACE+LOCK=NONE；knowledge_document 的
-- 复合外键与 CHECK 在 MySQL 8.4 不支持 INPLACE（错误 1845/1846），显式改用 COPY+LOCK=SHARED。
-- 为什么不用 SET foreign_key_checks=0：那会让历史行跳过外键一致性校验，且把会话状态带进迁移。
-- 代价是两次小表重建（允许读、阻塞写）；实际锁等待与维护窗口见 M05 gate 迁移证据，
-- 若客户库文档量显著超出预算，M09 安排维护窗口，而不是静默降级为“无外键/无 CHECK”。
-- 这是 ADR-0023 §7.2 “MySQL 拒绝 INPLACE 时记录锁等待并改维护窗口”的已记录降级。
--
-- 回滚（仅应急、需备份与用户批准；不做 down migration）：
--   1) 确认没有 CONNECTOR 文档或已归档，否则先把可见范围回退到 TENANT；
--   2) DROP TABLE kb_connector_item / kb_connector / knowledge_document_acl；
--   3) knowledge_document 先删 FK/CHECK，再删索引与列（含 bbox 列），并把
--      chk_knowledge_document_source 恢复为 ('UPLOAD','SEED')；
--   4) 删除 message_citation/artifact_citation.source_version、outbox 通道索引与三条权限；
--   5) 维护账号删除 flyway_schema_history 的 V12 行并校验 MAX(version)=11。
--   完整可执行 SQL 见 apps/api/src/test/java/com/riccon/api/migration/V12MigrationTest.java
--   的 rollbackV12Objects()；生产禁止直接执行，必须走 M09 备份/演练与用户批准。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) knowledge_document：文档级 ACL、解析元数据与保留到期
-- -----------------------------------------------------------------------------

-- 全部新列可空或带常量默认，使用 INSTANT 避免重建表；NOT NULL 列都有默认值，
-- 历史行自动获得 visibility='TENANT' 与 acl_version=1，与 M02/M03 行为一致。
ALTER TABLE knowledge_document
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT' COMMENT 'TENANT|DEPT|USERS；历史文档默认 TENANT',
    ADD COLUMN department_id BINARY(16) NULL COMMENT 'DEPT 可见时必填；复合外键到 department',
    ADD COLUMN connector_id BINARY(16) NULL COMMENT '来源连接器（逻辑引用，M05b 写入）',
    ADD COLUMN external_id VARCHAR(1000) NULL COMMENT '连接器源对象标识，仅展示/排障，不返回给员工',
    ADD COLUMN parse_engine VARCHAR(16) NULL COMMENT 'PDFBOX|POI|TESSERACT|RAPIDOCR|NONE',
    ADD COLUMN parse_warnings JSON NULL COMMENT '白名单警告数组，不含原文',
    ADD COLUMN parse_summary JSON NULL COMMENT '结构化摘要：工作表/OCR/索引范围，不含原文',
    ADD COLUMN acl_version INT NOT NULL DEFAULT 1 COMMENT 'ACL 变更计数，用于索引刷新幂等键',
    ADD COLUMN retention_expires_at DATETIME(6) NULL COMMENT '内容保留到期（UTC）；默认创建后 180 天，租户策略覆盖',
    ALGORITHM = INSTANT;

-- 索引单独执行（INPLACE, LOCK=NONE）：一个 ALTER 同时加列与索引会退化为 INPLACE/COPY。
ALTER TABLE knowledge_document
    ADD KEY idx_kd_retention (tenant_id, retention_expires_at),
    ADD KEY idx_kd_visibility (tenant_id, visibility, department_id, created_at),
    ALGORITHM = INPLACE,
    LOCK = NONE;

-- 复合外键阻止跨租户部门；department_id 为空时外键不生效，符合 TENANT/USERS 语义。
--
-- MySQL 8.4 的在线 DDL 只有在 foreign_key_checks=OFF 时才允许 ALGORITHM=INPLACE 加外键；
-- 本迁移不关闭会话级外键检查（避免历史行跳过一致性校验），显式使用 COPY + LOCK=SHARED：
-- knowledge_document 是小表（单租户文档量为千级），COPY 期间允许读、阻塞写。实际锁等待与
-- 维护窗口结论记录在 M05 gate 的迁移证据与 RUNBOOK；若客户库文档量显著超出预算，M09 需安排
-- 维护窗口而不是静默降级为“无外键”。
ALTER TABLE knowledge_document
    ADD CONSTRAINT fk_kd_department FOREIGN KEY (tenant_id, department_id)
        REFERENCES department (tenant_id, id),
    ALGORITHM = COPY,
    LOCK = SHARED;

-- 可见范围是封闭集合；DEPT 必须带部门，其他取值不得带部门，避免“半收窄”数据。
-- source_type 增加 CONNECTOR：已有行只有 UPLOAD/SEED，先删旧约束再以同名新约束替换。
--
-- MySQL 8.4 对 ADD CHECK 同样不支持 ALGORITHM=INPLACE（错误 1845），必须 COPY。
-- 这里把两个可见范围 CHECK 与 source_type CHECK 替换合并在一次 COPY + LOCK=SHARED 重建中，
-- 只阻塞写、允许读；单条 ALTER 在 MySQL 8.0+ 是原子 DDL，失败不会留下“无 CHECK”的中间态。
ALTER TABLE knowledge_document
    ADD CONSTRAINT chk_kd_visibility CHECK (visibility IN ('TENANT', 'DEPT', 'USERS')),
    ADD CONSTRAINT chk_kd_visibility_department CHECK (
        (visibility = 'DEPT' AND department_id IS NOT NULL)
        OR (visibility <> 'DEPT' AND department_id IS NULL)
    ),
    DROP CHECK chk_knowledge_document_source,
    ADD CONSTRAINT chk_knowledge_document_source CHECK (source_type IN ('UPLOAD', 'SEED', 'CONNECTOR')),
    ALGORITHM = COPY,
    LOCK = SHARED;

-- -----------------------------------------------------------------------------
-- 2) 文档用户级 ACL（USERS 可见范围）
-- -----------------------------------------------------------------------------

-- 主键覆盖“某文档的授权列表”；idx_kda_user 覆盖“某用户可见的文档”，供检索 EXISTS 与列表过滤。
-- 文档只软删除，ACL 行不级联删除，便于恢复；复合外键阻止跨租户文档/用户。
CREATE TABLE knowledge_document_acl (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    document_id BINARY(16) NOT NULL COMMENT '知识库文档 ID',
    user_id     BINARY(16) NOT NULL COMMENT '被授权账号 ID',
    granted_by  BINARY(16) NULL COMMENT '最近一次授权操作者（逻辑引用）',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, document_id, user_id),
    KEY idx_kda_user (tenant_id, user_id, document_id),
    CONSTRAINT fk_kda_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_document (tenant_id, id),
    CONSTRAINT fk_kda_user FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_kda_tenant CHECK (tenant_id <> '')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库文档用户级 ACL（M05）';

-- -----------------------------------------------------------------------------
-- 3) 引用版本快照：识别源内容已更新，不回溯改写历史引用
-- -----------------------------------------------------------------------------

ALTER TABLE message_citation
    ADD COLUMN source_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '引用时的 knowledge_document.checksum；用于识别源内容已更新',
    ALGORITHM = INSTANT;

ALTER TABLE artifact_citation
    ADD COLUMN source_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '引用时的 knowledge_document.checksum；产物引用仍保留快照但展示按 ACL 遮蔽',
    ALGORITHM = INSTANT;

-- source_version 为空的旧引用不做 UPDATED 判定；仅 sourceType=KNOWLEDGE 的引用写入该列，
-- FILE 引用保持 NULL，不参与版本比较（ADR-0023 §5.1）。

-- -----------------------------------------------------------------------------
-- 4) 分块定位：OCR 区域与置信度（ADR-0023 §4.3）
-- -----------------------------------------------------------------------------

-- locator_type 保持 PAGE|SHEET|PARAGRAPH，不新增枚举值；图片区域用可空列表达。
-- 不新增 CHECK（大表 ADD CHECK 会 COPY 并阻塞写），合法范围由 Worker 应用层校验。
ALTER TABLE file_chunk
    ADD COLUMN bbox_x INT NULL COMMENT '渲染像素坐标：左上角 X',
    ADD COLUMN bbox_y INT NULL COMMENT '渲染像素坐标：左上角 Y',
    ADD COLUMN bbox_w INT NULL COMMENT '渲染像素坐标：宽度',
    ADD COLUMN bbox_h INT NULL COMMENT '渲染像素坐标：高度',
    ADD COLUMN ocr_confidence DECIMAL(4, 3) NULL COMMENT 'OCR 块置信度 0.000–1.000',
    ALGORITHM = INSTANT;

ALTER TABLE knowledge_chunk
    ADD COLUMN bbox_x INT NULL COMMENT '渲染像素坐标：左上角 X',
    ADD COLUMN bbox_y INT NULL COMMENT '渲染像素坐标：左上角 Y',
    ADD COLUMN bbox_w INT NULL COMMENT '渲染像素坐标：宽度',
    ADD COLUMN bbox_h INT NULL COMMENT '渲染像素坐标：高度',
    ADD COLUMN ocr_confidence DECIMAL(4, 3) NULL COMMENT 'OCR 块置信度 0.000–1.000',
    ALGORITHM = INSTANT;

-- -----------------------------------------------------------------------------
-- 5) 外部连接器（M05b 实现行为；V12 只建表）
-- -----------------------------------------------------------------------------

-- idx_kb_connector_schedule 是 ADR-0023 §6.2 明确的跨租户调度索引例外（ADR-0012 要求索引含
-- tenant_id）；Worker 按 (status, next_sync_at) 领取到期连接器，不带用户输入。
CREATE TABLE kb_connector (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id          BINARY(16) NOT NULL COMMENT '连接器 ID（UUIDv7）',
    name        VARCHAR(120) NOT NULL,
    type        VARCHAR(16) NOT NULL COMMENT 'S3',
    status      VARCHAR(16) NOT NULL DEFAULT 'PAUSED' COMMENT 'ACTIVE|PAUSED|ERROR',
    endpoint_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '部署允许列表 ID',
    config      JSON NOT NULL COMMENT 'bucket/prefix/region/includeExtensions/excludeGlobs',
    default_visibility VARCHAR(16) NOT NULL DEFAULT 'TENANT' COMMENT 'TENANT|DEPT；DB 默认仅兼容，API 创建必须显式提供',
    default_department_id BINARY(16) NULL,
    credential_ciphertext VARBINARY(8192) NULL COMMENT '信封加密（ADR-0006，purpose=connector-credential）',
    credential_key_id VARCHAR(64) NULL,
    credential_masked_hint VARCHAR(128) NULL COMMENT '仅掩码',
    schedule_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否按部署固定周期自动同步；不存 per-connector cron',
    next_sync_at DATETIME(6) NULL,
    last_sync_started_at DATETIME(6) NULL COMMENT '最近一次同步开始（UTC），失败也写',
    last_sync_finished_at DATETIME(6) NULL COMMENT '最近一次同步结束（UTC），失败也写',
    last_sync_status VARCHAR(16) NULL COMMENT 'SUCCESS|PARTIAL|ERROR',
    last_sync_complete BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'list 是否完整（未分页错误/截断/超限），只有完整才传播删除',
    last_success_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    last_sync_summary JSON NULL COMMENT 'scanned/created/updated/skipped/failed/deleted/complete',
    created_by  BINARY(16) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at  DATETIME(6) NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_kb_connector_name (tenant_id, name),
    KEY idx_kb_connector_schedule (status, next_sync_at),
    CONSTRAINT fk_kb_connector_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_kb_connector_department FOREIGN KEY (tenant_id, default_department_id)
        REFERENCES department (tenant_id, id),
    CONSTRAINT chk_kb_connector_type CHECK (type IN ('S3')),
    CONSTRAINT chk_kb_connector_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ERROR')),
    CONSTRAINT chk_kb_connector_sync_status CHECK (
        last_sync_status IS NULL OR last_sync_status IN ('SUCCESS', 'PARTIAL', 'ERROR')
    ),
    CONSTRAINT chk_kb_connector_visibility CHECK (default_visibility IN ('TENANT', 'DEPT')),
    CONSTRAINT chk_kb_connector_visibility_department CHECK (
        (default_visibility = 'DEPT' AND default_department_id IS NOT NULL)
        OR (default_visibility <> 'DEPT' AND default_department_id IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库外部连接器（M05）';

-- external_id_hash 规避 utf8mb4 长唯一键限制并保持大小写敏感；展示用 external_id 原文。
-- tombstone 复用：源端重现时重置同 (connector, external_id_hash) 行的状态与 generation，
-- 不插入第二行（ADR-0023 §6.2 F-007）。
CREATE TABLE kb_connector_item (
    tenant_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    id           BINARY(16) NOT NULL,
    connector_id BINARY(16) NOT NULL,
    external_id  VARCHAR(1000) NOT NULL COMMENT '源对象 key（仅展示）',
    external_id_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256(external_id)，用于唯一键',
    external_version VARCHAR(255) NULL COMMENT 'ETag/version',
    checksum     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '内容 SHA-256',
    document_id  BINARY(16) NULL COMMENT '对应知识库文档（逻辑引用）',
    generation   INT NOT NULL DEFAULT 1 COMMENT '同一 external_id 的删除-重现代数；复用 tombstone 行时 +1',
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|SYNCED|SKIPPED|FAILED|DELETED',
    error_code   VARCHAR(64) NULL,
    last_seen_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at   DATETIME(6) NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_kb_item_external (tenant_id, connector_id, external_id_hash),
    KEY idx_kb_item_status (tenant_id, connector_id, status),
    KEY idx_kb_item_document (tenant_id, document_id),
    CONSTRAINT fk_kb_item_connector FOREIGN KEY (tenant_id, connector_id) REFERENCES kb_connector (tenant_id, id),
    CONSTRAINT chk_kb_item_status CHECK (status IN ('PENDING', 'SYNCED', 'SKIPPED', 'FAILED', 'DELETED')),
    CONSTRAINT chk_kb_item_generation CHECK (generation >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '连接器源对象同步状态（M05）';

-- -----------------------------------------------------------------------------
-- 6) outbox 双通道：按 event_type 过滤的领取索引（ADR-0023 §7.3）
-- -----------------------------------------------------------------------------

-- 领取 SQL 增加 event_type IN (...)：重通道处理 FILE_PARSE/KNOWLEDGE_INDEX/CONNECTOR_SYNC，
-- 默认通道保持 chat/report 事件顺序。索引把 event_type 放在 status 之后、时间列之前，
-- 避免每个通道全表扫描；批量与租约上限仍由 riccon.worker.outbox.* 配置控制。
ALTER TABLE agent_run_outbox
    ADD KEY idx_outbox_lane (status, event_type, available_at, lease_until),
    ALGORITHM = INPLACE,
    LOCK = NONE;

-- -----------------------------------------------------------------------------
-- 7) 权限字典与角色回填
-- -----------------------------------------------------------------------------

-- 权限字典。新增权限码必须同步 platform-domain PermissionCodes 与 OpenAPI 的
-- x-riccon-permission；NOT EXISTS 只服务于失败后 repair 重跑的幂等恢复。
INSERT INTO permission (code, description)
SELECT 'knowledge.acl.manage', '管理任意知识库文档的 ACL 并做管理性读取'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'knowledge.acl.manage');

INSERT INTO permission (code, description)
SELECT 'knowledge.connector.read', '查看知识库连接器配置与同步状态'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'knowledge.connector.read');

INSERT INTO permission (code, description)
SELECT 'knowledge.connector.manage', '创建、修改、删除连接器与触发同步'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'knowledge.connector.manage');

-- 既有租户管理员回填：本租户文档 ACL 与连接器管理。新租户由 RoleDefinitions 开通时写入。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'knowledge.acl.manage'
  FROM role r
 WHERE r.code = 'TENANT_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'knowledge.acl.manage');

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'knowledge.connector.read'
  FROM role r
 WHERE r.code = 'TENANT_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'knowledge.connector.read');

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'knowledge.connector.manage'
  FROM role r
 WHERE r.code = 'TENANT_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = 'knowledge.connector.manage');
