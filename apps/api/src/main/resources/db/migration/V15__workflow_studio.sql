-- =============================================================================
-- V15：M06 编排 Studio（ADR-0024）
--
-- 范围（additive，单迁移，不做 down migration）：
--   1) 9 张 workflow 表：definition/version/run/node_run/approval/approval_assignee/
--      tool_invocation/template(全局)/user_template(租户，M06b 预留)；
--   2) model_invocation 追加可空 workflow_run_id/workflow_node_run_id（INSTANT）+ 租户索引
--      （INPLACE/LOCK=NONE）；不改 purpose CHECK、不新增 CHECK；
--   3) 权限字典 workflow.read/edit/publish/run/approve/manage；回填 TENANT_ADMIN（全部 6）、
--      MEMBER（read/run/approve）；为存量租户创建 WORKFLOW_EDITOR 系统角色并回填 5 个权限；
--   4) 内置模板 market-research（J2 等价，visibility=BUILT_IN，固定 ID，升级不改变）；
--   5) workflow_version 不可变触发器（BEFORE UPDATE/DELETE 拒绝；最后创建）。
--
-- 约束与回滚（ADR-0024 §3.5/§10.4）：
--   * 只向前；修复走后续迁移或备份恢复，不修改已应用的 V15 文件（ADR-0010）。
--   * 应急 drop 只在“无 workflow 数据/审计行 + 用户书面同意”时执行；判定 SQL 与内联 drop
--     顺序由 apps/api 的 V15MigrationTest 维护，生产优先备份恢复。
--   * 触发器需要迁移账号 TRIGGER 权限；binlog 开启时需 log_bin_trust_function_creators=1
--     或等价 SUPER，预检与失败处置见 ADR-0024 §3.5。
--   * 所有 DML 使用 NOT EXISTS / 确定性 ID，可在失败 repair 后重复执行而不产生重复行。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 流程定义（草稿 + 乐观锁；已发布版本走 workflow_version）
-- -----------------------------------------------------------------------------
CREATE TABLE workflow_definition (
    tenant_id               VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                      BINARY(16) NOT NULL COMMENT '流程 ID（UUIDv7）',
    name                    VARCHAR(200) NOT NULL COMMENT '流程名；不要求租户内唯一',
    description             VARCHAR(1000) NULL COMMENT '说明',
    owner_user_id           BINARY(16) NOT NULL COMMENT '所有者账号 ID',
    draft_version           INT NOT NULL DEFAULT 1 COMMENT '草稿乐观锁版本',
    draft_graph             JSON NOT NULL COMMENT '当前草稿图；允许未发布前不完整，发布前必须校验',
    status                  VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED；停用阻止新运行',
    published_version_id    BINARY(16) NULL COMMENT '最近发布版本 ID（逻辑引用 workflow_version）',
    published_version_no    INT NULL COMMENT '最近发布版本号（冗余，便于列表）',
    source_template_id      BINARY(16) NULL COMMENT '来源模板 ID（逻辑引用内置/用户模板）',
    source_template_type    VARCHAR(16) NULL COMMENT 'BUILT_IN|USER',
    source_template_version INT NULL COMMENT '来源模板版本，升级提示用',
    created_by              BINARY(16) NOT NULL COMMENT '创建人账号 ID',
    updated_by              BINARY(16) NOT NULL COMMENT '最近修改人账号 ID',
    created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    KEY idx_workflow_definition_owner (tenant_id, owner_user_id, updated_at),
    KEY idx_workflow_definition_status (tenant_id, status, updated_at),
    KEY idx_workflow_definition_name (tenant_id, name),
    CONSTRAINT fk_workflow_definition_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_workflow_definition_owner FOREIGN KEY (tenant_id, owner_user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_workflow_definition_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_workflow_definition_updated_by FOREIGN KEY (tenant_id, updated_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_workflow_definition_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_workflow_definition_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_workflow_definition_draft_version CHECK (draft_version >= 1),
    CONSTRAINT chk_workflow_definition_published CHECK (
        (published_version_id IS NULL AND published_version_no IS NULL)
        OR (published_version_id IS NOT NULL AND published_version_no IS NOT NULL AND published_version_no >= 1)
    ),
    CONSTRAINT chk_workflow_definition_source_template CHECK (
        source_template_type IS NULL OR source_template_type IN ('BUILT_IN', 'USER')
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流定义（草稿+乐观锁，M06）';

-- -----------------------------------------------------------------------------
-- 2) 流程版本（不可变发布快照；运行绑定版本，草稿修改不影响在跑运行）
-- -----------------------------------------------------------------------------
CREATE TABLE workflow_version (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id              BINARY(16) NOT NULL COMMENT '版本 ID（UUIDv7）',
    workflow_id     BINARY(16) NOT NULL COMMENT '流程 ID',
    version_no      INT NOT NULL COMMENT '版本号，从 1 递增；流程内唯一',
    graph_json      JSON NOT NULL COMMENT '发布时规范化的图快照',
    graph_sha256    CHAR(64) NOT NULL COMMENT '规范化图哈希；运行绑定与排障',
    node_count      INT NOT NULL COMMENT '节点数',
    run_scope       VARCHAR(16) NOT NULL COMMENT 'ONLY_SELF|DEPARTMENT|TENANT',
    department_id   BINARY(16) NULL COMMENT 'DEPARTMENT 范围必填；FK department',
    published_by    BINARY(16) NOT NULL COMMENT '发布人账号 ID',
    published_at    DATETIME(6) NOT NULL COMMENT '发布时间（UTC）',
    created_at      DATETIME(6) NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_workflow_version_no (tenant_id, workflow_id, version_no),
    KEY idx_workflow_version_published (tenant_id, workflow_id, published_at),
    CONSTRAINT fk_workflow_version_definition FOREIGN KEY (tenant_id, workflow_id) REFERENCES workflow_definition (tenant_id, id),
    CONSTRAINT fk_workflow_version_department FOREIGN KEY (tenant_id, department_id) REFERENCES department (tenant_id, id),
    CONSTRAINT fk_workflow_version_published_by FOREIGN KEY (tenant_id, published_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_workflow_version_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_workflow_version_no CHECK (version_no >= 1),
    CONSTRAINT chk_workflow_version_node_count CHECK (node_count >= 1),
    CONSTRAINT chk_workflow_version_scope CHECK (run_scope IN ('ONLY_SELF', 'DEPARTMENT', 'TENANT')),
    -- DEPARTMENT 必须有部门；其余范围必须为空，避免范围与部门漂移。
    CONSTRAINT chk_workflow_version_department CHECK (
        (run_scope = 'DEPARTMENT' AND department_id IS NOT NULL)
        OR (run_scope IN ('ONLY_SELF', 'TENANT') AND department_id IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流发布版本（不可变，M06）';

-- -----------------------------------------------------------------------------
-- 3) 运行（自身即持久运行队列；审批等待释放执行槽位）
-- -----------------------------------------------------------------------------
CREATE TABLE workflow_run (
    tenant_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                   BINARY(16) NOT NULL COMMENT '运行 ID（UUIDv7）',
    workflow_id          BINARY(16) NOT NULL COMMENT '流程 ID',
    workflow_version_id  BINARY(16) NOT NULL COMMENT '运行绑定的版本 ID',
    version_no           INT NOT NULL COMMENT '版本号快照',
    requester_user_id    BINARY(16) NOT NULL COMMENT '发起人账号 ID',
    status               VARCHAR(16) NOT NULL COMMENT 'PENDING|RUNNING|WAITING_APPROVAL|SUCCEEDED|FAILED|CANCELLED',
    inputs_json          JSON NOT NULL COMMENT '运行输入（≤64 KiB，键/长度服务端校验）',
    result_json          JSON NULL COMMENT '最终结果（≤部署上限）；result_status 说明是否保存',
    result_status        VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE|SAVED|OVER_LIMIT',
    result_size_bytes    BIGINT NOT NULL DEFAULT 0 COMMENT '结果大小（字节）',
    current_node_key     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前节点（图内 key）',
    execution_ms         BIGINT NOT NULL DEFAULT 0 COMMENT '累计执行时间（毫秒，不含审批等待）',
    approval_wait_ms     BIGINT NOT NULL DEFAULT 0 COMMENT '累计审批等待（毫秒）',
    lease_owner          VARCHAR(128) NULL COMMENT '执行租约持有者；审批等待时为空',
    lease_until          DATETIME(6) NULL COMMENT '执行租约到期（UTC）；审批等待时为空',
    claim_attempts       INT NOT NULL DEFAULT 0 COMMENT '租约丢失重领次数；超过 5 次记 WF_INTERNAL',
    idempotency_key      VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'HTTP 创建幂等键',
    error_code           VARCHAR(64) NULL COMMENT '稳定错误码（WF_*）',
    error_message        VARCHAR(500) NULL COMMENT '脱敏失败原因',
    retention_expires_at DATETIME(6) NOT NULL COMMENT '到期不可见；默认创建时间 + 租户保留天数',
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC，轮询判据）',
    started_at           DATETIME(6) NULL COMMENT '首次开始执行时间（UTC）',
    finished_at          DATETIME(6) NULL COMMENT '终态时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_workflow_run_idempotency (tenant_id, workflow_id, requester_user_id, idempotency_key),
    KEY idx_workflow_run_workflow (tenant_id, workflow_id, created_at),
    KEY idx_workflow_run_status (tenant_id, status, created_at),
    KEY idx_workflow_run_requester (tenant_id, requester_user_id, created_at),
    KEY idx_workflow_run_retention (tenant_id, retention_expires_at),
    KEY idx_workflow_run_lease (tenant_id, status, lease_until),
    -- ADR-0009/0016 的新增跨租户领取例外（TM-85）：只用于 claim 元数据，故意不以 tenant_id 开头。
    KEY idx_workflow_run_claim (status, lease_until, id),
    KEY idx_workflow_run_version (tenant_id, workflow_version_id, created_at),
    CONSTRAINT fk_workflow_run_definition FOREIGN KEY (tenant_id, workflow_id) REFERENCES workflow_definition (tenant_id, id),
    CONSTRAINT fk_workflow_run_version FOREIGN KEY (tenant_id, workflow_version_id) REFERENCES workflow_version (tenant_id, id),
    CONSTRAINT fk_workflow_run_requester FOREIGN KEY (tenant_id, requester_user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_workflow_run_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_workflow_run_status CHECK (
        status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT chk_workflow_run_result_status CHECK (result_status IN ('NONE', 'SAVED', 'OVER_LIMIT')),
    CONSTRAINT chk_workflow_run_counters CHECK (
        version_no >= 1
        AND result_size_bytes >= 0
        AND execution_ms >= 0
        AND approval_wait_ms >= 0
        AND claim_attempts >= 0
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流运行（持久运行队列，M06）';

-- -----------------------------------------------------------------------------
-- 4) 节点运行（节点尝试独立持久化；重试不新建 run）
-- -----------------------------------------------------------------------------
CREATE TABLE workflow_node_run (
    tenant_id          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                 BINARY(16) NOT NULL COMMENT '节点运行 ID（UUIDv7）',
    workflow_run_id    BINARY(16) NOT NULL COMMENT '运行 ID',
    node_key           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '图内节点 key（客户端提供）',
    node_type          VARCHAR(32) NOT NULL COMMENT 'START|END|RETRIEVAL|MODEL|MCP_TOOL|APPROVAL|CONDITION',
    node_name          VARCHAR(200) NOT NULL COMMENT '节点名快照',
    attempt            INT NOT NULL DEFAULT 1 COMMENT '节点尝试号；重试递增',
    status             VARCHAR(24) NOT NULL COMMENT 'PENDING|RUNNING|WAITING_APPROVAL|SUCCEEDED|FAILED|SKIPPED|CANCELLED',
    input_summary      VARCHAR(2000) NULL COMMENT '节点输入摘要；不返回完整提示词或参数',
    output_json        JSON NULL COMMENT '下游使用的完整输出（内部，不直接返回 API；单节点 ≤256 KiB）',
    output_summary     VARCHAR(2000) NULL COMMENT '运行详情展示摘要',
    citations_json     JSON NULL COMMENT '引用快照（≤20 条，复用 M05 Citation 语义）',
    approval_id        BINARY(16) NULL COMMENT '逻辑引用 workflow_approval',
    tool_invocation_id BINARY(16) NULL COMMENT '逻辑引用 workflow_tool_invocation',
    error_code         VARCHAR(64) NULL COMMENT '稳定错误码（WF_*）',
    error_message      VARCHAR(500) NULL COMMENT '脱敏失败原因',
    started_at         DATETIME(6) NULL COMMENT '开始时间（UTC）',
    finished_at        DATETIME(6) NULL COMMENT '结束时间（UTC）',
    duration_ms        BIGINT NULL COMMENT '耗时（毫秒）',
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_workflow_node_run_attempt (tenant_id, workflow_run_id, node_key, attempt),
    KEY idx_workflow_node_run_timeline (tenant_id, workflow_run_id, started_at),
    CONSTRAINT fk_workflow_node_run_run FOREIGN KEY (tenant_id, workflow_run_id) REFERENCES workflow_run (tenant_id, id),
    CONSTRAINT chk_workflow_node_run_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_workflow_node_run_type CHECK (
        node_type IN ('START', 'END', 'RETRIEVAL', 'MODEL', 'MCP_TOOL', 'APPROVAL', 'CONDITION')
    ),
    CONSTRAINT chk_workflow_node_run_status CHECK (
        status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')
    ),
    CONSTRAINT chk_workflow_node_run_attempt CHECK (attempt >= 1),
    CONSTRAINT chk_workflow_node_run_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流节点运行（M06）';

-- -----------------------------------------------------------------------------
-- 5) 审批（不复用 J2 approval；参数密文复用 ADR-0017 信封加密）
-- -----------------------------------------------------------------------------
CREATE TABLE workflow_approval (
    tenant_id           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                  BINARY(16) NOT NULL COMMENT '审批 ID（UUIDv7）',
    workflow_run_id     BINARY(16) NOT NULL COMMENT '运行 ID',
    workflow_id         BINARY(16) NOT NULL COMMENT '流程 ID（冗余，待办查询用）',
    node_key            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '审批节点 key',
    attempt             INT NOT NULL COMMENT '节点尝试号',
    status              VARCHAR(16) NOT NULL COMMENT 'PENDING|APPROVED|REJECTED|CANCELLED',
    assignee_type       VARCHAR(16) NOT NULL COMMENT 'USER|ROLE',
    assignee_role_code  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'TENANT_ADMIN|WORKFLOW_EDITOR|MEMBER',
    action_kind         VARCHAR(16) NOT NULL COMMENT 'MCP_TOOL|MODEL|CONTINUE',
    target_node_key     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '解锁的下游节点 key',
    target_tool_id      BINARY(16) NULL COMMENT '目标工具 ID 快照（逻辑引用 mcp_tool）',
    target_tool_name    VARCHAR(128) NULL COMMENT '目标工具名快照',
    params_preview      JSON NOT NULL COMMENT '审批人可见的脱敏参数摘要（≤16 键、单值 ≤200 字符、≤4096 字节）',
    params_ciphertext   VARBINARY(8192) NULL COMMENT '仅 MCP_TOOL：可重放参数密文（AAD=tenantId|workflowApprovalId）',
    params_key_id       VARCHAR(64) NULL COMMENT '参数密文 key ID（信封加密）',
    params_sha256       CHAR(64) NULL COMMENT '规范化参数哈希；执行前核对',
    plan_sha256         CHAR(64) NOT NULL COMMENT '版本+节点配置+上游输出摘要的绑定哈希；执行前重算',
    requested_by        BINARY(16) NOT NULL COMMENT '运行发起人账号 ID',
    decided_by          BINARY(16) NULL COMMENT '审批人账号 ID',
    decided_at          DATETIME(6) NULL COMMENT '决定时间（UTC）',
    decision_comment    VARCHAR(500) NULL COMMENT '决定理由；拒绝时必填',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_workflow_approval_attempt (tenant_id, workflow_run_id, node_key, attempt),
    KEY idx_workflow_approval_status (tenant_id, status, created_at),
    KEY idx_workflow_approval_workflow (tenant_id, workflow_id, created_at),
    CONSTRAINT fk_workflow_approval_run FOREIGN KEY (tenant_id, workflow_run_id) REFERENCES workflow_run (tenant_id, id),
    CONSTRAINT fk_workflow_approval_definition FOREIGN KEY (tenant_id, workflow_id) REFERENCES workflow_definition (tenant_id, id),
    CONSTRAINT fk_workflow_approval_requested_by FOREIGN KEY (tenant_id, requested_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_workflow_approval_decided_by FOREIGN KEY (tenant_id, decided_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_workflow_approval_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_workflow_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_workflow_approval_action_kind CHECK (action_kind IN ('MCP_TOOL', 'MODEL', 'CONTINUE')),
    CONSTRAINT chk_workflow_approval_assignee_kind CHECK (
        (assignee_type = 'USER' AND assignee_role_code IS NULL)
        OR (assignee_type = 'ROLE' AND assignee_role_code IN ('TENANT_ADMIN', 'WORKFLOW_EDITOR', 'MEMBER'))
    ),
    -- 决定与审批人/时间必须一致，避免“已决定但没有审批人”。
    CONSTRAINT chk_workflow_approval_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
        OR (status = 'CANCELLED')
    ),
    CONSTRAINT chk_workflow_approval_comment CHECK (
        status <> 'REJECTED' OR (decision_comment IS NOT NULL AND decision_comment <> '')
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流审批（M06）';

-- 指定用户审批的指派人；角色审批不写本表，待办按 user_role 联接解析。
CREATE TABLE workflow_approval_assignee (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    approval_id BINARY(16) NOT NULL COMMENT '审批 ID',
    user_id     BINARY(16) NOT NULL COMMENT '审批人账号 ID',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, approval_id, user_id),
    KEY idx_workflow_approval_assignee_user (tenant_id, user_id, approval_id),
    CONSTRAINT fk_workflow_approval_assignee_approval FOREIGN KEY (tenant_id, approval_id) REFERENCES workflow_approval (tenant_id, id),
    CONSTRAINT fk_workflow_approval_assignee_user FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_workflow_approval_assignee_tenant CHECK (tenant_id <> '')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流审批指派人（M06）';

-- -----------------------------------------------------------------------------
-- 6) 工具调用（不复用 mcp_invocation：其 FK 绑定 agent_run/approval）
-- -----------------------------------------------------------------------------
CREATE TABLE workflow_tool_invocation (
    tenant_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                   BINARY(16) NOT NULL COMMENT '调用 ID（UUIDv7）',
    workflow_run_id      BINARY(16) NOT NULL COMMENT '运行 ID',
    workflow_approval_id BINARY(16) NOT NULL COMMENT '绑定的审批 ID；一个审批最多执行一次',
    node_key             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '节点 key',
    attempt              INT NOT NULL COMMENT '节点尝试号',
    mcp_tool_id          BINARY(16) NOT NULL COMMENT '工具 ID 快照（全局目录）',
    tool_name            VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '工具名快照',
    status               VARCHAR(16) NOT NULL COMMENT 'STARTED|SUCCEEDED|FAILED|DENIED',
    params_sha256        CHAR(64) NOT NULL COMMENT '入参规范化哈希',
    params_preview       JSON NULL COMMENT '入参脱敏摘要',
    result_sha256        CHAR(64) NULL COMMENT '结果哈希',
    result_size_bytes    BIGINT NULL COMMENT '结果大小（字节）',
    result_preview       VARCHAR(1000) NULL COMMENT '结果脱敏摘要；不存凭据',
    duration_ms          BIGINT NULL COMMENT '耗时（毫秒）',
    error_code           VARCHAR(64) NULL COMMENT '稳定错误码（WF_*）',
    started_at           DATETIME(6) NULL COMMENT '开始时间（UTC）',
    finished_at          DATETIME(6) NULL COMMENT '结束时间（UTC）',
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_workflow_tool_invocation_approval (tenant_id, workflow_approval_id),
    KEY idx_workflow_tool_invocation_run (tenant_id, workflow_run_id, started_at),
    KEY idx_workflow_tool_invocation_tool (mcp_tool_id),
    CONSTRAINT fk_workflow_tool_invocation_run FOREIGN KEY (tenant_id, workflow_run_id) REFERENCES workflow_run (tenant_id, id),
    CONSTRAINT fk_workflow_tool_invocation_approval FOREIGN KEY (tenant_id, workflow_approval_id) REFERENCES workflow_approval (tenant_id, id),
    CONSTRAINT fk_workflow_tool_invocation_tool FOREIGN KEY (mcp_tool_id) REFERENCES mcp_tool (id),
    CONSTRAINT chk_workflow_tool_invocation_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_workflow_tool_invocation_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'DENIED')),
    CONSTRAINT chk_workflow_tool_invocation_attempt CHECK (attempt >= 1),
    CONSTRAINT chk_workflow_tool_invocation_sizes CHECK (
        (result_size_bytes IS NULL OR result_size_bytes >= 0)
        AND (duration_ms IS NULL OR duration_ms >= 0)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流工具调用（M06）';

-- -----------------------------------------------------------------------------
-- 7) 全局内置模板目录（ADR-0012 §4 全局表例外由 ADR-0024 §3.3.7 扩展）
-- -----------------------------------------------------------------------------
CREATE TABLE workflow_template (
    id           BINARY(16) NOT NULL COMMENT '模板 ID（固定 UUID，升级不改变）',
    template_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定模板键，如 market-research',
    name         VARCHAR(200) NOT NULL COMMENT '展示名',
    scenario     VARCHAR(200) NOT NULL COMMENT '适用场景',
    description  VARCHAR(1000) NULL COMMENT '说明',
    graph_json   JSON NOT NULL COMMENT '内置图快照',
    visibility   VARCHAR(16) NOT NULL DEFAULT 'BUILT_IN' COMMENT '内置目录固定 BUILT_IN',
    version      INT NOT NULL DEFAULT 1 COMMENT '模板版本；升级由 M06b 决定',
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_workflow_template_key (template_key),
    CONSTRAINT chk_workflow_template_visibility CHECK (visibility IN ('BUILT_IN')),
    CONSTRAINT chk_workflow_template_version CHECK (version >= 1),
    CONSTRAINT chk_workflow_template_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流内置模板目录（全局只读，M06）';

-- 租户内用户模板（V15 预留，M06 无读写路径；M06b 启用共享/升级提示）。
CREATE TABLE workflow_user_template (
    tenant_id                   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                          BINARY(16) NOT NULL COMMENT '模板 ID（UUIDv7）',
    name                        VARCHAR(200) NOT NULL COMMENT '展示名',
    description                 VARCHAR(1000) NULL COMMENT '说明',
    scenario                    VARCHAR(200) NOT NULL COMMENT '适用场景',
    graph_json                  JSON NOT NULL COMMENT '图快照',
    visibility                  VARCHAR(16) NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE|TENANT',
    owner_user_id               BINARY(16) NOT NULL COMMENT '所有者账号 ID',
    source_workflow_id          BINARY(16) NULL COMMENT '来源流程 ID（逻辑引用）',
    source_workflow_version_no  INT NULL COMMENT '来源流程版本号',
    status                      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    created_by                  BINARY(16) NOT NULL COMMENT '创建人账号 ID',
    created_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    KEY idx_workflow_user_template_visibility (tenant_id, visibility, updated_at),
    KEY idx_workflow_user_template_owner (tenant_id, owner_user_id),
    CONSTRAINT fk_workflow_user_template_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_workflow_user_template_owner FOREIGN KEY (tenant_id, owner_user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_workflow_user_template_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_workflow_user_template_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_workflow_user_template_visibility CHECK (visibility IN ('PRIVATE', 'TENANT')),
    CONSTRAINT chk_workflow_user_template_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_workflow_user_template_source CHECK (
        (source_workflow_id IS NULL AND source_workflow_version_no IS NULL)
        OR (source_workflow_id IS NOT NULL AND source_workflow_version_no IS NOT NULL AND source_workflow_version_no >= 1)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作流用户模板（租户，M06b 预留）';

-- -----------------------------------------------------------------------------
-- 8) model_invocation：可空工作流引用（成本/排障用，逻辑引用、不加 FK）；不改 purpose CHECK
-- -----------------------------------------------------------------------------
ALTER TABLE model_invocation
    ADD COLUMN workflow_run_id BINARY(16) NULL COMMENT '工作流运行 ID（逻辑引用 workflow_run；M06）',
    ADD COLUMN workflow_node_run_id BINARY(16) NULL COMMENT '工作流节点运行 ID（逻辑引用 workflow_node_run；M06）',
    ALGORITHM = INSTANT;

ALTER TABLE model_invocation
    ADD KEY idx_model_invocation_workflow (tenant_id, workflow_run_id, started_at),
    ALGORITHM = INPLACE,
    LOCK = NONE;

-- -----------------------------------------------------------------------------
-- 9) 权限字典与角色回填（幂等：失败 repair 后可重复执行）
-- -----------------------------------------------------------------------------

-- 权限字典。新增权限码必须同步 platform-domain PermissionCodes 与 OpenAPI 的 x-riccon-permission。
INSERT INTO permission (code, description)
SELECT 'workflow.read', '读取可运行流程、自己的草稿/运行、模板列表与运行限制'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'workflow.read');

INSERT INTO permission (code, description)
SELECT 'workflow.edit', '创建/编辑流程草稿、校验、复制模板与另存副本'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'workflow.edit');

INSERT INTO permission (code, description)
SELECT 'workflow.publish', '发布流程版本、停用/启用流程'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'workflow.publish');

INSERT INTO permission (code, description)
SELECT 'workflow.run', '创建运行、取消/重试自己的运行'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'workflow.run');

INSERT INTO permission (code, description)
SELECT 'workflow.approve', '审批待办、审批详情与决定'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'workflow.approve');

INSERT INTO permission (code, description)
SELECT 'workflow.manage', '移交流程所有权'
 WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'workflow.manage');

-- 存量 TENANT_ADMIN：全部 6 个 workflow 权限。新租户由 RoleDefinitions 开通时写入。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, p.code
  FROM role r
  JOIN permission p
    ON p.code IN ('workflow.read', 'workflow.edit', 'workflow.publish',
                  'workflow.run', 'workflow.approve', 'workflow.manage')
 WHERE r.code = 'TENANT_ADMIN'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = p.code);

-- 存量 MEMBER：read/run/approve；普通员工不能编辑或发布。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, p.code
  FROM role r
  JOIN permission p
    ON p.code IN ('workflow.read', 'workflow.run', 'workflow.approve')
 WHERE r.code = 'MEMBER'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = p.code);

-- 存量租户创建 WORKFLOW_EDITOR 系统角色。平台租户不创建：平台管理员不持有 workflow 权限。
-- 系统角色 ID 使用确定性 BINARY(16)：SHA-256('role:WORKFLOW_EDITOR:'+tenant_id) 前 16 字节
-- （ADR-0012 UUIDv7 规则的系统数据例外，不对外暴露语义）。已有同码角色时不覆盖。
INSERT INTO role (tenant_id, id, code, name, description, is_system, created_at, updated_at)
SELECT t.id,
       UNHEX(SUBSTRING(SHA2(CONCAT('role:WORKFLOW_EDITOR:', t.id), 256), 1, 32)),
       'WORKFLOW_EDITOR',
       '流程编辑者',
       '创建/编辑自己的流程草稿并发布本部门或仅自己范围',
       TRUE,
       UTC_TIMESTAMP(6),
       UTC_TIMESTAMP(6)
  FROM tenant t
 WHERE t.id <> 'platform'
   AND NOT EXISTS (SELECT 1 FROM role r WHERE r.tenant_id = t.id AND r.code = 'WORKFLOW_EDITOR');

-- WORKFLOW_EDITOR：read/edit/publish/run/approve（不含 manage）。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, p.code
  FROM role r
  JOIN permission p
    ON p.code IN ('workflow.read', 'workflow.edit', 'workflow.publish',
                  'workflow.run', 'workflow.approve')
 WHERE r.code = 'WORKFLOW_EDITOR'
   AND NOT EXISTS (SELECT 1
                     FROM role_permission rp
                    WHERE rp.tenant_id = r.tenant_id
                      AND rp.role_id = r.id
                      AND rp.permission_code = p.code);

-- -----------------------------------------------------------------------------
-- 10) 内置模板种子（J2 等价：检索 → 模型 → 审批 → 工具 → 模型 → 结束）
--     固定模板 ID 01990000-0000-7000-8000-000000000601；升级不改变。
--     工具节点 toolId 为空：MCP 工具是平台/租户授权数据，复制模板后由用户在授权范围内选择。
-- -----------------------------------------------------------------------------
INSERT INTO workflow_template (
    id, template_key, name, scenario, description, graph_json, visibility, version, status, created_at, updated_at
)
SELECT UNHEX('01990000000070008000000000000601'),
       'market-research',
       '市场调研报告',
       '市场调研',
       '检索知识库并调用 1 个 MCP 数据工具，经人工审批后生成带引用的市场调研报告',
       '{"schemaVersion":1,"nodes":[{"id":"start","type":"START","name":"开始","config":{}},{"id":"retrieval","type":"RETRIEVAL","name":"检索知识库","config":{"queryTemplate":"{{input.topic}}","topK":5}},{"id":"draft","type":"MODEL","name":"生成调研草稿","config":{"promptTemplate":"根据检索结果整理市场调研要点：{{input.topic}}","outputKind":"TEXT"}},{"id":"approval","type":"APPROVAL","name":"审批取数工具","config":{"assigneeType":"ROLE","assigneeRole":"TENANT_ADMIN","actionKind":"MCP_TOOL","targetNodeKey":"tool"}},{"id":"tool","type":"MCP_TOOL","name":"获取市场数据","config":{"toolId":null,"argsTemplate":{"query":"{{input.topic}}"}}},{"id":"report","type":"MODEL","name":"生成调研报告","config":{"promptTemplate":"整合草稿与工具结果，输出带引用的市场调研报告","outputKind":"TEXT"}},{"id":"end","type":"END","name":"结束","config":{"resultFrom":"report","resultTitle":"市场调研报告"}}],"edges":[{"id":"e1","source":"start","target":"retrieval"},{"id":"e2","source":"retrieval","target":"draft"},{"id":"e3","source":"draft","target":"approval"},{"id":"e4","source":"approval","target":"tool"},{"id":"e5","source":"tool","target":"report"},{"id":"e6","source":"report","target":"end"}]}',
       'BUILT_IN',
       1,
       'ACTIVE',
       UTC_TIMESTAMP(6),
       UTC_TIMESTAMP(6)
 WHERE NOT EXISTS (SELECT 1 FROM workflow_template WHERE template_key = 'market-research');

-- -----------------------------------------------------------------------------
-- 11) 发布版本不可变触发器（最后创建；应用层没有更新/删除路径）
--     失败处置见 ADR-0024 §3.5：补 TRIGGER/binlog 变量后单独重放本段，或备份恢复。
-- -----------------------------------------------------------------------------
CREATE TRIGGER trg_workflow_version_no_update
    BEFORE UPDATE
    ON workflow_version
    FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workflow_version is immutable';

CREATE TRIGGER trg_workflow_version_no_delete
    BEFORE DELETE
    ON workflow_version
    FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workflow_version is immutable';

-- -----------------------------------------------------------------------------
-- 回滚说明（不做 down migration）：
--   * 受支持回滚 = 关闭 RICCON_WORKFLOW_ENABLED + 回滚 Worker + 保留/前向修复 API；
--     有 workflow 审计行后不支持把 API 回滚到 M06 前版本（ADR-0024 §10.4、DEC-0047=A）。
--   * 全量 API 回滚/应急 drop 只在“无 workflow 数据/审计行 + 用户书面同意”时执行；
--     判定 SQL 与 FK 逆序 drop（租户表 → 全局模板 → model_invocation 索引/列 → 权限/角色回填
--     → flyway_schema_history 15）内联在 apps/api 的 V15MigrationTest，不另建生产脚本。
-- -----------------------------------------------------------------------------
