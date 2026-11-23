package com.riccon.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.persistence.BinaryUuids;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * V13 引用分块关联迁移（M05 B9 修复）语义测试。
 *
 * <p>在一次性 Testcontainers MySQL 8.4.13 上先用 Flyway 应用到 V12，写入最小产物引用链，再应用 V13：
 * 验证 {@code artifact_citation.chunk_id} 可空、带租户索引、历史引用保持 NULL 且数据不丢失。
 * 回滚 schema 执行 V13 头注释中的删除顺序，验证列/索引移除后引用行仍可读；fresh install 不在本测试重复，
 * 由 V11/V12 迁移测试的 latest 目标覆盖。
 */
@Tag("migration")
class V13MigrationTest {

    /** 与合同 stack.versions.mysql 一致的测试镜像。 */
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

    /** 容器业务账号。 */
    private static final String USERNAME = "riccon";

    /** 仅容器内使用的占位密码。 */
    private static final String PASSWORD = "riccon_test_only";

    /** 业务租户。 */
    private static final String TENANT = "acme";

    /** 创建人账号。 */
    private static final UUID USER_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    /** 个人空间。 */
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-7000-8000-000000000101");

    /** 产物。 */
    private static final UUID ARTIFACT_ID = UUID.fromString("00000000-0000-7000-8000-000000000201");

    /** 产物版本。 */
    private static final UUID VERSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000202");

    /** 历史引用（V12 写入，无 chunk_id）。 */
    private static final UUID LEGACY_CITATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000301");

    /** 知识库文档。 */
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000401");

    /** 知识库分块。 */
    private static final UUID CHUNK_ID = UUID.fromString("00000000-0000-7000-8000-000000000402");

    /** 正向 schema JDBC。 */
    private static JdbcClient forward;

    /** 回滚 schema JDBC。 */
    private static JdbcClient rollback;

    /** 正向 schema URL。 */
    private static String forwardUrl;

    /** 回滚 schema URL。 */
    private static String rollbackUrl;

    /** 启动一次性容器并准备 V12 种子与回滚 schema。 */
    @BeforeAll
    static void prepareSchemas() {
        MYSQL.start();
        forwardUrl = jdbcUrl(FORWARD_SCHEMA);
        rollbackUrl = jdbcUrl(ROLLBACK_SCHEMA);
        createSchema(ROLLBACK_SCHEMA);

        // 正向：V1–V12 -> 最小产物引用链 -> V13。
        migrate(forwardUrl, USERNAME, "12");
        forward = jdbcClient(forwardUrl, USERNAME);
        seedCitationData(forward);
        migrate(forwardUrl, USERNAME, "13");

        // 回滚：V1–V13 -> 写入 chunk_id -> 执行 V13 头注释中的删除顺序。
        migrate(rollbackUrl, "root", "13");
        rollback = jdbcClient(rollbackUrl, "root");
        seedCitationData(rollback);
        rollback.sql("UPDATE artifact_citation SET chunk_id = :chunkId WHERE tenant_id = :tenantId AND id = :id")
                .param("chunkId", BinaryUuids.toBytes(CHUNK_ID))
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(LEGACY_CITATION_ID))
                .update();
        rollbackV13Objects(rollback);
    }

    /** V13 由 Flyway 成功应用，历史引用保留且 chunk_id 为 NULL。 */
    @Test
    @DisplayName("V13 在 V12 数据上应用成功且历史引用不变")
    void migration_appliesOnV12Data() {
        assertThat(flyway(forwardUrl, USERNAME).info().current().getVersion().getVersion())
                .isEqualTo("13");
        assertThat(count(forward, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version = '13'"))
                .isEqualTo(1L);
        Map<String, Object> row = forward.sql("""
                        SELECT source_type, chunk_id FROM artifact_citation
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(LEGACY_CITATION_ID))
                .query((rs, rowNum) -> {
                    Map<String, Object> mapped = new java.util.HashMap<>();
                    mapped.put("sourceType", rs.getString("source_type"));
                    mapped.put("chunkId", rs.getBytes("chunk_id"));
                    return mapped;
                })
                .single();
        assertThat(row).containsEntry("sourceType", "KNOWLEDGE").containsEntry("chunkId", null);
        assertThat(column(forward, "chunk_id")).isEqualTo(new ColumnInfo("binary(16)", "YES", null));
    }

    /** chunk_id 是普通可空列，可按租户索引查询。 */
    @Test
    @DisplayName("V13 建立租户 chunk_id 索引并可写入")
    void migration_createsChunkIndex() {
        List<String> columns = forward.sql("""
                        SELECT column_name FROM information_schema.statistics
                         WHERE table_schema = DATABASE() AND table_name = 'artifact_citation'
                           AND index_name = 'idx_artifact_citation_chunk'
                         ORDER BY seq_in_index
                        """).query(String.class).list();
        assertThat(columns).containsExactly("tenant_id", "chunk_id");

        forward.sql("UPDATE artifact_citation SET chunk_id = :chunkId WHERE tenant_id = :tenantId AND id = :id")
                .param("chunkId", BinaryUuids.toBytes(CHUNK_ID))
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(LEGACY_CITATION_ID))
                .update();
        byte[] stored = forward.sql("""
                        SELECT chunk_id FROM artifact_citation WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(LEGACY_CITATION_ID))
                .query(byte[].class)
                .single();
        assertThat(BinaryUuids.fromBytes(stored)).isEqualTo(CHUNK_ID);

        // 还原，保持其他用例的历史 NULL 语义。
        forward.sql("UPDATE artifact_citation SET chunk_id = NULL WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(LEGACY_CITATION_ID))
                .update();
    }

    /** 回滚删除索引与列后，引用行与正文元数据仍可读。 */
    @Test
    @DisplayName("V13 回滚删除 chunk_id 与索引且保留引用")
    void rollback_dropsChunkIdAndKeepsRows() {
        assertThat(column(rollback, "chunk_id")).isNull();
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.statistics
                         WHERE table_schema = DATABASE() AND table_name = 'artifact_citation'
                           AND index_name = 'idx_artifact_citation_chunk'
                        """)).isZero();
        Map<String, Object> row = rollback.sql("""
                        SELECT source_type, knowledge_document_id, title_snapshot
                          FROM artifact_citation WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(LEGACY_CITATION_ID))
                .query((rs, rowNum) -> {
                    Map<String, Object> mapped = new java.util.HashMap<>();
                    mapped.put("sourceType", rs.getString("source_type"));
                    mapped.put("documentId", rs.getBytes("knowledge_document_id"));
                    mapped.put("title", rs.getString("title_snapshot"));
                    return mapped;
                })
                .single();
        assertThat(row)
                .containsEntry("sourceType", "KNOWLEDGE")
                .containsEntry("documentId", BinaryUuids.toBytes(DOCUMENT_ID))
                .containsEntry("title", "引用标题");
        assertThat(flyway(rollbackUrl, "root").info().current().getVersion().getVersion())
                .isEqualTo("12");
    }

    /** V13 使用安全算法，回滚 SQL 与头注释一致。 */
    @Test
    @DisplayName("V13 DDL 使用 INSTANT/INPLACE 且回滚可核对")
    void migration_ddlUsesSafeAlgorithms() throws Exception {
        String sql = Files.readString(migrationDirectory().resolve("V13__citation_chunk_link.sql"));
        assertThat(sql)
                .contains("ADD COLUMN chunk_id BINARY(16) NULL")
                .contains("ALGORITHM = INSTANT")
                .contains("ADD KEY idx_artifact_citation_chunk (tenant_id, chunk_id)")
                .contains("ALGORITHM = INPLACE")
                .contains("LOCK = NONE")
                .contains("DROP INDEX idx_artifact_citation_chunk")
                .contains("DROP COLUMN chunk_id");
    }

    /**
     * 写入最小产物引用链：租户、账号、个人空间、产物、版本、知识库引用。
     *
     * @param jdbc JDBC 客户端
     */
    private static void seedCitationData(JdbcClient jdbc) {
        jdbc.sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """).param("tenantId", TENANT).param("name", TENANT).update();
        jdbc.sql("""
                        INSERT INTO app_user (
                            tenant_id, id, username, display_name, status, auth_source
                        ) VALUES (:tenantId, :id, 'migration-user', '迁移用户', 'ACTIVE', 'LOCAL')
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(USER_ID))
                .update();
        jdbc.sql("""
                        INSERT INTO workspace (
                            tenant_id, id, kind, name, owner_user_id, personal_owner_user_id,
                            status, created_by, visibility
                        ) VALUES (
                            :tenantId, :id, 'PERSONAL', '迁移空间', :userId, :userId,
                            'ACTIVE', :userId, 'PRIVATE'
                        )
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(WORKSPACE_ID))
                .param("userId", BinaryUuids.toBytes(USER_ID))
                .update();
        jdbc.sql("""
                        INSERT INTO artifact (
                            tenant_id, id, workspace_id, kind, title, status, current_version, created_by
                        ) VALUES (
                            :tenantId, :id, :workspaceId, 'REPORT', '迁移报告', 'SAVED', 1, :userId
                        )
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(ARTIFACT_ID))
                .param("workspaceId", BinaryUuids.toBytes(WORKSPACE_ID))
                .param("userId", BinaryUuids.toBytes(USER_ID))
                .update();
        jdbc.sql("""
                        INSERT INTO artifact_version (
                            tenant_id, id, artifact_id, version, storage_key, content_type,
                            size_bytes, sha256, summary, created_by
                        ) VALUES (
                            :tenantId, :id, :artifactId, 1, :storageKey, 'text/markdown',
                            16, :sha256, '摘要', :userId
                        )
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(VERSION_ID))
                .param("artifactId", BinaryUuids.toBytes(ARTIFACT_ID))
                .param("storageKey", "tenants/acme/artifact/v13.md")
                .param("sha256", "0".repeat(64))
                .param("userId", BinaryUuids.toBytes(USER_ID))
                .update();
        jdbc.sql("""
                        INSERT INTO artifact_citation (
                            tenant_id, id, artifact_version_id, ordinal, source_type,
                            knowledge_document_id, title_snapshot, snippet, locator_type,
                            paragraph_no, created_at
                        ) VALUES (
                            :tenantId, :id, :versionId, 1, 'KNOWLEDGE',
                            :documentId, '引用标题', '引用片段', 'PARAGRAPH',
                            1, NOW(6)
                        )
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(LEGACY_CITATION_ID))
                .param("versionId", BinaryUuids.toBytes(VERSION_ID))
                .param("documentId", BinaryUuids.toBytes(DOCUMENT_ID))
                .update();
    }

    /**
     * 执行 V13 回滚：先删索引、再删列，并删除 history 13 使 schema 版本回到 12。
     *
     * @param jdbc root JDBC 客户端
     */
    private static void rollbackV13Objects(JdbcClient jdbc) {
        jdbc.sql("ALTER TABLE artifact_citation DROP INDEX idx_artifact_citation_chunk, ALGORITHM=INPLACE, LOCK=NONE")
                .update();
        jdbc.sql("ALTER TABLE artifact_citation DROP COLUMN chunk_id, ALGORITHM=INSTANT")
                .update();
        jdbc.sql("DELETE FROM flyway_schema_history WHERE version = '13'").update();
    }

    /**
     * 查询列信息。
     *
     * @param jdbc JDBC 客户端
     * @param column 列名
     * @return 列信息；不存在时为空
     */
    private static ColumnInfo column(JdbcClient jdbc, String column) {
        return jdbc.sql("""
                        SELECT column_type, is_nullable, column_default
                          FROM information_schema.columns
                         WHERE table_schema = DATABASE() AND table_name = 'artifact_citation'
                           AND column_name = :column
                        """)
                .param("column", column)
                .query((rs, rowNum) -> new ColumnInfo(
                        rs.getString("column_type"), rs.getString("is_nullable"), rs.getString("column_default")))
                .optional()
                .orElse(null);
    }

    /**
     * 执行计数查询。
     *
     * @param jdbc JDBC 客户端
     * @param sql SQL
     * @return 计数
     */
    private static long count(JdbcClient jdbc, String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0L : value;
    }

    /**
     * Flyway 迁移到指定版本。
     *
     * @param url JDBC URL
     * @param user 账号
     * @param target 目标版本
     */
    private static void migrate(String url, String user, String target) {
        Flyway.configure()
                .dataSource(url, user, PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    /**
     * 构造不限制目标的 Flyway。
     *
     * @param url JDBC URL
     * @param user 账号
     * @return Flyway
     */
    private static Flyway flyway(String url, String user) {
        return Flyway.configure()
                .dataSource(url, user, PASSWORD)
                .locations("filesystem:" + migrationDirectory())
                .load();
    }

    /**
     * 定位权威迁移目录，避免测试复制一份会漂移的 SQL。
     *
     * @return 迁移目录
     */
    private static Path migrationDirectory() {
        Path moduleRelative = Path.of("src/main/resources/db/migration");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("apps/api/src/main/resources/db/migration");
    }

    /**
     * 创建测试 schema。
     *
     * @param schema schema 名
     */
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

    /**
     * 拼装带测试连接参数的 JDBC URL。
     *
     * @param schema schema 名
     * @return JDBC URL
     */
    private static String jdbcUrl(String schema) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC";
    }

    /**
     * 创建 JDBC 客户端。
     *
     * @param url JDBC URL
     * @param user 账号
     * @return JDBC 客户端
     */
    private static JdbcClient jdbcClient(String url, String user) {
        return JdbcClient.create(new DriverManagerDataSource(url, user, PASSWORD));
    }

    /** 列信息。 */
    private record ColumnInfo(String type, String nullable, String defaultValue) {}
}
