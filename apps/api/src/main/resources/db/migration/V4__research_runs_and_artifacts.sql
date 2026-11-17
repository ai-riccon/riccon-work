-- =============================================================================
-- V4：报告任务、运行、阶段、审批、产物与 outbox（M02 J2）
--
-- 状态机（ADR-0008）：
--   research_task: PENDING -> RUNNING -> WAITING_APPROVAL -> RUNNING
--                  -> SUCCEEDED | FAILED | CANCELLED
--   agent_run:     PENDING -> RUNNING -> WAITING_APPROVAL -> RUNNING
--                  -> SUCCEEDED | FAILED | CANCELLED；可重试错误进入 RETRY_WAIT
--   outbox:        PENDING -> DISPATCHED -> DONE | DEAD
-- 幂等：outbox 唯一键 (tenant_id, idempotency_key)；产物唯一键 (tenant_id, research_task_id)；
--       运行状态推进使用条件更新，重复投递不得第二次执行。
-- =============================================================================

-- 报告任务：用户可见的业务对象，与执行运行分离。
CREATE TABLE research_task (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id              BINARY(16) NOT NULL COMMENT '报告任务 ID（UUIDv7）',
    workspace_id    BINARY(16) NOT NULL COMMENT '所属工作空间',
    created_by      BINARY(16) NOT NULL COMMENT '创建人账号 ID',
    topic           VARCHAR(500) NOT NULL COMMENT '调研主题；不接受空串',
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|RUNNING|WAITING_APPROVAL|SUCCEEDED|FAILED|CANCELLED',
    current_stage   VARCHAR(16) NULL COMMENT 'RETRIEVAL|TOOL|APPROVAL|GENERATION',
    mcp_tool_id     BINARY(16) NULL COMMENT '选择的 MCP 工具 ID（逻辑引用，全局目录在 V6）',
    current_run_id  BINARY(16) NULL COMMENT '当前运行 ID（逻辑引用）',
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建幂等键，来自 Idempotency-Key',
    failure_code    VARCHAR(64) NULL COMMENT '失败错误码',
    failure_message VARCHAR(500) NULL COMMENT '脱敏后的失败原因',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    started_at      DATETIME(6) NULL COMMENT '首次开始时间（UTC）',
    finished_at     DATETIME(6) NULL COMMENT '终态时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_research_task_idempotency (tenant_id, created_by, idempotency_key),
    KEY idx_research_task_workspace (tenant_id, workspace_id, created_at),
    KEY idx_research_task_status (tenant_id, status, updated_at),
    CONSTRAINT fk_research_task_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_research_task_workspace FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspace (tenant_id, id),
    CONSTRAINT fk_research_task_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_research_task_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_research_task_topic CHECK (topic <> ''),
    CONSTRAINT chk_research_task_status CHECK (
        status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT chk_research_task_stage CHECK (
        current_stage IS NULL OR current_stage IN ('RETRIEVAL', 'TOOL', 'APPROVAL', 'GENERATION')
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '市场调研报告任务';

-- 执行运行：对话、文件解析与报告任务共用； Worker 通过租约与条件更新推进。
-- kind 决定哪些关联字段必须存在，见 chk_agent_run_kind。
CREATE TABLE agent_run (
    tenant_id        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id               BINARY(16) NOT NULL COMMENT '运行 ID（UUIDv7）',
    kind             VARCHAR(32) NOT NULL COMMENT 'CHAT|FILE_PARSE|KNOWLEDGE_INDEX|RESEARCH_REPORT',
    research_task_id BINARY(16) NULL COMMENT '报告任务 ID（kind=RESEARCH_REPORT）',
    conversation_id  BINARY(16) NULL COMMENT '会话 ID（kind=CHAT）',
    file_id          BINARY(16) NULL COMMENT '文件 ID（kind=FILE_PARSE）',
    user_id          BINARY(16) NOT NULL COMMENT '发起人账号 ID',
    status           VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|RUNNING|WAITING_APPROVAL|RETRY_WAIT|SUCCEEDED|FAILED|CANCELLED',
    current_stage    VARCHAR(16) NULL COMMENT 'RETRIEVAL|TOOL|APPROVAL|GENERATION',
    attempt          INT NOT NULL DEFAULT 1 COMMENT '当前尝试号，从 1 开始',
    max_attempts     INT NOT NULL DEFAULT 3 COMMENT '最大尝试次数',
    lease_owner      VARCHAR(128) NULL COMMENT '持有租约的 Worker 实例标识',
    lease_until      DATETIME(6) NULL COMMENT '租约到期时间（UTC）；过期可被重新领取',
    trace_id         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '贯穿 API/Worker/审计的 traceId',
    error_code       VARCHAR(64) NULL COMMENT '失败错误码',
    error_message    VARCHAR(500) NULL COMMENT '脱敏后的失败原因',
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    started_at       DATETIME(6) NULL COMMENT '开始时间（UTC）',
    finished_at      DATETIME(6) NULL COMMENT '终态时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    KEY idx_agent_run_task (tenant_id, research_task_id, created_at),
    KEY idx_agent_run_conversation (tenant_id, conversation_id, created_at),
    KEY idx_agent_run_file (tenant_id, file_id, created_at),
    KEY idx_agent_run_lease (tenant_id, status, lease_until),
    CONSTRAINT fk_agent_run_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_agent_run_task FOREIGN KEY (tenant_id, research_task_id) REFERENCES research_task (tenant_id, id),
    CONSTRAINT fk_agent_run_conversation FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    CONSTRAINT fk_agent_run_file FOREIGN KEY (tenant_id, file_id) REFERENCES file (tenant_id, id),
    CONSTRAINT fk_agent_run_user FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_agent_run_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_agent_run_kind CHECK (
        (kind = 'CHAT' AND conversation_id IS NOT NULL AND research_task_id IS NULL AND file_id IS NULL)
        OR (kind = 'FILE_PARSE' AND file_id IS NOT NULL AND research_task_id IS NULL AND conversation_id IS NULL)
        OR (kind = 'KNOWLEDGE_INDEX' AND research_task_id IS NULL AND conversation_id IS NULL)
        OR (kind = 'RESEARCH_REPORT' AND research_task_id IS NOT NULL AND conversation_id IS NULL AND file_id IS NULL)
    ),
    CONSTRAINT chk_agent_run_status CHECK (
        status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT chk_agent_run_stage CHECK (
        current_stage IS NULL OR current_stage IN ('RETRIEVAL', 'TOOL', 'APPROVAL', 'GENERATION')
    ),
    CONSTRAINT chk_agent_run_attempt CHECK (attempt >= 1 AND max_attempts >= 1 AND attempt <= max_attempts)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '执行运行与租约';

-- 运行阶段：每次尝试一条记录，构成 J2 时间线；details 只放白名单摘要。
CREATE TABLE agent_run_stage (
    tenant_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id            BINARY(16) NOT NULL COMMENT '阶段记录 ID（UUIDv7）',
    run_id        BINARY(16) NOT NULL COMMENT '所属运行 ID',
    stage         VARCHAR(16) NOT NULL COMMENT 'RETRIEVAL|TOOL|APPROVAL|GENERATION',
    attempt_no    INT NOT NULL DEFAULT 1 COMMENT '阶段尝试号，从 1 开始',
    status        VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING|SUCCEEDED|FAILED|SKIPPED',
    details       JSON NULL COMMENT '白名单摘要：查询 hash、命中数、工具调用 ID、审批 ID 等',
    error_code    VARCHAR(64) NULL COMMENT '稳定错误码',
    error_message VARCHAR(500) NULL COMMENT '脱敏后的失败原因',
    started_at    DATETIME(6) NULL COMMENT '开始时间（UTC）',
    finished_at   DATETIME(6) NULL COMMENT '结束时间（UTC）',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_agent_run_stage_attempt (tenant_id, run_id, stage, attempt_no),
    KEY idx_agent_run_stage_timeline (tenant_id, run_id, started_at),
    CONSTRAINT fk_agent_run_stage_run FOREIGN KEY (tenant_id, run_id) REFERENCES agent_run (tenant_id, id),
    CONSTRAINT chk_agent_run_stage_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_agent_run_stage_stage CHECK (stage IN ('RETRIEVAL', 'TOOL', 'APPROVAL', 'GENERATION')),
    CONSTRAINT chk_agent_run_stage_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT chk_agent_run_stage_attempt CHECK (attempt_no >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '运行阶段时间线';

-- 审批：每次工具调用尝试最多一个审批；批准后不可复用旧审批重试。
CREATE TABLE approval (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id              BINARY(16) NOT NULL COMMENT '审批 ID（UUIDv7）',
    research_task_id BINARY(16) NOT NULL COMMENT '报告任务 ID',
    run_id          BINARY(16) NOT NULL COMMENT '运行 ID',
    stage           VARCHAR(16) NOT NULL COMMENT '审批所在阶段，M02 固定 TOOL',
    attempt_no      INT NOT NULL DEFAULT 1 COMMENT '阶段尝试号',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|APPROVED|REJECTED|EXPIRED|CANCELLED',
    action_type     VARCHAR(32) NOT NULL DEFAULT 'MCP_TOOL_CALL' COMMENT 'M02 固定 MCP_TOOL_CALL',
    mcp_tool_id     BINARY(16) NOT NULL COMMENT '待调用的 MCP 工具 ID（逻辑引用）',
    tool_name       VARCHAR(128) NOT NULL COMMENT '工具名快照，审批展示必须使用原始工具名',
    params_preview  JSON NOT NULL COMMENT '脱敏后的入参摘要；禁止完整文件/会话/凭据',
    params_sha256   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '入参 SHA-256，用于审计核对',
    requested_by    BINARY(16) NOT NULL COMMENT '发起人账号 ID',
    decided_by      BINARY(16) NULL COMMENT '审批人账号 ID',
    decided_at      DATETIME(6) NULL COMMENT '审批时间（UTC）',
    decision_comment VARCHAR(500) NULL COMMENT '审批备注',
    expires_at      DATETIME(6) NULL COMMENT '审批过期时间（UTC）',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_approval_run_stage (tenant_id, run_id, stage, attempt_no),
    KEY idx_approval_pending (tenant_id, status, created_at),
    KEY idx_approval_task (tenant_id, research_task_id, created_at),
    CONSTRAINT fk_approval_task FOREIGN KEY (tenant_id, research_task_id) REFERENCES research_task (tenant_id, id),
    CONSTRAINT fk_approval_run FOREIGN KEY (tenant_id, run_id) REFERENCES agent_run (tenant_id, id),
    CONSTRAINT fk_approval_requested_by FOREIGN KEY (tenant_id, requested_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_approval_decided_by FOREIGN KEY (tenant_id, decided_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_approval_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_approval_action CHECK (action_type = 'MCP_TOOL_CALL'),
    CONSTRAINT chk_approval_attempt CHECK (attempt_no >= 1),
    -- 已决定的审批必须有审批人与时间；待审批状态不得提前写入决定。
    CONSTRAINT chk_approval_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
        OR status IN ('EXPIRED', 'CANCELLED')
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '外部工具调用审批';

-- 产物：一个报告任务最多一个产物；重试沿用同一产物并递增版本，避免重复产物。
CREATE TABLE artifact (
    tenant_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                   BINARY(16) NOT NULL COMMENT '产物 ID（UUIDv7）',
    workspace_id         BINARY(16) NOT NULL COMMENT '所属工作空间',
    research_task_id     BINARY(16) NULL COMMENT '来源报告任务；聊天产物可为空',
    conversation_id      BINARY(16) NULL COMMENT '来源会话；报告产物可为空',
    kind                 VARCHAR(16) NOT NULL DEFAULT 'REPORT' COMMENT 'M02 固定 REPORT',
    title                VARCHAR(300) NOT NULL COMMENT '产物标题',
    status               VARCHAR(16) NOT NULL DEFAULT 'SAVED' COMMENT 'DRAFT|SAVED|DELETED',
    current_version      INT NOT NULL DEFAULT 0 COMMENT '当前版本号；0 表示尚未保存内容',
    created_by           BINARY(16) NOT NULL COMMENT '创建人账号 ID',
    retention_expires_at DATETIME(6) NULL COMMENT '到期时间（UTC）；默认创建时间加租户保留天数',
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    deleted_at           DATETIME(6) NULL COMMENT '软删除时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_artifact_research_task (tenant_id, research_task_id),
    KEY idx_artifact_workspace (tenant_id, workspace_id, created_at),
    KEY idx_artifact_retention (tenant_id, retention_expires_at),
    CONSTRAINT fk_artifact_workspace FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspace (tenant_id, id),
    CONSTRAINT fk_artifact_task FOREIGN KEY (tenant_id, research_task_id) REFERENCES research_task (tenant_id, id),
    CONSTRAINT fk_artifact_conversation FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    CONSTRAINT fk_artifact_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_artifact_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_artifact_kind CHECK (kind = 'REPORT'),
    CONSTRAINT chk_artifact_status CHECK (status IN ('DRAFT', 'SAVED', 'DELETED')),
    CONSTRAINT chk_artifact_version CHECK (current_version >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '报告产物';

-- 产物版本：正文在对象存储，本表保存可校验的元数据与摘要。
CREATE TABLE artifact_version (
    tenant_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id           BINARY(16) NOT NULL COMMENT '版本 ID（UUIDv7）',
    artifact_id  BINARY(16) NOT NULL COMMENT '所属产物',
    version      INT NOT NULL COMMENT '版本号，从 1 开始',
    storage_key  VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对象存储 key，服务端生成',
    content_type VARCHAR(127) NOT NULL COMMENT '正文 MIME 类型，如 application/json 或 text/markdown',
    size_bytes   BIGINT NOT NULL COMMENT '正文大小（字节）',
    sha256       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '正文 SHA-256',
    summary      VARCHAR(1000) NULL COMMENT '摘要，用于列表展示',
    created_by   BINARY(16) NOT NULL COMMENT '生成人账号 ID',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_artifact_version (tenant_id, artifact_id, version),
    UNIQUE KEY uk_artifact_version_key (tenant_id, storage_key),
    CONSTRAINT fk_artifact_version_artifact FOREIGN KEY (tenant_id, artifact_id) REFERENCES artifact (tenant_id, id),
    CONSTRAINT fk_artifact_version_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_artifact_version_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_artifact_version_number CHECK (version >= 1),
    CONSTRAINT chk_artifact_version_size CHECK (size_bytes > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '产物版本';

-- 产物引用清单：每条必须能对应任务时间线中的检索命中或 MCP 调用，供 AC-10 人工抽查。
CREATE TABLE artifact_citation (
    tenant_id             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                    BINARY(16) NOT NULL COMMENT '引用 ID（UUIDv7）',
    artifact_version_id   BINARY(16) NOT NULL COMMENT '所属产物版本',
    ordinal               INT NOT NULL COMMENT '引用清单序号，从 1 开始',
    source_type           VARCHAR(16) NOT NULL COMMENT 'FILE|KNOWLEDGE|MCP',
    file_id               BINARY(16) NULL COMMENT '来源文件 ID（source_type=FILE）',
    knowledge_document_id BINARY(16) NULL COMMENT '来源知识库文档 ID（source_type=KNOWLEDGE）',
    mcp_invocation_id     BINARY(16) NULL COMMENT '来源 MCP 调用 ID（source_type=MCP，逻辑引用）',
    title_snapshot        VARCHAR(300) NOT NULL COMMENT '引用时的来源标题快照',
    snippet               VARCHAR(1000) NOT NULL COMMENT '引用片段摘要',
    locator_type          VARCHAR(16) NULL COMMENT 'PAGE|SHEET|PARAGRAPH；MCP 来源可为空',
    page_no               INT NULL COMMENT '页码，从 1 开始',
    sheet_name            VARCHAR(255) NULL COMMENT '工作表名',
    cell_range            VARCHAR(32) NULL COMMENT 'Excel 单元格范围',
    paragraph_no          INT NULL COMMENT '段落序号，从 1 开始',
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_artifact_citation_ordinal (tenant_id, artifact_version_id, ordinal),
    KEY idx_artifact_citation_file (tenant_id, file_id),
    KEY idx_artifact_citation_document (tenant_id, knowledge_document_id),
    CONSTRAINT fk_artifact_citation_version FOREIGN KEY (tenant_id, artifact_version_id) REFERENCES artifact_version (tenant_id, id),
    CONSTRAINT chk_artifact_citation_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_artifact_citation_ordinal CHECK (ordinal >= 1),
    CONSTRAINT chk_artifact_citation_source CHECK (source_type IN ('FILE', 'KNOWLEDGE', 'MCP')),
    CONSTRAINT chk_artifact_citation_locator CHECK (
        locator_type IS NULL OR locator_type IN ('PAGE', 'SHEET', 'PARAGRAPH')
    ),
    CONSTRAINT chk_artifact_citation_source_id CHECK (
        (source_type = 'FILE' AND file_id IS NOT NULL AND knowledge_document_id IS NULL AND mcp_invocation_id IS NULL)
        OR (source_type = 'KNOWLEDGE' AND knowledge_document_id IS NOT NULL AND file_id IS NULL AND mcp_invocation_id IS NULL)
        OR (source_type = 'MCP' AND mcp_invocation_id IS NOT NULL AND file_id IS NULL AND knowledge_document_id IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '产物引用清单';

-- Outbox：API 在业务事务内写入，Worker 轮询领取。
-- 领取查询是唯一允许跨租户读取业务表的路径（ADR-0016）；领取后必须校验令牌租户与 run.tenant_id 一致。
-- idx_outbox_claim 故意不以 tenant_id 开头：轮询需要跨租户按状态与可用时间排序。
CREATE TABLE agent_run_outbox (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id              BINARY(16) NOT NULL COMMENT '事件 ID（UUIDv7）',
    run_id          BINARY(16) NOT NULL COMMENT '关联运行 ID',
    event_type      VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件名，riccon.<domain>.<event>.v1',
    event_version   SMALLINT NOT NULL DEFAULT 1 COMMENT '事件 schema 版本',
    idempotency_key VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件幂等键，同租户唯一',
    payload         JSON NOT NULL COMMENT '脱敏后的任务参数；不含提示词、文件原文与凭据',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|DISPATCHED|DONE|DEAD',
    attempts        INT NOT NULL DEFAULT 0 COMMENT '已领取次数',
    available_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最早可领取时间（UTC）',
    lease_until     DATETIME(6) NULL COMMENT '领取租约到期时间（UTC）',
    lease_owner     VARCHAR(128) NULL COMMENT '领取者实例标识',
    last_error      VARCHAR(500) NULL COMMENT '最近一次失败原因（脱敏）',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_outbox_idempotency (tenant_id, idempotency_key),
    KEY idx_outbox_claim (status, available_at, lease_until),
    KEY idx_outbox_run (tenant_id, run_id),
    CONSTRAINT fk_outbox_run FOREIGN KEY (tenant_id, run_id) REFERENCES agent_run (tenant_id, id),
    CONSTRAINT chk_outbox_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'DISPATCHED', 'DONE', 'DEAD')),
    CONSTRAINT chk_outbox_version CHECK (event_version >= 1),
    CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Worker 任务 outbox';
