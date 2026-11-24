package com.riccon.worker.index.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.config.OpenSearchProperties;
import com.riccon.worker.index.domain.EmbeddingModel;
import com.riccon.worker.index.repository.KnowledgeRepository;
import com.riccon.worker.parse.repository.FileRepository;
import com.riccon.worker.parse.service.DocumentParserRegistry;
import com.riccon.worker.parse.service.FileContentExtractor;
import com.riccon.worker.parse.service.S3FileContentReader;
import com.riccon.worker.parse.service.TextChunker;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.service.EventPayloadParser;
import com.riccon.worker.run.service.OutboxPoller;
import com.riccon.worker.support.TestDocuments;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * IT-KB-113：索引 v2 mapping、重灌幂等与别名切换/回切（ADR-0023 §7.1）。
 *
 * <p>测试用真实 MySQL + OpenSearch：v1 索引由默认 Worker 写入；v2 由显式使用 v2 配置的
 * {@link OpenSearchIndexClient} 与 {@link KnowledgeIndexService} 重灌，模拟灰度时
 * {@code RICCON_OPENSEARCH_INDEX_VERSION=v2} 的 Worker。断言：
 *
 * <ul>
 *   <li>v2 mapping 含 ACL/解析/OCR 区域字段，{@code dynamic: strict}；
 *   <li>v2 重灌后分块数与分块 ID 与 MySQL 完全一致，重复重灌不产生重复；
 *   <li>别名在重灌完成前保持 v1，切换后唯一指向 v2，可回切 v1。
 * </ul>
 */
class KnowledgeIndexV2MigrationTest extends WorkerIntegrationTestBase {

    /** 轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** 默认（v1）索引客户端。 */
    @Autowired
    private OpenSearchIndexClient indexClient;

    /** OpenSearch 配置，用于派生 v2 客户端。 */
    @Autowired
    private OpenSearchProperties properties;

    /** 知识库仓库，用于构造 v2 索引服务。 */
    @Autowired
    private KnowledgeRepository knowledgeRepository;

    /** 文件仓库。 */
    @Autowired
    private FileRepository fileRepository;

    /** 内容抽取器。 */
    @Autowired
    private FileContentExtractor contentExtractor;

    /** 平台对象读取器；v2 服务构造需要。 */
    @Autowired
    private S3FileContentReader contentReader;

    /** 解析器注册表；v2 服务构造需要。 */
    @Autowired
    private DocumentParserRegistry parserRegistry;

    /** 文本切分器。 */
    @Autowired
    private TextChunker textChunker;

    /** 向量模型。 */
    @Autowired
    private EmbeddingModel embeddingModel;

    /** payload 解析器。 */
    @Autowired
    private EventPayloadParser payloadParser;

    /** HTTP 客户端。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** pollOnce 跨租户领取事件；每个测试先清空残留 outbox。 */
    @BeforeEach
    void clearStaleOutbox() {
        clearOutbox();
    }

    /** v2 mapping/重灌/别名切换与回切。 */
    @Test
    void itKb113_mappingV2AliasSwitchAndReindex() {
        String tenant = uniqueTenant("tenant-a");
        TenantId tenantId = TenantId.of(tenant);
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID workspaceId = data.createWorkspace(tenant, userId);
        UUID fileId = data.createFile(tenant, workspaceId, userId, FileFormat.PDF, TestDocuments.pdf(2));
        UUID documentId = data.createKnowledgeDocument(tenant, "v2-report", fileId, userId);
        data.publishKnowledgeIndex(tenant, documentId, fileId, userId, 1);

        // 1) v1 写入：别名指向 v1，分块 2 条。
        assertThat(poller.pollOnce()).isEqualTo(1);
        long chunkCount = countChunks(tenant, documentId);
        assertThat(chunkCount).isEqualTo(2);
        assertThat(indexClient.aliasTargets(tenantId)).containsExactly(indexClient.indexName(tenantId));

        // 2) v2 建索引不改变已有别名；mapping 含 M05 字段。
        OpenSearchIndexClient v2Client = v2Client();
        v2Client.ensureIndex(tenantId, embeddingModel.dimensions());
        assertThat(head(v2Client.indexName(tenantId))).isEqualTo(200);
        assertThat(indexClient.aliasTargets(tenantId)).containsExactly(indexClient.indexName(tenantId));
        assertMappingV2(v2Client.indexName(tenantId));

        // 3) 用 v2 服务重灌（模拟 Worker index-version=v2），分块 ID 与 MySQL 一致。
        KnowledgeIndexService v2Service = new KnowledgeIndexService(
                knowledgeRepository,
                fileRepository,
                contentExtractor,
                contentReader,
                parserRegistry,
                textChunker,
                embeddingModel,
                v2Client,
                payloadParser);
        handleFull(v2Service, tenant, documentId, fileId, userId, 2);
        assertThat(countIndexed(v2Client.indexName(tenantId), tenant)).isEqualTo(chunkCount);
        assertThat(indexedChunkIds(v2Client.indexName(tenantId), tenant))
                .containsExactlyInAnyOrderElementsOf(mysqlChunkIds(tenant, documentId));
        assertThat(indexClient.aliasTargets(tenantId)).containsExactly(indexClient.indexName(tenantId));

        // 4) 别名原子切换 v2，再回切 v1；v1 保留。
        assertThat(v2Client.switchAlias(tenantId, "v2")).containsExactly(indexClient.indexName(tenantId));
        assertThat(v2Client.aliasTargets(tenantId)).containsExactly(v2Client.indexName(tenantId));
        assertThat(v2Client.switchAlias(tenantId, "v1")).containsExactly(v2Client.indexName(tenantId));
        assertThat(v2Client.aliasTargets(tenantId)).containsExactly(indexClient.indexName(tenantId));

        // 5) 重复重灌：MySQL 分块数不变，v2 无重复文档，ID 仍与 MySQL 一致。
        handleFull(v2Service, tenant, documentId, fileId, userId, 3);
        assertThat(countChunks(tenant, documentId)).isEqualTo(chunkCount);
        JsonNode search = searchDocuments(v2Client.indexName(tenantId), tenant);
        assertThat(search.path("hits").path("total").path("value").asLong()).isEqualTo(chunkCount);
        Set<String> ids = new HashSet<>();
        for (JsonNode hit : search.path("hits").path("hits")) {
            ids.add(hit.path("_id").asText());
        }
        assertThat(ids).hasSize((int) chunkCount);
    }

    /**
     * 创建 v2 客户端（沿用测试端点/前缀，只把版本改为 v2）。
     *
     * @return v2 客户端
     */
    private OpenSearchIndexClient v2Client() {
        OpenSearchProperties v2 = new OpenSearchProperties(
                properties.url(),
                properties.username(),
                properties.password(),
                properties.connectTimeout(),
                properties.requestTimeout(),
                properties.indexPrefix(),
                "v2");
        return new OpenSearchIndexClient(v2, objectMapper);
    }

    /**
     * 发布并执行一次 FULL 重灌事件（绕过 poller，直接调用指定版本的索引服务）。
     *
     * @param service 索引服务
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param fileId 文件 ID
     * @param userId 发起人
     * @param attempt 尝试号
     */
    private void handleFull(
            KnowledgeIndexService service, String tenant, UUID documentId, UUID fileId, UUID userId, int attempt) {
        UUID runId =
                data.createRun(tenant, com.riccon.platform.domain.research.RunKind.KNOWLEDGE_INDEX, null, userId, 3);
        String payload = data.knowledgeIndexPayload(documentId, fileId, attempt);
        UUID eventId = UUID.randomUUID();
        ClaimedOutboxEvent event = new ClaimedOutboxEvent(
                eventId,
                tenant,
                runId,
                EventTypes.KNOWLEDGE_INDEX_REQUESTED,
                1,
                "doc:" + documentId + ":index:" + attempt,
                payload,
                attempt,
                Instant.now(),
                Instant.now().plusSeconds(60));
        try (TenantContext.TenantScope ignored = TenantContext.open(TenantId.of(tenant))) {
            service.handle(event);
        }
    }

    /**
     * 断言 v2 mapping 字段与 dynamic strict。
     *
     * @param index 索引名
     */
    private void assertMappingV2(String index) {
        JsonNode root = getJson("/" + index + "/_mapping");
        JsonNode mapping = root.path(index).path("mappings");
        assertThat(mapping.path("dynamic").asText()).isEqualTo("strict");
        JsonNode properties = mapping.path("properties");
        for (String field : List.of(
                "visibility",
                "department_id",
                "acl_version",
                "source_type",
                "connector_id",
                "parse_engine",
                "ocr_confidence",
                "bbox_x",
                "bbox_y",
                "bbox_w",
                "bbox_h")) {
            assertThat(properties.has(field)).as("mapping field %s", field).isTrue();
        }
    }

    /**
     * 统计 MySQL 分块数。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 数量
     */
    private long countChunks(String tenant, UUID documentId) {
        return jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                        """)
                .param("tenantId", tenant)
                .param("documentId", UuidBinary.toBytes(documentId))
                .query(Long.class)
                .single();
    }

    /**
     * 查询 MySQL 分块 ID。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 分块 ID
     */
    private List<String> mysqlChunkIds(String tenant, UUID documentId) {
        return jdbcClient
                .sql("""
                        SELECT id FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                        """)
                .param("tenantId", tenant)
                .param("documentId", UuidBinary.toBytes(documentId))
                .query((rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("id")).toString())
                .list();
    }

    /**
     * 查询索引分块 ID。
     *
     * @param index 索引名
     * @param tenant 租户
     * @return 分块 ID
     */
    private List<String> indexedChunkIds(String index, String tenant) {
        JsonNode search = searchDocuments(index, tenant);
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (JsonNode hit : search.path("hits").path("hits")) {
            ids.add(hit.path("_id").asText());
        }
        return ids;
    }

    /**
     * 查询租户全部索引文档（最多 10000）。
     *
     * @param index 索引名
     * @param tenant 租户
     * @return _search 响应
     */
    private JsonNode searchDocuments(String index, String tenant) {
        Map<String, Object> request =
                Map.of("size", 10000, "_source", false, "query", Map.of("term", Map.of("tenant_id", tenant)));
        return postJson("/" + index + "/_search", objectMapper.writeValueAsString(request));
    }

    /**
     * 统计索引文档数。
     *
     * @param index 索引名
     * @param tenant 租户
     * @return 数量
     */
    private long countIndexed(String index, String tenant) {
        Map<String, Object> request = Map.of("query", Map.of("term", Map.of("tenant_id", tenant)));
        JsonNode response = postJson("/" + index + "/_count", objectMapper.writeValueAsString(request));
        return response.path("count").asLong();
    }

    /**
     * 发送 GET JSON 请求。
     *
     * @param path 路径
     * @return 响应 JSON
     */
    private JsonNode getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(openSearchEndpoint() + path))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("opensearch GET failed: " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opensearch GET failed", ex);
        }
    }

    /**
     * 发送 JSON POST 请求。
     *
     * @param path 路径
     * @param body JSON
     * @return 响应 JSON
     */
    private JsonNode postJson(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(openSearchEndpoint() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("opensearch POST failed: " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opensearch POST failed", ex);
        }
    }

    /**
     * HEAD 索引/别名。
     *
     * @param path 路径
     * @return 状态码
     */
    private int head(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(openSearchEndpoint() + "/" + path))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return httpClient
                    .send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opensearch HEAD failed", ex);
        }
    }
}
