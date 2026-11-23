package com.riccon.worker.index.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.worker.WorkerIntegrationTestBase;
import com.riccon.worker.persistence.UuidBinary;
import com.riccon.worker.run.service.OutboxPoller;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 索引模式 FULL/ACL_ONLY/DELETE 的集成行为（ADR-0023 §5.5/§7.3、IT-KB-108/109/113）。
 *
 * <p>ACL_ONLY 只刷新索引 ACL 字段并保持 {@code chunk_id} 稳定；旧 aclVersion 事件必须跳过；
 * DELETE 只清理分块与索引且幂等；跨租户 DELETE 不得触碰对方数据。
 */
class KnowledgeIndexModesIntegrationTest extends WorkerIntegrationTestBase {

    /** 轮询器。 */
    @Autowired
    private OutboxPoller poller;

    /** 索引客户端，用于别名/索引查询。 */
    @Autowired
    private OpenSearchIndexClient indexClient;

    /** HTTP 客户端。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** pollOnce 跨租户领取事件；每个测试先清空残留 outbox。 */
    @BeforeEach
    void clearStaleOutbox() {
        clearOutbox();
    }

    /** ACL_ONLY 刷新 ACL 字段但不替换分块；过期 aclVersion 事件跳过。 */
    @Test
    void aclOnly_refreshesAclFieldsWithoutReplacingChunks_andSkipsStaleVersion() {
        String tenant = uniqueTenant("tenant-a");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID documentId = data.createKnowledgeDocument(tenant, "acl-doc", null, userId);
        data.createKnowledgeChunk(tenant, documentId, 1, "first chunk", 1);
        data.createKnowledgeChunk(tenant, documentId, 2, "second chunk", 2);
        setAcl(tenant, documentId, "USERS", 2);
        data.publishKnowledgeIndex(tenant, documentId, null, userId, 1);

        assertThat(poller.pollOnce()).isEqualTo(1);
        List<String> chunkIds = chunkIds(tenant, documentId);
        assertThat(chunkIds).hasSize(2);
        assertThat(indexCount(tenant, documentId)).isEqualTo(2);

        UUID aclEvent = publish(tenant, userId, documentId, 2, "ACL_ONLY", 2, "ACL_CHANGED");
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, documentId)).isEqualTo("INDEXED");
        assertThat(chunkIds(tenant, documentId)).containsExactlyElementsOf(chunkIds);
        assertThat(indexCount(tenant, documentId)).isEqualTo(2);
        JsonNode source = firstSource(tenant, documentId);
        assertThat(source.path("visibility").asText()).isEqualTo("USERS");
        assertThat(source.path("acl_version").asInt()).isEqualTo(2);
        assertThat(source.path("source_type").asText()).isEqualTo("UPLOAD");
        assertThat(outboxStatus(aclEvent)).isEqualTo("DONE");

        // 数据库已有更新版本（5），旧事件（4）晚到时不得把索引字段写回旧 ACL。
        setAcl(tenant, documentId, "TENANT", 5);
        UUID staleEvent = publish(tenant, userId, documentId, 3, "ACL_ONLY", 4, "ACL_CHANGED");
        assertThat(poller.pollOnce()).isEqualTo(1);

        JsonNode staleSource = firstSource(tenant, documentId);
        assertThat(staleSource.path("visibility").asText()).isEqualTo("USERS");
        assertThat(staleSource.path("acl_version").asInt()).isEqualTo(2);
        assertThat(outboxStatus(staleEvent)).isEqualTo("DONE");
    }

    /** DELETE 清理分块与索引，重复投递幂等，文档状态保持 DELETED。 */
    @Test
    void delete_removesChunksAndIndexIdempotently() {
        String tenant = uniqueTenant("tenant-a");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID documentId = data.createKnowledgeDocument(tenant, "delete-doc", null, userId);
        data.createKnowledgeChunk(tenant, documentId, 1, "chunk", 1);
        data.publishKnowledgeIndex(tenant, documentId, null, userId, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(chunkIds(tenant, documentId)).hasSize(1);
        assertThat(indexCount(tenant, documentId)).isEqualTo(1);

        softDelete(tenant, documentId);
        UUID firstDelete = publish(tenant, userId, documentId, 2, "DELETE", 1, "USER");
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(documentStatus(tenant, documentId)).isEqualTo("DELETED");
        assertThat(chunkIds(tenant, documentId)).isEmpty();
        assertThat(indexCount(tenant, documentId)).isZero();
        assertThat(outboxStatus(firstDelete)).isEqualTo("DONE");

        UUID secondDelete = publish(tenant, userId, documentId, 3, "DELETE", 2, "USER");
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(chunkIds(tenant, documentId)).isEmpty();
        assertThat(indexCount(tenant, documentId)).isZero();
        assertThat(outboxStatus(secondDelete)).isEqualTo("DONE");
    }

    /** 没有分块/索引的失败文档也能安全处理 DELETE（清理为空操作）。 */
    @Test
    void delete_documentWithoutChunks_isNoOp() {
        String tenant = uniqueTenant("tenant-a");
        data.createTenant(tenant);
        UUID userId = data.createUser(tenant);
        UUID documentId = data.createKnowledgeDocument(tenant, "failed-doc", null, userId);
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET status = 'DELETED', error_code = 'KB_NO_TEXT', deleted_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .update();
        UUID event = publish(tenant, userId, documentId, 1, "DELETE", 1, "RETENTION");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(chunkIds(tenant, documentId)).isEmpty();
        assertThat(indexCount(tenant, documentId)).isZero();
        assertThat(outboxStatus(event)).isEqualTo("DONE");
    }

    /** 跨租户 DELETE 事件不得删除对方分块或索引。 */
    @Test
    void delete_crossTenant_doesNotTouchOtherTenant() {
        String tenantB = uniqueTenant("tenant-b");
        data.createTenant(tenantB);
        UUID userB = data.createUser(tenantB);
        UUID documentB = data.createKnowledgeDocument(tenantB, "other-doc", null, userB);
        data.createKnowledgeChunk(tenantB, documentB, 1, "other chunk", 1);
        data.publishKnowledgeIndex(tenantB, documentB, null, userB, 1);
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(indexCount(tenantB, documentB)).isEqualTo(1);

        String tenantA = uniqueTenant("tenant-a");
        data.createTenant(tenantA);
        UUID userA = data.createUser(tenantA);
        UUID event = publish(tenantA, userA, documentB, 5, "DELETE", 1, "USER");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(outboxStatus(event)).isEqualTo("DEAD");
        assertThat(chunkIds(tenantB, documentB)).hasSize(1);
        assertThat(indexCount(tenantB, documentB)).isEqualTo(1);
    }

    /**
     * 写入指定模式的知识库索引事件。
     *
     * @param tenant 租户
     * @param userId 发起人
     * @param documentId 文档 ID
     * @param attempt 尝试号
     * @param mode 模式
     * @param aclVersion ACL 版本
     * @param reason 原因
     * @return 事件 ID
     */
    private UUID publish(
            String tenant, UUID userId, UUID documentId, int attempt, String mode, int aclVersion, String reason) {
        UUID runId = data.createRun(tenant, RunKind.KNOWLEDGE_INDEX, null, userId, 3);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", documentId.toString());
        payload.put("attempt", attempt);
        payload.put("mode", mode);
        payload.put("aclVersion", aclVersion);
        payload.put("reason", reason);
        String key = switch (mode) {
            case "ACL_ONLY" -> "doc:" + documentId + ":acl:" + aclVersion;
            case "DELETE" -> "doc:" + documentId + ":delete:" + aclVersion;
            default -> "doc:" + documentId + ":index:" + attempt;
        };
        return data.createOutbox(
                tenant, runId, EventTypes.KNOWLEDGE_INDEX_REQUESTED, objectMapper.writeValueAsString(payload), key);
    }

    /**
     * 更新文档 ACL 与版本。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param visibility 可见范围
     * @param aclVersion ACL 版本
     */
    private void setAcl(String tenant, UUID documentId, String visibility, int aclVersion) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET visibility = :visibility, acl_version = :aclVersion
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .param("visibility", visibility)
                .param("aclVersion", aclVersion)
                .update();
    }

    /**
     * 软删除文档。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     */
    private void softDelete(String tenant, UUID documentId) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET status = 'DELETED', deleted_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", UuidBinary.toBytes(documentId))
                .update();
    }

    /**
     * 查询文档分块 ID。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 按 ordinal 排序的 UUID 字符串
     */
    private List<String> chunkIds(String tenant, UUID documentId) {
        return jdbcClient
                .sql("""
                        SELECT id FROM knowledge_chunk
                         WHERE tenant_id = :tenantId AND document_id = :documentId
                         ORDER BY ordinal
                        """)
                .param("tenantId", tenant)
                .param("documentId", UuidBinary.toBytes(documentId))
                .query((rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes("id")).toString())
                .list();
    }

    /**
     * 统计别名下的文档数。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return 数量
     */
    private long indexCount(String tenant, UUID documentId) {
        if (indexClient.aliasTargets(com.riccon.platform.common.tenant.TenantId.of(tenant))
                .isEmpty()) {
            // 文档从未索引或已被 DELETE 清理：没有别名时视为 0，避免把 404 当测试错误。
            return 0;
        }
        Map<String, Object> query = Map.of("query", Map.of("term", Map.of("document_id", documentId.toString())));
        JsonNode response = postJson(
                "/" + indexClient.aliasName(com.riccon.platform.common.tenant.TenantId.of(tenant)) + "/_count",
                objectMapper.writeValueAsString(query));
        return response.path("count").asLong();
    }

    /**
     * 返回索引文档第一条 _source。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @return _source
     */
    private JsonNode firstSource(String tenant, UUID documentId) {
        Map<String, Object> request =
                Map.of("size", 1, "query", Map.of("term", Map.of("document_id", documentId.toString())));
        JsonNode response = postJson(
                "/" + indexClient.aliasName(com.riccon.platform.common.tenant.TenantId.of(tenant)) + "/_search",
                objectMapper.writeValueAsString(request));
        return response.path("hits").path("hits").get(0).path("_source");
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
}
