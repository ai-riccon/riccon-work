-- =============================================================================
-- V2：身份、租户与工作空间（M02）
--
-- 约定（ADR-0009 / ADR-0012）：
--   * 所有租户业务表以 (tenant_id, id) 为复合主键，唯一键与索引以 tenant_id 开头；
--   * id 为应用生成的 UUIDv7，存 BINARY(16)；tenant_id 为 1–64 位 ASCII 字符串；
--   * 时间统一为 UTC DATETIME(6)，由应用写入；连接初始化必须 SET time_zone='+00:00'；
--   * 状态列使用 VARCHAR + CHECK，便于向前兼容新增值；
--   * 租户表必须有 tenant_id 非空检查，外键使用复合键阻止跨租户关联。
-- =============================================================================

-- 租户全局表：唯一不带 tenant_id 的业务表（ADR-0009 第 4 节例外）。
-- id 同时作为登录时使用的租户代码；保留值 platform 承载平台管理员与平台级目录。
CREATE TABLE tenant (
    id             VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户主键，同时作为登录租户代码；platform 为保留租户',
    name           VARCHAR(200) NOT NULL COMMENT '租户展示名，可含中文',
    status         VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|SUSPENDED',
    plan           VARCHAR(32) NOT NULL DEFAULT 'standard' COMMENT 'M02 固定 standard；platform 租户为 internal',
    retention_days INT NOT NULL DEFAULT 180 COMMENT '对话与产物默认保留天数，合同要求至少 180 天',
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT chk_tenant_retention CHECK (retention_days >= 30)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '租户（全局表）';

-- 平台保留租户：平台管理员的归属租户；不创建默认账号或密码。
INSERT INTO tenant (id, name, status, plan, retention_days)
VALUES ('platform', '平台管理', 'ACTIVE', 'internal', 180);

-- 账号表：M02 只支持内置账号，但预留 OIDC issuer/subject 字段供 M03 绑定。
CREATE TABLE app_user (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id              BINARY(16) NOT NULL COMMENT '账号 ID（UUIDv7）',
    username        VARCHAR(100) NOT NULL COMMENT '登录名；同租户内大小写不敏感唯一',
    display_name    VARCHAR(200) NOT NULL COMMENT '展示名，不含登录语义',
    email           VARCHAR(320) NULL COMMENT '可选联系方式；M02 不用于登录',
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    auth_source     VARCHAR(32) NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL|OIDC',
    external_issuer VARCHAR(255) NULL COMMENT 'OIDC issuer（M03）',
    external_subject VARCHAR(255) NULL COMMENT 'OIDC subject（M03）',
    last_login_at   DATETIME(6) NULL COMMENT '最近登录成功时间（UTC）',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_app_user_username (tenant_id, username),
    UNIQUE KEY uk_app_user_external_identity (tenant_id, external_issuer, external_subject),
    KEY idx_app_user_status (tenant_id, status, created_at),
    CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_app_user_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_app_user_auth_source CHECK (auth_source IN ('LOCAL', 'OIDC')),
    -- OIDC 账号必须同时有 issuer 与 subject；LOCAL 账号不填写外部身份。
    CONSTRAINT chk_app_user_external CHECK (
        (auth_source = 'LOCAL' AND external_issuer IS NULL AND external_subject IS NULL)
        OR (auth_source = 'OIDC' AND external_issuer IS NOT NULL AND external_subject IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '租户账号';

-- 密码凭据表：与账号分离，便于 M03 接入 OIDC 后账号可以没有本地密码。
-- password_hash 必须带 DelegatingPasswordEncoder 前缀（M02 使用 {bcrypt}），禁止明文。
CREATE TABLE user_credential (
    tenant_id           VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    user_id             BINARY(16) NOT NULL COMMENT '账号 ID',
    password_hash       VARCHAR(255) NOT NULL COMMENT '带 {bcrypt} 前缀的密码哈希',
    algorithm           VARCHAR(32) NOT NULL DEFAULT 'bcrypt' COMMENT '哈希算法标识，用于轮换',
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE COMMENT '首次登录是否强制改密',
    failed_attempts     INT NOT NULL DEFAULT 0 COMMENT '连续失败次数，用于账号锁定',
    locked_until        DATETIME(6) NULL COMMENT '锁定截止时间（UTC）',
    password_updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '密码最近更新时间（UTC）',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT fk_user_credential_user FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_user_credential_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_user_credential_attempts CHECK (failed_attempts >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '内置账号密码凭据';

-- 权限全局字典：权限码不随租户变化，避免每个租户复制一份导致漂移。
CREATE TABLE permission (
    code        VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权限码，格式 domain.action',
    description VARCHAR(255) NOT NULL COMMENT '权限说明，用于管理端展示',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '权限字典（全局表）';

-- M02 权限字典。新增权限码必须同步 PermissionCodes 与 OpenAPI 的 x-riccon-permission。
INSERT INTO permission (code, description)
VALUES ('workspace.read', '读取工作空间列表与详情'),
       ('workspace.write', '创建或修改团队工作空间'),
       ('file.read', '读取文件元数据与下载地址'),
       ('file.upload', '上传文件'),
       ('file.delete', '删除文件'),
       ('conversation.read', '读取会话与消息'),
       ('conversation.write', '创建会话、发送消息与停止生成'),
       ('knowledge.read', '读取知识库文档'),
       ('knowledge.manage', '添加、删除与重建知识库文档'),
       ('report.read', '读取报告任务与产物'),
       ('report.create', '创建报告任务'),
       ('report.approve', '审批报告任务中的外部工具调用'),
       ('artifact.read', '读取产物与版本'),
       ('model.read', '查看可用模型目录'),
       ('mcp.read', '查看本租户已授权的 MCP 工具'),
       ('tenant.user.manage', '租户内账号管理'),
       ('tenant.role.manage', '租户内角色授予与撤销'),
       ('platform.tenant.manage', '平台租户管理'),
       ('platform.model.read', '平台模型目录只读'),
       ('platform.mcp.read', '平台 MCP 目录只读'),
       ('audit.read', '审计查询（M03 启用）');

-- 租户内角色。系统角色由租户开通流程创建并复制权限映射，不在迁移中为每个租户预置。
CREATE TABLE role (
    tenant_id   VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id          BINARY(16) NOT NULL COMMENT '角色 ID（UUIDv7）',
    code        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '角色码，如 TENANT_ADMIN/MEMBER',
    name        VARCHAR(100) NOT NULL COMMENT '角色展示名',
    description VARCHAR(255) NULL COMMENT '角色说明',
    is_system   BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否系统内置角色，内置角色不可删除',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_role_code (tenant_id, code),
    CONSTRAINT fk_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_role_tenant CHECK (tenant_id <> '')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '租户角色';

-- 角色-权限映射：permission_code 引用全局权限字典。
CREATE TABLE role_permission (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    role_id         BINARY(16) NOT NULL COMMENT '角色 ID',
    permission_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权限码',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (tenant_id, role_id, permission_code),
    KEY idx_role_permission_code (tenant_id, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (tenant_id, role_id) REFERENCES role (tenant_id, id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_code) REFERENCES permission (code),
    CONSTRAINT chk_role_permission_tenant CHECK (tenant_id <> '')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '角色权限映射';

-- 账号-角色映射。granted_by 为逻辑引用，不建外键：系统开通或服务身份可能没有对应账号。
CREATE TABLE user_role (
    tenant_id  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    user_id    BINARY(16) NOT NULL COMMENT '账号 ID',
    role_id    BINARY(16) NOT NULL COMMENT '角色 ID',
    granted_by BINARY(16) NULL COMMENT '授予人账号 ID；系统授予为空',
    granted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '授予时间（UTC）',
    PRIMARY KEY (tenant_id, user_id, role_id),
    KEY idx_user_role_role (tenant_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (tenant_id, role_id) REFERENCES role (tenant_id, id),
    CONSTRAINT chk_user_role_tenant CHECK (tenant_id <> '')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '账号角色映射';

-- 工作空间：个人空间每人一个，团队空间由有权限的员工创建。
-- personal_owner_user_id 只在 PERSONAL 时填写，用于唯一约束；MySQL 唯一索引允许多个 NULL。
CREATE TABLE workspace (
    tenant_id              VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id                     BINARY(16) NOT NULL COMMENT '工作空间 ID（UUIDv7）',
    kind                   VARCHAR(16) NOT NULL COMMENT 'PERSONAL|TEAM',
    name                   VARCHAR(200) NOT NULL COMMENT '工作空间名称',
    description            VARCHAR(1000) NULL COMMENT '工作空间说明',
    owner_user_id          BINARY(16) NOT NULL COMMENT '所有者账号 ID',
    personal_owner_user_id BINARY(16) NULL COMMENT '仅个人空间填写，用于保证每人一个个人空间',
    status                 VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|ARCHIVED',
    created_by             BINARY(16) NOT NULL COMMENT '创建人账号 ID',
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    deleted_at             DATETIME(6) NULL COMMENT '软删除时间（UTC）；个人空间禁止删除',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_workspace_personal_owner (tenant_id, personal_owner_user_id),
    KEY idx_workspace_owner (tenant_id, owner_user_id, updated_at),
    KEY idx_workspace_list (tenant_id, status, updated_at),
    CONSTRAINT fk_workspace_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_workspace_owner FOREIGN KEY (tenant_id, owner_user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_workspace_created_by FOREIGN KEY (tenant_id, created_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_workspace_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_workspace_kind CHECK (kind IN ('PERSONAL', 'TEAM')),
    CONSTRAINT chk_workspace_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    -- 个人空间必须有 personal_owner_user_id；团队空间必须为空，避免唯一索引误伤团队空间。
    CONSTRAINT chk_workspace_personal CHECK (
        (kind = 'PERSONAL' AND personal_owner_user_id IS NOT NULL)
        OR (kind = 'TEAM' AND personal_owner_user_id IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '工作空间';
