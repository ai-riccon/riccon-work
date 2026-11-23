package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import tools.jackson.databind.JsonNode;

/**
 * M05 独立越权/安全矩阵测试基座（verifier 所有）。
 *
 * <p>在 {@link KnowledgeAclTestSupport} 的基础上补充引用（citation）种子与会话种子，用于验证
 * “ACL 收紧后旧引用不可访问”以及跨租户/跨部门引用遮蔽。所有 fixture 仍通过真实 HTTP 创建；
 * 只有 citation 快照按生产表结构直接落库，因为产品没有测试专用写入后门。
 *
 * <p>查询/检索（search）走 worker 的 {@code AgentContextJdbc}，不在 API 测试内重复；对应
 * TEN-034/035/036 的检索面由 worker {@code AgentContextJdbcAclTest} 覆盖并在 V2 复跑。
 */
@Tag("security")
abstract class M05AclMatrixTestSupport extends KnowledgeAclTestSupport {

    /**
     * 断言 404 且错误码为 {@code RICCON-TENANT-404}，且响应不含敏感标题/片段。
     *
     * @param response HTTP 响应
     * @param forbidden 不得出现在响应体中的敏感值
     */
    protected void assertTenant404(ApiTestClient.ApiResponse response, String... forbidden) {
        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");
        for (String value : forbidden) {
            assertThat(response.body()).as("404 响应不得泄露 " + value).doesNotContain(value);
        }
    }

    /**
     * 断言 403 且错误码为 {@code RICCON-AUTH-403}。
     *
     * @param response HTTP 响应
     */
    protected void assertAuth403(ApiTestClient.ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /**
     * 种子一条会话，归属指定账号。
     *
     * @param tenant 租户
     * @param workspaceId 工作空间
     * @param createdBy 创建人
     * @return 会话 ID
     */
    protected UUID seedConversation(String tenant, String workspaceId, UUID createdBy) {
        UUID conversationId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO conversation (
                            tenant_id, id, workspace_id, created_by, title, status, created_at, updated_at
                        ) VALUES (:tenantId, :id, :workspaceId, :createdBy, 'M05 矩阵会话', 'ACTIVE', NOW(6), NOW(6))
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(conversationId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(workspaceId)))
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .update();
        return conversationId;
    }

    /**
     * 种子一条 assistant 消息。
     *
     * @param tenant 租户
     * @param conversationId 会话 ID
     * @return 消息 ID
     */
    protected UUID seedAssistantMessage(String tenant, UUID conversationId) {
        UUID messageId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO message (
                            tenant_id, id, conversation_id, seq, role, content, status, created_at, updated_at
                        ) VALUES (:tenantId, :id, :conversationId, 1, 'ASSISTANT', '回答', 'COMPLETED', NOW(6), NOW(6))
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(messageId))
                .param("conversationId", BinaryUuids.toBytes(conversationId))
                .update();
        return messageId;
    }

    /**
     * 种子知识库引用（含分块定位）。
     *
     * @param tenant 租户
     * @param messageId 消息 ID
     * @param ordinal 序号
     * @param documentId 文档 ID
     * @param chunkId 分块 ID
     * @param title 标题快照
     * @param snippet 片段快照
     * @return 引用 ID
     */
    protected UUID seedCitation(
            String tenant, UUID messageId, int ordinal, UUID documentId, UUID chunkId, String title, String snippet) {
        UUID citationId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO message_citation (
                            tenant_id, id, message_id, ordinal, source_type, file_id, knowledge_document_id,
                            chunk_id, locator_type, page_no, sheet_name, cell_range, paragraph_no,
                            title_snapshot, snippet, score, created_at, source_version
                        ) VALUES (
                            :tenantId, :id, :messageId, :ordinal, 'KNOWLEDGE', NULL, :documentId,
                            :chunkId, 'SHEET', 2, '销售数据', 'B2:D10', 3,
                            :title, :snippet, 0.5, NOW(6), NULL
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(citationId))
                .param("messageId", BinaryUuids.toBytes(messageId))
                .param("ordinal", ordinal)
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("chunkId", chunkId == null ? null : BinaryUuids.toBytes(chunkId))
                .param("title", title)
                .param("snippet", snippet)
                .update();
        return citationId;
    }

    /**
     * 种子知识库分块（仅用于让引用有可定位分块；检索本身由 worker 测试覆盖）。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param ordinal 序号
     * @param confidence 置信度
     * @return 分块 ID
     */
    protected UUID seedChunk(String tenant, UUID documentId, int ordinal, String confidence) {
        UUID chunkId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_chunk (
                            tenant_id, id, document_id, ordinal, locator_type, paragraph_no,
                            content, token_count, bbox_x, bbox_y, bbox_w, bbox_h, ocr_confidence
                        ) VALUES (
                            :tenantId, :id, :documentId, :ordinal, 'PARAGRAPH', :ordinal,
                            'OCR 分块', 3, 10, 20, 30, 40, :confidence
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(chunkId))
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("ordinal", ordinal)
                .param("confidence", new java.math.BigDecimal(confidence))
                .update();
        return chunkId;
    }

    /**
     * 读取会话消息中的引用列表；会话必须属于调用者。
     *
     * @param session 会话
     * @param conversationId 会话 ID
     * @return 引用 JSON 数组
     */
    protected JsonNode citations(LoginSession session, UUID conversationId) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/conversations/" + conversationId + "/messages", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode items = client.json(response).path("items");
        assertThat(items).isNotEmpty();
        JsonNode citations = items.get(0).path("citations");
        assertThat(citations.isArray()).isTrue();
        return citations;
    }

    /**
     * 断言引用处于遮蔽状态且不泄露标题/片段/定位。
     *
     * @param citation 引用
     * @param expectedStatus 期望状态（NO_ACCESS/DELETED）
     */
    protected void assertMaskedCitation(JsonNode citation, String expectedStatus) {
        assertThat(citation.path("sourceStatus").asText()).isEqualTo(expectedStatus);
        assertThat(citation.path("title").asText()).isEmpty();
        assertThat(citation.path("snippet").asText()).isEmpty();
        assertThat(citation.path("locatorType").isNull()).isTrue();
        assertThat(citation.path("bbox").isNull()).isTrue();
        assertThat(citation.path("ocrConfidence").isNull()).isTrue();
        assertThat(citation.path("cellRange").isNull()).isTrue();
        assertThat(citation.path("pageNo").isNull()).isTrue();
        assertThat(citation.path("sheetName").isNull()).isTrue();
    }

    /**
     * 读取列表中的文档 ID。
     *
     * @param session 会话
     * @return 文档 ID 列表
     */
    protected List<String> visibleDocumentIds(LoginSession session) {
        ApiTestClient.ApiResponse response = listDocuments(session);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        for (JsonNode item : client.json(response).path("items")) {
            ids.add(item.path("id").asText());
        }
        return ids;
    }
}
