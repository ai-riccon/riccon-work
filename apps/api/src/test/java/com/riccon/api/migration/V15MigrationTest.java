package com.riccon.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.platform.domain.identity.PermissionCodes;
import com.riccon.platform.domain.identity.RoleCodes;
import com.riccon.platform.domain.workflow.WorkflowTemplateKeys;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V15 编排 Studio 迁移（M06，ADR-0024 §3.5/§10.4）。
 *
 * <p>在一次性 Testcontainers MySQL 8.4.13 上覆盖：
 * <ol>
 *   <li>正向：V1–V14 建库并写入存量租户/角色/用户，再应用 V15；断言 9 张表、model_invocation 可空
 *       引用与索引、6 个权限码、TENANT_ADMIN/MEMBER 回填、确定性 WORKFLOW_EDITOR 角色、内置模板种子；</li>
 *   <li>不可变：workflow_version 的 UPDATE/DELETE 被触发器拒绝，INSERT 正常；</li>
 *   <li>幂等：把 V15 的 INSERT 段重复执行一次，权限/角色/映射/模板行数不增加（repair 重跑场景）；</li>
 *   <li>应急 drop：无 workflow 数据时内联 drop 成功；有 definition/version 数据时判定 SQL 拒绝且数据保留；</li>
 *   <li>fresh install：V1–V15 一次建库，权限字典覆盖 {@link PermissionCodes} 全集，RoleDefinitions 的
 *       三个租户系统角色可按定义创建。</li>
 * </ol>
 *
 * <p>应急 drop SQL 按 ADR-0024 §10.4 内联在本测试，不新增生产脚本；T16b 只按同一顺序做演练断言。
 */
@Tag("migration")
class V15MigrationTest {

    /** 与合同 stack.versions.mysql 一致的测试镜像。 */
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13")
            .withDatabaseName("riccon")
            .withUsername("riccon")
            .withPassword("riccon_test_only")
            .withCommand(
                    "--log-bin-trust-function-creators=1",
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    /** 正向 schema：V1–V14 + 存量数据 + V15。 */
    private static final String FORWARD_SCHEMA = "riccon_v15_forward";

    /** fresh schema：V1–V15 一次建库。 */
    private static final String FRESH_SCHEMA = "riccon_v15_fresh";

    /** 应急 drop schema：V1–V15 且无 workflow 数据。 */
    private static final String DROP_SCHEMA = "riccon_v15_drop";

    /** 容器密码（测试占位）。 */
    private static final String PASSWORD = "riccon_test_only";

    /** 存量租户 A。 */
    private static final String TENANT_A = "acme";

    /** 存量租户 B（验证回填覆盖所有租户）。 */
    private static final String TENANT_B = "globex";

    /** 平台保留租户。 */
    private static final String PLATFORM_TENANT = "platform";

    /** 租户 A 管理员角色 ID。 */
    private static final UUID TENANT_A_ADMIN_ROLE = UUID.fromString("00000000-0000-7000-8000-000000000801");

    /** 租户 A 员工角色 ID。 */
    private static final UUID TENANT_A_MEMBER_ROLE = UUID.fromString("00000000-0000-7000-8000-000000000802");

    /** 租户 B 管理员角色 ID。 */
    private static final UUID TENANT_B_ADMIN_ROLE = UUID.fromString("00000000-0000-7000-8000-000000000803");

    /** 租户 B 员工角色 ID。 */
    private static final UUID TENANT_B_MEMBER_ROLE = UUID.fromString("00000000-0000-7000-8000-000000000804");

    /** 平台管理员角色 ID。 */
    private static final UUID PLATFORM_ADMIN_ROLE = UUID.fromString("00000000-0000-7000-8000-000000000805");

    /** 租户 A 所有者用户 ID。 */
    private static final UUID TENANT_A_USER = UUID.fromString("00000000-0000-7000-8000-000000000811");

    /** 租户 B 所有者用户 ID。 */
    private static final UUID TENANT_B_USER = UUID.fromString("00000000-0000-7000-8000-000000000812");

    /** 平台管理员用户 ID。 */
    private static final UUID PLATFORM_USER = UUID.fromString("00000000-0000-7000-8000-000000000813");

    /** 正向流程 ID。 */
    private static final UUID FORWARD_WORKFLOW = UUID.fromString("00000000-0000-7000-8000-000000000821");

    /** 正向版本 ID。 */
    private static final UUID FORWARD_VERSION = UUID.fromString("00000000-0000-7000-8000-000000000822");

    /** 正向 schema JDBC。 */
    private static JdbcClient forward;

    /** fresh schema JDBC。 */
    private static JdbcClient fresh;

    /** 应急 drop schema JDBC。 */
    private static JdbcClient droppable;

    /** 正向 URL。 */
    private static String forwardUrl;

    /** fresh URL。 */
    private static String freshUrl;

    /** 应急 drop URL。 */
    private static String dropUrl;

    /** 启动容器并准备三个 schema。 */
    @BeforeAll
    static void prepareSchemas() {
        MYSQL.start();
        forwardUrl = jdbcUrl(FORWARD_SCHEMA);
        freshUrl = jdbcUrl(FRESH_SCHEMA);
        dropUrl = jdbcUrl(DROP_SCHEMA);
        createSchema(FORWARD_SCHEMA);
        createSchema(FRESH_SCHEMA);
        createSchema(DROP_SCHEMA);

        // 正向：V1–V14 -> 存量租户/角色/用户 -> V15 -> 一条可用于不可变/判定 SQL 的流程与版本。
        migrate(forwardUrl, "14");
        forward = jdbcClient(forwardUrl);
        seedLegacyTenants(forward);
        migrate(forwardUrl, "15");
        seedWorkflowRows(forward);

        // fresh：空库一次到 V15。
        migrate(freshUrl, "15");
        fresh = jdbcClient(freshUrl);

        // 应急 drop：空库到 V15，无 workflow 数据。
        migrate(dropUrl, "15");
        droppable = jdbcClient(dropUrl);
    }

    /** V15 建齐 9 张 workflow 表、model_invocation 可空引用与索引、4 个触发器。 */
    @Test
    @DisplayName("V15 新增 9 张表、model_invocation 可空引用与索引、workflow_version 触发器")
    void forward_createsWorkflowSchema() {
        assertThat(flyway(forwardUrl).info().current().getVersion().getVersion())
                .isEqualTo("15");
        List<String> tables = forward.sql("""
                        SELECT table_name FROM information_schema.tables
                         WHERE table_schema = DATABASE() AND table_name LIKE 'workflow%'
                         ORDER BY table_name
                        """).query(String.class).list();
        assertThat(tables)
                .containsExactly(
                        "workflow_approval",
                        "workflow_approval_assignee",
                        "workflow_definition",
                        "workflow_node_run",
                        "workflow_run",
                        "workflow_template",
                        "workflow_tool_invocation",
                        "workflow_user_template",
                        "workflow_version");

        // model_invocation：两列可空（INSTANT 追加），purpose CHECK 语义不变。
        assertThat(columns(forward, "model_invocation", "workflow_run_id"))
                .isEqualTo(new ColumnInfo("binary(16)", "YES", null));
        assertThat(columns(forward, "model_invocation", "workflow_node_run_id"))
                .isEqualTo(new ColumnInfo("binary(16)", "YES", null));
        assertThat(indexColumns(forward, "model_invocation", "idx_model_invocation_workflow"))
                .containsExactly("tenant_id", "workflow_run_id", "started_at");
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM information_schema.table_constraints
                         WHERE table_schema = DATABASE() AND table_name = 'model_invocation'
                           AND constraint_name = 'chk_model_invocation_purpose' AND constraint_type = 'CHECK'
                        """)).isEqualTo(1L);

        assertThat(triggers(forward, "workflow_version"))
                .containsExactlyInAnyOrder("trg_workflow_version_no_update", "trg_workflow_version_no_delete");
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()
                        """)).isEqualTo(4L);
    }

    /** workflow_version 不可变：UPDATE/DELETE 被触发器拒绝。 */
    @Test
    @DisplayName("workflow_version 触发器拒绝 UPDATE/DELETE")
    void workflowVersionIsImmutable() {
        assertThatThrownBy(() -> forward.sql("""
                                UPDATE workflow_version SET node_count = node_count + 1
                                 WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", TENANT_A)
                        .param("id", BinaryUuids.toBytes(FORWARD_VERSION))
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> forward.sql("""
                                DELETE FROM workflow_version
                                 WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", TENANT_A)
                        .param("id", BinaryUuids.toBytes(FORWARD_VERSION))
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");

        assertThat(count(forward, "SELECT COUNT(*) FROM workflow_version")).isEqualTo(1L);
    }

    /** 权限/角色回填：6 个权限码、TENANT_ADMIN 全部、MEMBER 3 个、确定性 WORKFLOW_EDITOR、平台无权限。 */
    @Test
    @DisplayName("V15 回填 workflow 权限与确定性 WORKFLOW_EDITOR 角色，平台租户不获得")
    void permissionsAndRolesAreBackfilled() {
        for (String code : List.of(
                "workflow.read",
                "workflow.edit",
                "workflow.publish",
                "workflow.run",
                "workflow.approve",
                "workflow.manage")) {
            assertThat(permissionCount(forward, code)).as(code).isEqualTo(1L);
        }

        assertThat(workflowPermissionCount(forward, TENANT_A, TENANT_A_ADMIN_ROLE))
                .isEqualTo(6L);
        assertThat(workflowPermissionCount(forward, TENANT_A, TENANT_A_MEMBER_ROLE))
                .isEqualTo(3L);
        assertThat(workflowPermissionCount(forward, TENANT_B, TENANT_B_ADMIN_ROLE))
                .isEqualTo(6L);
        assertThat(workflowPermissionCount(forward, TENANT_B, TENANT_B_MEMBER_ROLE))
                .isEqualTo(3L);

        String expectedEditorId = deterministicRoleId("WORKFLOW_EDITOR", TENANT_A);
        String actualEditorId =
                forward.sql("""
                        SELECT LOWER(HEX(id)) FROM role WHERE tenant_id = :tenantId AND code = 'WORKFLOW_EDITOR'
                        """).param("tenantId", TENANT_A).query(String.class).single();
        assertThat(actualEditorId).isEqualTo(expectedEditorId);
        assertThat(forward.sql("""
                        SELECT LOWER(HEX(id)) FROM role WHERE tenant_id = :tenantId AND code = 'WORKFLOW_EDITOR'
                        """)
                        .param("tenantId", TENANT_B)
                        .query(String.class)
                        .single())
                .isEqualTo(deterministicRoleId("WORKFLOW_EDITOR", TENANT_B));

        UUID editorId = UUID.fromString(hexToUuid(actualEditorId));
        assertThat(workflowPermissionCount(forward, TENANT_A, editorId)).isEqualTo(5L);
        assertThat(rolePermissionCount(forward, TENANT_A, editorId, PermissionCodes.WORKFLOW_MANAGE))
                .isZero();
        assertThat(forward.sql("""
                        SELECT COUNT(*) FROM role WHERE tenant_id = :tenantId AND code = 'WORKFLOW_EDITOR'
                        """)
                        .param("tenantId", PLATFORM_TENANT)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(workflowPermissionCount(forward, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE))
                .isZero();
    }

    /** 内置模板种子：固定 ID、BUILT_IN、7 节点/6 边、JSON 合法。 */
    @Test
    @DisplayName("V15 种子内置市场调研模板（固定 ID，BUILT_IN）")
    void builtInTemplateIsSeeded() {
        Map<String, Object> row = forward.sql("""
                        SELECT LOWER(HEX(id)) AS id, template_key, visibility, version, status,
                               JSON_VALID(graph_json) AS valid,
                               JSON_LENGTH(graph_json, '$.nodes') AS nodes,
                               JSON_LENGTH(graph_json, '$.edges') AS edges
                          FROM workflow_template
                         WHERE template_key = 'market-research'
                        """)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "id", rs.getString("id"),
                        "key", rs.getString("template_key"),
                        "visibility", rs.getString("visibility"),
                        "version", rs.getInt("version"),
                        "status", rs.getString("status"),
                        "valid", rs.getInt("valid"),
                        "nodes", rs.getLong("nodes"),
                        "edges", rs.getLong("edges")))
                .single();
        assertThat(row)
                .containsEntry(
                        "id",
                        WorkflowTemplateKeys.MARKET_RESEARCH_ID.replace("-", "").toLowerCase())
                .containsEntry("key", WorkflowTemplateKeys.MARKET_RESEARCH)
                .containsEntry("visibility", "BUILT_IN")
                .containsEntry("version", 1)
                .containsEntry("status", "ACTIVE")
                .containsEntry("valid", 1)
                .containsEntry("nodes", 7L)
                .containsEntry("edges", 6L);
    }

    /** 重复执行 V15 的 INSERT 段不产生重复行（失败 repair 后重跑场景）。 */
    @Test
    @DisplayName("V15 DML 二次执行幂等")
    void dmlReplayIsIdempotent() throws Exception {
        Map<String, Long> before = snapshotCounts(forward);
        for (String statement : insertStatements(migrationDirectory().resolve("V15__workflow_studio.sql"))) {
            forward.sql(statement).update();
        }
        assertThat(snapshotCounts(forward)).isEqualTo(before);
    }

    /** 无 workflow 数据时应急 drop 成功；有数据时判定 SQL 拒绝且数据保留。 */
    @Test
    @DisplayName("应急 drop：无数据可 drop，有数据拒绝并保留")
    void emergencyDropRefusesWithDataAndSucceedsWithout() {
        // 有数据（forward 已插入 definition/version）：判定 SQL 非 0，拒绝 drop 且数据保留。
        assertThatThrownBy(() -> emergencyDrop(forward))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow data exists");
        assertThat(count(forward, "SELECT COUNT(*) FROM workflow_definition")).isEqualTo(1L);
        assertThat(count(forward, "SELECT COUNT(*) FROM workflow_version")).isEqualTo(1L);

        // 无数据：drop 成功，表/列/索引/权限/触发器/历史行全部清理。
        emergencyDrop(droppable);
        assertThat(count(droppable, """
                        SELECT COUNT(*) FROM information_schema.tables
                         WHERE table_schema = DATABASE() AND table_name LIKE 'workflow%'
                        """)).isZero();
        assertThat(count(droppable, """
                        SELECT COUNT(*) FROM information_schema.statistics
                         WHERE table_schema = DATABASE() AND table_name = 'model_invocation'
                           AND index_name = 'idx_model_invocation_workflow'
                        """)).isZero();
        assertThat(count(droppable, """
                        SELECT COUNT(*) FROM information_schema.columns
                         WHERE table_schema = DATABASE() AND table_name = 'model_invocation'
                           AND column_name IN ('workflow_run_id', 'workflow_node_run_id')
                        """)).isZero();
        assertThat(count(droppable, """
                        SELECT COUNT(*) FROM information_schema.triggers
                         WHERE trigger_schema = DATABASE() AND trigger_name LIKE 'trg_workflow_version%'
                        """)).isZero();
        assertThat(permissionCount(droppable, "workflow.read")).isZero();
        assertThat(flyway(dropUrl).info().current().getVersion().getVersion()).isEqualTo("14");
    }

    /** fresh install：V1–V15、字典覆盖 PermissionCodes 全集、RoleDefinitions 三角色可按定义创建。 */
    @Test
    @DisplayName("fresh install V1–V15 与 RoleDefinitions 权限字典一致")
    void freshInstallCoversRoleDefinitions() {
        assertThat(flyway(freshUrl).info().current().getVersion().getVersion()).isEqualTo("15");
        assertThat(count(fresh, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"))
                .isEqualTo(58L);

        List<String> seeded = fresh.sql("SELECT code FROM permission ORDER BY code")
                .query(String.class)
                .list();
        assertThat(seeded).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(permissionCodeConstants());

        insertTenant(fresh, TENANT_A);
        insertUser(fresh, TENANT_A, TENANT_A_USER, "owner-a");
        for (RoleDefinition definition : RoleDefinitions.tenantSystemRoles()) {
            insertRoleWithPermissions(fresh, TENANT_A, roleIdFor(definition.code()), definition);
        }
        assertThat(workflowPermissionCount(fresh, TENANT_A, roleIdFor(RoleCodes.TENANT_ADMIN)))
                .isEqualTo(6L);
        assertThat(workflowPermissionCount(fresh, TENANT_A, roleIdFor(RoleCodes.MEMBER)))
                .isEqualTo(3L);
        assertThat(workflowPermissionCount(fresh, TENANT_A, roleIdFor(RoleCodes.WORKFLOW_EDITOR)))
                .isEqualTo(5L);
    }

    /**
     * 写入 V1–V14 时代的租户、用户与角色。
     *
     * @param jdbc 正向 schema
     */
    private static void seedLegacyTenants(JdbcClient jdbc) {
        insertTenant(jdbc, TENANT_A);
        insertTenant(jdbc, TENANT_B);
        insertUser(jdbc, TENANT_A, TENANT_A_USER, "owner-a");
        insertUser(jdbc, TENANT_B, TENANT_B_USER, "owner-b");
        insertUser(jdbc, PLATFORM_TENANT, PLATFORM_USER, "platform-admin");
        insertRole(jdbc, TENANT_A, TENANT_A_ADMIN_ROLE, "TENANT_ADMIN");
        insertRole(jdbc, TENANT_A, TENANT_A_MEMBER_ROLE, "MEMBER");
        insertRole(jdbc, TENANT_B, TENANT_B_ADMIN_ROLE, "TENANT_ADMIN");
        insertRole(jdbc, TENANT_B, TENANT_B_MEMBER_ROLE, "MEMBER");
        insertRole(jdbc, PLATFORM_TENANT, PLATFORM_ADMIN_ROLE, "PLATFORM_ADMIN");
    }

    /**
     * 插入一条流程与版本，用于不可变触发器、判定 SQL 与应急 drop 拒绝。
     *
     * @param jdbc 正向 schema
     */
    private static void seedWorkflowRows(JdbcClient jdbc) {
        jdbc.sql("""
                        INSERT INTO workflow_definition (
                            tenant_id, id, name, description, owner_user_id, draft_version, draft_graph,
                            status, created_by, updated_by
                        ) VALUES (
                            :tenantId, :id, '市场调研', NULL, :owner, 1,
                            '{"schemaVersion":1,"nodes":[],"edges":[]}', 'ACTIVE', :owner, :owner
                        )
                        """)
                .param("tenantId", TENANT_A)
                .param("id", BinaryUuids.toBytes(FORWARD_WORKFLOW))
                .param("owner", BinaryUuids.toBytes(TENANT_A_USER))
                .update();
        jdbc.sql("""
                        INSERT INTO workflow_version (
                            tenant_id, id, workflow_id, version_no, graph_json, graph_sha256,
                            node_count, run_scope, department_id, published_by, published_at, created_at
                        ) VALUES (
                            :tenantId, :id, :workflowId, 1,
                            '{"schemaVersion":1,"nodes":[],"edges":[]}', :sha256, 1,
                            'ONLY_SELF', NULL, :publishedBy, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                        )
                        """)
                .param("tenantId", TENANT_A)
                .param("id", BinaryUuids.toBytes(FORWARD_VERSION))
                .param("workflowId", BinaryUuids.toBytes(FORWARD_WORKFLOW))
                .param("sha256", "0".repeat(64))
                .param("publishedBy", BinaryUuids.toBytes(TENANT_A_USER))
                .update();
    }

    /**
     * 统计 V15 相关行数，用于二次执行幂等断言。
     *
     * @param jdbc schema
     * @return 计数快照
     */
    private static Map<String, Long> snapshotCounts(JdbcClient jdbc) {
        return Map.of(
                "permissions", count(jdbc, """
                                SELECT COUNT(*) FROM permission WHERE code LIKE 'workflow.%'
                                """),
                "roles", count(jdbc, "SELECT COUNT(*) FROM role WHERE code = 'WORKFLOW_EDITOR'"),
                "rolePermissions", count(jdbc, """
                                SELECT COUNT(*) FROM role_permission WHERE permission_code LIKE 'workflow.%'
                                """),
                "templates", count(jdbc, "SELECT COUNT(*) FROM workflow_template"));
    }

    /**
     * 应急 drop（仅在无数据且用户书面同意时使用；ADR-0024 §10.4）。
     *
     * <p>先执行判定 SQL，任一非 0 立即拒绝；随后按 FK 逆序 drop 租户表 → 全局模板 →
     * model_invocation 索引/列 → 权限/角色回填 → flyway history 15。
     *
     * @param jdbc schema
     * @throws IllegalStateException 存在 workflow 数据
     */
    private static void emergencyDrop(JdbcClient jdbc) {
        Map<String, Long> counts = Map.of(
                "definitions", count(jdbc, "SELECT COUNT(*) FROM workflow_definition"),
                "versions", count(jdbc, "SELECT COUNT(*) FROM workflow_version"),
                "runs", count(jdbc, "SELECT COUNT(*) FROM workflow_run"),
                "nodeRuns", count(jdbc, "SELECT COUNT(*) FROM workflow_node_run"),
                "approvals", count(jdbc, "SELECT COUNT(*) FROM workflow_approval"),
                "toolInvocations", count(jdbc, "SELECT COUNT(*) FROM workflow_tool_invocation"),
                "userTemplates", count(jdbc, "SELECT COUNT(*) FROM workflow_user_template"));
        long auditEvents = count(jdbc, "SELECT COUNT(*) FROM audit_event WHERE action LIKE 'workflow.%'");
        boolean hasData = counts.values().stream().anyMatch(value -> value > 0) || auditEvents > 0;
        if (hasData) {
            throw new IllegalStateException("workflow data exists; emergency drop refused: " + counts);
        }

        jdbc.sql("DROP TRIGGER IF EXISTS trg_workflow_version_no_update").update();
        jdbc.sql("DROP TRIGGER IF EXISTS trg_workflow_version_no_delete").update();
        for (String table : List.of(
                "workflow_approval_assignee",
                "workflow_tool_invocation",
                "workflow_approval",
                "workflow_node_run",
                "workflow_run",
                "workflow_version",
                "workflow_definition",
                "workflow_user_template",
                "workflow_template")) {
            jdbc.sql("DROP TABLE " + table).update();
        }
        jdbc.sql("""
                        ALTER TABLE model_invocation
                            DROP INDEX idx_model_invocation_workflow,
                            DROP COLUMN workflow_node_run_id,
                            DROP COLUMN workflow_run_id
                        """).update();
        jdbc.sql("""
                        DELETE FROM role_permission WHERE permission_code LIKE 'workflow.%'
                        """).update();
        jdbc.sql("DELETE FROM role WHERE code = 'WORKFLOW_EDITOR'").update();
        jdbc.sql("DELETE FROM permission WHERE code LIKE 'workflow.%'").update();
        jdbc.sql("DELETE FROM flyway_schema_history WHERE version = '15'").update();
    }

    /**
     * 读取 V15 文件中可重复执行的 INSERT 语句。
     *
     * <p>去掉整行注释后按分号切分；只保留 INSERT，忽略 CREATE TABLE/ALTER/TRIGGER 片段。语句内不含
     * 分号字符串，因此切分是确定的。
     *
     * @param file 迁移文件
     * @return INSERT 语句
     * @throws Exception 读取失败
     */
    private static List<String> insertStatements(Path file) throws Exception {
        String withoutComments = Files.readString(file)
                .lines()
                .filter(line -> !line.strip().startsWith("--"))
                .reduce("", (left, right) -> left + "\n" + right);
        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.split(";")) {
            String statement = part.strip();
            if (statement.regionMatches(true, 0, "INSERT INTO", 0, "INSERT INTO".length())) {
                statements.add(statement);
            }
        }
        return statements;
    }

    /**
     * 计算确定性系统角色 ID：SHA-256('role:'+code+':'+tenantId) 前 16 字节。
     *
     * @param code 角色码
     * @param tenantId 租户
     * @return 32 位小写十六进制
     */
    private static String deterministicRoleId(String code, String tenantId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("role:" + code + ":" + tenantId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot compute deterministic role id", exception);
        }
    }

    /**
     * 把 32 位十六进制转成 UUID 文本（用于查询参数）。
     *
     * @param hex 32 位十六进制
     * @return UUID 文本
     */
    private static String hexToUuid(String hex) {
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20, 32);
    }

    /** 读取 PermissionCodes 的全部 String 常量，断言字典一一对应。 */
    private static List<String> permissionCodeConstants() {
        List<String> codes = new ArrayList<>();
        for (Field field : PermissionCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    codes.add((String) field.get(null));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(
                            "cannot read PermissionCodes constant: " + field.getName(), exception);
                }
            }
        }
        return codes;
    }

    /**
     * 写入租户（platform 由 V2 已存在，不重复插入）。
     *
     * @param jdbc schema
     * @param tenantId 租户
     */
    private static void insertTenant(JdbcClient jdbc, String tenantId) {
        jdbc.sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """).param("tenantId", tenantId).param("name", tenantId).update();
    }

    /**
     * 写入账号。
     *
     * @param jdbc schema
     * @param tenantId 租户
     * @param userId 用户 ID
     * @param username 登录名
     */
    private static void insertUser(JdbcClient jdbc, String tenantId, UUID userId, String username) {
        jdbc.sql("""
                        INSERT INTO app_user (tenant_id, id, username, display_name, status, auth_source)
                        VALUES (:tenantId, :id, :username, :displayName, 'ACTIVE', 'LOCAL')
                        """)
                .param("tenantId", tenantId)
                .param("id", BinaryUuids.toBytes(userId))
                .param("username", username)
                .param("displayName", username)
                .update();
    }

    /**
     * 写入角色。
     *
     * @param jdbc schema
     * @param tenantId 租户
     * @param roleId 角色 ID
     * @param code 角色码
     */
    private static void insertRole(JdbcClient jdbc, String tenantId, UUID roleId, String code) {
        jdbc.sql("""
                        INSERT INTO role (tenant_id, id, code, name, is_system)
                        VALUES (:tenantId, :id, :code, :name, TRUE)
                        """)
                .param("tenantId", tenantId)
                .param("id", BinaryUuids.toBytes(roleId))
                .param("code", code)
                .param("name", code)
                .update();
    }

    /**
     * 按 RoleDefinitions 创建角色与全部权限映射，模拟开通/引导路径。
     *
     * @param jdbc schema
     * @param tenantId 租户
     * @param roleId 角色 ID
     * @param definition 角色定义
     */
    private static void insertRoleWithPermissions(
            JdbcClient jdbc, String tenantId, UUID roleId, RoleDefinition definition) {
        insertRole(jdbc, tenantId, roleId, definition.code());
        for (String permission : definition.permissions()) {
            jdbc.sql("""
                            INSERT INTO role_permission (tenant_id, role_id, permission_code)
                            VALUES (:tenantId, :roleId, :permissionCode)
                            """)
                    .param("tenantId", tenantId)
                    .param("roleId", BinaryUuids.toBytes(roleId))
                    .param("permissionCode", permission)
                    .update();
        }
    }

    /**
     * 返回 fresh schema 使用的固定角色 ID。
     *
     * @param code 角色码
     * @return 角色 ID
     */
    private static UUID roleIdFor(String code) {
        return switch (code) {
            case RoleCodes.TENANT_ADMIN -> TENANT_A_ADMIN_ROLE;
            case RoleCodes.MEMBER -> TENANT_A_MEMBER_ROLE;
            case RoleCodes.WORKFLOW_EDITOR -> UUID.fromString("00000000-0000-7000-8000-000000000809");
            default -> throw new IllegalArgumentException("unexpected tenant role code: " + code);
        };
    }

    /** 统计权限字典行数。 */
    private static long permissionCount(JdbcClient jdbc, String code) {
        return count(jdbc, "SELECT COUNT(*) FROM permission WHERE code = '" + code + "'");
    }

    /** 统计角色持有的 workflow.* 权限数。 */
    private static long workflowPermissionCount(JdbcClient jdbc, String tenant, UUID roleId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM role_permission
                         WHERE tenant_id = :tenantId AND role_id = :roleId AND permission_code LIKE 'workflow.%'
                        """)
                .param("tenantId", tenant)
                .param("roleId", BinaryUuids.toBytes(roleId))
                .query(Long.class)
                .single();
    }

    /** 统计角色是否持有指定权限。 */
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

    /** 列类型/可空/默认值。 */
    private static ColumnInfo columns(JdbcClient jdbc, String table, String column) {
        return jdbc.sql("""
                        SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
                          FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :table AND COLUMN_NAME = :column
                        """)
                .param("table", table)
                .param("column", column)
                .query((rs, rowNum) -> new ColumnInfo(
                        rs.getString("COLUMN_TYPE"), rs.getString("IS_NULLABLE"), rs.getString("COLUMN_DEFAULT")))
                .single();
    }

    /** 读取索引列顺序。 */
    private static List<String> indexColumns(JdbcClient jdbc, String table, String index) {
        return jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = :table AND INDEX_NAME = :index
                         ORDER BY SEQ_IN_INDEX
                        """)
                .param("table", table)
                .param("index", index)
                .query(String.class)
                .list();
    }

    /** 读取表上的触发器名。 */
    private static List<String> triggers(JdbcClient jdbc, String table) {
        return jdbc.sql("""
                        SELECT TRIGGER_NAME FROM information_schema.TRIGGERS
                         WHERE TRIGGER_SCHEMA = DATABASE() AND EVENT_OBJECT_TABLE = :table
                         ORDER BY TRIGGER_NAME
                        """).param("table", table).query(String.class).list();
    }

    /** 单值计数。 */
    private static long count(JdbcClient jdbc, String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0L : value;
    }

    /** 迁移到目标版本。 */
    private static void migrate(String url, String target) {
        Flyway.configure()
                .dataSource(url, "root", PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    /** 构造不限目标的 Flyway。 */
    private static Flyway flyway(String url) {
        return Flyway.configure()
                .dataSource(url, "root", PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .load();
    }

    /** 定位权威迁移目录。 */
    private static Path migrationDirectory() {
        Path moduleRelative = Path.of("src/main/resources/db/migration");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("apps/api/src/main/resources/db/migration");
    }

    /** 创建 schema。 */
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

    /** 拼装 JDBC URL。 */
    private static String jdbcUrl(String schema) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }

    /** 创建 JDBC 客户端。 */
    private static JdbcClient jdbcClient(String url) {
        return JdbcClient.create(new DriverManagerDataSource(url, "root", PASSWORD));
    }

    /** 列信息。 */
    private record ColumnInfo(String type, String nullable, String defaultValue) {}
}
