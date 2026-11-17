-- =============================================================================
-- V3：会话、消息、文件与知识库（M02 J1）
--
-- 权威数据在 MySQL：会话、消息、引用、文件元数据、解析分块与知识库分块。
-- OpenSearch 只保存可重建的检索索引，索引名按租户派生（ADR-0009 第 7 节）。
-- 引用保存来源快照，源文件软删除后历史回答仍可解释来源。
-- =============================================================================

-- 会话：属于工作空间，保留期由租户策略在创建时计算。
CREATE TABLE conversation (
    tenant_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                   BINARY(16) NOT NULL COMMENT '会话 ID（UUIDv7）',
    workspace_id         BINARY(16) NOT NULL COMMENT '所属工作空间',
    created_by           BINARY(16) NOT NULL COMMENT '创建人账号 ID',
    title                VARCHAR(200) NULL COMMENT '会话标题；为空时前端显示新会话',
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|ARCHIVED',
    last_message_at      DATETIME(6) NULL COMMENT '最近一条消息时间（UTC）',
    retention_expires_at DATETIME(6) NULL COMMENT '到期时间（UTC）；默认创建时间加租户保留天数',
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    deleted_at           DATETIME(6) NULL COMMENT '软删除时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    KEY idx_conversation_workspace (tenant_id, workspace_id, updated_at),
    KEY idx_conversation_owner (tenant_id, created_by, updated_at),
    KEY idx_conversation_retention (tenant_id, retention_expires_at),
    CONSTRAINT fk_conversation_workspace FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspace (tenant_id, id),
    CONSTRAINT fk_conversation_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_conversation_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_conversation_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '会话';

-- 逻辑文件：对象存储保存原文，本表保存元数据与解析入口。
-- storage_key 固定格式 tenants/{tenantId}/file/{yyyy}/{objectId}，由服务端生成，客户端不可传入。
CREATE TABLE file (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id              BINARY(16) NOT NULL COMMENT '文件 ID（UUIDv7）',
    workspace_id    BINARY(16) NOT NULL COMMENT '所属工作空间',
    conversation_id BINARY(16) NULL COMMENT '上传所在会话；知识库文件可为空',
    uploaded_by     BINARY(16) NOT NULL COMMENT '上传人账号 ID',
    original_name   VARCHAR(255) NOT NULL COMMENT '原始文件名，仅作元数据与展示，不进入存储路径',
    format          VARCHAR(16) NOT NULL COMMENT 'PDF|DOCX|XLSX|PNG|JPEG',
    mime_type       VARCHAR(127) NOT NULL COMMENT '服务端嗅探后的 MIME 类型',
    size_bytes      BIGINT NOT NULL COMMENT '文件大小（字节），上限 200 MB',
    sha256          CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内容 SHA-256，十六进制小写',
    storage_key     VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对象存储 key，服务端生成',
    status          VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE|DELETED',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    deleted_at      DATETIME(6) NULL COMMENT '软删除时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_file_storage_key (tenant_id, storage_key),
    KEY idx_file_workspace (tenant_id, workspace_id, created_at),
    KEY idx_file_conversation (tenant_id, conversation_id, created_at),
    KEY idx_file_checksum (tenant_id, sha256),
    CONSTRAINT fk_file_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_file_workspace FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspace (tenant_id, id),
    CONSTRAINT fk_file_conversation FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    CONSTRAINT fk_file_uploaded_by FOREIGN KEY (tenant_id, uploaded_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_file_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_file_format CHECK (format IN ('PDF', 'DOCX', 'XLSX', 'PNG', 'JPEG')),
    CONSTRAINT chk_file_status CHECK (status IN ('AVAILABLE', 'DELETED')),
    CONSTRAINT chk_file_size CHECK (size_bytes > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '逻辑文件元数据';

-- 文件解析尝试：同一文件可因失败重试产生多条记录，attempt 递增。
CREATE TABLE file_parse (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id              BINARY(16) NOT NULL COMMENT '解析尝试 ID（UUIDv7）',
    file_id         BINARY(16) NOT NULL COMMENT '文件 ID',
    attempt         INT NOT NULL DEFAULT 1 COMMENT '解析尝试号，从 1 开始',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|RUNNING|SUCCEEDED|FAILED',
    parser_version  VARCHAR(32) NULL COMMENT '解析器版本，便于失败复现',
    page_count      INT NULL COMMENT 'PDF/图片页数',
    sheet_count     INT NULL COMMENT 'Excel 工作表数量',
    chunk_count     INT NULL COMMENT '成功后生成的分块数量',
    text_object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '抽取全文的对象 key，用于重建分块',
    error_code      VARCHAR(64) NULL COMMENT '稳定错误码，如 RICCON-FILE-422',
    error_message   VARCHAR(500) NULL COMMENT '脱敏后的失败原因，不包含原文或路径',
    started_at      DATETIME(6) NULL COMMENT '开始时间（UTC）',
    finished_at     DATETIME(6) NULL COMMENT '结束时间（UTC）',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_file_parse_attempt (tenant_id, file_id, attempt),
    KEY idx_file_parse_status (tenant_id, status, created_at),
    KEY idx_file_parse_file (tenant_id, file_id, created_at),
    CONSTRAINT fk_file_parse_file FOREIGN KEY (tenant_id, file_id) REFERENCES file (tenant_id, id),
    CONSTRAINT chk_file_parse_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_file_parse_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_file_parse_attempt CHECK (attempt >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文件解析尝试';

-- 文件分块：保存权威文本与定位，OpenSearch 索引可从本表重建。
CREATE TABLE file_chunk (
    tenant_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id            BINARY(16) NOT NULL COMMENT '分块 ID（UUIDv7）',
    file_id       BINARY(16) NOT NULL COMMENT '文件 ID',
    parse_id      BINARY(16) NOT NULL COMMENT '所属解析尝试 ID',
    ordinal       INT NOT NULL COMMENT '文件内分块序号，从 1 开始',
    locator_type  VARCHAR(16) NOT NULL COMMENT 'PAGE|SHEET|PARAGRAPH',
    page_no       INT NULL COMMENT '页码，从 1 开始',
    sheet_name    VARCHAR(255) NULL COMMENT '工作表名',
    cell_range    VARCHAR(32) NULL COMMENT 'Excel 单元格范围，如 A1:D20',
    paragraph_no  INT NULL COMMENT '段落序号，从 1 开始',
    content       MEDIUMTEXT NOT NULL COMMENT '分块原文，作为不可信数据进入模型上下文',
    token_count   INT NULL COMMENT '估算 token 数，用于上下文预算',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_file_chunk_ordinal (tenant_id, parse_id, ordinal),
    KEY idx_file_chunk_file (tenant_id, file_id, ordinal),
    CONSTRAINT fk_file_chunk_file FOREIGN KEY (tenant_id, file_id) REFERENCES file (tenant_id, id),
    CONSTRAINT fk_file_chunk_parse FOREIGN KEY (tenant_id, parse_id) REFERENCES file_parse (tenant_id, id),
    CONSTRAINT chk_file_chunk_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_file_chunk_ordinal CHECK (ordinal >= 1),
    CONSTRAINT chk_file_chunk_locator CHECK (locator_type IN ('PAGE', 'SHEET', 'PARAGRAPH'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '文件分块与引用定位';

-- 知识库文档：M02 无文档级 ACL，同租户员工可检索全部文档（THREAT-MODEL R-01）。
CREATE TABLE knowledge_document (
    tenant_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id            BINARY(16) NOT NULL COMMENT '知识库文档 ID（UUIDv7）',
    title         VARCHAR(300) NOT NULL COMMENT '文档标题',
    source_type   VARCHAR(16) NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD|SEED',
    file_id       BINARY(16) NULL COMMENT '来源文件；演示语料可为空',
    checksum      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内容 SHA-256',
    mime_type     VARCHAR(127) NOT NULL COMMENT '来源 MIME 类型',
    size_bytes    BIGINT NOT NULL COMMENT '来源大小（字节）',
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|INDEXING|INDEXED|FAILED|DELETED',
    error_code    VARCHAR(64) NULL COMMENT '索引失败错误码',
    error_message VARCHAR(500) NULL COMMENT '脱敏后的失败原因',
    indexed_at    DATETIME(6) NULL COMMENT '索引完成时间（UTC）',
    created_by    BINARY(16) NULL COMMENT '添加人账号 ID；系统语料为空',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    deleted_at    DATETIME(6) NULL COMMENT '软删除时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    KEY idx_knowledge_document_status (tenant_id, status, created_at),
    KEY idx_knowledge_document_file (tenant_id, file_id),
    CONSTRAINT fk_knowledge_document_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_knowledge_document_file FOREIGN KEY (tenant_id, file_id) REFERENCES file (tenant_id, id),
    CONSTRAINT chk_knowledge_document_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_knowledge_document_source CHECK (source_type IN ('UPLOAD', 'SEED')),
    CONSTRAINT chk_knowledge_document_status CHECK (status IN ('PENDING', 'INDEXING', 'INDEXED', 'FAILED', 'DELETED')),
    CONSTRAINT chk_knowledge_document_size CHECK (size_bytes >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库文档元数据';

-- 知识库分块：MySQL 为权威，OpenSearch 索引文档 ID 与本地 chunk id 一致，便于重建与引用校验。
CREATE TABLE knowledge_chunk (
    tenant_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id           BINARY(16) NOT NULL COMMENT '分块 ID（UUIDv7）',
    document_id  BINARY(16) NOT NULL COMMENT '所属知识库文档',
    ordinal      INT NOT NULL COMMENT '文档内分块序号，从 1 开始',
    locator_type VARCHAR(16) NOT NULL COMMENT 'PAGE|SHEET|PARAGRAPH',
    page_no      INT NULL COMMENT '页码，从 1 开始',
    sheet_name   VARCHAR(255) NULL COMMENT '工作表名',
    cell_range   VARCHAR(32) NULL COMMENT 'Excel 单元格范围',
    paragraph_no INT NULL COMMENT '段落序号，从 1 开始',
    content      MEDIUMTEXT NOT NULL COMMENT '分块原文，作为不可信数据进入模型上下文',
    token_count  INT NULL COMMENT '估算 token 数',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_knowledge_chunk_ordinal (tenant_id, document_id, ordinal),
    KEY idx_knowledge_chunk_document (tenant_id, document_id, ordinal),
    CONSTRAINT fk_knowledge_chunk_document FOREIGN KEY (tenant_id, document_id) REFERENCES knowledge_document (tenant_id, id),
    CONSTRAINT chk_knowledge_chunk_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_knowledge_chunk_ordinal CHECK (ordinal >= 1),
    CONSTRAINT chk_knowledge_chunk_locator CHECK (locator_type IN ('PAGE', 'SHEET', 'PARAGRAPH'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库分块与引用定位';

-- 消息：seq 在会话内单调递增，保证流式停止后重开顺序稳定。
-- run_id/model_id 为跨聚合逻辑引用，不建外键，避免运行与模型目录更新时阻塞消息写入。
CREATE TABLE message (
    tenant_id         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                BINARY(16) NOT NULL COMMENT '消息 ID（UUIDv7）',
    conversation_id   BINARY(16) NOT NULL COMMENT '所属会话',
    seq               BIGINT NOT NULL COMMENT '会话内序号，从 1 开始',
    role              VARCHAR(16) NOT NULL COMMENT 'USER|ASSISTANT|SYSTEM|TOOL',
    content           MEDIUMTEXT NOT NULL COMMENT '消息内容；系统提示不通过此表回显',
    status            VARCHAR(16) NOT NULL DEFAULT 'COMPLETED' COMMENT 'PENDING|STREAMING|COMPLETED|STOPPED|FAILED',
    client_message_id VARCHAR(64) NULL COMMENT '客户端幂等键，用于重试去重',
    run_id            BINARY(16) NULL COMMENT '关联运行 ID（逻辑引用）',
    model_id          BINARY(16) NULL COMMENT '生成该消息的模型 ID（逻辑引用）',
    error_code        VARCHAR(64) NULL COMMENT '失败错误码',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_message_seq (tenant_id, conversation_id, seq),
    UNIQUE KEY uk_message_client_id (tenant_id, conversation_id, client_message_id),
    KEY idx_message_conversation (tenant_id, conversation_id, created_at),
    KEY idx_message_run (tenant_id, run_id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    CONSTRAINT chk_message_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_message_seq CHECK (seq >= 1),
    CONSTRAINT chk_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    CONSTRAINT chk_message_status CHECK (status IN ('PENDING', 'STREAMING', 'COMPLETED', 'STOPPED', 'FAILED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '会话消息';

-- 消息引用：保存来源快照与定位，chunk_id 为逻辑引用，源文件删除或索引重建后历史引用仍可展示。
CREATE TABLE message_citation (
    tenant_id             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                    BINARY(16) NOT NULL COMMENT '引用 ID（UUIDv7）',
    message_id            BINARY(16) NOT NULL COMMENT '所属消息',
    ordinal               INT NOT NULL COMMENT '引用展示序号，从 1 开始',
    source_type           VARCHAR(16) NOT NULL COMMENT 'FILE|KNOWLEDGE',
    file_id               BINARY(16) NULL COMMENT '来源文件 ID（source_type=FILE）',
    knowledge_document_id BINARY(16) NULL COMMENT '来源知识库文档 ID（source_type=KNOWLEDGE）',
    chunk_id              BINARY(16) NULL COMMENT '分块 ID（逻辑引用，索引重建后可能变化）',
    locator_type          VARCHAR(16) NOT NULL COMMENT 'PAGE|SHEET|PARAGRAPH',
    page_no               INT NULL COMMENT '页码，从 1 开始',
    sheet_name            VARCHAR(255) NULL COMMENT '工作表名',
    cell_range            VARCHAR(32) NULL COMMENT 'Excel 单元格范围',
    paragraph_no          INT NULL COMMENT '段落序号，从 1 开始',
    title_snapshot        VARCHAR(300) NOT NULL COMMENT '引用时的来源标题快照',
    snippet               VARCHAR(1000) NOT NULL COMMENT '引用片段，限制长度，不存原文全文',
    score                 DECIMAL(6, 5) NULL COMMENT '检索相关度，0–1',
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_message_citation_ordinal (tenant_id, message_id, ordinal),
    KEY idx_message_citation_file (tenant_id, file_id),
    KEY idx_message_citation_document (tenant_id, knowledge_document_id),
    CONSTRAINT fk_message_citation_message FOREIGN KEY (tenant_id, message_id) REFERENCES message (tenant_id, id),
    CONSTRAINT chk_message_citation_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_message_citation_ordinal CHECK (ordinal >= 1),
    CONSTRAINT chk_message_citation_source CHECK (source_type IN ('FILE', 'KNOWLEDGE')),
    CONSTRAINT chk_message_citation_locator CHECK (locator_type IN ('PAGE', 'SHEET', 'PARAGRAPH')),
    -- 来源类型与来源 ID 必须一致，防止把知识库引用标成文件引用。
    CONSTRAINT chk_message_citation_source_id CHECK (
        (source_type = 'FILE' AND file_id IS NOT NULL AND knowledge_document_id IS NULL)
        OR (source_type = 'KNOWLEDGE' AND knowledge_document_id IS NOT NULL AND file_id IS NULL)
    ),
    CONSTRAINT chk_message_citation_score CHECK (score IS NULL OR (score >= 0 AND score <= 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '消息引用与定位快照';
