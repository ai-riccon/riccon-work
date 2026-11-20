package com.riccon.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.platform.domain.identity.PermissionCodes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V10 审计查询/导出迁移（M10 B3a）语义测试。
 *
 * <p>不使用热栈或应用上下文：在一次性 Testcontainers MySQL 8.4.13 上先用 Flyway 只应用到 V9，
 * 种入 M02/M03 形态的租户、账号、角色、权限、空间、部门与跨分区审计事件，再应用 V10，验证
 * {@code audit_export_request}、两个审计查询索引、权限字典/角色回填、CHECK/外键与 M02/M03 数据兼容。
 * 空库直接应用最新迁移（当前 V11）通过不算本测试的替代；空库路径另行验证 RoleDefinitions 全量权限字典覆盖。
 *
 * <p>演练 schema：{@code riccon_rollback} 执行 ADR-0020 §5.4 回滚并验证回到 V9；{@code riccon_fresh}
 * 应用最新迁移（当前 V11）验证 RoleDefinitions 的权限字典覆盖；{@code riccon_failure} 用重名索引注入
 * 索引 DDL 失败，验证 OPS-005 的单语句原子性与 OPS-006 的 repair 恢复路径。所有 schema 都在同一个
 * 一次性容器内，容器销毁即清理，不连接生产或共享热栈。
 *
 * <p>生产执行前提：V10 生产执行与 M10 gate 仍需 Ops 评审结论（OPS-004/005/006/007 的操作细节在
 * ops 运行手册）与用户 T4 生产批准；本测试只证明迁移与回滚在非生产副本上可执行。
 */
class V10MigrationTest {

    /** 与合同 stack.versions.mysql 一致的测试镜像；命令参数与 API 集成测试保持一致。 */
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13")
            .withDatabaseName("riccon")
            .withUsername("riccon")
            .withPassword("riccon_test_only")
            .withCommand(
                    "--log-bin-trust-function-creators=1",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    /** 正向迁移 schema。 */
    private static final String FORWARD_SCHEMA = "riccon";

    /** 回滚演练 schema。 */
    private static final String ROLLBACK_SCHEMA = "riccon_rollback";

    /** fresh install 演练 schema。 */
    private static final String FRESH_SCHEMA = "riccon_fresh";

    /** 失败注入演练 schema。 */
    private static final String FAILURE_SCHEMA = "riccon_failure";

    /** 容器业务账号。 */
    private static final String USERNAME = "riccon";

    /** 仅容器内使用的占位密码，不对应任何真实环境。 */
    private static final String PASSWORD = "riccon_test_only";

    /** 平台保留租户。 */
    private static final String PLATFORM_TENANT = "platform";

    /** 租户 A。 */
    private static final String TENANT_A = "acme";

    /** 租户 B，用于跨租户断言。 */
    private static final String TENANT_B = "globex";

    /** B1 之后新建的租户：V10 前已由 RoleDefinitions 授予 audit.read，用于回填幂等断言。 */
    private static final String B1_TENANT = "initech";

    /** 平台管理员账号。 */
    private static final UUID PLATFORM_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000001");

    /** 租户 A 管理员账号。 */
    private static final UUID TENANT_A_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000002");

    /** 租户 A 普通员工账号。 */
    private static final UUID TENANT_A_MEMBER_USER = uuid("00000000-0000-7000-8000-000000000003");

    /** 租户 B 管理员账号。 */
    private static final UUID TENANT_B_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000004");

    /** 租户 B 普通员工账号。 */
    private static final UUID TENANT_B_MEMBER_USER = uuid("00000000-0000-7000-8000-000000000005");

    /** B1 租户管理员账号。 */
    private static final UUID B1_TENANT_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000006");

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

    /** B1 租户管理员角色。 */
    private static final UUID B1_TENANT_ADMIN_ROLE = uuid("00000000-0000-7000-8000-000000000106");

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

    /** 租户 B 启用部门。 */
    private static final UUID TENANT_B_DEPARTMENT = uuid("00000000-0000-7000-8000-000000000302");

    /** 租户 A 部门空间；B3 新代码写入 DEPT。 */
    private static final UUID TENANT_A_DEPT_WORKSPACE = uuid("00000000-0000-7000-8000-000000000401");

    /** 租户 A 自定义空间；B3 新代码写入 CUSTOM。 */
    private static final UUID TENANT_A_CUSTOM_WORKSPACE = uuid("00000000-0000-7000-8000-000000000402");

    /** 审计事件 1：租户 A 文件上传（p2026_11）。 */
    private static final UUID AUDIT_EVENT_FILE = uuid("00000000-0000-7000-8000-000000000501");

    /** 审计事件 2：租户 A 模型完成（p2026_12，含 promptTokens/completionTokens）。 */
    private static final UUID AUDIT_EVENT_MODEL = uuid("00000000-0000-7000-8000-000000000502");

    /** 审计事件 3：租户 A 普通员工被拒（p2027_01）。 */
    private static final UUID AUDIT_EVENT_DENIED = uuid("00000000-0000-7000-8000-000000000503");

    /** 审计事件 4：平台租户事件。 */
    private static final UUID AUDIT_EVENT_PLATFORM = uuid("00000000-0000-7000-8000-000000000504");

    /** 正向 schema 的 JDBC 客户端。 */
    private static JdbcClient forward;

    /** 回滚 schema 的 JDBC 客户端（root）。 */
    private static JdbcClient rollback;

    /** fresh install schema 的 JDBC 客户端（root）。 */
    private static JdbcClient fresh;

    /** 失败注入 schema 的 JDBC 客户端（root）。 */
    private static JdbcClient failure;

    /** 正向 schema 的 Flyway URL。 */
    private static String forwardUrl;

    /** 回滚 schema 的 Flyway URL。 */
    private static String rollbackUrl;

    /** fresh install schema 的 Flyway URL。 */
    private static String freshUrl;

    /** 失败注入 schema 的 Flyway URL。 */
    private static String failureUrl;

    /**
     * 启动一次性容器并准备四个 schema：正向 V9 种子→V10、回滚演练、fresh install、失败注入。
     *
     * <p>失败注入只预创建重名索引并停在 V9；实际失败迁移与恢复在对应测试方法中执行，避免依赖顺序。
     */
    @BeforeAll
    static void prepareSchemas() {
        MYSQL.start();
        forwardUrl = jdbcUrl(FORWARD_SCHEMA);
        rollbackUrl = jdbcUrl(ROLLBACK_SCHEMA);
        freshUrl = jdbcUrl(FRESH_SCHEMA);
        failureUrl = jdbcUrl(FAILURE_SCHEMA);
        createSchema(ROLLBACK_SCHEMA);
        createSchema(FRESH_SCHEMA);
        createSchema(FAILURE_SCHEMA);

        // 正向：V1–V8 -> M02 种子 -> V9 -> M03 种子与跨分区审计事件 -> V10，保留 V10 后状态。
        migrate(forwardUrl, USERNAME, "8");
        forward = jdbcClient(forwardUrl, USERNAME);
        seedM02Data(forward);
        migrate(forwardUrl, USERNAME, "9");
        seedM03Data(forward);
        seedAuditData(forward);
        migrate(forwardUrl, USERNAME, "10");

        // 回滚：相同路径到 V10，再执行 ADR-0020 §5.4 回滚 SQL。
        migrate(rollbackUrl, "root", "8");
        rollback = jdbcClient(rollbackUrl, "root");
        seedM02Data(rollback);
        migrate(rollbackUrl, "root", "9");
        seedM03Data(rollback);
        seedAuditData(rollback);
        migrate(rollbackUrl, "root", "10");
        rollbackV10Objects();

        // fresh install：空库直接应用最新迁移（含 V11），验证当前 RoleDefinitions 的权限字典覆盖与引导路径可用。
        migrateLatest(freshUrl, "root");
        fresh = jdbcClient(freshUrl, "root");

        // 失败注入：先占用第二条索引名，让 V10 的单语句索引 ALTER 以重名失败。
        migrate(failureUrl, "root", "9");
        failure = jdbcClient(failureUrl, "root");
        failure.sql("""
                        ALTER TABLE audit_event
                            ADD KEY idx_audit_tenant_actor_time (tenant_id, actor_id, occurred_at)
                        """).update();
    }

    /** V10 在 V1–V9 种子数据上由 Flyway 成功应用，history 记录成功版本 10。 */
    @Test
    @DisplayName("V10 在 V9 种子数据上应用成功")
    void migration_appliesOnV9SeedData() {
        assertThat(flyway(forwardUrl, USERNAME, "10")
                        .info()
                        .current()
                        .getVersion()
                        .getVersion())
                .isEqualTo("10");
        assertThat(forward.sql("""
                        SELECT COUNT(*) FROM flyway_schema_history
                         WHERE success = 1 AND version = '10'
                        """).query(Long.class).single()).isEqualTo(1L);
    }

    /** 导出请求表的列、租户键、索引、复合外键与 CHECK 与 ADR-0020 §5.1 一致。 */
    @Test
    @DisplayName("V10 创建 audit_export_request 的租户键/索引/外键/CHECK")
    void migration_createsAuditExportRequestWithTenantKeys() {
        List<String> columns = columnList(
                forward,
                FORWARD_SCHEMA,
                "audit_export_request",
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table ORDER BY ORDINAL_POSITION");
        assertThat(columns)
                .containsExactly(
                        "tenant_id",
                        "id",
                        "requested_by",
                        "status",
                        "filters",
                        "filters_hash",
                        "estimated_row_count",
                        "approved_by",
                        "approved_by_tenant_id",
                        "decided_at",
                        "decision_comment",
                        "expires_at",
                        "created_at",
                        "updated_at");

        assertThat(indexColumns(forward, FORWARD_SCHEMA, "audit_export_request", "PRIMARY"))
                .containsExactly("tenant_id", "id");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "audit_export_request", "idx_audit_export_status"))
                .containsExactly("tenant_id", "status", "created_at");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "audit_export_request", "idx_audit_export_expiry"))
                .containsExactly("tenant_id", "status", "expires_at");

        // 复合外键：只允许同租户申请人；审批人不建外键（平台管理员属于 platform 租户）。
        List<String> foreignKeyPairs = forward.sql("""
                        SELECT CONCAT(COLUMN_NAME, '->', REFERENCED_COLUMN_NAME) AS pair
                          FROM information_schema.KEY_COLUMN_USAGE
                         WHERE TABLE_SCHEMA = :schema
                           AND TABLE_NAME = 'audit_export_request'
                           AND CONSTRAINT_NAME = 'fk_audit_export_requester'
                           AND REFERENCED_TABLE_NAME = 'app_user'
                         ORDER BY ORDINAL_POSITION
                        """)
                .param("schema", FORWARD_SCHEMA)
                .query(String.class)
                .list();
        assertThat(foreignKeyPairs).containsExactly("tenant_id->tenant_id", "requested_by->id");

        Map<String, String> checks = checkClauses(forward, FORWARD_SCHEMA, "audit_export_request");
        assertThat(checks)
                .containsKeys(
                        "chk_audit_export_tenant",
                        "chk_audit_export_status",
                        "chk_audit_export_rows",
                        "chk_audit_export_self_approval",
                        "chk_audit_export_decision");
        assertThat(checks.get("chk_audit_export_status"))
                .contains("PENDING_APPROVAL")
                .contains("APPROVED")
                .contains("REJECTED");
        assertThat(checks.get("chk_audit_export_rows")).contains("estimated_row_count");
        assertThat(checks.get("chk_audit_export_self_approval")).contains("requested_by");
        assertThat(checks.get("chk_audit_export_decision"))
                .contains("expires_at")
                .contains("decided_at")
                .contains("approved_by_tenant_id");
    }

    /** 状态机：申请插入为 PENDING；条件更新只允许一次成功，重复审批影响 0 行。 */
    @Test
    @DisplayName("V10 支持导出申请与并发条件审批更新")
    void migration_supportsExportRequestStateMachine() {
        UUID exportId = uuid("00000000-0000-7000-8000-000000000601");
        insertPendingExport(forward, TENANT_A, exportId, TENANT_A_ADMIN_USER, 120);
        assertThat(exportStatus(forward, TENANT_A, exportId)).isEqualTo("PENDING_APPROVAL");

        int first = approveExport(forward, TENANT_A, exportId, TENANT_A_MEMBER_USER, TENANT_A);
        int second = approveExport(forward, TENANT_A, exportId, TENANT_A_MEMBER_USER, TENANT_A);
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(exportStatus(forward, TENANT_A, exportId)).isEqualTo("APPROVED");
        assertThat(forward.sql("""
                        SELECT approved_by, expires_at
                          FROM audit_export_request
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                        .param("tenantId", TENANT_A)
                        .param("id", BinaryUuids.toBytes(exportId))
                        .query((rs, rowNum) ->
                                rs.getBytes("approved_by") != null && rs.getTimestamp("expires_at") != null)
                        .single())
                .isTrue();

        // 拒绝路径只写决定与时间，不设过期。
        UUID rejectedId = uuid("00000000-0000-7000-8000-000000000602");
        insertPendingExport(forward, TENANT_A, rejectedId, TENANT_A_ADMIN_USER, 5);
        forward.sql("""
                        UPDATE audit_export_request
                           SET status = 'REJECTED',
                               approved_by = :approvedBy,
                               approved_by_tenant_id = :approverTenant,
                               decided_at = NOW(6),
                               decision_comment = '收窄时间范围'
                         WHERE tenant_id = :tenantId AND id = :id AND status = 'PENDING_APPROVAL'
                        """)
                .param("approvedBy", BinaryUuids.toBytes(TENANT_A_MEMBER_USER))
                .param("approverTenant", TENANT_A)
                .param("tenantId", TENANT_A)
                .param("id", BinaryUuids.toBytes(rejectedId))
                .update();
        assertThat(exportStatus(forward, TENANT_A, rejectedId)).isEqualTo("REJECTED");
        // 拒绝不设过期：NULL 不能被 JdbcClient.single() 映射，用计数断言。
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM audit_export_request
                         WHERE tenant_id = :tenantId AND id = :id AND expires_at IS NULL
                        """, "tenantId", TENANT_A, "id", BinaryUuids.toBytes(rejectedId)))
                .isEqualTo(1L);
    }

    /** CHECK/外键：非法状态、负行数、自批、状态字段不一致、跨租户申请人与重复主键都被拒绝。 */
    @Test
    @DisplayName("V10 拒绝非法导出请求状态与跨租户申请人")
    void migration_enforcesExportRequestConstraints() {
        // 状态非法时也会同时违反决策一致性 CHECK，因此只断言被 M10 导出约束拒绝；
        // 具体 chk_audit_export_status 的存在性已在上一个测试用 information_schema 断言。
        assertRejected(
                "chk_audit_export_",
                () -> insertExportRequest(
                        forward, TENANT_A, UUID.randomUUID(), TENANT_A_ADMIN_USER, "FROZEN", 0, null, null, null));

        assertRejected(
                "chk_audit_export_rows",
                () -> insertExportRequest(
                        forward,
                        TENANT_A,
                        UUID.randomUUID(),
                        TENANT_A_ADMIN_USER,
                        "PENDING_APPROVAL",
                        -1,
                        null,
                        null,
                        null));

        assertRejected(
                "chk_audit_export_self_approval",
                () -> insertExportRequest(
                        forward,
                        TENANT_A,
                        UUID.randomUUID(),
                        TENANT_A_ADMIN_USER,
                        "APPROVED",
                        1,
                        TENANT_A_ADMIN_USER,
                        TENANT_A,
                        true));

        assertRejected(
                "chk_audit_export_decision",
                () -> insertExportRequest(
                        forward,
                        TENANT_A,
                        UUID.randomUUID(),
                        TENANT_A_ADMIN_USER,
                        "APPROVED",
                        1,
                        TENANT_A_MEMBER_USER,
                        TENANT_A,
                        false));

        assertRejected(
                "chk_audit_export_decision",
                () -> insertExportRequest(
                        forward,
                        TENANT_A,
                        UUID.randomUUID(),
                        TENANT_A_ADMIN_USER,
                        "PENDING_APPROVAL",
                        1,
                        TENANT_A_MEMBER_USER,
                        TENANT_A,
                        false));

        // 复合外键：租户 A 的申请不能引用租户 B 的账号。
        assertRejected(
                "fk_audit_export_requester",
                () -> insertPendingExport(forward, TENANT_A, UUID.randomUUID(), TENANT_B_MEMBER_USER, 1));

        UUID duplicate = uuid("00000000-0000-7000-8000-000000000603");
        insertPendingExport(forward, TENANT_A, duplicate, TENANT_A_ADMIN_USER, 1);
        assertRejected("PRIMARY", () -> insertPendingExport(forward, TENANT_A, duplicate, TENANT_A_ADMIN_USER, 1));
    }

    /** 两个审计查询索引定义正确，分区表与 append-only 触发器不被 V10 改变。 */
    @Test
    @DisplayName("V10 为分区表添加租户+动作/操作者索引")
    void migration_createsAuditQueryIndexesForPartitionedTable() {
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "audit_event", "idx_audit_tenant_action_time"))
                .containsExactly("tenant_id", "action", "occurred_at");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "audit_event", "idx_audit_tenant_actor_time"))
                .containsExactly("tenant_id", "actor_id", "occurred_at");

        assertThat(count(forward, """
                        SELECT COUNT(*) FROM information_schema.TRIGGERS
                         WHERE TRIGGER_SCHEMA = :schema AND EVENT_OBJECT_TABLE = 'audit_event'
                        """, "schema", FORWARD_SCHEMA)).isEqualTo(2L);
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM information_schema.PARTITIONS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'audit_event'
                        """, "schema", FORWARD_SCHEMA)).isEqualTo(15L);

        // EXPLAIN 只证明索引对查询可用；不把优化器选择当契约，避免统计信息导致偶发失败。
        String actionPossibleKeys = explainPossibleKeys(
                forward,
                "SELECT event_id FROM audit_event WHERE tenant_id = :tenantId AND action = :action"
                        + " AND occurred_at >= :fromTime AND occurred_at < :toTime",
                TENANT_A,
                "file.uploaded");
        assertThat(actionPossibleKeys).contains("idx_audit_tenant_action_time");
        String actorPossibleKeys = explainPossibleKeys(
                forward,
                "SELECT event_id FROM audit_event WHERE tenant_id = :tenantId AND actor_id = :actorId"
                        + " AND occurred_at >= :fromTime AND occurred_at < :toTime",
                TENANT_A,
                TENANT_A_ADMIN_USER.toString());
        assertThat(actorPossibleKeys).contains("idx_audit_tenant_actor_time");
    }

    /** OPS-005：V10 的两条索引必须在同一条 ALTER 中，并显式 INPLACE/LOCK=NONE。 */
    @Test
    @DisplayName("V10 索引 DDL 为单语句 INPLACE/LOCK=NONE")
    void migration_indexDdlIsSingleInplaceStatement() throws Exception {
        // 去掉注释行后只看可执行 SQL，避免把文件末尾的回滚示例当成第二条 ALTER。
        String sql = Files.readString(migrationDirectory().resolve("V10__audit_query_export.sql"))
                .lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(sql.split("ALTER TABLE audit_event", -1)).hasSize(2);
        int start = sql.indexOf("ALTER TABLE audit_event");
        int end = sql.indexOf(';', start);
        String statement = sql.substring(start, end);
        assertThat(statement)
                .contains("idx_audit_tenant_action_time")
                .contains("idx_audit_tenant_actor_time")
                .contains("ALGORITHM = INPLACE")
                .contains("LOCK = NONE");
        // 单条语句内不应出现第二个分号（注释中的回滚 SQL 在 statement 之外）。
        assertThat(statement).doesNotContain(";");
    }

    /** 权限字典与角色回填：租户管理员得查询+同租户审批，平台管理员得查询+平台审批，MEMBER 不变。 */
    @Test
    @DisplayName("V10 回填审计查询与导出审批权限")
    void migration_backfillsAuditApprovalPermissions() {
        assertThat(permissionCount(forward, PermissionCodes.AUDIT_READ)).isEqualTo(1L);
        assertThat(permissionCount(forward, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);
        assertThat(permissionCount(forward, PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);

        assertThat(rolePermissionCount(forward, TENANT_A, TENANT_A_ADMIN_ROLE, PermissionCodes.AUDIT_READ))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(forward, TENANT_A, TENANT_A_ADMIN_ROLE, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(
                        forward, TENANT_A, TENANT_A_ADMIN_ROLE, PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE))
                .isZero();
        assertThat(rolePermissionCount(forward, TENANT_B, TENANT_B_ADMIN_ROLE, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(forward, TENANT_A, TENANT_A_MEMBER_ROLE, PermissionCodes.AUDIT_READ))
                .isZero();
        assertThat(rolePermissionCount(forward, TENANT_A, TENANT_A_MEMBER_ROLE, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isZero();
        assertThat(rolePermissionCount(
                        forward, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(
                        forward, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isZero();

        // B1 之后新建的租户可能已由 RoleDefinitions 写入 audit.read：V10 回填必须幂等，不能主键冲突。
        assertThat(rolePermissionCount(forward, B1_TENANT, B1_TENANT_ADMIN_ROLE, PermissionCodes.AUDIT_READ))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(forward, B1_TENANT, B1_TENANT_ADMIN_ROLE, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(forward, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.AUDIT_READ))
                .isEqualTo(1L);
    }

    /** V10 只新增对象：M02/M03 数据可读，审计仍 append-only 且触发器数量不变。 */
    @Test
    @DisplayName("V10 保留 M02/M03 数据与审计 append-only")
    void migration_keepsM02AndM03DataReadable() {
        assertThat(count(forward, "SELECT COUNT(*) FROM tenant WHERE id IN ('platform', 'acme', 'globex', 'initech')"))
                .isEqualTo(4L);
        assertThat(count(
                        forward,
                        "SELECT COUNT(*) FROM app_user WHERE tenant_id IN ('platform', 'acme', 'globex', 'initech')"))
                .isEqualTo(6L);
        assertThat(count(
                        forward,
                        "SELECT COUNT(*) FROM role WHERE tenant_id IN ('platform', 'acme', 'globex', 'initech')"))
                .isEqualTo(6L);
        assertThat(count(
                        forward,
                        "SELECT COUNT(*) FROM workspace WHERE id IN (:personalA, :teamA, :personalB, :teamB)",
                        "personalA",
                        BinaryUuids.toBytes(TENANT_A_PERSONAL_WORKSPACE),
                        "teamA",
                        BinaryUuids.toBytes(TENANT_A_TEAM_WORKSPACE),
                        "personalB",
                        BinaryUuids.toBytes(TENANT_B_PERSONAL_WORKSPACE),
                        "teamB",
                        BinaryUuids.toBytes(TENANT_B_TEAM_WORKSPACE)))
                .isEqualTo(4L);
        assertThat(count(forward, "SELECT COUNT(*) FROM department WHERE tenant_id IN ('acme', 'globex')"))
                .isEqualTo(2L);
        assertThat(count(forward, "SELECT COUNT(*) FROM workspace_member WHERE tenant_id = 'acme'"))
                .isEqualTo(1L);
        assertThat(count(forward, "SELECT COUNT(*) FROM audit_event WHERE tenant_id IN ('acme', 'platform')"))
                .isEqualTo(4L);
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM information_schema.TRIGGERS
                         WHERE TRIGGER_SCHEMA = :schema AND EVENT_OBJECT_TABLE = 'audit_event'
                        """, "schema", FORWARD_SCHEMA)).isEqualTo(2L);

        // 审计 append-only 仍然生效：V10 不改触发器，也不允许通过迁移绕过。
        assertThatThrownBy(() -> forward.sql("UPDATE audit_event SET outcome = 'FAILURE' WHERE event_id = :eventId")
                        .param("eventId", BinaryUuids.toBytes(AUDIT_EVENT_FILE))
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    /** fresh install：V1–V11 后 RoleDefinitions 的每条权限都能在字典中找到并用于角色创建。 */
    @Test
    @DisplayName("空库 V1–V11 后 RoleDefinitions 权限字典完整")
    void freshDatabase_bootstrapCoversRoleDefinitions() {
        assertThat(flywayLatest(freshUrl, "root").info().current().getVersion().getVersion())
                .isEqualTo("11");
        assertThat(count(fresh, """
                        SELECT COUNT(*) FROM information_schema.TABLES
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'audit_export_request'
                        """, "schema", FRESH_SCHEMA)).isEqualTo(1L);
        assertThat(indexColumns(fresh, FRESH_SCHEMA, "audit_event", "idx_audit_tenant_action_time"))
                .containsExactly("tenant_id", "action", "occurred_at");
        assertThat(permissionCount(fresh, PermissionCodes.AUDIT_EXPORT_APPROVE)).isEqualTo(1L);
        assertThat(permissionCount(fresh, PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);

        // 模拟 BootstrapAdminRunner / TenantAdminService：按 RoleDefinitions 创建角色与权限映射；
        // 任何权限码缺失都会因 role_permission 外键失败，而不是留到启动期才发现。
        insertTenant(fresh, TENANT_A);
        insertTenant(fresh, PLATFORM_TENANT);
        createRoleWithPermissions(fresh, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, RoleDefinitions.platformAdminRole());
        for (RoleDefinition definition : RoleDefinitions.tenantSystemRoles()) {
            createRoleWithPermissions(fresh, TENANT_A, roleIdFor(definition.code()), definition);
        }

        assertThat(rolePermissionCount(fresh, TENANT_A, TENANT_A_ADMIN_ROLE, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(
                        fresh, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, PermissionCodes.PLATFORM_AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(fresh, TENANT_A, TENANT_A_MEMBER_ROLE, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isZero();
    }

    /** ADR-0020 §5.4 回滚：结构回到 V9，M02/M03 数据与审计可读，V10 history 行可被维护账号移除。 */
    @Test
    @DisplayName("ADR-0020 §5.4 回滚回到 V9 且 M02/M03 数据可读")
    void rollback_returnsToV9AndKeepsDataReadable() {
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.TABLES
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'audit_export_request'
                        """, "schema", ROLLBACK_SCHEMA)).isZero();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = :schema
                           AND INDEX_NAME IN ('idx_audit_tenant_action_time', 'idx_audit_tenant_actor_time')
                        """, "schema", ROLLBACK_SCHEMA)).isZero();
        assertThat(count(
                        rollback,
                        "SELECT COUNT(*) FROM permission WHERE code IN "
                                + "('audit.export.approve', 'platform.audit.export.approve')"))
                .isZero();
        assertThat(count(
                        rollback,
                        "SELECT COUNT(*) FROM role_permission WHERE permission_code IN "
                                + "('audit.read', 'audit.export.approve', 'platform.audit.export.approve')"))
                .isZero();
        // M02 基线 3 条 + V9 部门回填 6 条（5 条 department.read、1 条 platform.department.manage）
        // 必须保留；回滚只删除 V10 相关（含按 ADR §5.4 一并清理的 audit.read）映射。
        assertThat(count(rollback, "SELECT COUNT(*) FROM role_permission")).isEqualTo(9L);
        assertThat(count(rollback, "SELECT COUNT(*) FROM role_permission WHERE permission_code = 'department.read'"))
                .isEqualTo(5L);
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM role_permission
                         WHERE permission_code = 'platform.department.manage'
                        """)).isEqualTo(1L);
        assertThat(count(rollback, "SELECT COUNT(*) FROM permission WHERE code = 'audit.read'"))
                .isEqualTo(1L);

        // M02/M03 数据可读；audit_event 与触发器不受回滚影响。
        assertThat(count(
                        rollback,
                        "SELECT COUNT(*) FROM app_user WHERE tenant_id IN ('platform', 'acme', 'globex', 'initech')"))
                .isEqualTo(6L);
        assertThat(count(rollback, "SELECT COUNT(*) FROM department")).isEqualTo(2L);
        assertThat(count(
                        rollback,
                        "SELECT COUNT(*) FROM workspace WHERE kind = 'TEAM' AND visibility IN ('DEPT', 'CUSTOM')"))
                .isEqualTo(2L);
        assertThat(count(rollback, "SELECT COUNT(*) FROM audit_event")).isEqualTo(4L);
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.TRIGGERS
                         WHERE TRIGGER_SCHEMA = :schema AND EVENT_OBJECT_TABLE = 'audit_event'
                        """, "schema", ROLLBACK_SCHEMA)).isEqualTo(2L);

        // OPS-007：维护账号删除 V10 历史行后，schema 版本回到 9；不使用 flyway repair 掩盖 checksum。
        rollback.sql("DELETE FROM flyway_schema_history WHERE version = '10'").update();
        assertThat(count(rollback, "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history"))
                .isEqualTo(9L);
        Flyway latest = flyway(rollbackUrl, "root", "10");
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(latest.info().pending())
                .extracting(info -> info.getVersion().getVersion())
                .contains("10");
    }

    /**
     * OPS-005/006：重名索引使单语句 ALTER 失败时，V10 的两条索引都不存在（原子性；人为冲突索引除外）；
     * 表与权限已提交，按恢复矩阵清理冲突 → DROP TABLE → repair → 重跑后成功。
     */
    @Test
    @DisplayName("索引失败注入后按 OPS-006 恢复并重跑成功")
    void failureInjection_recoversWithRepairAndRerun() {
        assertThatThrownBy(() -> migrate(failureUrl, "root", "10"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("10");
        assertThat(count(failure, """
                        SELECT COUNT(*) FROM flyway_schema_history
                         WHERE version = '10' AND success = 0
                        """)).isEqualTo(1L);
        // OPS-005：同一 ALTER 的两条索引同成败；失败后不能留下 V10 第一条索引。
        assertThat(count(failure, """
                        SELECT COUNT(*) FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = :schema
                           AND INDEX_NAME = 'idx_audit_tenant_action_time'
                        """, "schema", FAILURE_SCHEMA)).isZero();
        // 人为冲突的 actor 索引仍在（它不是 V10 创建的），恢复时必须先移除。
        assertThat(count(failure, """
                        SELECT COUNT(*) FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = :schema
                           AND INDEX_NAME = 'idx_audit_tenant_actor_time'
                        """, "schema", FAILURE_SCHEMA)).isEqualTo(3L);
        // MySQL DDL 非事务：表与权限 INSERT 已提交，恢复矩阵必须先清掉空表再 repair 重跑。
        assertThat(count(failure, """
                        SELECT COUNT(*) FROM information_schema.TABLES
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'audit_export_request'
                        """, "schema", FAILURE_SCHEMA)).isEqualTo(1L);
        assertThat(permissionCount(failure, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);

        // OPS-006 恢复：移除人为冲突索引 -> 丢弃 V10 新建的空表 -> repair -> 重跑。
        failure.sql("ALTER TABLE audit_event DROP INDEX idx_audit_tenant_actor_time")
                .update();
        failure.sql("DROP TABLE audit_export_request").update();
        Flyway flyway = flyway(failureUrl, "root", "10");
        flyway.repair();
        flyway.migrate();

        assertThat(count(failure, """
                        SELECT COUNT(*) FROM flyway_schema_history
                         WHERE version = '10' AND success = 0
                        """)).isZero();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(indexColumns(failure, FAILURE_SCHEMA, "audit_event", "idx_audit_tenant_action_time"))
                .containsExactly("tenant_id", "action", "occurred_at");
        assertThat(indexColumns(failure, FAILURE_SCHEMA, "audit_event", "idx_audit_tenant_actor_time"))
                .containsExactly("tenant_id", "actor_id", "occurred_at");
        assertThat(permissionCount(failure, PermissionCodes.AUDIT_EXPORT_APPROVE))
                .isEqualTo(1L);
    }

    /** 构造正向/回滚/fresh 共用的 M02 种子；此时表结构仍是 V9。 */
    private static void seedM02Data(JdbcClient jdbc) {
        insertTenant(jdbc, TENANT_A);
        insertTenant(jdbc, TENANT_B);
        insertTenant(jdbc, B1_TENANT);

        insertUser(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_USER, "platform-admin", "Platform Admin");
        insertUser(jdbc, TENANT_A, TENANT_A_ADMIN_USER, "acme-admin", "Acme Admin");
        insertUser(jdbc, TENANT_A, TENANT_A_MEMBER_USER, "acme-member", "Acme Member");
        insertUser(jdbc, TENANT_B, TENANT_B_ADMIN_USER, "globex-admin", "Globex Admin");
        insertUser(jdbc, TENANT_B, TENANT_B_MEMBER_USER, "globex-member", "Globex Member");
        insertUser(jdbc, B1_TENANT, B1_TENANT_ADMIN_USER, "initech-admin", "Initech Admin");

        insertRole(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "PLATFORM_ADMIN");
        insertRole(jdbc, TENANT_A, TENANT_A_ADMIN_ROLE, "TENANT_ADMIN");
        insertRole(jdbc, TENANT_A, TENANT_A_MEMBER_ROLE, "MEMBER");
        insertRole(jdbc, TENANT_B, TENANT_B_ADMIN_ROLE, "TENANT_ADMIN");
        insertRole(jdbc, TENANT_B, TENANT_B_MEMBER_ROLE, "MEMBER");
        insertRole(jdbc, B1_TENANT, B1_TENANT_ADMIN_ROLE, "TENANT_ADMIN");

        // M02 基线 role_permission：回滚后应保留，用于证明只删除 V10 相关授权。
        insertRolePermission(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "platform.tenant.manage");
        insertRolePermission(jdbc, TENANT_A, TENANT_A_MEMBER_ROLE, "workspace.read");
        insertRolePermission(jdbc, TENANT_B, TENANT_B_MEMBER_ROLE, "workspace.read");
        // 模拟 B1 已发布后新建的租户与平台管理员：V10 前已拥有 audit.read，回填必须幂等。
        insertRolePermission(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "audit.read");
        insertRolePermission(jdbc, B1_TENANT, B1_TENANT_ADMIN_ROLE, "audit.read");

        // M02 旧代码创建的空间：V8 还没有 visibility/department_id 列。
        insertLegacyWorkspace(
                jdbc, TENANT_A, TENANT_A_PERSONAL_WORKSPACE, "PERSONAL", "Acme Personal", TENANT_A_MEMBER_USER, true);
        insertLegacyWorkspace(jdbc, TENANT_A, TENANT_A_TEAM_WORKSPACE, "TEAM", "Acme Team", TENANT_A_ADMIN_USER, false);
        insertLegacyWorkspace(
                jdbc, TENANT_B, TENANT_B_PERSONAL_WORKSPACE, "PERSONAL", "Globex Personal", TENANT_B_MEMBER_USER, true);
        insertLegacyWorkspace(
                jdbc, TENANT_B, TENANT_B_TEAM_WORKSPACE, "TEAM", "Globex Team", TENANT_B_ADMIN_USER, false);
    }

    /** V9 之后的 M03 形态数据：部门、成员归属、DEPT/CUSTOM 空间与一条显式成员。 */
    private static void seedM03Data(JdbcClient jdbc) {
        insertDepartment(jdbc, TENANT_A, TENANT_A_DEPARTMENT, "Engineering");
        insertDepartment(jdbc, TENANT_B, TENANT_B_DEPARTMENT, "Globex Engineering");
        assignDepartment(jdbc, TENANT_A, TENANT_A_MEMBER_USER, TENANT_A_DEPARTMENT);
        assignDepartment(jdbc, TENANT_B, TENANT_B_MEMBER_USER, TENANT_B_DEPARTMENT);

        insertWorkspace(jdbc, TENANT_A, TENANT_A_DEPT_WORKSPACE, "DEPT", TENANT_A_DEPARTMENT, "Acme Engineering");
        insertWorkspace(jdbc, TENANT_A, TENANT_A_CUSTOM_WORKSPACE, "CUSTOM", TENANT_A_DEPARTMENT, "Acme Custom");
        insertWorkspaceMember(jdbc, TENANT_A, TENANT_A_CUSTOM_WORKSPACE, TENANT_A_MEMBER_USER, TENANT_A_ADMIN_USER);
    }

    /** 跨分区审计事件；用于证明加索引后分区数据和审计可读性不变。 */
    private static void seedAuditData(JdbcClient jdbc) {
        insertAuditEvent(
                jdbc,
                AUDIT_EVENT_FILE,
                "2026-11-15 10:00:00.000000",
                TENANT_A,
                "USER",
                TENANT_A_ADMIN_USER.toString(),
                "file.uploaded",
                "FILE",
                "file-1",
                "SUCCESS",
                "trace-file",
                "{\"fileId\":\"file-1\",\"contentType\":\"application/pdf\"}");
        insertAuditEvent(
                jdbc,
                AUDIT_EVENT_MODEL,
                "2026-12-02 09:00:00.000000",
                TENANT_A,
                "SERVICE",
                "worker",
                "model.invocation.completed",
                "MODEL",
                "fake-model",
                "SUCCESS",
                "trace-model",
                "{\"modelId\":\"fake-model\",\"promptTokens\":10,\"completionTokens\":20}");
        insertAuditEvent(
                jdbc,
                AUDIT_EVENT_DENIED,
                "2027-01-03 12:00:00.000000",
                TENANT_A,
                "USER",
                TENANT_A_ADMIN_USER.toString(),
                "user.access.denied",
                "USER",
                TENANT_A_MEMBER_USER.toString(),
                "DENIED",
                "trace-denied",
                "{\"userId\":\"acme-member\",\"reason\":\"disabled\"}");
        insertAuditEvent(
                jdbc,
                AUDIT_EVENT_PLATFORM,
                "2026-11-20 08:00:00.000000",
                PLATFORM_TENANT,
                "USER",
                PLATFORM_ADMIN_USER.toString(),
                "tenant.created",
                "TENANT",
                TENANT_A,
                "SUCCESS",
                "trace-tenant",
                "{\"plan\":\"standard\"}");
    }

    /** 执行 ADR-0020 §5.4 的回滚 SQL；顺序与 ADR 一致，先删表/索引，再删权限映射与字典。 */
    private static void rollbackV10Objects() {
        List<String> statements = List.of(
                "DROP TABLE audit_export_request",
                "ALTER TABLE audit_event DROP INDEX idx_audit_tenant_action_time,"
                        + " DROP INDEX idx_audit_tenant_actor_time",
                "DELETE FROM role_permission WHERE permission_code IN "
                        + "('audit.read', 'audit.export.approve', 'platform.audit.export.approve')",
                "DELETE FROM permission WHERE code IN " + "('audit.export.approve', 'platform.audit.export.approve')");
        for (String statement : statements) {
            rollback.sql(statement).update();
        }
    }

    /** 以给定目标版本执行 Flyway。 */
    private static void migrate(String url, String user, String target) {
        flyway(url, user, target).migrate();
    }

    /** 应用当前代码库的最新迁移（不钉历史 target）；仅 fresh install 用例使用。 */
    private static void migrateLatest(String url, String user) {
        flywayLatest(url, user).migrate();
    }

    /** 构造指定目标版本的 Flyway 实例。 */
    private static Flyway flyway(String url, String user, String target) {
        return Flyway.configure()
                .dataSource(url, user, PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    /** 构造不限制目标的 Flyway 实例；只用于验证当前最新 schema 的 fresh install 用例。 */
    private static Flyway flywayLatest(String url, String user) {
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

    /** 使用 root 创建演练 schema；Testcontainers 只创建正向业务 schema。 */
    private static void createSchema(String schema) {
        String serverUrl = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
        try (Connection connection = DriverManager.getConnection(serverUrl, "root", PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE DATABASE IF NOT EXISTS " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create schema " + schema, exception);
        }
    }

    /** 拼装带测试连接参数的 JDBC URL。 */
    private static String jdbcUrl(String schema) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }

    /** 创建 JDBC 客户端；root 只用于测试自建 schema。 */
    private static JdbcClient jdbcClient(String url, String user) {
        return JdbcClient.create(new DriverManagerDataSource(url, user, PASSWORD));
    }

    /** 种一个租户；platform 由 V2 写入，这里只补业务租户。 */
    private static void insertTenant(JdbcClient jdbc, String tenant) {
        if (PLATFORM_TENANT.equals(tenant)) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """).param("tenantId", tenant).param("name", tenant).update();
    }

    /** 种一个账号。 */
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

    /** 按 RoleDefinitions 创建角色与全部权限映射，模拟开通/引导路径。 */
    private static void createRoleWithPermissions(
            JdbcClient jdbc, String tenant, UUID roleId, RoleDefinition definition) {
        insertRole(jdbc, tenant, roleId, definition.code());
        for (String permission : definition.permissions()) {
            insertRolePermission(jdbc, tenant, roleId, permission);
        }
    }

    /** 按角色码返回测试固定角色 ID；仅用于 seed 的四个已知角色。 */
    private static UUID roleIdFor(String code) {
        return switch (code) {
            case "TENANT_ADMIN" -> TENANT_A_ADMIN_ROLE;
            case "MEMBER" -> TENANT_A_MEMBER_ROLE;
            default -> throw new IllegalArgumentException("unexpected tenant role code: " + code);
        };
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

    /** 种一个租户 A 的 DEPT/CUSTOM 空间。 */
    private static void insertWorkspace(
            JdbcClient jdbc, String tenant, UUID workspaceId, String visibility, UUID departmentId, String name) {
        jdbc.sql("""
                        INSERT INTO workspace (
                            tenant_id, id, kind, name, description, owner_user_id, personal_owner_user_id,
                            department_id, visibility, status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, 'TEAM', :name, NULL, :ownerUserId, NULL,
                            :departmentId, :visibility, 'ACTIVE', :createdBy, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(workspaceId))
                .param("name", name)
                .param("ownerUserId", BinaryUuids.toBytes(TENANT_A_ADMIN_USER))
                .param("departmentId", BinaryUuids.toBytes(departmentId))
                .param("visibility", visibility)
                .param("createdBy", BinaryUuids.toBytes(TENANT_A_ADMIN_USER))
                .update();
    }

    /** 种一个部门。 */
    private static void insertDepartment(JdbcClient jdbc, String tenant, UUID departmentId, String name) {
        jdbc.sql("""
                        INSERT INTO department (tenant_id, id, name, status, created_by)
                        VALUES (:tenantId, :id, :name, 'ACTIVE', :createdBy)
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(departmentId))
                .param("name", name)
                .param("createdBy", BinaryUuids.toBytes(PLATFORM_ADMIN_USER))
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

    /** 种一条审计事件。 */
    private static void insertAuditEvent(
            JdbcClient jdbc,
            UUID eventId,
            String occurredAt,
            String tenant,
            String actorType,
            String actorId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String traceId,
            String details) {
        jdbc.sql("""
                        INSERT INTO audit_event (
                            event_id, occurred_at, tenant_id, actor_type, actor_id, action,
                            target_type, target_id, outcome, trace_id, request_id, source_ip,
                            details, schema_version
                        ) VALUES (
                            :eventId, :occurredAt, :tenantId, :actorType, :actorId, :action,
                            :targetType, :targetId, :outcome, :traceId, NULL, NULL,
                            :details, 1
                        )
                        """)
                .param("eventId", BinaryUuids.toBytes(eventId))
                .param("occurredAt", occurredAt)
                .param("tenantId", tenant)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("outcome", outcome)
                .param("traceId", traceId)
                .param("details", details)
                .update();
    }

    /** 种一条合法的 PENDING 导出申请。 */
    private static void insertPendingExport(
            JdbcClient jdbc, String tenant, UUID exportId, UUID requestedBy, int estimatedRows) {
        insertExportRequest(jdbc, tenant, exportId, requestedBy, "PENDING_APPROVAL", estimatedRows, null, null, null);
    }

    /**
     * 种一条导出请求；approvedBy 非空时按状态写入审批字段。
     *
     * @param withExpiry 仅对 APPROVED 生效：true 写 expires_at，false 故意留空用于 CHECK 断言
     */
    private static void insertExportRequest(
            JdbcClient jdbc,
            String tenant,
            UUID exportId,
            UUID requestedBy,
            String status,
            int estimatedRows,
            UUID approvedBy,
            String approvedByTenant,
            Boolean withExpiry) {
        jdbc.sql("""
                        INSERT INTO audit_export_request (
                            tenant_id, id, requested_by, status, filters, filters_hash,
                            estimated_row_count, approved_by, approved_by_tenant_id, decided_at, expires_at
                        ) VALUES (
                            :tenantId, :id, :requestedBy, :status, :filters, :filtersHash,
                            :estimatedRows, :approvedBy, :approvedByTenant,
                            CASE WHEN :approvedBy IS NULL THEN NULL ELSE NOW(6) END,
                            CASE WHEN :withExpiry = TRUE THEN DATE_ADD(NOW(6), INTERVAL 24 HOUR) ELSE NULL END
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(exportId))
                .param("requestedBy", BinaryUuids.toBytes(requestedBy))
                .param("status", status)
                .param(
                        "filters",
                        "{\"occurredFrom\":\"2026-11-01T00:00:00+08:00\",\"occurredTo\":\"2026-11-30T23:59:59+08:00\"}")
                .param("filtersHash", "a".repeat(64))
                .param("estimatedRows", estimatedRows)
                .param("approvedBy", approvedBy == null ? null : BinaryUuids.toBytes(approvedBy))
                .param("approvedByTenant", approvedByTenant)
                .param("withExpiry", withExpiry != null && withExpiry)
                .update();
    }

    /** 条件审批更新；返回受影响行数，用于验证并发只成功一次。 */
    private static int approveExport(
            JdbcClient jdbc, String tenant, UUID exportId, UUID approvedBy, String approvedByTenant) {
        return jdbc.sql("""
                        UPDATE audit_export_request
                           SET status = 'APPROVED',
                               approved_by = :approvedBy,
                               approved_by_tenant_id = :approvedByTenant,
                               decided_at = NOW(6),
                               expires_at = DATE_ADD(NOW(6), INTERVAL 24 HOUR)
                         WHERE tenant_id = :tenantId
                           AND id = :id
                           AND status = 'PENDING_APPROVAL'
                        """)
                .param("approvedBy", BinaryUuids.toBytes(approvedBy))
                .param("approvedByTenant", approvedByTenant)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(exportId))
                .update();
    }

    /** 读取导出请求状态。 */
    private static String exportStatus(JdbcClient jdbc, String tenant, UUID exportId) {
        return jdbc.sql("SELECT status FROM audit_export_request WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(exportId))
                .query(String.class)
                .single();
    }

    /** 查询 EXPLAIN 的 possible_keys；只断言索引可用，不锁死优化器选择。 */
    private static String explainPossibleKeys(JdbcClient jdbc, String sql, String tenant, String actionOrActor) {
        String paramName = sql.contains(":action") ? "action" : "actorId";
        return jdbc.sql("EXPLAIN " + sql)
                .param("tenantId", tenant)
                .param(paramName, actionOrActor)
                .param("fromTime", "2026-11-01 00:00:00.000000")
                .param("toTime", "2027-02-01 00:00:00.000000")
                .query((rs, rowNum) -> rs.getString("possible_keys"))
                .single();
    }

    /** 读取表/索引的列顺序。 */
    private static List<String> indexColumns(JdbcClient jdbc, String schema, String table, String index) {
        return jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table AND INDEX_NAME = :index
                         ORDER BY SEQ_IN_INDEX
                        """)
                .param("schema", schema)
                .param("table", table)
                .param("index", index)
                .query(String.class)
                .list();
    }

    /** 读取 CHECK 约束名与子句。 */
    private static Map<String, String> checkClauses(JdbcClient jdbc, String schema, String table) {
        return jdbc
                .sql("""
                        SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
                          FROM information_schema.TABLE_CONSTRAINTS tc
                          JOIN information_schema.CHECK_CONSTRAINTS cc
                            ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                           AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                         WHERE tc.TABLE_SCHEMA = :schema
                           AND tc.TABLE_NAME = :table
                           AND tc.CONSTRAINT_TYPE = 'CHECK'
                        """)
                .param("schema", schema)
                .param("table", table)
                .query((rs, rowNum) -> Map.entry(rs.getString("CONSTRAINT_NAME"), rs.getString("CHECK_CLAUSE")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 读取列清单；列名由调用方 SQL 决定。 */
    private static List<String> columnList(JdbcClient jdbc, String schema, String table, String sql) {
        return jdbc.sql(sql)
                .param("schema", schema)
                .param("table", table)
                .query(String.class)
                .list();
    }

    /** 统计权限字典行数。 */
    private static long permissionCount(JdbcClient jdbc, String code) {
        return jdbc.sql("SELECT COUNT(*) FROM permission WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    /** 统计某个角色是否持有指定权限。 */
    private static long rolePermissionCount(JdbcClient jdbc, String tenant, UUID roleId, String permissionCode) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM role_permission
                         WHERE tenant_id = :tenantId AND role_id = :roleId AND permission_code = :permissionCode
                        """)
                .param("tenantId", tenant)
                .param("roleId", BinaryUuids.toBytes(roleId))
                .param("permissionCode", permissionCode)
                .query(Long.class)
                .single();
    }

    /** 单值计数；可变参数按 名值对 绑定，便于在同一 SQL 中混合固定 schema。 */
    private static long count(JdbcClient jdbc, String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (int index = 0; index < params.length; index += 2) {
            spec = spec.param((String) params[index], params[index + 1]);
        }
        return spec.query(Long.class).single();
    }

    /** 断言数据库以指定约束名拒绝写入；错误信息必须包含约束名，避免被其他约束误判为通过。 */
    private static void assertRejected(String constraintName, ThrowingCallable callable) {
        // MySQL 8 的 CHECK 违反被 Spring 归为 UncategorizedSQLException，外键/唯一键归为
        // DataIntegrityViolationException；统一断言到 DataAccessException，再用约束名保证拒绝原因具体。
        assertThatThrownBy(callable).isInstanceOf(DataAccessException.class).hasMessageContaining(constraintName);
    }

    /** 解析测试常量 UUID。 */
    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
