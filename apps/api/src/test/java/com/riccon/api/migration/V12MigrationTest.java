package com.riccon.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.identity.domain.RoleDefinition;
import com.riccon.api.identity.domain.RoleDefinitions;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.platform.domain.identity.PermissionCodes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
 * V12 知识库纵深迁移（M05 B1）语义测试。
 *
 * <p>不使用热栈或应用上下文：在一次性 Testcontainers MySQL 8.4.13 上先用 Flyway 只应用到 V11，
 * 种入 M02/M03 形态的租户、部门、账号、角色、历史知识库文档与分块，再应用 V12，验证文档级 ACL、
 * 解析元数据、引用版本快照、分块 bbox/置信度、连接器表、outbox 通道索引与权限回填。历史文档
 * 必须保持 {@code visibility=TENANT}、部门为空、分块与迁移前一致（ADR-0023 第 5.7 节）。
 *
 * <p>回滚演练在第二个 schema 上执行：V12 后写入 ACL/连接器数据，按 ADR-0023 §7.2 的删除顺序回到
 * V11，验证 M02 数据仍可读。fresh install 在第三个 schema 上验证 V1–V13 的权限字典与
 * {@link RoleDefinitions} 完全一致。
 *
 * <p>生产执行前提：V12 的一次性迁移 Job、备份/恢复与旧 Worker 回滚窗口仍需 M09 RUNBOOK 与用户 T4
 * 生产批准；本测试只证明迁移与回滚在非生产副本上可执行。
 */
@Tag("migration")
class V12MigrationTest {

    /** 与合同 stack.versions.mysql 一致的测试镜像；命令参数与 V11 迁移测试保持一致。 */
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

    /** 容器业务账号。 */
    private static final String USERNAME = "riccon";

    /** 仅容器内使用的占位密码，不对应任何真实环境。 */
    private static final String PASSWORD = "riccon_test_only";

    /** 业务租户。 */
    private static final String TENANT = "acme";

    /** 第二个租户，用于跨租户外键测试。 */
    private static final String OTHER_TENANT = "globex";

    /** 租户管理员账号。 */
    private static final UUID TENANT_ADMIN_USER = uuid("00000000-0000-7000-8000-000000000002");

    /** 普通员工账号。 */
    private static final UUID MEMBER_USER = uuid("00000000-0000-7000-8000-000000000003");

    /** 其他租户账号，用于跨租户 ACL 拒绝。 */
    private static final UUID OTHER_USER = uuid("00000000-0000-7000-8000-000000000004");

    /** 租户管理员角色。 */
    private static final UUID TENANT_ADMIN_ROLE = uuid("00000000-0000-7000-8000-000000000102");

    /** 普通员工角色。 */
    private static final UUID MEMBER_ROLE = uuid("00000000-0000-7000-8000-000000000103");

    /** 研发部门。 */
    private static final UUID DEPARTMENT_RESEARCH = uuid("00000000-0000-7000-8000-000000000201");

    /** 历史 UPLOAD 文档。 */
    private static final UUID LEGACY_UPLOAD_DOC = uuid("00000000-0000-7000-8000-000000000301");

    /** 历史 SEED 文档。 */
    private static final UUID LEGACY_SEED_DOC = uuid("00000000-0000-7000-8000-000000000302");

    /** 历史知识库分块。 */
    private static final UUID LEGACY_CHUNK = uuid("00000000-0000-7000-8000-000000000401");

    /** 连接器。 */
    private static final UUID CONNECTOR_ID = uuid("00000000-0000-7000-8000-000000000501");

    /** 连接器源对象。 */
    private static final UUID CONNECTOR_ITEM_ID = uuid("00000000-0000-7000-8000-000000000502");

    /** 正向 schema 的 JDBC 客户端。 */
    private static JdbcClient forward;

    /** 回滚 schema 的 JDBC 客户端（root）。 */
    private static JdbcClient rollback;

    /** fresh install schema 的 JDBC 客户端（root）。 */
    private static JdbcClient fresh;

    /** 正向 schema 的 Flyway URL。 */
    private static String forwardUrl;

    /** 回滚 schema 的 Flyway URL。 */
    private static String rollbackUrl;

    /** fresh install schema 的 Flyway URL。 */
    private static String freshUrl;

    /**
     * 启动一次性容器并准备三个 schema：V11 种子→V12、回滚演练、fresh install。
     */
    @BeforeAll
    static void prepareSchemas() {
        MYSQL.start();
        forwardUrl = jdbcUrl(FORWARD_SCHEMA);
        rollbackUrl = jdbcUrl(ROLLBACK_SCHEMA);
        freshUrl = jdbcUrl(FRESH_SCHEMA);
        createSchema(ROLLBACK_SCHEMA);
        createSchema(FRESH_SCHEMA);

        // 正向：V1–V11 -> M02/M03 种子与历史文档 -> V12 -> M05 ACL/连接器数据。
        migrate(forwardUrl, USERNAME, "11");
        forward = jdbcClient(forwardUrl, USERNAME);
        seedV11Data(forward);
        migrate(forwardUrl, USERNAME, "12");
        seedM05Data(forward);

        // 回滚：相同路径到 V12，再执行 V12 回滚。
        migrate(rollbackUrl, "root", "11");
        rollback = jdbcClient(rollbackUrl, "root");
        seedV11Data(rollback);
        migrate(rollbackUrl, "root", "12");
        seedM05Data(rollback);
        rollbackV12Objects(rollback);

        // fresh install：空库直接应用 V1–V13，验证权限字典与角色创建路径。
        migrate(freshUrl, "root", "13");
        fresh = jdbcClient(freshUrl, "root");
    }

    /** V12 在 V11 数据上由 Flyway 成功应用，history 记录成功版本 12。 */
    @Test
    @DisplayName("V12 在 V11 数据上应用成功")
    void migration_appliesOnV11SeedData() {
        assertThat(flyway(forwardUrl, USERNAME).info().current().getVersion().getVersion())
                .isEqualTo("12");
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM flyway_schema_history
                         WHERE success = 1 AND version = '12'
                        """)).isEqualTo(1L);
    }

    /** 历史文档保持 TENANT 可见、无部门、acl_version=1、解析元数据为空，分块不变。 */
    @Test
    @DisplayName("V12 不改变历史文档可见性与分块")
    void migration_keepsHistoricalDocumentsTenantVisible() {
        Map<String, Object> document = forward.sql("""
                        SELECT visibility, department_id, acl_version, parse_engine,
                               parse_warnings, parse_summary, retention_expires_at
                          FROM knowledge_document WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("visibility", rs.getString("visibility"));
                    row.put("departmentId", rs.getBytes("department_id"));
                    row.put("aclVersion", rs.getInt("acl_version"));
                    row.put("parseEngine", rs.getString("parse_engine"));
                    row.put("parseWarnings", rs.getString("parse_warnings"));
                    row.put("parseSummary", rs.getString("parse_summary"));
                    row.put("retentionExpiresAt", rs.getObject("retention_expires_at"));
                    return row;
                })
                .single();
        assertThat(document)
                .containsEntry("visibility", "TENANT")
                .containsEntry("departmentId", null)
                .containsEntry("aclVersion", 1)
                .containsEntry("parseEngine", null)
                .containsEntry("parseWarnings", null)
                .containsEntry("parseSummary", null)
                .containsEntry("retentionExpiresAt", null);

        assertThat(count(forward, "SELECT COUNT(*) FROM knowledge_document")).isEqualTo(2L);
        assertThat(forward.sql("""
                        SELECT content FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                        .param("tenantId", TENANT)
                        .param("id", BinaryUuids.toBytes(LEGACY_CHUNK))
                        .query(String.class)
                        .single())
                .isEqualTo("legacy chunk one");
    }

    /** knowledge_document 新列顺序、类型、默认值与索引符合 ADR-0023 §5.1。 */
    @Test
    @DisplayName("V12 为 knowledge_document 增加 ACL 与解析列")
    void migration_extendsKnowledgeDocument() {
        List<String> columns = columnList(forward, FORWARD_SCHEMA, "knowledge_document");
        assertThat(columns)
                .endsWith(
                        "visibility",
                        "department_id",
                        "connector_id",
                        "external_id",
                        "parse_engine",
                        "parse_warnings",
                        "parse_summary",
                        "acl_version",
                        "retention_expires_at");

        ColumnInfo visibility = columnInfo(forward, FORWARD_SCHEMA, "knowledge_document", "visibility");
        assertThat(visibility.type()).isEqualTo("varchar(16)");
        assertThat(visibility.nullable()).isEqualTo("NO");
        assertThat(visibility.defaultValue()).isEqualTo("TENANT");
        ColumnInfo aclVersion = columnInfo(forward, FORWARD_SCHEMA, "knowledge_document", "acl_version");
        assertThat(aclVersion.type()).isEqualTo("int");
        assertThat(aclVersion.nullable()).isEqualTo("NO");
        assertThat(aclVersion.defaultValue()).isEqualTo("1");
        assertThat(columnInfo(forward, FORWARD_SCHEMA, "knowledge_document", "retention_expires_at")
                        .type())
                .isEqualTo("datetime(6)");

        assertThat(indexColumns(forward, FORWARD_SCHEMA, "knowledge_document", "idx_kd_retention"))
                .containsExactly("tenant_id", "retention_expires_at");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "knowledge_document", "idx_kd_visibility"))
                .containsExactly("tenant_id", "visibility", "department_id", "created_at");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "knowledge_document", "fk_kd_department"))
                .containsExactly("tenant_id->tenant_id", "department_id->id");

        Map<String, String> checks = checkClauses(forward, FORWARD_SCHEMA, "knowledge_document");
        assertThat(checks)
                .containsKeys("chk_kd_visibility", "chk_kd_visibility_department", "chk_knowledge_document_source");
        assertThat(checks.get("chk_kd_visibility"))
                .contains("TENANT")
                .contains("DEPT")
                .contains("USERS");
        assertThat(checks.get("chk_knowledge_document_source")).contains("CONNECTOR");
    }

    /** source_type/visibility CHECK 接受 CONNECTOR，并拒绝“DEPT 无部门”等半收窄数据。 */
    @Test
    @DisplayName("V12 拒绝非法可见范围与来源类型")
    void migration_enforcesVisibilityAndSourceChecks() {
        assertRejected(
                "chk_kd_visibility",
                () -> forward.sql("""
                                UPDATE knowledge_document SET visibility = 'BROKEN'
                                 WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", TENANT)
                        .param("id", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                        .update());
        assertRejected(
                "chk_kd_visibility_department",
                () -> forward.sql("""
                                UPDATE knowledge_document SET visibility = 'DEPT', department_id = NULL
                                 WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", TENANT)
                        .param("id", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                        .update());
        assertRejected(
                "chk_kd_visibility_department",
                () -> forward.sql("""
                                UPDATE knowledge_document
                                   SET visibility = 'TENANT', department_id = :departmentId
                                 WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", TENANT)
                        .param("id", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                        .param("departmentId", BinaryUuids.toBytes(DEPARTMENT_RESEARCH))
                        .update());
        assertRejected(
                "chk_knowledge_document_source",
                () -> forward.sql("""
                                UPDATE knowledge_document SET source_type = 'BROKEN'
                                 WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", TENANT)
                        .param("id", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                        .update());

        // CONNECTOR 是合法来源；DEPT 必须带部门（用事务内写入后回滚证明可写，不污染共享 schema）。
        inTransaction(forward, tx -> {
            tx.sql("""
                            INSERT INTO knowledge_document (
                                tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes
                            ) VALUES (:tenantId, :id, 'connector doc', 'CONNECTOR', NULL,
                                      :checksum, 'application/pdf', 10)
                            """)
                    .param("tenantId", TENANT)
                    .param("id", BinaryUuids.toBytes(UUID.randomUUID()))
                    .param("checksum", "a".repeat(64))
                    .update();
            tx.sql("""
                            UPDATE knowledge_document
                               SET visibility = 'DEPT', department_id = :departmentId
                             WHERE tenant_id = :tenantId AND id = :id
                            """)
                    .param("tenantId", TENANT)
                    .param("id", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                    .param("departmentId", BinaryUuids.toBytes(DEPARTMENT_RESEARCH))
                    .update();
            assertThat(count(tx, "SELECT COUNT(*) FROM knowledge_document WHERE source_type = 'CONNECTOR'"))
                    .isEqualTo(1L);
            assertThat(count(tx, "SELECT COUNT(*) FROM knowledge_document WHERE visibility = 'DEPT'"))
                    .isEqualTo(1L);
        });
    }

    /** knowledge_document_acl 的主键、索引与复合外键阻止跨租户授权。 */
    @Test
    @DisplayName("V12 创建文档 ACL 表并阻止跨租户")
    void migration_createsDocumentAclTable() {
        List<String> columns = columnList(forward, FORWARD_SCHEMA, "knowledge_document_acl");
        assertThat(columns).containsExactly("tenant_id", "document_id", "user_id", "granted_by", "created_at");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "knowledge_document_acl", "PRIMARY"))
                .containsExactly("tenant_id", "document_id", "user_id");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "knowledge_document_acl", "idx_kda_user"))
                .containsExactly("tenant_id", "user_id", "document_id");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "knowledge_document_acl", "fk_kda_document"))
                .containsExactly("tenant_id->tenant_id", "document_id->id");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "knowledge_document_acl", "fk_kda_user"))
                .containsExactly("tenant_id->tenant_id", "user_id->id");

        assertRejected(
                "fk_kda_user",
                () -> forward.sql("""
                                INSERT INTO knowledge_document_acl (tenant_id, document_id, user_id)
                                VALUES (:tenantId, :documentId, :userId)
                                """)
                        .param("tenantId", TENANT)
                        .param("documentId", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                        .param("userId", BinaryUuids.toBytes(OTHER_USER))
                        .update());
    }

    /** message_citation/artifact_citation 增加可空 source_version。 */
    @Test
    @DisplayName("V12 为引用增加 source_version 快照")
    void migration_addsCitationSourceVersion() {
        for (String table : List.of("message_citation", "artifact_citation")) {
            ColumnInfo column = columnInfo(forward, FORWARD_SCHEMA, table, "source_version");
            assertThat(column.type()).as(table).isEqualTo("char(64)");
            assertThat(column.nullable()).as(table).isEqualTo("YES");
            assertThat(column.defaultValue()).as(table).isNull();
        }
    }

    /** file_chunk/knowledge_chunk 增加可空 bbox 与置信度列，且不新增 CHECK。 */
    @Test
    @DisplayName("V12 为分块增加 bbox 与 OCR 置信度")
    void migration_addsChunkLocatorColumns() {
        for (String table : List.of("file_chunk", "knowledge_chunk")) {
            List<String> columns = columnList(forward, FORWARD_SCHEMA, table);
            assertThat(columns).as(table).endsWith("bbox_x", "bbox_y", "bbox_w", "bbox_h", "ocr_confidence");
            for (String column : List.of("bbox_x", "bbox_y", "bbox_w", "bbox_h")) {
                ColumnInfo info = columnInfo(forward, FORWARD_SCHEMA, table, column);
                assertThat(info.type()).as(table + "." + column).isEqualTo("int");
                assertThat(info.nullable()).as(table + "." + column).isEqualTo("YES");
            }
            ColumnInfo confidence = columnInfo(forward, FORWARD_SCHEMA, table, "ocr_confidence");
            assertThat(confidence.type()).as(table).isEqualTo("decimal(4,3)");
            assertThat(confidence.nullable()).as(table).isEqualTo("YES");
        }
        assertThat(checkClauses(forward, FORWARD_SCHEMA, "knowledge_chunk"))
                .doesNotContainKeys("chk_bbox", "chk_ocr_confidence");
    }

    /** kb_connector/kb_connector_item 的列、唯一键、外键与 CHECK 与 ADR §6.2 一致。 */
    @Test
    @DisplayName("V12 创建连接器表并约束唯一键与状态")
    void migration_createsConnectorTables() {
        assertThat(columnList(forward, FORWARD_SCHEMA, "kb_connector"))
                .containsExactly(
                        "tenant_id",
                        "id",
                        "name",
                        "type",
                        "status",
                        "endpoint_id",
                        "config",
                        "default_visibility",
                        "default_department_id",
                        "credential_ciphertext",
                        "credential_key_id",
                        "credential_masked_hint",
                        "schedule_enabled",
                        "next_sync_at",
                        "last_sync_started_at",
                        "last_sync_finished_at",
                        "last_sync_status",
                        "last_sync_complete",
                        "last_success_at",
                        "last_error_code",
                        "last_sync_summary",
                        "created_by",
                        "created_at",
                        "updated_at",
                        "deleted_at");
        assertThat(columnList(forward, FORWARD_SCHEMA, "kb_connector_item"))
                .containsExactly(
                        "tenant_id",
                        "id",
                        "connector_id",
                        "external_id",
                        "external_id_hash",
                        "external_version",
                        "checksum",
                        "document_id",
                        "generation",
                        "status",
                        "error_code",
                        "last_seen_at",
                        "created_at",
                        "updated_at",
                        "deleted_at");

        assertThat(indexColumns(forward, FORWARD_SCHEMA, "kb_connector", "PRIMARY"))
                .containsExactly("tenant_id", "id");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "kb_connector", "uk_kb_connector_name"))
                .containsExactly("tenant_id", "name");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "kb_connector", "idx_kb_connector_schedule"))
                .containsExactly("status", "next_sync_at");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "kb_connector_item", "PRIMARY"))
                .containsExactly("tenant_id", "id");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "kb_connector_item", "uk_kb_item_external"))
                .containsExactly("tenant_id", "connector_id", "external_id_hash");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "kb_connector_item", "idx_kb_item_status"))
                .containsExactly("tenant_id", "connector_id", "status");
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "kb_connector_item", "idx_kb_item_document"))
                .containsExactly("tenant_id", "document_id");

        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "kb_connector", "fk_kb_connector_tenant"))
                .containsExactly("tenant_id->id");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "kb_connector", "fk_kb_connector_department"))
                .containsExactly("tenant_id->tenant_id", "default_department_id->id");
        assertThat(foreignKeyColumns(forward, FORWARD_SCHEMA, "kb_connector_item", "fk_kb_item_connector"))
                .containsExactly("tenant_id->tenant_id", "connector_id->id");

        Map<String, String> connectorChecks = checkClauses(forward, FORWARD_SCHEMA, "kb_connector");
        assertThat(connectorChecks)
                .containsKeys(
                        "chk_kb_connector_type",
                        "chk_kb_connector_status",
                        "chk_kb_connector_sync_status",
                        "chk_kb_connector_visibility",
                        "chk_kb_connector_visibility_department");
        assertThat(connectorChecks.get("chk_kb_connector_status"))
                .contains("ACTIVE")
                .contains("PAUSED")
                .contains("ERROR");
        Map<String, String> itemChecks = checkClauses(forward, FORWARD_SCHEMA, "kb_connector_item");
        assertThat(itemChecks).containsKeys("chk_kb_item_status", "chk_kb_item_generation");

        ColumnInfo generation = columnInfo(forward, FORWARD_SCHEMA, "kb_connector_item", "generation");
        assertThat(generation.type()).isEqualTo("int");
        assertThat(generation.defaultValue()).isEqualTo("1");
        ColumnInfo status = columnInfo(forward, FORWARD_SCHEMA, "kb_connector", "status");
        assertThat(status.defaultValue()).isEqualTo("PAUSED");

        assertRejected(
                "chk_kb_connector_type",
                () -> insertConnector(forward, TENANT, UUID.randomUUID(), "connector-bad-type", "GCS", "TENANT", null));
        assertRejected(
                "chk_kb_connector_visibility_department",
                () -> insertConnector(
                        forward, TENANT, UUID.randomUUID(), "connector-dept-missing", "S3", "DEPT", null));
        assertRejected(
                "chk_kb_item_generation",
                () -> insertConnectorItem(forward, TENANT, CONNECTOR_ID, UUID.randomUUID(), "b".repeat(64), 0));
    }

    /** outbox 领取索引覆盖通道过滤列。 */
    @Test
    @DisplayName("V12 为 outbox 通道增加领取索引")
    void migration_addsOutboxLaneIndex() {
        assertThat(indexColumns(forward, FORWARD_SCHEMA, "agent_run_outbox", "idx_outbox_lane"))
                .containsExactly("status", "event_type", "available_at", "lease_until");
    }

    /** 权限字典与既有 TENANT_ADMIN 回填只授予租户管理员。 */
    @Test
    @DisplayName("V12 回填 M05 权限码与角色映射")
    void migration_backfillsM05Permissions() {
        for (String code : List.of(
                PermissionCodes.KNOWLEDGE_ACL_MANAGE,
                PermissionCodes.KNOWLEDGE_CONNECTOR_READ,
                PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE)) {
            assertThat(permissionCount(forward, code)).as(code).isEqualTo(1L);
            assertThat(rolePermissionCount(forward, TENANT, TENANT_ADMIN_ROLE, code))
                    .as(code)
                    .isEqualTo(1L);
            assertThat(rolePermissionCount(forward, TENANT, MEMBER_ROLE, code))
                    .as(code)
                    .isZero();
        }
    }

    /** fresh install：V1–V13 的权限字典与 RoleDefinitions 完全一致，角色创建不会因缺码失败。 */
    @Test
    @DisplayName("空库 V1–V13 的权限字典覆盖 PermissionCodes 全集")
    void freshDatabase_permissionDictionaryCoversAllPermissionCodes() {
        List<String> seeded = fresh.sql("SELECT code FROM permission ORDER BY code")
                .query(String.class)
                .list();
        List<String> expected = permissionCodeConstants();
        assertThat(seeded).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);

        insertTenant(fresh, "freshco");
        for (RoleDefinition definition : RoleDefinitions.tenantSystemRoles()) {
            insertRoleWithPermissions(fresh, "freshco", roleIdFor(definition.code()), definition);
        }
        assertThat(rolePermissionCount(fresh, "freshco", TENANT_ADMIN_ROLE, PermissionCodes.KNOWLEDGE_ACL_MANAGE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(fresh, "freshco", TENANT_ADMIN_ROLE, PermissionCodes.KNOWLEDGE_CONNECTOR_MANAGE))
                .isEqualTo(1L);
        assertThat(rolePermissionCount(fresh, "freshco", MEMBER_ROLE, PermissionCodes.KNOWLEDGE_ACL_MANAGE))
                .isZero();
    }

    /** V12 SQL 使用安全算法，且回滚脚本可核对：不含破坏性 DDL，source CHECK 明确收窄回 V11。 */
    @Test
    @DisplayName("V12 DDL 使用 INSTANT/INPLACE 且回滚可核对")
    void migration_ddlUsesSafeAlgorithms() throws Exception {
        String sql = Files.readString(migrationDirectory().resolve("V12__knowledge_depth.sql"))
                .lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));

        assertThat(sql)
                .contains("ALGORITHM = INSTANT")
                .contains("ALGORITHM = INPLACE")
                .contains("ALGORITHM = COPY")
                .contains("LOCK = NONE")
                .contains("LOCK = SHARED")
                .doesNotContain("foreign_key_checks")
                .contains("DROP CHECK chk_knowledge_document_source")
                .contains("CHECK (source_type IN ('UPLOAD', 'SEED', 'CONNECTOR'))")
                .doesNotContain("DROP TABLE knowledge_document")
                .doesNotContain("DROP TABLE knowledge_chunk")
                .doesNotContain("DROP TABLE file_chunk")
                .doesNotContain("DROP TABLE agent_run_outbox");
        // MySQL 8.4 的 FK 与 CHECK 各需要一次 COPY 重建；数量锁定，防止回退成 INPLACE 后 gate 才发现。
        assertThat(sql.split("ALGORITHM = COPY", -1)).hasSize(3);
        assertThat(sql.split("ALGORITHM = INPLACE", -1)).hasSizeGreaterThanOrEqualTo(2);
        // 回滚 SQL 以测试方法 + 迁移头注释双份可核对，避免只存在文档里。
        assertThat(V12MigrationTest.class.getDeclaredMethod("rollbackV12Objects", JdbcClient.class))
                .isNotNull();
    }

    /** 回滚删除 V12 对象与权限，回到 V11 且 M02 历史数据可读。 */
    @Test
    @DisplayName("V12 回滚回到 V11 且历史数据可读")
    void rollback_returnsToV11WithoutM05Objects() {
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.TABLES
                         WHERE TABLE_SCHEMA = :schema
                           AND TABLE_NAME IN ('knowledge_document_acl', 'kb_connector', 'kb_connector_item')
                        """, "schema", ROLLBACK_SCHEMA)).isZero();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = 'knowledge_document'
                           AND COLUMN_NAME IN ('visibility', 'department_id', 'connector_id', 'external_id',
                                               'parse_engine', 'parse_warnings', 'parse_summary',
                                               'acl_version', 'retention_expires_at')
                        """, "schema", ROLLBACK_SCHEMA)).isZero();
        for (String table : List.of("file_chunk", "knowledge_chunk")) {
            assertThat(count(rollback, """
                            SELECT COUNT(*) FROM information_schema.COLUMNS
                             WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table
                               AND COLUMN_NAME IN ('bbox_x', 'bbox_y', 'bbox_w', 'bbox_h', 'ocr_confidence')
                            """, "schema", ROLLBACK_SCHEMA, "table", table))
                    .as(table)
                    .isZero();
        }
        for (String table : List.of("message_citation", "artifact_citation")) {
            assertThat(count(rollback, """
                            SELECT COUNT(*) FROM information_schema.COLUMNS
                             WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table AND COLUMN_NAME = 'source_version'
                            """, "schema", ROLLBACK_SCHEMA, "table", table))
                    .as(table)
                    .isZero();
        }
        assertThat(indexExists(rollback, ROLLBACK_SCHEMA, "agent_run_outbox", "idx_outbox_lane"))
                .isFalse();
        assertThat(checkClauses(rollback, ROLLBACK_SCHEMA, "knowledge_document").get("chk_knowledge_document_source"))
                .contains("UPLOAD")
                .contains("SEED")
                .doesNotContain("CONNECTOR");
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM permission
                         WHERE code IN ('knowledge.acl.manage', 'knowledge.connector.read',
                                        'knowledge.connector.manage')
                        """)).isZero();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM role_permission
                         WHERE permission_code IN ('knowledge.acl.manage', 'knowledge.connector.read',
                                                   'knowledge.connector.manage')
                        """)).isZero();

        // M02 历史数据可读；分块内容不变。
        assertThat(rollback.sql("""
                        SELECT title FROM knowledge_document
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                        .param("tenantId", TENANT)
                        .param("id", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                        .query(String.class)
                        .single())
                .isEqualTo("legacy-upload");
        assertThat(count(rollback, "SELECT COUNT(*) FROM knowledge_chunk")).isEqualTo(1L);

        // 维护账号删除 V12 历史行后，schema 版本回到 11；不使用 flyway repair。
        rollback.sql("DELETE FROM flyway_schema_history WHERE version = '12'").update();
        assertThat(count(rollback, "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history"))
                .isEqualTo(11L);
        assertThat(flyway(rollbackUrl, "root").info().current().getVersion().getVersion())
                .isEqualTo("11");
    }

    /** 构造 V11 形态的 M02/M03 数据：部门、管理员/员工、角色与历史知识库文档。 */
    private static void seedV11Data(JdbcClient jdbc) {
        insertTenant(jdbc, TENANT);
        insertTenant(jdbc, OTHER_TENANT);
        insertDepartment(jdbc, TENANT, DEPARTMENT_RESEARCH, "研发部");
        insertUser(jdbc, TENANT, TENANT_ADMIN_USER, "acme-admin", "Acme Admin", DEPARTMENT_RESEARCH);
        insertUser(jdbc, TENANT, MEMBER_USER, "acme-member", "Acme Member", null);
        insertUser(jdbc, OTHER_TENANT, OTHER_USER, "globex-user", "Globex User", null);
        insertRole(jdbc, TENANT, TENANT_ADMIN_ROLE, "TENANT_ADMIN");
        insertRole(jdbc, TENANT, MEMBER_ROLE, "MEMBER");
        insertRolePermission(jdbc, TENANT, TENANT_ADMIN_ROLE, "workspace.read");
        insertLegacyDocument(jdbc, TENANT, LEGACY_UPLOAD_DOC, "legacy-upload", "UPLOAD");
        insertLegacyDocument(jdbc, TENANT, LEGACY_SEED_DOC, "legacy-seed", "SEED");
        insertKnowledgeChunk(jdbc, TENANT, LEGACY_UPLOAD_DOC, LEGACY_CHUNK, 1, "legacy chunk one");
    }

    /** V12 之后写入 ACL 与连接器数据，供回滚演练验证删除顺序。 */
    private static void seedM05Data(JdbcClient jdbc) {
        jdbc.sql("""
                        INSERT INTO knowledge_document_acl (tenant_id, document_id, user_id, granted_by)
                        VALUES (:tenantId, :documentId, :userId, :grantedBy)
                        """)
                .param("tenantId", TENANT)
                .param("documentId", BinaryUuids.toBytes(LEGACY_UPLOAD_DOC))
                .param("userId", BinaryUuids.toBytes(MEMBER_USER))
                .param("grantedBy", BinaryUuids.toBytes(TENANT_ADMIN_USER))
                .update();
        insertConnector(jdbc, TENANT, CONNECTOR_ID, "docs-bucket", "S3", "TENANT", null);
        insertConnectorItem(jdbc, TENANT, CONNECTOR_ID, CONNECTOR_ITEM_ID, "b".repeat(64), 1);
    }

    /** 按 ADR-0023 §7.2 回滚 V12：先删子表，再删约束/索引/列，最后删权限。 */
    private static void rollbackV12Objects(JdbcClient jdbc) {
        // 回滚前置条件：不能有 CONNECTOR 文档（本演练 schema 未写入）。
        assertThat(count(jdbc, "SELECT COUNT(*) FROM knowledge_document WHERE source_type = 'CONNECTOR'"))
                .isZero();

        List<String> statements = List.of(
                "DROP TABLE IF EXISTS kb_connector_item",
                "DROP TABLE IF EXISTS kb_connector",
                "DROP TABLE IF EXISTS knowledge_document_acl",
                "ALTER TABLE knowledge_document DROP CHECK chk_kd_visibility_department,"
                        + " DROP CHECK chk_kd_visibility,"
                        + " DROP FOREIGN KEY fk_kd_department",
                "ALTER TABLE knowledge_document DROP CHECK chk_knowledge_document_source",
                "ALTER TABLE knowledge_document ADD CONSTRAINT chk_knowledge_document_source"
                        + " CHECK (source_type IN ('UPLOAD', 'SEED'))",
                "ALTER TABLE knowledge_document DROP KEY idx_kd_retention, DROP KEY idx_kd_visibility",
                "ALTER TABLE knowledge_document DROP COLUMN retention_expires_at,"
                        + " DROP COLUMN acl_version,"
                        + " DROP COLUMN parse_summary,"
                        + " DROP COLUMN parse_warnings,"
                        + " DROP COLUMN parse_engine,"
                        + " DROP COLUMN external_id,"
                        + " DROP COLUMN connector_id,"
                        + " DROP COLUMN department_id,"
                        + " DROP COLUMN visibility",
                "ALTER TABLE file_chunk DROP COLUMN bbox_x, DROP COLUMN bbox_y, DROP COLUMN bbox_w,"
                        + " DROP COLUMN bbox_h, DROP COLUMN ocr_confidence",
                "ALTER TABLE knowledge_chunk DROP COLUMN bbox_x, DROP COLUMN bbox_y, DROP COLUMN bbox_w,"
                        + " DROP COLUMN bbox_h, DROP COLUMN ocr_confidence",
                "ALTER TABLE message_citation DROP COLUMN source_version",
                "ALTER TABLE artifact_citation DROP COLUMN source_version",
                "ALTER TABLE agent_run_outbox DROP KEY idx_outbox_lane",
                "DELETE FROM role_permission WHERE permission_code IN"
                        + " ('knowledge.acl.manage', 'knowledge.connector.read', 'knowledge.connector.manage')",
                "DELETE FROM permission WHERE code IN"
                        + " ('knowledge.acl.manage', 'knowledge.connector.read', 'knowledge.connector.manage')");
        for (String statement : statements) {
            jdbc.sql(statement).update();
        }
    }

    /** 执行给定目标版本的 Flyway 迁移。 */
    private static void migrate(String url, String user, String target) {
        Flyway.configure()
                .dataSource(url, user, PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    /** 构造不限制目标的 Flyway。 */
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

    /** 事务包裹执行，结束回滚；用于证明新约束可写且不污染共享 schema。 */
    private static void inTransaction(JdbcClient jdbc, TransactionWork work) {
        String schema = jdbc.sql("SELECT DATABASE()").query(String.class).single();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema
                        + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",
                USERNAME,
                PASSWORD)) {
            connection.setAutoCommit(false);
            JdbcClient transactional = JdbcClient.create(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            work.run(transactional);
            connection.rollback();
        } catch (Exception exception) {
            throw new IllegalStateException("transaction test failed", exception);
        }
    }

    /** 事务内工作。 */
    @FunctionalInterface
    private interface TransactionWork {
        /**
         * 执行事务内断言/写入。
         *
         * @param jdbc 与事务同一连接的 JdbcClient
         * @throws Exception 任意失败
         */
        void run(JdbcClient jdbc) throws Exception;
    }

    /** 种一个租户。 */
    private static void insertTenant(JdbcClient jdbc, String tenant) {
        jdbc.sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """).param("tenantId", tenant).param("name", tenant).update();
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
                .param("createdBy", BinaryUuids.toBytes(TENANT_ADMIN_USER))
                .update();
    }

    /** 种一个账号，可带主部门。 */
    private static void insertUser(
            JdbcClient jdbc, String tenant, UUID userId, String username, String displayName, UUID departmentId) {
        jdbc.sql("""
                        INSERT INTO app_user (tenant_id, id, username, display_name, status, auth_source, department_id)
                        VALUES (:tenantId, :id, :username, :displayName, 'ACTIVE', 'LOCAL', :departmentId)
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(userId))
                .param("username", username)
                .param("displayName", displayName)
                .param("departmentId", departmentId == null ? null : BinaryUuids.toBytes(departmentId))
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
    private static void insertRoleWithPermissions(
            JdbcClient jdbc, String tenant, UUID roleId, RoleDefinition definition) {
        insertRole(jdbc, tenant, roleId, definition.code());
        for (String permission : definition.permissions()) {
            insertRolePermission(jdbc, tenant, roleId, permission);
        }
    }

    /** 按角色码返回 fresh schema 使用的固定角色 ID。 */
    private static UUID roleIdFor(String code) {
        return switch (code) {
            case "TENANT_ADMIN" -> TENANT_ADMIN_ROLE;
            case "MEMBER" -> MEMBER_ROLE;
            default -> throw new IllegalArgumentException("unexpected tenant role code: " + code);
        };
    }

    /** 种一个历史知识库文档（M02 形态，无 ACL 列）。 */
    private static void insertLegacyDocument(
            JdbcClient jdbc, String tenant, UUID documentId, String title, String sourceType) {
        jdbc.sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by
                        ) VALUES (
                            :tenantId, :id, :title, :sourceType, NULL, :checksum, 'text/plain', 16,
                            'INDEXED', :createdBy
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("sourceType", sourceType)
                .param("checksum", "c".repeat(64))
                .param("createdBy", BinaryUuids.toBytes(TENANT_ADMIN_USER))
                .update();
    }

    /** 种一条历史知识库分块。 */
    private static void insertKnowledgeChunk(
            JdbcClient jdbc, String tenant, UUID documentId, UUID chunkId, int ordinal, String content) {
        jdbc.sql("""
                        INSERT INTO knowledge_chunk (
                            tenant_id, id, document_id, ordinal, locator_type, paragraph_no, content, token_count
                        ) VALUES (
                            :tenantId, :id, :documentId, :ordinal, 'PARAGRAPH', 1, :content, 4
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(chunkId))
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("ordinal", ordinal)
                .param("content", content)
                .update();
    }

    /** 种一个连接器。 */
    private static void insertConnector(
            JdbcClient jdbc,
            String tenant,
            UUID connectorId,
            String name,
            String type,
            String defaultVisibility,
            UUID defaultDepartmentId) {
        jdbc.sql("""
                        INSERT INTO kb_connector (
                            tenant_id, id, name, type, status, endpoint_id, config,
                            default_visibility, default_department_id
                        ) VALUES (
                            :tenantId, :id, :name, :type, 'PAUSED', 'demo',
                            '{"bucket":"docs","prefix":"kb/"}', :defaultVisibility, :defaultDepartmentId
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(connectorId))
                .param("name", name)
                .param("type", type)
                .param("defaultVisibility", defaultVisibility)
                .param(
                        "defaultDepartmentId",
                        defaultDepartmentId == null ? null : BinaryUuids.toBytes(defaultDepartmentId))
                .update();
    }

    /** 种一个连接器源对象。 */
    private static void insertConnectorItem(
            JdbcClient jdbc, String tenant, UUID connectorId, UUID itemId, String externalIdHash, int generation) {
        jdbc.sql("""
                        INSERT INTO kb_connector_item (
                            tenant_id, id, connector_id, external_id, external_id_hash, generation, status
                        ) VALUES (
                            :tenantId, :id, :connectorId, 'docs/a.pdf', :externalIdHash, :generation, 'PENDING'
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(itemId))
                .param("connectorId", BinaryUuids.toBytes(connectorId))
                .param("externalIdHash", externalIdHash)
                .param("generation", generation)
                .update();
    }

    /** 读取列清单（按 ordinal 顺序）。 */
    private static List<String> columnList(JdbcClient jdbc, String schema, String table) {
        return jdbc.sql("""
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table
                         ORDER BY ORDINAL_POSITION
                        """)
                .param("schema", schema)
                .param("table", table)
                .query(String.class)
                .list();
    }

    /** 读取列类型/可空/默认值。 */
    private static ColumnInfo columnInfo(JdbcClient jdbc, String schema, String table, String column) {
        return jdbc.sql("""
                        SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
                          FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table AND COLUMN_NAME = :column
                        """)
                .param("schema", schema)
                .param("table", table)
                .param("column", column)
                .query((rs, rowNum) -> new ColumnInfo(
                        rs.getString("COLUMN_TYPE"), rs.getString("IS_NULLABLE"), rs.getString("COLUMN_DEFAULT")))
                .single();
    }

    /** 读取索引列顺序。 */
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

    /** 判断索引是否存在。 */
    private static boolean indexExists(JdbcClient jdbc, String schema, String table, String index) {
        return count(jdbc, """
                        SELECT COUNT(*) FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table AND INDEX_NAME = :index
                        """, "schema", schema, "table", table, "index", index) > 0;
    }

    /** 读取复合外键的 列->被引用列 对。 */
    private static List<String> foreignKeyColumns(JdbcClient jdbc, String schema, String table, String constraintName) {
        return jdbc.sql("""
                        SELECT CONCAT(COLUMN_NAME, '->', REFERENCED_COLUMN_NAME) AS pair
                          FROM information_schema.KEY_COLUMN_USAGE
                         WHERE TABLE_SCHEMA = :schema AND TABLE_NAME = :table
                           AND CONSTRAINT_NAME = :constraintName AND REFERENCED_TABLE_NAME IS NOT NULL
                         ORDER BY ORDINAL_POSITION
                        """)
                .param("schema", schema)
                .param("table", table)
                .param("constraintName", constraintName)
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

    /** 单值计数；可变参数按 名值对 绑定。 */
    private static long count(JdbcClient jdbc, String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (int index = 0; index < params.length; index += 2) {
            spec = spec.param((String) params[index], params[index + 1]);
        }
        return spec.query(Long.class).single();
    }

    /** 断言数据库以指定约束名拒绝写入；错误信息必须包含约束名，避免被其他约束误判为通过。 */
    private static void assertRejected(String constraintName, ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(DataAccessException.class).hasMessageContaining(constraintName);
    }

    /** 解析测试常量 UUID。 */
    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    /** 读取 PermissionCodes 接口的全部 public static final String 常量。 */
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

    /** 列信息。 */
    private record ColumnInfo(String type, String nullable, String defaultValue) {}
}
