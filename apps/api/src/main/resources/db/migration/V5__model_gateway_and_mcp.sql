-- =============================================================================
-- V5：模型网关、MCP 目录与调用记录（M02）
--
-- 全局目录：model_provider、model、mcp_server、mcp_tool 由平台统一维护，不按租户复制（ADR-0012 例外）。
-- 租户数据：tenant_data_key、provider_credential、mcp_tool_grant、model_invocation、mcp_invocation。
-- 凭据：按 ADR-0006 使用 AES-256-GCM 信封加密；本表只存密文、keyId 与 masterKeyId，禁止明文。
-- M02 模型凭据为部署级，tenant_id 使用 platform；M04 可改为租户级而不改表结构。
-- =============================================================================

-- 模型供应商全局目录：M02 只有一个 OpenAI 兼容端点，base_url 由部署配置固定，禁止用户可控。
CREATE TABLE model_provider (
    id         BINARY(16) NOT NULL COMMENT '供应商 ID（UUIDv7）',
    code       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '供应商代码，如 openai-compatible',
    name       VARCHAR(200) NOT NULL COMMENT '展示名',
    protocol   VARCHAR(32) NOT NULL DEFAULT 'OPENAI_COMPATIBLE' COMMENT 'M02 固定 OPENAI_COMPATIBLE',
    base_url   VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定 HTTPS 端点；禁止用户输入',
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_provider_code (code),
    CONSTRAINT chk_model_provider_protocol CHECK (protocol = 'OPENAI_COMPATIBLE'),
    CONSTRAINT chk_model_provider_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模型供应商（全局表）';

-- 模型全局目录：能力、上下文与可选价格；M04 用价格做成本看板。
CREATE TABLE model (
    id                    BINARY(16) NOT NULL COMMENT '模型 ID（UUIDv7）',
    provider_id           BINARY(16) NOT NULL COMMENT '供应商 ID',
    model_key             VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '上游模型标识，如 gpt-4o-mini',
    display_name          VARCHAR(200) NOT NULL COMMENT '展示名',
    context_window        INT NOT NULL COMMENT '上下文窗口（token）',
    max_output_tokens     INT NOT NULL COMMENT '最大输出 token',
    capabilities          JSON NULL COMMENT '能力列表：chat/stream/tool 等',
    input_price_per_1m    DECIMAL(12, 6) NULL COMMENT '输入价格（每百万 token，币种由部署约定）',
    output_price_per_1m   DECIMAL(12, 6) NULL COMMENT '输出价格（每百万 token）',
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_provider_key (provider_id, model_key),
    CONSTRAINT fk_model_provider FOREIGN KEY (provider_id) REFERENCES model_provider (id),
    CONSTRAINT chk_model_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_model_context CHECK (context_window > 0 AND max_output_tokens > 0),
    CONSTRAINT chk_model_price CHECK (
        (input_price_per_1m IS NULL OR input_price_per_1m >= 0)
        AND (output_price_per_1m IS NULL OR output_price_per_1m >= 0)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模型目录（全局表）';

-- 租户数据密钥：被主密钥包裹的 DEK，按 tenant_id + purpose 生成（ADR-0006）。
CREATE TABLE tenant_data_key (
    tenant_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户；平台级凭据使用 platform',
    key_id        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DEK 标识，写入密文前缀',
    purpose       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用途，如 model-credential/mcp-credential',
    wrapped_dek   VARBINARY(512) NOT NULL COMMENT '被主密钥包裹的 DEK',
    wrap_nonce    VARBINARY(24) NOT NULL COMMENT '包裹 DEK 使用的 nonce',
    algorithm     VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定 AES-256-GCM',
    master_key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '主密钥标识，用于轮换',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    retired_at    DATETIME(6) NULL COMMENT '停用时间（UTC）；无引用后可清理',
    PRIMARY KEY (tenant_id, key_id),
    KEY idx_tenant_data_key_purpose (tenant_id, purpose, retired_at),
    CONSTRAINT fk_tenant_data_key_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_tenant_data_key_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_tenant_data_key_algorithm CHECK (algorithm = 'AES-256-GCM')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '租户数据密钥（信封加密）';

-- 供应商凭据：只存密文。M02 为部署级（tenant_id=platform），M04 可写租户级记录。
CREATE TABLE provider_credential (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户；平台级为 platform',
    id              BINARY(16) NOT NULL COMMENT '凭据记录 ID（UUIDv7）',
    provider_id     BINARY(16) NOT NULL COMMENT '供应商 ID',
    credential_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'M02 固定 API_KEY',
    ciphertext      VARCHAR(4096) NOT NULL COMMENT 'enc:v1:<keyId>:<Base64(nonce||ciphertext||tag)>',
    key_id          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DEK 标识',
    master_key_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '主密钥标识',
    version         INT NOT NULL DEFAULT 1 COMMENT '凭据版本，用于轮换审计',
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|RETIRED',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    rotated_at      DATETIME(6) NULL COMMENT '最近轮换时间（UTC）',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_provider_credential (tenant_id, provider_id, credential_type),
    CONSTRAINT fk_provider_credential_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_provider_credential_provider FOREIGN KEY (provider_id) REFERENCES model_provider (id),
    CONSTRAINT fk_provider_credential_key FOREIGN KEY (tenant_id, key_id) REFERENCES tenant_data_key (tenant_id, key_id),
    CONSTRAINT chk_provider_credential_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_provider_credential_type CHECK (credential_type = 'API_KEY'),
    CONSTRAINT chk_provider_credential_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT chk_provider_credential_version CHECK (version >= 1)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模型供应商凭据（密文）';

-- 模型调用记录：用于会话运行详情与 J2 时间线；审计仍写 model.invocation.* 事件，二者不互相替代。
CREATE TABLE model_invocation (
    tenant_id           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                  BINARY(16) NOT NULL COMMENT '调用 ID（UUIDv7）',
    run_id              BINARY(16) NULL COMMENT '关联运行 ID',
    conversation_id     BINARY(16) NULL COMMENT '关联会话 ID',
    message_id          BINARY(16) NULL COMMENT '关联回答消息 ID',
    model_id            BINARY(16) NOT NULL COMMENT '模型 ID',
    model_key_snapshot  VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '调用时的模型标识快照',
    purpose             VARCHAR(16) NOT NULL COMMENT 'CHAT|REPORT',
    status              VARCHAR(16) NOT NULL DEFAULT 'STARTED' COMMENT 'STARTED|SUCCEEDED|FAILED|CANCELLED',
    first_token_ms      INT NULL COMMENT '首字延迟（毫秒）',
    duration_ms         BIGINT NULL COMMENT '总耗时（毫秒）',
    prompt_tokens       INT NULL COMMENT '输入 token 数',
    completion_tokens   INT NULL COMMENT '输出 token 数',
    error_code          VARCHAR(64) NULL COMMENT '失败错误码',
    trace_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '追踪 ID',
    started_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '开始时间（UTC）',
    finished_at         DATETIME(6) NULL COMMENT '结束时间（UTC）',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    KEY idx_model_invocation_run (tenant_id, run_id, started_at),
    KEY idx_model_invocation_conversation (tenant_id, conversation_id, started_at),
    KEY idx_model_invocation_model (tenant_id, model_id, started_at),
    CONSTRAINT fk_model_invocation_run FOREIGN KEY (tenant_id, run_id) REFERENCES agent_run (tenant_id, id),
    CONSTRAINT fk_model_invocation_conversation FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversation (tenant_id, id),
    CONSTRAINT fk_model_invocation_message FOREIGN KEY (tenant_id, message_id) REFERENCES message (tenant_id, id),
    CONSTRAINT fk_model_invocation_model FOREIGN KEY (model_id) REFERENCES model (id),
    CONSTRAINT chk_model_invocation_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_model_invocation_purpose CHECK (purpose IN ('CHAT', 'REPORT')),
    CONSTRAINT chk_model_invocation_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_model_invocation_tokens CHECK (
        (prompt_tokens IS NULL OR prompt_tokens >= 0)
        AND (completion_tokens IS NULL OR completion_tokens >= 0)
        AND (first_token_ms IS NULL OR first_token_ms >= 0)
        AND (duration_ms IS NULL OR duration_ms >= 0)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '模型调用记录';

-- MCP server 全局目录：M02 只允许平台内置白名单；M07 才引入租户注册与审批。
CREATE TABLE mcp_server (
    id             BINARY(16) NOT NULL COMMENT 'MCP server ID（UUIDv7）',
    code           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内部稳定代码',
    name           VARCHAR(200) NOT NULL COMMENT '展示名',
    transport      VARCHAR(16) NOT NULL COMMENT 'HTTP|STDIO',
    endpoint       VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'HTTP 端点；必须固定且受允许列表约束',
    command        VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'stdio 命令；仅测试/开发',
    credential_ref VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'M02 为环境变量名；M07 改为凭据 ID',
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mcp_server_code (code),
    CONSTRAINT chk_mcp_server_transport CHECK (transport IN ('HTTP', 'STDIO')),
    CONSTRAINT chk_mcp_server_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_mcp_server_target CHECK (
        (transport = 'HTTP' AND endpoint IS NOT NULL AND command IS NULL)
        OR (transport = 'STDIO' AND command IS NOT NULL AND endpoint IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'MCP server 目录（全局表）';

-- MCP 工具全局目录：M02 只允许 READ；requires_approval 必须为 TRUE。
CREATE TABLE mcp_tool (
    id                BINARY(16) NOT NULL COMMENT '工具 ID（UUIDv7）',
    server_id         BINARY(16) NOT NULL COMMENT '所属 server',
    name              VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '工具名，审批展示使用原始值',
    description       VARCHAR(1000) NULL COMMENT '工具描述；视为不可信数据，不进入系统指令',
    input_schema      JSON NULL COMMENT 'JSON Schema 入参约束',
    risk_level        VARCHAR(16) NOT NULL COMMENT 'READ|WRITE；M02 只允许 READ',
    requires_approval BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'M02 必须为 TRUE，调用前人工审批',
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mcp_tool_name (server_id, name),
    CONSTRAINT fk_mcp_tool_server FOREIGN KEY (server_id) REFERENCES mcp_server (id),
    CONSTRAINT chk_mcp_tool_risk CHECK (risk_level IN ('READ', 'WRITE')),
    CONSTRAINT chk_mcp_tool_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'MCP 工具目录（全局表）';

-- 租户工具授权：J2 只能选择 enabled=TRUE 且工具状态可用的记录。
CREATE TABLE mcp_tool_grant (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    mcp_tool_id BINARY(16) NOT NULL COMMENT '工具 ID',
    enabled     BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否授权本租户使用',
    granted_by  BINARY(16) NULL COMMENT '授权人账号 ID；系统预置为空',
    granted_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '授权时间（UTC）',
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, mcp_tool_id),
    KEY idx_mcp_tool_grant_tenant (tenant_id, enabled),
    CONSTRAINT fk_mcp_tool_grant_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_mcp_tool_grant_tool FOREIGN KEY (mcp_tool_id) REFERENCES mcp_tool (id),
    CONSTRAINT fk_mcp_tool_grant_user FOREIGN KEY (tenant_id, granted_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_mcp_tool_grant_tenant CHECK (tenant_id <> '')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '租户 MCP 工具授权';

-- MCP 调用记录：不存请求/响应原文，只存 hash、长度与脱敏预览；审计写 mcp.tool.invoked。
CREATE TABLE mcp_invocation (
    tenant_id         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                BINARY(16) NOT NULL COMMENT '调用 ID（UUIDv7）',
    run_id            BINARY(16) NOT NULL COMMENT '所属运行 ID',
    research_task_id  BINARY(16) NULL COMMENT '所属报告任务 ID',
    approval_id       BINARY(16) NULL COMMENT '关联审批 ID；M02 工具调用必须有审批',
    mcp_tool_id       BINARY(16) NOT NULL COMMENT '工具 ID',
    tool_name         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '工具名快照',
    status            VARCHAR(16) NOT NULL DEFAULT 'STARTED' COMMENT 'STARTED|SUCCEEDED|FAILED|DENIED',
    params_sha256     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '入参 SHA-256',
    params_preview    JSON NULL COMMENT '脱敏后的入参摘要，长度受限',
    result_sha256     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '结果 SHA-256；失败时为空',
    result_size_bytes BIGINT NULL COMMENT '结果大小（字节）',
    result_preview    VARCHAR(1000) NULL COMMENT '脱敏后的结果摘要，不存原文',
    duration_ms       BIGINT NULL COMMENT '调用耗时（毫秒）',
    error_code        VARCHAR(64) NULL COMMENT '失败/拒绝错误码',
    started_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '开始时间（UTC）',
    finished_at       DATETIME(6) NULL COMMENT '结束时间（UTC）',
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    KEY idx_mcp_invocation_run (tenant_id, run_id, started_at),
    KEY idx_mcp_invocation_task (tenant_id, research_task_id, started_at),
    KEY idx_mcp_invocation_tool (tenant_id, mcp_tool_id, started_at),
    CONSTRAINT fk_mcp_invocation_run FOREIGN KEY (tenant_id, run_id) REFERENCES agent_run (tenant_id, id),
    CONSTRAINT fk_mcp_invocation_task FOREIGN KEY (tenant_id, research_task_id) REFERENCES research_task (tenant_id, id),
    CONSTRAINT fk_mcp_invocation_approval FOREIGN KEY (tenant_id, approval_id) REFERENCES approval (tenant_id, id),
    CONSTRAINT fk_mcp_invocation_tool FOREIGN KEY (mcp_tool_id) REFERENCES mcp_tool (id),
    CONSTRAINT chk_mcp_invocation_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_mcp_invocation_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'DENIED')),
    CONSTRAINT chk_mcp_invocation_size CHECK (result_size_bytes IS NULL OR result_size_bytes >= 0),
    CONSTRAINT chk_mcp_invocation_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'MCP 工具调用记录';
