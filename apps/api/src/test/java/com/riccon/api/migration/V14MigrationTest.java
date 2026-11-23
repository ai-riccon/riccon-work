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
 * V14 知识库 FULLTEXT 迁移（M05-B3b，DEC-0038=A）。
 *
 * <p>在一次性 Testcontainers MySQL 8.4.13 上先应用到 V13、写入历史文档/分块，再应用 V14：
 * 验证 ngram FULLTEXT 索引、中文/英文/标题 MATCH、空查询、ACL join 的 FULLTEXT 访问路径与回滚。
 * fresh install 在第三个 schema 上验证 V1–V14 可一次建库。
 */
@Tag("migration")
class V14MigrationTest {

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

    /** fresh install schema。 */
    private static final String FRESH_SCHEMA = "riccon_fresh";

    /** 容器密码（测试占位）。 */
    private static final String PASSWORD = "riccon_test_only";

    /** 租户。 */
    private static final String TENANT = "acme";

    /** 中文文档。 */
    private static final UUID CHINESE_DOCUMENT = UUID.fromString("00000000-0000-7000-8000-000000000501");

    /** 英文文档。 */
    private static final UUID ENGLISH_DOCUMENT = UUID.fromString("00000000-0000-7000-8000-000000000502");

    /** 中文分块。 */
    private static final UUID CHINESE_CHUNK = UUID.fromString("00000000-0000-7000-8000-000000000511");

    /** 英文分块。 */
    private static final UUID ENGLISH_CHUNK = UUID.fromString("00000000-0000-7000-8000-000000000512");

    /** 正向 schema JDBC。 */
    private static JdbcClient forward;

    /** 回滚 schema JDBC。 */
    private static JdbcClient rollback;

    /** fresh schema JDBC。 */
    private static JdbcClient fresh;

    /** 正向 URL。 */
    private static String forwardUrl;

    /** 回滚 URL。 */
    private static String rollbackUrl;

    /** fresh URL。 */
    private static String freshUrl;

    /** 启动容器并准备三个 schema。 */
    @BeforeAll
    static void prepareSchemas() {
        MYSQL.start();
        forwardUrl = jdbcUrl(FORWARD_SCHEMA);
        rollbackUrl = jdbcUrl(ROLLBACK_SCHEMA);
        freshUrl = jdbcUrl(FRESH_SCHEMA);
        createSchema(ROLLBACK_SCHEMA);
        createSchema(FRESH_SCHEMA);

        // 正向：V1–V13 -> 历史文档/分块 -> V14。
        migrate(forwardUrl, "root", "13");
        forward = jdbcClient(forwardUrl, "root");
        seedKnowledge(forward);
        migrate(forwardUrl, "root", "14");

        // 回滚：V1–V14 -> 执行 V14 头注释中的回滚顺序。
        migrate(rollbackUrl, "root", "14");
        rollback = jdbcClient(rollbackUrl, "root");
        seedKnowledge(rollback);
        rollbackV14Objects(rollback);

        // fresh：空库一次到 V14。
        migrate(freshUrl, "root", "14");
        fresh = jdbcClient(freshUrl, "root");
    }

    /** V14 由 Flyway 成功应用，索引为 FULLTEXT ngram。 */
    @Test
    @DisplayName("V14 建立 chunk/content 与 document/title ngram FULLTEXT")
    void migration_createsNgramFulltextIndexes() {
        assertThat(flyway(forwardUrl, "root").info().current().getVersion().getVersion())
                .isEqualTo("14");
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM information_schema.statistics
                         WHERE table_schema = DATABASE() AND table_name = 'knowledge_chunk'
                           AND index_name = 'ft_knowledge_chunk_content' AND index_type = 'FULLTEXT'
                        """)).isEqualTo(1L);
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM information_schema.statistics
                         WHERE table_schema = DATABASE() AND table_name = 'knowledge_document'
                           AND index_name = 'ft_knowledge_document_title' AND index_type = 'FULLTEXT'
                        """)).isEqualTo(1L);
        assertThat(showCreate(forward, "knowledge_chunk"))
                .containsIgnoringCase("FULLTEXT KEY `ft_knowledge_chunk_content`")
                .containsIgnoringCase("WITH PARSER")
                .containsIgnoringCase("`ngram`");
        assertThat(showCreate(forward, "knowledge_document"))
                .containsIgnoringCase("FULLTEXT KEY `ft_knowledge_document_title`")
                .containsIgnoringCase("WITH PARSER")
                .containsIgnoringCase("`ngram`");
        assertThat(count(forward, "SELECT COUNT(*) FROM knowledge_chunk")).isEqualTo(2L);
    }

    /** 中文、英文、标题、空查询与 ACL join 均可用同一 FULLTEXT 路径。 */
    @Test
    @DisplayName("V14 中文/英文/标题 MATCH 与 ACL join")
    void migration_fulltextMatchesChineseEnglishAndTitle() {
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM knowledge_chunk
                         WHERE MATCH(content) AGAINST('市场增长' IN NATURAL LANGUAGE MODE)
                        """)).isGreaterThanOrEqualTo(1L);
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM knowledge_chunk
                         WHERE MATCH(content) AGAINST('market growth' IN NATURAL LANGUAGE MODE)
                        """)).isGreaterThanOrEqualTo(1L);
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM knowledge_chunk
                         WHERE MATCH(content) AGAINST('市场 growth' IN NATURAL LANGUAGE MODE)
                        """)).isGreaterThanOrEqualTo(1L);
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM knowledge_document
                         WHERE MATCH(title) AGAINST('报告' IN NATURAL LANGUAGE MODE)
                        """)).isGreaterThanOrEqualTo(1L);
        // ngram token size=2：单字查询不产生 token，返回空而不是退化为全表 LIKE。
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM knowledge_chunk
                         WHERE MATCH(content) AGAINST('市' IN NATURAL LANGUAGE MODE)
                        """)).isZero();
        assertThat(count(forward, """
                        SELECT COUNT(*) FROM knowledge_chunk
                         WHERE MATCH(content) AGAINST('' IN NATURAL LANGUAGE MODE)
                        """)).isZero();

        // ACL join：正文 MATCH + 文档可见性 + 未删除状态，EXPLAIN 必须使用 FULLTEXT 索引。
        List<Map<String, String>> plan = forward.sql("""
                        EXPLAIN SELECT c.id
                          FROM knowledge_chunk c
                          JOIN knowledge_document d
                            ON d.tenant_id = c.tenant_id AND d.id = c.document_id
                         WHERE c.tenant_id = :tenantId
                           AND d.status = 'INDEXED'
                           AND d.deleted_at IS NULL
                           AND MATCH(c.content) AGAINST('市场增长' IN NATURAL LANGUAGE MODE)
                           AND (d.visibility = 'TENANT'
                                OR d.created_by = :actorId
                                OR EXISTS (SELECT 1 FROM knowledge_document_acl acl
                                            WHERE acl.tenant_id = d.tenant_id
                                              AND acl.document_id = d.id
                                              AND acl.user_id = :actorId))
                         ORDER BY c.document_id, c.ordinal
                         LIMIT 60
                        """)
                .param("tenantId", TENANT)
                .param("actorId", BinaryUuids.toBytes(UUID.randomUUID()))
                .query((rs, rowNum) -> Map.of(
                        "table", rs.getString("table"),
                        "type", rs.getString("type"),
                        "key", String.valueOf(rs.getString("key"))))
                .list();
        assertThat(plan).anySatisfy(row -> {
            assertThat(row.get("table")).isEqualTo("c");
            assertThat(String.valueOf(row.get("type"))).isEqualTo("fulltext");
            assertThat(String.valueOf(row.get("key"))).isEqualTo("ft_knowledge_chunk_content");
        });

        List<Map<String, String>> titlePlan = forward.sql("""
                        EXPLAIN SELECT c.id
                          FROM knowledge_chunk c
                          JOIN knowledge_document d
                            ON d.tenant_id = c.tenant_id AND d.id = c.document_id
                         WHERE c.tenant_id = :tenantId
                           AND d.status = 'INDEXED'
                           AND d.deleted_at IS NULL
                           AND MATCH(d.title) AGAINST('报告' IN NATURAL LANGUAGE MODE)
                           AND d.visibility = 'TENANT'
                         ORDER BY c.document_id, c.ordinal
                         LIMIT 60
                        """)
                .param("tenantId", TENANT)
                .query((rs, rowNum) -> Map.of(
                        "table", rs.getString("table"),
                        "type", rs.getString("type"),
                        "key", String.valueOf(rs.getString("key"))))
                .list();
        assertThat(titlePlan).anySatisfy(row -> {
            assertThat(row.get("table")).isEqualTo("d");
            assertThat(String.valueOf(row.get("type"))).isEqualTo("fulltext");
            assertThat(String.valueOf(row.get("key"))).isEqualTo("ft_knowledge_document_title");
        });
    }

    /** 回滚删除索引并恢复 V13，历史数据不丢失。 */
    @Test
    @DisplayName("V14 回滚删除 FULLTEXT 索引且保留历史数据")
    void rollback_dropsFulltextIndexesAndKeepsRows() {
        assertThat(count(rollback, """
                        SELECT COUNT(*) FROM information_schema.statistics
                         WHERE table_schema = DATABASE()
                           AND index_name IN ('ft_knowledge_chunk_content', 'ft_knowledge_document_title')
                        """)).isZero();
        assertThat(count(rollback, "SELECT COUNT(*) FROM knowledge_chunk")).isEqualTo(2L);
        assertThat(count(rollback, "SELECT COUNT(*) FROM knowledge_document")).isEqualTo(2L);
        assertThat(flyway(rollbackUrl, "root").info().current().getVersion().getVersion())
                .isEqualTo("13");
    }

    /** fresh install V1–V14 与迁移头注释回滚 SQL 可核对。 */
    @Test
    @DisplayName("V14 fresh install 与回滚 SQL")
    void freshInstallAndRollbackSqlAreStable() throws Exception {
        assertThat(flyway(freshUrl, "root").info().current().getVersion().getVersion())
                .isEqualTo("14");
        assertThat(count(fresh, """
                        SELECT COUNT(*) FROM information_schema.statistics
                         WHERE table_schema = DATABASE()
                           AND index_name IN ('ft_knowledge_chunk_content', 'ft_knowledge_document_title')
                        """)).isEqualTo(2L);
        String sql = Files.readString(migrationDirectory().resolve("V14__knowledge_fulltext_search.sql"));
        assertThat(sql)
                .contains("ft_knowledge_chunk_content")
                .contains("ft_knowledge_document_title")
                .contains("WITH PARSER ngram")
                .contains("DROP INDEX ft_knowledge_chunk_content")
                .contains("DROP INDEX ft_knowledge_document_title")
                .contains("ALGORITHM = INPLACE")
                .contains("LOCK = SHARED");
    }

    /**
     * 写入历史知识库文档与分块。
     *
     * @param jdbc JDBC 客户端
     */
    private static void seedKnowledge(JdbcClient jdbc) {
        jdbc.sql("""
                        INSERT INTO tenant (id, name, status, plan, retention_days)
                        VALUES (:tenantId, :name, 'ACTIVE', 'standard', 180)
                        """).param("tenantId", TENANT).param("name", TENANT).update();
        insertDocument(jdbc, CHINESE_DOCUMENT, "市场调研报告");
        insertDocument(jdbc, ENGLISH_DOCUMENT, "Market Report");
        insertChunk(jdbc, CHINESE_CHUNK, CHINESE_DOCUMENT, "市场增长与客户需求旺盛，market growth");
        insertChunk(jdbc, ENGLISH_CHUNK, ENGLISH_DOCUMENT, "market growth and customer demand");
    }

    /**
     * 插入 INDEXED 的 SEED 文档。
     *
     * @param jdbc JDBC 客户端
     * @param documentId 文档 ID
     * @param title 标题
     */
    private static void insertDocument(JdbcClient jdbc, UUID documentId, String title) {
        jdbc.sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :title, 'SEED', NULL, :checksum, 'text/plain', 4,
                            'INDEXED', NULL, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("checksum", "0".repeat(64))
                .update();
    }

    /**
     * 插入分块。
     *
     * @param jdbc JDBC 客户端
     * @param chunkId 分块 ID
     * @param documentId 文档 ID
     * @param content 内容
     */
    private static void insertChunk(JdbcClient jdbc, UUID chunkId, UUID documentId, String content) {
        jdbc.sql("""
                        INSERT INTO knowledge_chunk (
                            tenant_id, id, document_id, ordinal, locator_type, page_no, content, created_at
                        ) VALUES (
                            :tenantId, :id, :documentId, 1, 'PARAGRAPH', NULL, :content, NOW(6)
                        )
                        """)
                .param("tenantId", TENANT)
                .param("id", BinaryUuids.toBytes(chunkId))
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("content", content)
                .update();
    }

    /**
     * 执行 V14 回滚：删索引并删除 history 14。
     *
     * @param jdbc root JDBC 客户端
     */
    private static void rollbackV14Objects(JdbcClient jdbc) {
        jdbc.sql("ALTER TABLE knowledge_chunk DROP INDEX ft_knowledge_chunk_content, ALGORITHM=INPLACE, LOCK=NONE")
                .update();
        jdbc.sql("ALTER TABLE knowledge_document DROP INDEX ft_knowledge_document_title, ALGORITHM=INPLACE, LOCK=NONE")
                .update();
        jdbc.sql("DELETE FROM flyway_schema_history WHERE version = '14'").update();
    }

    /**
     * 读取建表语句。
     *
     * @param jdbc JDBC 客户端
     * @param table 表名
     * @return 建表 SQL
     */
    private static String showCreate(JdbcClient jdbc, String table) {
        return jdbc.sql("SHOW CREATE TABLE " + table)
                .query((rs, rowNum) -> rs.getString(2))
                .single();
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
     * 迁移到目标版本。
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
     * 构造不限目标的 Flyway。
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
     * 定位权威迁移目录。
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
     * 创建 schema。
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
     * 拼装 JDBC URL。
     *
     * @param schema schema 名
     * @return URL
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
}
