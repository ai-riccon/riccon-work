package com.riccon.worker.index.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestDataFactory;
import com.riccon.worker.support.TestDocuments;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 知识库切分、embedding 与租户 OpenSearch 索引集成测试。 */
class KnowledgeIndexServiceIntegrationTest extends WorkerIntegrationTestBase {

    /** 轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** 知识库索引处理器，用于重建测试。 */
    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

    /** 索引客户端，用于派生索引名与别名。 */
    @Autowired
    private OpenSearchIndexClient indexClient;

    /** 测试用 JSON 解析器。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** OpenSearch HTTP 客户端。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** 文件来源文档完成切分、embedding 与索引。 */
    @Test
    void pollOnce_fileDocument_indexesChunksWithLocators() {
        Seed seed = seedFileDocument("tenant-a", "market-report");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(seed.tenant(), seed.documentId())).isEqualTo("INDEXED");
        assertThat(outboxStatus(seed.eventId())).isEqualTo("DONE");
        assertThat(runStatus(seed.runId())).isEqualTo("SUCCEEDED");
        List<KnowledgeChunkRow> chunks = chunks(seed.tenant(), seed.documentId());
        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(KnowledgeChunkRow::pageNo).containsExactly(1, 2);
        assertThat(countDocuments(seed.tenant(), seed.documentId())).isEqualTo(2);

        JsonNode source = firstSource(seed.tenant(), seed.documentId());
        assertThat(source.path("tenant_id").asText()).isEqualTo(seed.tenant());
        assertThat(source.path("document_id").asText())
                .isEqualTo(seed.documentId().toString());
        assertThat(source.path("title").asText()).isEqualTo("market-report");
        assertThat(source.path("locator_type").asText()).isEqualTo("PAGE");
        assertThat(source.path("embedding").isArray()).isTrue();
        assertThat(source.path("embedding")).hasSize(64);
    }

    /** 重复索引同一文档不产生重复分块或重复索引文档。 */
    @Test
    void handle_reindexDocument_rebuildsWithoutDuplicates() {
        Seed seed = seedFileDocument("tenant-a", "rebuild-report");
        assertThat(poller.pollOnce()).isEqualTo(1);
        int chunkCount = chunks(seed.tenant(), seed.documentId()).size();
        long documentCount = countDocuments(seed.tenant(), seed.documentId());

        ClaimedOutboxEvent duplicate = new ClaimedOutboxEvent(
                seed.eventId(),
                seed.tenant(),
                seed.runId(),
                EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                1,
                "knowledge:" + seed.documentId() + ":1",
                seed.payload(),
                2,
                Instant.now(),
                Instant.now().plusSeconds(30));
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(seed.tenant()))) {
            knowledgeIndexService.handle(duplicate);
        }

        assertThat(chunks(seed.tenant(), seed.documentId())).hasSize(chunkCount);
        assertThat(countDocuments(seed.tenant(), seed.documentId())).isEqualTo(documentCount);
        assertThat(documentStatus(seed.tenant(), seed.documentId())).isEqualTo("INDEXED");
    }

    /** 无来源文件的演示语料从 MySQL 既有分块重建。 */
    @Test
    void pollOnce_seedDocument_reindexesExistingChunks() {
        String tenant = "tenant-a";
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID documentId = data.createKnowledgeDocument(tenant, "seed-corpus", null, userId);
        data.createKnowledgeChunk(tenant, documentId, 1, "seed paragraph one", 1);
        data.createKnowledgeChunk(tenant, documentId, 2, "seed paragraph two", 2);
        TestDataFactory.PublishedEvent event = data.publishKnowledgeIndex(tenant, documentId, null, userId, 1);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, documentId)).isEqualTo("INDEXED");
        assertThat(chunks(tenant, documentId)).hasSize(2);
        assertThat(countDocuments(tenant, documentId)).isEqualTo(2);
        JsonNode source = firstSource(tenant, documentId);
        assertThat(source.path("locator_type").asText()).isEqualTo("PARAGRAPH");
        assertThat(source.path("paragraph_no").asInt()).isEqualTo(1);
    }

    /** 缺少来源文件且无既有分块时保留失败原因。 */
    @Test
    void pollOnce_missingSource_marksFailed() {
        String tenant = "tenant-a";
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID documentId = data.createKnowledgeDocument(tenant, "empty-doc", null, userId);
        TestDataFactory.PublishedEvent event = data.publishKnowledgeIndex(tenant, documentId, null, userId, 1);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, documentId)).isEqualTo("FAILED");
        assertThat(documentErrorCode(tenant, documentId)).isEqualTo("RICCON-INDEX-422");
        assertThat(outboxStatus(event.eventId())).isEqualTo("DEAD");
        assertThat(runStatus(event.runId())).isEqualTo("FAILED");
    }

    /** 已软删除文档跳过索引，事件正常完成。 */
    @Test
    void pollOnce_deletedDocument_skips() {
        String tenant = "tenant-a";
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID documentId = data.createKnowledgeDocument(tenant, "deleted-doc", null, userId);
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET status = 'DELETED', deleted_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .update();
        TestDataFactory.PublishedEvent event = data.publishKnowledgeIndex(tenant, documentId, null, userId, 1);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, documentId)).isEqualTo("DELETED");
        assertThat(outboxStatus(event.eventId())).isEqualTo("DONE");
        assertThat(runStatus(event.runId())).isEqualTo("SUCCEEDED");
    }

    /** 事件 payload 引用其他租户 fileId 时必须拒绝，不得读取对方文件。 */
    @Test
    void pollOnce_crossTenantFile_doesNotLeak() {
        Seed tenantB = seedFileDocument("tenant-b", "other-report", false);
        String tenantA = "tenant-a";
        data.createTenant(tenantA);
        UUID userA = data.createUser(tenantA);
        UUID workspaceA = data.createWorkspace(tenantA, userA);
        UUID fileA = data.createFile(tenantA, workspaceA, userA, FileFormat.PDF, TestDocuments.pdf(1));
        UUID documentA = data.createKnowledgeDocument(tenantA, "leak-attempt", fileA, userA);
        TestDataFactory.PublishedEvent event =
                data.publishKnowledgeIndex(tenantA, documentA, tenantB.fileId(), userA, 1);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenantA, documentA)).isEqualTo("FAILED");
        assertThat(documentErrorCode(tenantA, documentA)).isEqualTo("RICCON-WORKER-422");
        assertThat(chunks(tenantA, documentA)).isEmpty();
        assertThat(outboxStatus(event.eventId())).isEqualTo("DEAD");
        assertThat(documentStatus(tenantB.tenant(), tenantB.documentId())).isEqualTo("PENDING");
    }

    /** 索引映射包含 knn_vector 维度，与 EmbeddingModel 一致。 */
    @Test
    void ensureIndex_mappingUsesEmbeddingDimensions() {
        Seed seed = seedFileDocument("tenant-a", "mapping-report");
        assertThat(poller.pollOnce()).isEqualTo(1);

        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        openSearchEndpoint() + "/" + indexClient.indexName(TenantId.of(seed.tenant())) + "/_mapping"))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode dimension = root.path(indexClient.indexName(TenantId.of(seed.tenant())))
                    .path("mappings")
                    .path("properties")
                    .path("embedding")
                    .path("dimension");
            assertThat(dimension.asInt()).isEqualTo(64);
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opensearch mapping query failed", ex);
        }
    }

    /**
     * 创建带来源文件的文档与索引事件。
     *
     * @param tenant 租户
     * @param title 标题
     * @return 种子
     */
    private Seed seedFileDocument(String tenant, String title) {
        return seedFileDocument(tenant, title, true);
    }

    /**
     * 创建带来源文件的文档与可选索引事件。
     *
     * @param tenant 租户
     * @param title 标题
     * @param publish 是否发布索引事件
     * @return 种子
     */
    private Seed seedFileDocument(String tenant, String title, boolean publish) {
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, FileFormat.PDF, TestDocuments.pdf(2));
        UUID documentId = data.createKnowledgeDocument(tenant, title, fileId, userId);
        if (!publish) {
            return new Seed(tenant, documentId, fileId, null, null, null);
        }
        TestDataFactory.PublishedEvent event = data.publishKnowledgeIndex(tenant, documentId, fileId, userId, 1);
        return new Seed(tenant, documentId, fileId, event.runId(), event.eventId(), event.payload());
    }

    /**
     * 统计文档在租户别名下的索引文档数。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 数量
     */
    private long countDocuments(String tenant, UUID documentId) {
        Map<String, Object> query = Map.of(
                "query",
                Map.of(
                        "bool",
                        Map.of(
                                "filter",
                                List.of(
                                        Map.of("term", Map.of("tenant_id", tenant)),
                                        Map.of("term", Map.of("document_id", documentId.toString()))))));
        JsonNode response = postJson("/" + indexClient.aliasName(TenantId.of(tenant)) + "/_count", json(query));
        return response.path("count").asLong();
    }

    /**
     * 返回文档的第一条索引源。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return _source 节点
     */
    private JsonNode firstSource(String tenant, UUID documentId) {
        Map<String, Object> request =
                Map.of("size", 1, "query", Map.of("term", Map.of("document_id", documentId.toString())));
        JsonNode response = postJson("/" + indexClient.aliasName(TenantId.of(tenant)) + "/_search", json(request));
        return response.path("hits").path("hits").get(0).path("_source");
    }

    /**
     * 发送 JSON POST 并返回解析结果。
     *
     * @param path 路径
     * @param body JSON 请求体
     * @return JSON 响应
     */
    private JsonNode postJson(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(openSearchEndpoint() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("opensearch query failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opensearch query failed", ex);
        }
    }

    /**
     * 查询文档状态。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 状态
     */
    private String documentStatus(String tenant, UUID documentId) {
        return jdbcClient
                .sql("SELECT status FROM knowledge_document WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .query(String.class)
                .single();
    }

    /**
     * 查询文档错误码。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 错误码
     */
    private String documentErrorCode(String tenant, UUID documentId) {
        return jdbcClient
                .sql("SELECT error_code FROM knowledge_document WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .query(String.class)
                .optional()
                .orElse("");
    }

    /**
     * 查询知识库分块。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 分块
     */
    private List<KnowledgeChunkRow> chunks(String tenant, UUID documentId) {
        return jdbcClient
                .sql("""
                        SELECT locator_type, page_no, paragraph_no, content
                          FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                         ORDER BY ordinal
                        """)
                .param("tenantId", tenant)
                .param("documentId", UuidBinary.toBytes(documentId))
                .query((rs, rowNum) -> new KnowledgeChunkRow(
                        rs.getString("locator_type"),
                        (Integer) rs.getObject("page_no"),
                        (Integer) rs.getObject("paragraph_no"),
                        rs.getString("content")))
                .list();
    }

    /**
     * 查询 outbox 状态。
     *
     * @param eventId 事件 ID
     * @return 状态
     */
    private String outboxStatus(UUID eventId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run_outbox WHERE id = :id")
                .param("id", UuidBinary.toBytes(eventId))
                .query(String.class)
                .single();
    }

    /**
     * 查询运行状态。
     *
     * @param runId 运行 ID
     * @return 状态
     */
    private String runStatus(UUID runId) {
        return jdbcClient
                .sql("SELECT status FROM agent_run WHERE id = :id")
                .param("id", UuidBinary.toBytes(runId))
                .query(String.class)
                .single();
    }

    /**
     * 序列化 JSON。
     *
     * @param value 对象
     * @return JSON
     */
    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /**
     * 测试种子。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param fileId 文件 ID
     * @param runId 运行 ID
     * @param eventId 事件 ID
     * @param payload 事件 payload
     */
    private record Seed(String tenant, UUID documentId, UUID fileId, UUID runId, UUID eventId, String payload) {}

    /**
     * 知识库分块行。
     *
     * @param locatorType 定位类型
     * @param pageNo 页码
     * @param paragraphNo 段落号
     * @param content 内容
     */
    private record KnowledgeChunkRow(String locatorType, Integer pageNo, Integer paragraphNo, String content) {}
}
