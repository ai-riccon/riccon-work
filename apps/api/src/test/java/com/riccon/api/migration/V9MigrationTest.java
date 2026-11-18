package com.riccon.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.persistence.BinaryUuids;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V9 组织与部门数据范围迁移语义测试。
 *
 * <p>不使用热栈或应用上下文：一次性 Testcontainers MySQL 8.4 上先用 Flyway 只应用到 V8，
 * 种入 M02 形态的租户/账号/角色/权限/个人空间/团队空间，再应用 V9，验证回填、NOT NULL、
 * 严格 CHECK、权限回填与复合外键；空库直接应用 V9 通过不算本测试的替代。
 *
 * <p>回滚演练在同一个一次性容器的第二个 schema（{@code riccon_rollback}）上执行：同样从 V1–V8
 * 开始、种入相同 M02 数据、应用 V9，再执行 ADR-0018 §6.3 的回滚 SQL，验证回到 V8 且 M02
 * 数据可读。第二个 schema 是迁移状态的逻辑副本，避免在一次性容器里做物理备份。
 *
 * <p><strong>V9 未发布前提与本地重置</strong>：本测试只在“从未应用过宽松版 V9”的环境成立。
 * 已经应用旧版 V9 的持久环境不得修改 V9 文件，必须新增 V10 前向迁移并保留 V9 校验和
 * （ADR-0018 §6.2 F-009）；本地/测试库优先 {@code docker compose ... down -v} 或 drop/recreate
 * schema 后重跑 Flyway。禁止用 {@code flyway repair} 掩盖 checksum。回滚前必须停 API/Worker
 * 写入、排空旧 API 实例并做全量备份；{@code flyway_schema_history} 的 V9 行由维护账号按
 * ADR-0010 处理，应用账号不得删除，生产回滚演练在 M09 完成（B-13）。
 */
class V9MigrationTest {

    /** 与合同 stack.versions.mysql 一致的测试镜像；命令参数与 API 集成测试保持一致。 */
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13")
            .withDatabaseName("riccon")
            .withUsername("riccon")
            .withPassword("riccon_test_only")
            .withCommand(
                    "--log-bin-trust-function-creators=1",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    /** 正向迁移 schema；Testcontainers 按 withDatabaseName 创建。 */
    private static final String FORWARD_SCHEMA = "riccon";

    /** 回滚演练 schema；由 root 在同一容器内创建，内容通过相同迁移与种子构造。 */
    private static final String ROLLBACK_SCHEMA = "riccon_rollback";

    /** 容器业务账号。 */
    private static final String USERNAME = "riccon";

    /** 仅容器内使用的占位密码，不对应任何真实环境；root 在同一容器内复用该密码。 */
    private static final String PASSWORD = "riccon_test_only";

    /** 平台保留租户。 */
    private static final String PLATFORM_TENANT = "platform";

    /** 租户 A。 */
    private static final String TENANT_A = "acme";

    /** 租户 B，用于跨租户断言。 */
    private static final String TENANT_B = "globex";

    /** 平台管理员账号。 */
    private static final UUID PLATFORM_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000001");

    /** 租户 A 管理员账号。 */
    private static final UUID TENANT_A_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000002");

    /** 租户 A 普通员工账号。 */
    private static final UUID TENANT_A_MEMBER_USER = uuid("00000000-0000-7000-8000-000000000003");

    /** 租户 A 第二个普通员工账号；用于个人空间与非法组合种子。 */
    private static final UUID TENANT_A_MEMBER_TWO_USER = uuid("00000000-0000-7000-8000-000000000004");

    /** 租户 B 管理员账号。 */
    private static final UUID TENANT_B_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000005");

    /** 租户 B 普通员工账号。 */
    private static final UUID TENANT_B_MEMBER_USER = uuid("00000000-0000-7000-8000-000000000006");

    /** 平台管理员角色。 */
    private static final UUID PLATFORM_ADMIN_ROLE = uuid("00000000-0000-7000-8000-000000000101");

    /** 租户 A 管理员角色。 */
    private static final UUID TENANT_A_ADMIN_ROLE = uuid("00000000-0000-7000-8000-000000000102");

    /** 租户 A 普通成员角色。 */
    private static final UUID TENANT_A_MEMBER_ROLE = uuid("00000000-0000-7000-8000-000000000103");

    /** 租户 B 管理员角色。 */
    private static final UUID TENANT_B_ADMIN_ROLE = uuid("00000000-0000-7000-8000-000000000104");

    /** 租户 B 普通成员角色。 */
    private static final UUID TENANT_B_MEMBER_ROLE = uuid("00000000-0000-7000-8000-000000000105");

    /** 租户 A 个人空间；M02 旧代码不写 visibility。 */
    private static final UUID TENANT_A_PERSONAL_WORKSPACE = uuid("00000000-0000-7000-8000-000000000201");

    /** 租户 A 历史团队空间；M02 旧代码不写 visibility。 */
    private static final UUID TENANT_A_TEAM_WORKSPACE = uuid("00000000-0000-7000-8000-000000000202");

    /** 租户 B 个人空间。 */
    private static final UUID TENANT_B_PERSONAL_WORKSPACE = uuid("00000000-0000-7000-8000-000000000203");

    /** 租户 B 历史团队空间。 */
    private static final UUID TENANT_B_TEAM_WORKSPACE = uuid("00000000-0000-7000-8000-000000000204");

    /** 租户 A 启用部门；V9 之后创建。 */
    private static final UUID TENANT_A_DEPARTMENT = uuid("00000000-0000-7000-8000-000000000301");

    /** 租户 A 停用部门；验证停用时间约束。 */
    private static final UUID TENANT_A_DISABLED_DEPARTMENT = uuid("00000000-0000-7000-8000-000000000302");

    /** 租户 B 启用部门；用于跨租户断言。 */
    private static final UUID TENANT_B_DEPARTMENT = uuid("00000000-0000-7000-8000-000000000303");

    /** 租户 A 部门空间；B3 新代码写入 DEPT。 */
    private static final UUID TENANT_A_DEPT_WORKSPACE = uuid("00000000-0000-7000-8000-000000000401");

    /** 租户 A 自定义空间；B3 新代码写入 CUSTOM。 */
    private static final UUID TENANT_A_CUSTOM_WORKSPACE = uuid("00000000-0000-7000-8000-000000000402");

    /** 正向 schema 的 JDBC 客户端。 */
    private static JdbcClient forward;

    /** 回滚 schema 的 JDBC 客户端（root；该 schema 由测试创建）。 */
    private static JdbcClient rollback;

    /** 正向 schema 的 Flyway URL。 */
    private static String forwardUrl;

    /** 回滚 schema 的 Flyway URL。 */
    private static String rollbackUrl;

    /**
     * 启动一次性容器，构造正向与回滚两个 schema，并在回滚 schema 上完成回滚演练。
     *
     * <p>回滚演练在测试方法执行前完成，测试方法只做断言，避免依赖执行顺序。
     */
    @BeforeAll
    static void prepareSchemas() {
        MYSQL.start();
        forwardUrl = jdbcUrl(FORWARD_SCHEMA);
        rollbackUrl = jdbcUrl(ROLLBACK_SCHEMA);
        createRollbackSchema();

        // 正向：V1–V8 -> M02 seed -> V9，保留 V9 后的状态供全部正向断言使用。
        migrate(forwardUrl, USERNAME, "8");
        forward = jdbcClient(forwardUrl, USERNAME);
        seedM02Data(forward);
        migrate(forwardUrl, USERNAME, "9");
        seedM03Data(forward);

        // 回滚：相同路径到 V9，再执行 ADR-0018 §6.3 SQL；随后由维护账号语义删除 V9 历史行。
        migrate(rollbackUrl, "root", "8");
        rollback = jdbcClient(rollbackUrl, "root");
        seedM02Data(rollback);
        migrate(rollbackUrl, "root", "9");
        dropV9Objects();
    }

    /**
     * 回填：V8 时期不存在的 visibility 列在 V9 ADD COLUMN 后对所有既有行为 NULL，
     * 回填必须按 kind 落到 PRIVATE/TENANT，且列在回填后收紧为 NOT NULL。
     */
    @Test
    @DisplayName("V9 回填 M02 空间可见性并收紧 NOT NULL")
    void migration_backfillsLegacyWorkspaceVisibility() {
        assertThat(visibility(TENANT_A, TENANT_A_PERSONAL_WORKSPACE)).isEqualTo("PRIVATE");
        assertThat(visibility(TENANT_A, TENANT_A_TEAM_WORKSPACE)).isEqualTo("TENANT");
        assertThat(visibility(TENANT_B, TENANT_B_PERSONAL_WORKSPACE)).isEqualTo("PRIVATE");
        assertThat(visibility(TENANT_B, TENANT_B_TEAM_WORKSPACE)).isEqualTo("TENANT");
        assertThat(nullVisibilityRows()).isZero();

        String nullable = forward.sql("""
                        SELECT IS_NULLABLE
                          FROM information_schema.columns
                         WHERE table_schema = :schema AND table_name = 'workspace' AND column_name = 'visibility'
                        """)
                .param("schema", FORWARD_SCHEMA)
                .query(String.class)
                .single();
        assertThat(nullable).isEqualTo("NO");

        String comment = forward.sql("""
                        SELECT COLUMN_COMMENT
                          FROM information_schema.columns
                         WHERE table_schema = :schema AND table_name = 'workspace' AND column_name = 'visibility'
                        """)
                .param("schema", FORWARD_SCHEMA)
                .query(String.class)
                .single();
        assertThat(comment).contains("TENANT").contains("历史");
    }

    /** 严格 CHECK：只接受 ADR-0018 §6.2 的三类组合，且不再容忍 visibility IS NULL。 */
    @Test
    @DisplayName("V9 收紧 chk_workspace_scope 为严格组合")
    void migration_createsStrictWorkspaceScopeCheck() {
        String clause = forward.sql("""
                        SELECT cc.CHECK_CLAUSE
                          FROM information_schema.TABLE_CONSTRAINTS tc
                          JOIN information_schema.CHECK_CONSTRAINTS cc
                            ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                           AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                         WHERE tc.TABLE_SCHEMA = :schema
                           AND tc.TABLE_NAME = 'workspace'
                           AND tc.CONSTRAINT_NAME = 'chk_workspace_scope'
                        """)
                .param("schema", FORWARD_SCHEMA)
                .query(String.class)
                .single();
        assertThat(clause)
                .contains("PRIVATE")
                .contains("TENANT")
                .contains("DEPT")
                .contains("CUSTOM")
                .doesNotContainIgnoringCase("visibility is null");
    }

    /** 权限回填：department.read 给租户管理员与成员；平台管理权限只给 platform 的 PLATFORM_ADMIN。 */
    @Test
    @DisplayName("V9 回填部门权限且不创建 department.manage")
    void migration_backfillsDepartmentPermissions() {
        assertThat(permissionCount("department.read")).isEqualTo(1L);
        assertThat(permissionCount("platform.department.manage")).isEqualTo(1L);
        assertThat(permissionCount("department.manage")).isZero();

        assertThat(rolePermissionCount(TENANT_A, TENANT_A_ADMIN_ROLE, "department.read"))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(TENANT_A, TENANT_A_MEMBER_ROLE, "department.read"))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(TENANT_B, TENANT_B_ADMIN_ROLE, "department.read"))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(TENANT_B, TENANT_B_MEMBER_ROLE, "department.read"))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "department.read"))
                .isZero();

        assertThat(rolePermissionCount(PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "platform.department.manage"))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(TENANT_A, TENANT_A_ADMIN_ROLE, "platform.department.manage"))
                .isZero();
        assertThat(rolePermissionCount(TENANT_B, TENANT_B_MEMBER_ROLE, "platform.department.manage"))
                .isZero();
        assertThat(rolePermissionCount(TENANT_A, TENANT_A_ADMIN_ROLE, "department.manage"))
                .isZero();
    }

    /** M02 数据在 V9 后保持可读，行数与关键字段不变；迁移只新增列，不改写业务数据。 */
    @Test
    @DisplayName("V9 保留 M02 租户/账号/角色/空间数据")
    void migration_keepsM02DataReadable() {
        assertThat(count("SELECT COUNT(*) FROM tenant WHERE id IN ('platform', 'acme', 'globex')"))
                .isEqualTo(3L);
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE tenant_id IN ('platform', 'acme', 'globex')"))
                .isEqualTo(6L);
        assertThat(count("SELECT COUNT(*) FROM role WHERE tenant_id IN ('platform', 'acme', 'globex')"))
                .isEqualTo(5L);
        // 只统计 M02 种子空间；V9 之后新增的 DEPT/CUSTOM 空间不计入 M02 数据基线。
        assertThat(forward.sql("""
                        SELECT COUNT(*) FROM workspace WHERE id IN (
                            :personalA, :teamA, :personalB, :teamB
                        )
                        """)
                        .param("personalA", BinaryUuids.toBytes(TENANT_A_PERSONAL_WORKSPACE))
                        .param("teamA", BinaryUuids.toBytes(TENANT_A_TEAM_WORKSPACE))
                        .param("personalB", BinaryUuids.toBytes(TENANT_B_PERSONAL_WORKSPACE))
                        .param("teamB", BinaryUuids.toBytes(TENANT_B_TEAM_WORKSPACE))
                        .query(Long.class)
                        .single())
                .isEqualTo(4L);
        assertThat(forward.sql("SELECT name FROM workspace WHERE tenant_id = :tenantId AND id = :id")
                        .param("tenantId", TENANT_A)
                        .param("id", BinaryUuids.toBytes(TENANT_A_TEAM_WORKSPACE))
                        .query(String.class)
                        .single())
                .isEqualTo("Acme Team");
    }

    /** 合法组合在 V9 后仍可写：历史 TENANT、新 DEPT/CUSTOM、个人空间 PRIVATE。 */
    @Test
    @DisplayName("V9 接受合法空间组合")
    void migration_acceptsValidWorkspaceCombinations() {
        assertThat(workspaceCount(TENANT_A, TENANT_A_DEPT_WORKSPACE)).isEqualTo(1L);
        assertThat(workspaceCount(TENANT_A, TENANT_A_CUSTOM_WORKSPACE)).isEqualTo(1L);
        // 历史 TENANT 团队空间与个人 PRIVATE 空间由种子覆盖，回填后仍满足严格 CHECK。
        assertThat(workspaceCount(TENANT_A, TENANT_A_TEAM_WORKSPACE)).isEqualTo(1L);
        assertThat(workspaceCount(TENANT_A, TENANT_A_PERSONAL_WORKSPACE)).isEqualTo(1L);
    }

    /** 非法可见性组合必须由 CHECK 拒绝；错误信息必须指向 chk_workspace_scope，避免假绿。 */
    @Test
    @DisplayName("V9 拒绝非法空间可见性组合")
    void migration_rejectsIllegalWorkspaceCombinations() {
        assertRejected(
                "chk_workspace_scope",
                () -> insertTenantAWorkspace(UUID.randomUUID(), "TEAM", "TENANT", TENANT_A_DEPARTMENT, "非法 TENANT"));
        assertRejected(
                "chk_workspace_scope",
                () -> insertTenantAWorkspace(UUID.randomUUID(), "TEAM", "PRIVATE", null, "非法 PRIVATE"));
        assertRejected(
                "chk_workspace_scope",
                () -> insertTenantAWorkspace(UUID.randomUUID(), "TEAM", "DEPT", null, "非法无部门 DEPT"));
        assertRejected(
                "chk_workspace_scope",
                () -> insertTenantAWorkspace(UUID.randomUUID(), "TEAM", "CUSTOM", null, "非法无部门 CUSTOM"));
        assertRejected(
                "chk_workspace_scope",
                () -> insertPersonalWorkspace(UUID.randomUUID(), TENANT_A_MEMBER_TWO_USER, TENANT_A_DEPARTMENT));
        assertRejected(
                "chk_workspace_scope",
                () -> insertTenantAWorkspace(UUID.randomUUID(), "TEAM", "PUBLIC", null, "非法可见性值"));
    }

    /** 列已 NOT NULL：显式写入 NULL 必须失败，不能靠回填掩盖新写入。 */
    @Test
    @DisplayName("V9 拒绝显式 NULL 可见性")
    void migration_rejectsExplicitNullVisibility() {
        assertThatThrownBy(() -> insertTenantAWorkspace(UUID.randomUUID(), "TEAM", null, null, "NULL 团队空间"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("visibility")
                .hasMessageContaining("cannot be null");
    }

    /** 复合外键：账号主部门、空间部门、空间成员都必须同租户，跨租户一律拒绝。 */
    @Test
    @DisplayName("V9 复合外键阻止跨租户部门与成员")
    void migration_enforcesCrossTenantForeignKeys() {
        assertRejected(
                "fk_app_user_department",
                () -> forward.sql("""
                                UPDATE app_user SET department_id = :departmentId
                                 WHERE tenant_id = :tenantId AND id = :userId
                                """)
                        .param("departmentId", BinaryUuids.toBytes(TENANT_B_DEPARTMENT))
                        .param("tenantId", TENANT_A)
                        .param("userId", BinaryUuids.toBytes(TENANT_A_MEMBER_USER))
                        .update());
        assertRejected(
                "fk_workspace_department",
                () -> insertTenantAWorkspace(UUID.randomUUID(), "TEAM", "DEPT", TENANT_B_DEPARTMENT, "跨租户部门空间"));
        assertRejected(
                "fk_workspace_member_user",
                () -> insertWorkspaceMember(
                        TENANT_A, TENANT_A_CUSTOM_WORKSPACE, TENANT_B_MEMBER_USER, TENANT_A_ADMIN_USER));
        assertRejected(
                "fk_workspace_member_workspace",
                () -> insertWorkspaceMember(
                        TENANT_B, TENANT_A_CUSTOM_WORKSPACE, TENANT_A_MEMBER_USER, TENANT_B_ADMIN_USER));
        assertRejected(
                "fk_workspace_member_granted_by",
                () -> insertWorkspaceMember(
                        TENANT_A, TENANT_A_CUSTOM_WORKSPACE, TENANT_A_MEMBER_TWO_USER, TENANT_B_ADMIN_USER));
    }

    /** 部门 CHECK 与唯一键：状态/停用时间必须成对，租户内名称唯一（大小写不敏感）。 */
    @Test
    @DisplayName("V9 部门 CHECK 与唯一键生效")
    void migration_enforcesDepartmentChecksAndUniqueness() {
        assertThatThrownBy(() -> insertDepartment(TENANT_A, UUID.randomUUID(), "非法状态", "FROZEN", false))
                .isInstanceOf(DataAccessException.class);
        assertRejected(
                "chk_department_disabled",
                () -> insertDepartment(TENANT_A, UUID.randomUUID(), "停用无时间", "DISABLED", false));
        assertRejected(
                "chk_department_disabled",
                () -> insertDepartment(TENANT_A, UUID.randomUUID(), "启用有时间", "ACTIVE", true));
        assertRejected(
                "uk_department_name",
                () -> insertDepartment(TENANT_A, UUID.randomUUID(), "engineering", "ACTIVE", false));

        // 同一名称在同一租户内（大小写不敏感）唯一，但允许跨租户复用。
        insertDepartment(TENANT_B, UUID.randomUUID(), "engineering", "ACTIVE", false);
        assertThat(forward.sql("""
                        SELECT COUNT(*) FROM department
                         WHERE tenant_id = :tenantId AND name = 'engineering'
                        """)
                        .param("tenantId", TENANT_B)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    /** CUSTOM 成员主键：同一空间同一账号只能有一行显式成员。 */
    @Test
    @DisplayName("V9 CUSTOM 成员唯一键生效")
    void migration_enforcesWorkspaceMemberUniqueness() {
        assertRejected(
                "workspace_member.PRIMARY",
                () -> insertWorkspaceMember(
                        TENANT_A, TENANT_A_CUSTOM_WORKSPACE, TENANT_A_MEMBER_USER, TENANT_A_ADMIN_USER));
    }

    /**
     * 回滚演练：ADR-0018 §6.3 SQL 在 V9 后的逻辑副本上删除全部 V9 对象与权限回填，
     * 回到 V8 结构，M02 数据仍可读；V9 历史行按 ADR-0010 的维护账号语义移除，不使用 flyway repair。
     */
    @Test
    @DisplayName("ADR-0018 §6.3 回滚回到 V8 且 M02 数据可读")
    void rollback_returnsToV8AndKeepsM02DataReadable() {
        assertThat(rollbackCount("""
                        SELECT COUNT(*) FROM information_schema.tables
                         WHERE table_schema = :schema AND table_name IN ('department', 'workspace_member')
                        """)).isZero();
        assertThat(rollbackCount("""
                        SELECT COUNT(*) FROM information_schema.columns
                         WHERE table_schema = :schema
                           AND ((table_name = 'workspace' AND column_name IN ('department_id', 'visibility'))
                             OR (table_name = 'app_user' AND column_name = 'department_id'))
                        """)).isZero();
        assertThat(rollbackCount("""
                        SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                         WHERE table_schema = :schema AND constraint_name = 'chk_workspace_scope'
                        """)).isZero();
        assertThat(rollbackCount("""
                        SELECT COUNT(*) FROM information_schema.statistics
                         WHERE table_schema = :schema
                           AND index_name IN ('idx_workspace_department', 'idx_app_user_department')
                        """)).isZero();
        assertThat(rollbackCount("""
                        SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                         WHERE table_schema = :schema
                           AND constraint_name IN ('fk_workspace_department', 'fk_app_user_department')
                        """)).isZero();

        assertThat(rollbackCount("SELECT COUNT(*) FROM permission WHERE code IN "
                        + "('department.read', 'platform.department.manage')"))
                .isZero();
        assertThat(rollbackCount("SELECT COUNT(*) FROM role_permission WHERE permission_code IN "
                        + "('department.read', 'platform.department.manage')"))
                .isZero();
        assertThat(rollbackCount("SELECT COUNT(*) FROM permission WHERE code = 'workspace.read'"))
                .isEqualTo(1L);
        // 种子中 3 条 M02 基线 role_permission 必须保留。
        assertThat(rollbackCount("SELECT COUNT(*) FROM role_permission")).isEqualTo(3L);

        // M02 数据可读：行数与关键字段与迁移前一致。
        assertThat(rollbackCount("SELECT COUNT(*) FROM workspace WHERE tenant_id IN ('acme', 'globex')"))
                .isEqualTo(4L);
        assertThat(rollbackCount("SELECT COUNT(*) FROM app_user WHERE tenant_id IN ('platform', 'acme', 'globex')"))
                .isEqualTo(6L);
        assertThat(rollbackCount("""
                        SELECT COUNT(*) FROM workspace
                         WHERE tenant_id = 'acme' AND kind = 'TEAM' AND name = 'Acme Team'
                        """)).isEqualTo(1L);

        // 维护账号按 ADR-0010 删除 V9 历史行后，schema 版本回到 8；不使用 flyway repair。
        rollback.sql("DELETE FROM flyway_schema_history WHERE version = '9'").update();
        assertThat(rollbackCount("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history"))
                .isEqualTo(8L);
        Flyway latest = flyway(rollbackUrl, "root");
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThat(latest.info().pending())
                .extracting(info -> info.getVersion().getVersion())
                .contains("9");
    }

    /** 构造正向/回滚两个 schema 共用的 M02 种子；此时表结构仍是 V8。 */
    private static void seedM02Data(JdbcClient jdbc) {
        // 租户：platform 由 V2 写入；这里补一个正常租户与一个跨租户对照租户。
        jdbc.sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantA, 'Acme', 'ACTIVE', 'standard', 180),
                               (:tenantB, 'Globex', 'ACTIVE', 'standard', 180)
                        """).param("tenantA", TENANT_A).param("tenantB", TENANT_B).update();

        insertUser(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_USER, "platform-admin", "Platform Admin");
        insertUser(jdbc, TENANT_A, TENANT_A_ADMIN_USER, "acme-admin", "Acme Admin");
        insertUser(jdbc, TENANT_A, TENANT_A_MEMBER_USER, "acme-member", "Acme Member");
        insertUser(jdbc, TENANT_A, TENANT_A_MEMBER_TWO_USER, "acme-member-two", "Acme Member Two");
        insertUser(jdbc, TENANT_B, TENANT_B_ADMIN_USER, "globex-admin", "Globex Admin");
        insertUser(jdbc, TENANT_B, TENANT_B_MEMBER_USER, "globex-member", "Globex Member");

        insertRole(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "PLATFORM_ADMIN");
        insertRole(jdbc, TENANT_A, TENANT_A_ADMIN_ROLE, "TENANT_ADMIN");
        insertRole(jdbc, TENANT_A, TENANT_A_MEMBER_ROLE, "MEMBER");
        insertRole(jdbc, TENANT_B, TENANT_B_ADMIN_ROLE, "TENANT_ADMIN");
        insertRole(jdbc, TENANT_B, TENANT_B_MEMBER_ROLE, "MEMBER");

        // M02 基线 role_permission：回滚后应保留，用于证明只删除 V9 新增的授权。
        insertRolePermission(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "platform.tenant.manage");
        insertRolePermission(jdbc, TENANT_A, TENANT_A_MEMBER_ROLE, "workspace.read");
        insertRolePermission(jdbc, TENANT_B, TENANT_B_MEMBER_ROLE, "workspace.read");

        // M02 旧代码创建的空间：V8 还没有 visibility/department_id 列。
        insertLegacyWorkspace(
                jdbc, TENANT_A, TENANT_A_PERSONAL_WORKSPACE, "PERSONAL", "Acme Personal", TENANT_A_MEMBER_USER, true);
        insertLegacyWorkspace(jdbc, TENANT_A, TENANT_A_TEAM_WORKSPACE, "TEAM", "Acme Team", TENANT_A_ADMIN_USER, false);
        insertLegacyWorkspace(
                jdbc, TENANT_B, TENANT_B_PERSONAL_WORKSPACE, "PERSONAL", "Globex Personal", TENANT_B_MEMBER_USER, true);
        insertLegacyWorkspace(
                jdbc, TENANT_B, TENANT_B_TEAM_WORKSPACE, "TEAM", "Globex Team", TENANT_B_ADMIN_USER, false);
    }

    /** V9 之后的 B2/B3 形态数据：部门、成员归属、DEPT/CUSTOM 空间与一条显式成员。 */
    private static void seedM03Data(JdbcClient jdbc) {
        insertDepartment(jdbc, TENANT_A, TENANT_A_DEPARTMENT, "Engineering", "ACTIVE", false);
        insertDepartment(jdbc, TENANT_A, TENANT_A_DISABLED_DEPARTMENT, "Legacy Dept", "DISABLED", true);
        insertDepartment(jdbc, TENANT_B, TENANT_B_DEPARTMENT, "Globex Engineering", "ACTIVE", false);

        assignDepartment(jdbc, TENANT_A, TENANT_A_MEMBER_USER, TENANT_A_DEPARTMENT);
        assignDepartment(jdbc, TENANT_A, TENANT_A_MEMBER_TWO_USER, TENANT_A_DEPARTMENT);
        assignDepartment(jdbc, TENANT_B, TENANT_B_MEMBER_USER, TENANT_B_DEPARTMENT);

        insertTenantAWorkspace(TENANT_A_DEPT_WORKSPACE, "TEAM", "DEPT", TENANT_A_DEPARTMENT, "部门空间");
        insertTenantAWorkspace(TENANT_A_CUSTOM_WORKSPACE, "TEAM", "CUSTOM", TENANT_A_DEPARTMENT, "自定义空间");
        insertWorkspaceMember(TENANT_A, TENANT_A_CUSTOM_WORKSPACE, TENANT_A_MEMBER_USER, TENANT_A_ADMIN_USER);
    }

    /** 执行 ADR-0018 §6.3 的回滚 SQL；顺序与 ADR 一致，先删子表/外键/索引/CHECK，再删列与权限。 */
    private static void dropV9Objects() {
        List<String> statements = List.of(
                "DROP TABLE workspace_member",
                "ALTER TABLE workspace DROP FOREIGN KEY fk_workspace_department",
                "ALTER TABLE workspace DROP INDEX idx_workspace_department",
                "ALTER TABLE workspace DROP CHECK chk_workspace_scope",
                "ALTER TABLE workspace DROP COLUMN visibility",
                "ALTER TABLE workspace DROP COLUMN department_id",
                "ALTER TABLE app_user DROP FOREIGN KEY fk_app_user_department",
                "ALTER TABLE app_user DROP INDEX idx_app_user_department",
                "ALTER TABLE app_user DROP COLUMN department_id",
                "DROP TABLE department",
                "DELETE FROM role_permission WHERE permission_code IN "
                        + "('department.read', 'platform.department.manage')",
                "DELETE FROM permission WHERE code IN ('department.read', 'platform.department.manage')");
        for (String statement : statements) {
            rollback.sql(statement).update();
        }
    }

    /** 以给定目标版本执行 Flyway；target=8 用于构造 V8 种子，target=9 用于应用 V9。 */
    private static void migrate(String url, String user, String target) {
        flyway(url, user, target).migrate();
    }

    /** 构造指定目标版本的 Flyway 实例。 */
    private static Flyway flyway(String url, String user, String target) {
        return Flyway.configure()
                .dataSource(url, user, PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    /** 构造不限制目标的 Flyway 实例，用于回滚后读取 current/pending 状态。 */
    private static Flyway flyway(String url, String user) {
        return Flyway.configure()
                .dataSource(url, user, PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .load();
    }

    /** 定位权威迁移目录，避免测试复制一份会漂移的 SQL。 */
    private static Path migrationDirectory() {
        Path moduleRelative = Path.of("src/main/resources/db/migration");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative.toAbsolutePath().normalize();
        }
        Path repoRelative = Path.of("apps/api/src/main/resources/db/migration");
        if (Files.isDirectory(repoRelative)) {
            return repoRelative.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("cannot locate apps/api/db/migration for migration tests");
    }

    /** 使用 root 创建回滚演练 schema；Testcontainers 只创建业务 schema。 */
    private static void createRollbackSchema() {
        String serverUrl = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
        try (Connection connection = DriverManager.getConnection(serverUrl, "root", PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + ROLLBACK_SCHEMA
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create rollback schema", exception);
        }
    }

    /** 拼装带测试连接参数的 JDBC URL。 */
    private static String jdbcUrl(String schema) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }

    /** 创建 JDBC 客户端；root 只用于测试自建的回滚 schema。 */
    private static JdbcClient jdbcClient(String url, String user) {
        return JdbcClient.create(new DriverManagerDataSource(url, user, PASSWORD));
    }

    /** 种一个 V8 形态账号。 */
    private static void insertUser(JdbcClient jdbc, String tenant, UUID userId, String username, String displayName) {
        jdbc.sql("""
                        INSERT INTO app_user (tenant_id, id, username, display_name, status, auth_source)
                        VALUES (:tenantId, :id, :username, :displayName, 'ACTIVE', 'LOCAL')
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(userId))
                .param("username", username)
                .param("displayName", displayName)
                .update();
    }

    /** 种一个系统角色。 */
    private static void insertRole(JdbcClient jdbc, String tenant, UUID roleId, String code) {
        jdbc.sql("""
                        INSERT INTO role (tenant_id, id, code, name, is_system)
                        VALUES (:tenantId, :id, :code, :name, TRUE)
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(roleId))
                .param("code", code)
                .param("name", code)
                .update();
    }

    /** 种一条角色权限映射。 */
    private static void insertRolePermission(JdbcClient jdbc, String tenant, UUID roleId, String permissionCode) {
        jdbc.sql("""
                        INSERT INTO role_permission (tenant_id, role_id, permission_code)
                        VALUES (:tenantId, :roleId, :permissionCode)
                        """)
                .param("tenantId", tenant)
                .param("roleId", BinaryUuids.toBytes(roleId))
                .param("permissionCode", permissionCode)
                .update();
    }

    /** 种一个 M02 形态空间；V8 表没有 visibility/department_id 列。 */
    private static void insertLegacyWorkspace(
            JdbcClient jdbc, String tenant, UUID workspaceId, String kind, String name, UUID owner, boolean personal) {
        jdbc.sql("""
                        INSERT INTO workspace (
                            tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                            status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :kind, :name, NULL, :ownerUserId, :personalOwnerUserId,
                            'ACTIVE', :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(workspaceId))
                .param("kind", kind)
                .param("name", name)
                .param("ownerUserId", BinaryUuids.toBytes(owner))
                .param("personalOwnerUserId", personal ? BinaryUuids.toBytes(owner) : null)
                .param("createdBy", BinaryUuids.toBytes(owner))
                .update();
    }

    /**
     * 种一个租户 A 的 V9 形态空间；tenant/所有者固定为租户 A，避免跨租户种子互相污染。
     * visibility 由参数显式传入，供非法组合断言使用。
     */
    private static void insertTenantAWorkspace(
            UUID workspaceId, String kind, String visibility, UUID departmentId, String name) {
        forward.sql("""
                        INSERT INTO workspace (
                            tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                            department_id, visibility, status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :kind, :name, NULL, :ownerUserId, NULL,
                            :departmentId, :visibility, 'ACTIVE', :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", TENANT_A)
                .param("id", BinaryUuids.toBytes(workspaceId))
                .param("kind", kind)
                .param("name", name)
                .param("ownerUserId", BinaryUuids.toBytes(TENANT_A_ADMIN_USER))
                .param("departmentId", departmentId == null ? null : BinaryUuids.toBytes(departmentId))
                .param("visibility", visibility)
                .param("createdBy", BinaryUuids.toBytes(TENANT_A_ADMIN_USER))
                .update();
    }

    /** 种一个租户 A 的个人空间；用于 PERSONAL + 非空部门的非法组合断言。 */
    private static void insertPersonalWorkspace(UUID workspaceId, UUID owner, UUID departmentId) {
        forward.sql("""
                        INSERT INTO workspace (
                            tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                            department_id, visibility, status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, 'PERSONAL', '非法个人空间', NULL, :ownerUserId, :ownerUserId,
                            :departmentId, 'PRIVATE', 'ACTIVE', :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", TENANT_A)
                .param("id", BinaryUuids.toBytes(workspaceId))
                .param("ownerUserId", BinaryUuids.toBytes(owner))
                .param("departmentId", BinaryUuids.toBytes(departmentId))
                .param("createdBy", BinaryUuids.toBytes(owner))
                .update();
    }

    /** 种一个部门（正向 schema）。 */
    private static void insertDepartment(
            String tenant, UUID departmentId, String name, String status, boolean disabled) {
        insertDepartment(forward, tenant, departmentId, name, status, disabled);
    }

    /**
     * 种一个部门；disabled 为真时写 disabled_at=NOW(6)，否则为 NULL。
     * 该布尔参数只控制“是否有停用时间”，状态本身由 status 参数决定，便于构造非法组合。
     */
    private static void insertDepartment(
            JdbcClient jdbc, String tenant, UUID departmentId, String name, String status, boolean disabled) {
        jdbc.sql("""
                        INSERT INTO department (tenant_id, id, name, status, created_by, disabled_at)
                        VALUES (
                            :tenantId, :id, :name, :status, :createdBy,
                            CASE WHEN :disabled THEN NOW(6) ELSE NULL END
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(departmentId))
                .param("name", name)
                .param("status", status)
                .param("createdBy", BinaryUuids.toBytes(PLATFORM_ADMIN_USER))
                .param("disabled", disabled)
                .update();
    }

    /** 把账号分配到主部门。 */
    private static void assignDepartment(JdbcClient jdbc, String tenant, UUID userId, UUID departmentId) {
        jdbc.sql("""
                        UPDATE app_user SET department_id = :departmentId
                         WHERE tenant_id = :tenantId AND id = :userId
                        """)
                .param("departmentId", BinaryUuids.toBytes(departmentId))
                .param("tenantId", tenant)
                .param("userId", BinaryUuids.toBytes(userId))
                .update();
    }

    /** 种一条 CUSTOM 空间显式成员（正向 schema）。 */
    private static void insertWorkspaceMember(String tenant, UUID workspaceId, UUID userId, UUID grantedBy) {
        insertWorkspaceMember(forward, tenant, workspaceId, userId, grantedBy);
    }

    /** 种一条 CUSTOM 空间显式成员。 */
    private static void insertWorkspaceMember(
            JdbcClient jdbc, String tenant, UUID workspaceId, UUID userId, UUID grantedBy) {
        jdbc.sql("""
                        INSERT INTO workspace_member (tenant_id, workspace_id, user_id, granted_by)
                        VALUES (:tenantId, :workspaceId, :userId, :grantedBy)
                        """)
                .param("tenantId", tenant)
                .param("workspaceId", BinaryUuids.toBytes(workspaceId))
                .param("userId", BinaryUuids.toBytes(userId))
                .param("grantedBy", BinaryUuids.toBytes(grantedBy))
                .update();
    }

    /** 断言数据库以指定约束名拒绝写入；错误信息必须包含约束名，避免被其他约束误判为通过。 */
    private static void assertRejected(String constraintName, ThrowingCallable callable) {
        // MySQL 8 的 CHECK 违反（3819/HY000）被 Spring 归为 UncategorizedSQLException，
        // 外键与唯一键则归为 DataIntegrityViolationException；统一断言到 DataAccessException，
        // 再用约束名保证拒绝原因具体，不把其他失败误判为通过。
        assertThatThrownBy(callable).isInstanceOf(DataAccessException.class).hasMessageContaining(constraintName);
    }

    /** 读取空间可见性。 */
    private static String visibility(String tenant, UUID workspaceId) {
        return forward.sql("""
                        SELECT visibility FROM workspace WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(workspaceId))
                .query(String.class)
                .single();
    }

    /** 正向 schema 中仍有 NULL 可见性的行数；必须为 0。 */
    private static long nullVisibilityRows() {
        return count("SELECT COUNT(*) FROM workspace WHERE visibility IS NULL");
    }

    /** 统计权限字典行数。 */
    private static long permissionCount(String code) {
        return forward.sql("SELECT COUNT(*) FROM permission WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    /** 统计某个角色是否持有指定权限。 */
    private static long rolePermissionCount(String tenant, UUID roleId, String permissionCode) {
        return forward.sql("""
                        SELECT COUNT(*) FROM role_permission
                         WHERE tenant_id = :tenantId AND role_id = :roleId AND permission_code = :permissionCode
                        """)
                .param("tenantId", tenant)
                .param("roleId", BinaryUuids.toBytes(roleId))
                .param("permissionCode", permissionCode)
                .query(Long.class)
                .single();
    }

    /** 正向 schema 单值计数。 */
    private static long count(String sql) {
        return forward.sql(sql).query(Long.class).single();
    }

    /** 空间行数。 */
    private static long workspaceCount(String tenant, UUID workspaceId) {
        return forward.sql("SELECT COUNT(*) FROM workspace WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(workspaceId))
                .query(Long.class)
                .single();
    }

    /** 回滚 schema 单值计数；带 :schema 参数的 SQL 由调用方提供。 */
    private static long rollbackCount(String sql) {
        JdbcClient.StatementSpec spec = rollback.sql(sql);
        if (sql.contains(":schema")) {
            spec = spec.param("schema", ROLLBACK_SCHEMA);
        }
        return spec.query(Long.class).single();
    }

    /** 解析测试常量 UUID。 */
    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
