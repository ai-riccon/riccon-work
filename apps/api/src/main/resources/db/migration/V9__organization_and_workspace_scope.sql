-- =============================================================================
-- V9：组织与部门数据范围（M03 唯一迁移；发布前由 Builder 在同一文件收紧）
--
-- 依据 docs/adr/ADR-0018-m03-organization-and-department-scope.md：
--   * 单级部门、单主部门；app_user.department_id 直接列 + 复合外键；
--   * workspace 增加 department_id 与 visibility；历史 TEAM 空间回填为 TENANT，
--     保持 M02“团队空间对本租户启用员工可见”的既有可见面，不猜部门；
--   * workspace_member 只服务 CUSTOM 可见性；
--   * 本迁移不删除、不改类型、不重建 M02 表；旧代码在滚动发布期间可继续插入。
--
-- V9 尚未发布：本提交先保证 M02 旧代码兼容（visibility 允许 NULL）；
-- Builder 完成代码切换后直接修正本文件，回填 NULL 并收紧为 NOT NULL + 严格 CHECK，
-- 不新增 V10（ADR-0018 6.2）。
--
-- 时间口径：DATETIME(6) UTC；所有租户表复合键，唯一键/索引以 tenant_id 开头。
-- =============================================================================

-- 部门：单级、租户内唯一名称；负责人可空且会移交，使用逻辑引用 + 索引，
-- 由服务层校验“本租户、本部门、启用账号”，避免在可变关系上再叠加外键约束。
CREATE TABLE department (
    tenant_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    id              BINARY(16) NOT NULL COMMENT '部门 ID（UUIDv7）',
    name            VARCHAR(100) NOT NULL COMMENT '部门名；租户内唯一，库排序规则大小写/重音不敏感',
    manager_user_id BINARY(16) NULL COMMENT '负责人账号 ID；必须是本租户启用成员，服务层校验',
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|DISABLED',
    created_by      BINARY(16) NOT NULL COMMENT '创建人账号 ID；跨租户时为平台管理员 UUID，逻辑引用 app_user',
    disabled_at     DATETIME(6) NULL COMMENT '停用时间（UTC）；ACTIVE 时为空',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (tenant_id, id),
    UNIQUE KEY uk_department_name (tenant_id, name),
    KEY idx_department_manager (tenant_id, manager_user_id),
    KEY idx_department_status (tenant_id, status, created_at),
    CONSTRAINT fk_department_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_department_tenant CHECK (tenant_id <> ''),
    CONSTRAINT chk_department_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    -- 停用必须有时间，启用必须没有时间，避免出现“启用的停用部门”。
    CONSTRAINT chk_department_disabled CHECK (
        (status = 'ACTIVE' AND disabled_at IS NULL)
        OR (status = 'DISABLED' AND disabled_at IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '租户部门（单级，M03）';

-- 账号的主部门：一列强制“至多一个部门”；复合外键阻止跨租户关联。
ALTER TABLE app_user
    ADD COLUMN department_id BINARY(16) NULL COMMENT '主部门 ID；单主部门，NULL 表示未分配',
    ADD KEY idx_app_user_department (tenant_id, department_id, status),
    ADD CONSTRAINT fk_app_user_department
        FOREIGN KEY (tenant_id, department_id) REFERENCES department (tenant_id, id);

-- 空间归属与可见范围。
-- visibility 的语义（ADR-0018 2.1）：
--   PRIVATE：个人空间，仅 owner_user_id；
--   TENANT ：仅 M02 历史团队空间，本租户启用员工可见，禁止新建；
--   DEPT   ：所属部门当前启用成员可见；
--   CUSTOM ：所属部门当前启用成员中，workspace_member 显式列出的成员可见。
ALTER TABLE workspace
    ADD COLUMN department_id BINARY(16) NULL COMMENT '所属部门；PERSONAL 与历史 TENANT 为空',
    ADD COLUMN visibility VARCHAR(16) NULL COMMENT 'PRIVATE|TENANT|DEPT|CUSTOM；发布前由 Builder 回填并收紧 NOT NULL',
    ADD KEY idx_workspace_department (tenant_id, department_id, visibility, status, updated_at),
    ADD CONSTRAINT fk_workspace_department
        FOREIGN KEY (tenant_id, department_id) REFERENCES department (tenant_id, id),
    -- 宽松 CHECK：只拒绝“已填写但组合非法”的值；NULL 留给旧代码，发布前收紧。
    ADD CONSTRAINT chk_workspace_scope CHECK (
        (visibility IS NULL AND department_id IS NULL)
        OR (kind = 'PERSONAL' AND visibility = 'PRIVATE' AND department_id IS NULL)
        OR (kind = 'TEAM' AND visibility = 'TENANT' AND department_id IS NULL)
        OR (kind = 'TEAM' AND visibility IN ('DEPT', 'CUSTOM') AND department_id IS NOT NULL)
    );

-- CUSTOM 空间的显式成员。DEPT 成员由 app_user.department_id 派生，不冗余写行。
CREATE TABLE workspace_member (
    tenant_id    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属租户',
    workspace_id BINARY(16) NOT NULL COMMENT '工作空间 ID',
    user_id      BINARY(16) NOT NULL COMMENT '成员账号 ID',
    granted_by   BINARY(16) NOT NULL COMMENT '加入操作人账号 ID',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '加入时间（UTC）',
    PRIMARY KEY (tenant_id, workspace_id, user_id),
    KEY idx_workspace_member_user (tenant_id, user_id, workspace_id),
    CONSTRAINT fk_workspace_member_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspace (tenant_id, id),
    CONSTRAINT fk_workspace_member_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_workspace_member_granted_by
        FOREIGN KEY (tenant_id, granted_by) REFERENCES app_user (tenant_id, id),
    CONSTRAINT chk_workspace_member_tenant CHECK (tenant_id <> '')
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '自定义可见空间的显式成员（M03）';

-- 回填既有空间：不猜部门，保持 M02 可见面。
UPDATE workspace
   SET visibility = 'PRIVATE'
 WHERE kind = 'PERSONAL'
   AND visibility IS NULL;

UPDATE workspace
   SET visibility = 'TENANT'
 WHERE kind = 'TEAM'
   AND visibility IS NULL;

-- 权限字典：
--   department.read：普通员工/租户管理员读取自己部门与成员列表；
--   platform.department.manage：平台管理员跨租户管理租户部门元数据。
-- 租户管理员不获得部门管理权限；部门负责人只通过资源关系管理本部门空间。
INSERT INTO permission (code, description)
VALUES ('department.read', '读取自己所属部门与部门成员列表'),
       ('platform.department.manage', '平台管理员跨租户管理租户部门元数据（不含业务内容）');

-- 既有租户角色回填；新租户由 RoleDefinitions 在开通时写入。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'department.read'
  FROM role r
 WHERE r.code IN ('TENANT_ADMIN', 'MEMBER');

-- 平台管理员角色只存在于 platform 保留租户；空库时角色可能尚未创建，
-- 由 BootstrapAdminRunner 在启动时按 RoleDefinitions 创建（Builder 更新常量）。
INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'platform.department.manage'
  FROM role r
 WHERE r.tenant_id = 'platform'
   AND r.code = 'PLATFORM_ADMIN';
