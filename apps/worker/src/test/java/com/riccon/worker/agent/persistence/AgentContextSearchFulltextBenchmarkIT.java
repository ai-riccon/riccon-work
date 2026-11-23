package com.riccon.worker.agent.persistence;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M05-B3b FULLTEXT 微基准（不进入默认套件）。
 *
 * <p>类名以 IT 结尾，默认 surefire 不收集；用 {@code -Dtest=AgentContextSearchFulltextBenchmarkIT} 手动运行：
 * 构造 5,000 文档 / 100,000 分块，
 * 测量中文/英文检索 p50/p95 与 {@code EXPLAIN ANALYZE} 访问路径。目标 p95 ≤500ms 由 B11 在
 * 真实/空闲宿主复测判定；本测试只记录实测，不通过阈值放宽 AC。
 */
@Tag("perf")
class AgentContextSearchFulltextBenchmarkIT extends WorkerIntegrationTestBase {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(AgentContextSearchFulltextBenchmarkIT.class);

    /** 文档数。 */
    private static final int DOCUMENT_COUNT = 5_000;

    /** 每文档分块数。 */
    private static final int CHUNKS_PER_DOCUMENT = 20;

    /** 测量次数。 */
    private static final int ITERATIONS = 30;

    /** 被测检索。 */
    @Autowired
    private AgentContextJdbc contextJdbc;

    /** 100k 分块微基准。 */
    @Test
    @DisplayName("FULLTEXT 100k 分块中文/英文 p50/p95")
    void fulltextSearch_100kChunks_recordsLatency() throws Exception {
        String tenant = uniqueTenant("ft-perf");
        data.createTenant(tenant);
        UUID user = data.createUser(tenant);
        List<UUID> documentIds = seedDocuments(tenant, user);
        seedChunks(tenant, documentIds);
        TenantId tenantId = TenantId.of(tenant);

        // 预热 5 次，排除连接/缓存冷启动。
        for (int index = 0; index < 5; index++) {
            TenantContext.callWithTenant(tenantId, () -> contextJdbc.search(tenantId, user, false, "市场增长", 60, 5));
        }
        long[] chinese = measure(tenantId, user, "市场增长");
        long[] english = measure(tenantId, user, "market growth");

        logLatency("chinese", chinese);
        logLatency("english", english);
        String explainPlan = explainContentQuery(tenant, user, "市场增长");
        LOG.info(
                "PERF fulltext documents={} chunks={} chineseP50={}ms chineseP95={}ms englishP50={}ms englishP95={}ms explain={}",
                DOCUMENT_COUNT,
                DOCUMENT_COUNT * CHUNKS_PER_DOCUMENT,
                percentile(chinese, 50),
                percentile(chinese, 95),
                percentile(english, 50),
                percentile(english, 95),
                explainPlan);
    }

    /**
     * 测量查询耗时。
     *
     * @param tenantId 租户
     * @param user 用户
     * @param query 查询
     * @return 每次耗时（纳秒）
     */
    private long[] measure(TenantId tenantId, UUID user, String query) {
        long[] samples = new long[ITERATIONS];
        for (int index = 0; index < ITERATIONS; index++) {
            long start = System.nanoTime();
            TenantContext.callWithTenant(tenantId, () -> contextJdbc.search(tenantId, user, false, query, 60, 5));
            samples[index] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return samples;
    }

    /**
     * 记录百分位。
     *
     * @param language 语言
     * @param samples 样本
     */
    private static void logLatency(String language, long[] samples) {
        LOG.info(
                "PERF fulltext {} p50={}ms p95={}ms p99={}ms",
                language,
                percentile(samples, 50),
                percentile(samples, 95),
                percentile(samples, 99));
    }

    /**
     * 计算百分位耗时（毫秒）。
     *
     * @param samples 已排序样本
     * @param percent 百分位
     * @return 毫秒
     */
    private static long percentile(long[] samples, int percent) {
        int index = (int) Math.ceil(percent / 100.0 * samples.length) - 1;
        return Duration.ofNanos(samples[Math.max(0, Math.min(samples.length - 1, index))])
                .toMillis();
    }

    /**
     * 对正文 FULLTEXT + ACL join 执行 EXPLAIN ANALYZE，返回首行计划文本。
     *
     * @param tenant 租户
     * @param user 用户
     * @param query 查询
     * @return 首行计划文本
     */
    private String explainContentQuery(String tenant, UUID user, String query) {
        List<String> plan = jdbcClient
                .sql("""
                        EXPLAIN ANALYZE
                        SELECT c.id
                          FROM knowledge_chunk c
                          JOIN knowledge_document d
                            ON d.tenant_id = c.tenant_id AND d.id = c.document_id
                         WHERE c.tenant_id = :tenantId
                           AND d.status = 'INDEXED'
                           AND d.deleted_at IS NULL
                           AND MATCH(c.content) AGAINST(:query IN NATURAL LANGUAGE MODE)
                           AND (d.visibility = 'TENANT'
                                OR d.created_by = :actorId
                                OR EXISTS (SELECT 1 FROM knowledge_document_acl acl
                                            WHERE acl.tenant_id = d.tenant_id
                                              AND acl.document_id = d.id
                                              AND acl.user_id = :actorId))
                         ORDER BY MATCH(c.content) AGAINST(:query IN NATURAL LANGUAGE MODE) DESC,
                                  c.document_id, c.ordinal
                         LIMIT 60
                        """)
                .param("tenantId", tenant)
                .param("query", query)
                .param("actorId", UuidBinary.toBytes(user))
                .query(String.class)
                .list();
        for (String line : plan) {
            LOG.info("PERF fulltext explain {}", line);
        }
        return plan.isEmpty() ? "" : plan.get(0);
    }

    /**
     * 批量插入文档。
     *
     * @param tenant 租户
     * @param user 创建人
     * @return 文档 ID
     * @throws SQLException 插入失败
     */
    private List<UUID> seedDocuments(String tenant, UUID user) throws SQLException {
        List<UUID> documentIds = new ArrayList<>(DOCUMENT_COUNT);
        String sql = """
                INSERT INTO knowledge_document (
                    tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                    status, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, 'SEED', NULL, ?, 'text/plain', 4, 'INDEXED', ?, NOW(6), NOW(6))
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (int index = 0; index < DOCUMENT_COUNT; index++) {
                UUID documentId = UuidBinary.newV7();
                documentIds.add(documentId);
                statement.setString(1, tenant);
                statement.setBytes(2, UuidBinary.toBytes(documentId));
                statement.setString(3, index % 50 == 0 ? "市场调研报告-" + index : "普通文档-" + index);
                statement.setString(4, "0".repeat(64));
                statement.setBytes(5, UuidBinary.toBytes(user));
                statement.addBatch();
                if (index % 1000 == 999) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
            connection.commit();
        }
        return documentIds;
    }

    /**
     * 批量插入分块。
     *
     * @param tenant 租户
     * @param documentIds 文档 ID
     * @throws SQLException 插入失败
     */
    private void seedChunks(String tenant, List<UUID> documentIds) throws SQLException {
        String sql = """
                INSERT INTO knowledge_chunk (
                    tenant_id, id, document_id, ordinal, locator_type, page_no, content, created_at
                ) VALUES (?, ?, ?, ?, 'PARAGRAPH', NULL, ?, NOW(6))
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            int inserted = 0;
            for (UUID documentId : documentIds) {
                for (int ordinal = 1; ordinal <= CHUNKS_PER_DOCUMENT; ordinal++) {
                    String content = switch ((ordinal + documentId.hashCode()) % 100) {
                        case 0 -> "市场增长与客户需求旺盛 market growth";
                        case 1 -> "市场增长数据与客户需求";
                        case 2 -> "market growth and customer demand";
                        default -> "普通办公文档内容 " + ordinal;
                    };
                    statement.setString(1, tenant);
                    statement.setBytes(2, UuidBinary.toBytes(UuidBinary.newV7()));
                    statement.setBytes(3, UuidBinary.toBytes(documentId));
                    statement.setInt(4, ordinal);
                    statement.setString(5, content);
                    statement.addBatch();
                    inserted++;
                    if (inserted % 1000 == 0) {
                        statement.executeBatch();
                    }
                }
            }
            statement.executeBatch();
            connection.commit();
        }
    }
}
