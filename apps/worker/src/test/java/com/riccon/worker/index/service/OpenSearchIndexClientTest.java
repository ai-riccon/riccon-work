package com.riccon.worker.index.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.index.domain.HashingEmbeddingModel;
import com.riccon.worker.index.domain.IndexException;
import com.riccon.worker.index.domain.KnowledgeChunkRecord;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** OpenSearch 租户索引写入/重建集成测试。 */
class OpenSearchIndexClientTest extends WorkerIntegrationTestBase {

    /** 被测客户端。 */
    @Autowired
    private OpenSearchIndexClient indexClient;

    /** JSON 解析器。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 确定性向量模型。 */
    private final HashingEmbeddingModel embeddingModel = new HashingEmbeddingModel(64);

    /** 测试 HTTP 客户端。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** 确保索引与别名幂等创建。 */
    @Test
    void ensureIndex_createsAliasIdempotently() {
        TenantId tenant = TenantId.of("tenant-os-a");

        indexClient.ensureIndex(tenant, 64);
        indexClient.ensureIndex(tenant, 64);

        assertThat(head("/_alias/" + indexClient.aliasName(tenant))).isEqualTo(200);
        assertThat(head("/" + indexClient.indexName(tenant))).isEqualTo(200);
    }

    /** 重建只影响目标文档，不影响同租户其他文档。 */
    @Test
    void replaceDocument_rebuildsOnlyTargetDocument() {
        TenantId tenant = TenantId.of("tenant-os-b");
        UUID documentOne = UUID.randomUUID();
        UUID documentTwo = UUID.randomUUID();
        KnowledgeChunkRecord chunkOne = chunk("first chunk", LocatorType.PAGE, 1, null, null, null);
        KnowledgeChunkRecord chunkTwo = chunk("second chunk", LocatorType.PAGE, 2, null, null, null);
        KnowledgeChunkRecord other = chunk("other document", LocatorType.SHEET, null, "Sheet1", "A1:B2", null);

        indexClient.replaceDocument(tenant, documentOne, "Doc One", List.of(chunkOne, chunkTwo), embeddings(2));
        indexClient.replaceDocument(tenant, documentTwo, "Doc Two", List.of(other), embeddings(1));
        assertThat(count(tenant, documentOne)).isEqualTo(2);
        assertThat(count(tenant, documentTwo)).isEqualTo(1);

        KnowledgeChunkRecord rebuilt = chunk("rebuilt chunk", LocatorType.PARAGRAPH, null, null, null, 3);
        indexClient.replaceDocument(tenant, documentOne, "Doc One", List.of(rebuilt), embeddings(1));

        assertThat(count(tenant, documentOne)).isEqualTo(1);
        assertThat(count(tenant, documentTwo)).isEqualTo(1);
        JsonNode source = firstSource(tenant, documentOne);
        assertThat(source.path("tenant_id").asText()).isEqualTo(tenant.value());
        assertThat(source.path("locator_type").asText()).isEqualTo("PARAGRAPH");
        assertThat(source.path("paragraph_no").asInt()).isEqualTo(3);
    }

    /** 空分块重建会清理该文档旧索引。 */
    @Test
    void replaceDocument_emptyChunks_deletesExisting() {
        TenantId tenant = TenantId.of("tenant-os-c");
        UUID documentId = UUID.randomUUID();
        indexClient.replaceDocument(
                tenant,
                documentId,
                "Doc",
                List.of(chunk("chunk", LocatorType.PAGE, 1, null, null, null)),
                embeddings(1));
        assertThat(count(tenant, documentId)).isEqualTo(1);

        indexClient.replaceDocument(tenant, documentId, "Doc", List.of(), List.of());

        assertThat(count(tenant, documentId)).isZero();
    }

    /** 大写租户不能归一到小写索引名，否则存在跨租户碰撞风险。 */
    @Test
    void ensureIndex_uppercaseTenant_rejected() {
        assertThatThrownBy(() -> indexClient.ensureIndex(TenantId.of("TenantA"), 64))
                .isInstanceOf(IndexException.class)
                .satisfies(ex -> assertThat(((IndexException) ex).retryable()).isFalse());
    }

    /** 分块与向量数量必须一致。 */
    @Test
    void replaceDocument_embeddingCountMismatch_rejected() {
        TenantId tenant = TenantId.of("tenant-os-d");
        assertThatThrownBy(() -> indexClient.replaceDocument(
                        tenant,
                        UUID.randomUUID(),
                        "Doc",
                        List.of(chunk("chunk", LocatorType.PAGE, 1, null, null, null)),
                        List.of()))
                .isInstanceOf(IndexException.class)
                .hasMessageContaining("embedding count");
    }

    /**
     * 统计文档索引数。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 数量
     */
    private long count(TenantId tenant, UUID documentId) {
        Map<String, Object> query = Map.of("query", Map.of("term", Map.of("document_id", documentId.toString())));
        JsonNode response = postJson("/" + indexClient.aliasName(tenant) + "/_count", json(query));
        return response.path("count").asLong();
    }

    /**
     * 返回文档第一条索引源。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return _source
     */
    private JsonNode firstSource(TenantId tenant, UUID documentId) {
        Map<String, Object> query =
                Map.of("size", 1, "query", Map.of("term", Map.of("document_id", documentId.toString())));
        JsonNode response = postJson("/" + indexClient.aliasName(tenant) + "/_search", json(query));
        return response.path("hits").path("hits").get(0).path("_source");
    }

    /**
     * 发送 JSON POST。
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
                throw new IllegalStateException("opensearch request failed: " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opensearch request failed", ex);
        }
    }

    /**
     * 发送 HEAD。
     *
     * @param path 路径
     * @return 状态码
     */
    private int head(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(openSearchEndpoint() + path))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return httpClient
                    .send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("opensearch head failed", ex);
        }
    }

    /**
     * 构造分块记录。
     *
     * @param content 内容
     * @param locator 定位
     * @param pageNo 页码
     * @param sheetName 工作表
     * @param cellRange 范围
     * @param paragraphNo 段落
     * @return 分块
     */
    private static KnowledgeChunkRecord chunk(
            String content,
            LocatorType locator,
            Integer pageNo,
            String sheetName,
            String cellRange,
            Integer paragraphNo) {
        return new KnowledgeChunkRecord(
                UUID.randomUUID(), content, locator, pageNo, sheetName, cellRange, paragraphNo, 2);
    }

    /**
     * 生成指定数量的向量。
     *
     * @param count 数量
     * @return 向量列表
     */
    private List<float[]> embeddings(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> embeddingModel.embed("text-" + index))
                .toList();
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
}
