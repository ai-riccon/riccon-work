package com.riccon.api.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiTestClient;
import com.riccon.api.support.ResearchApiTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * M05-B9：J2 产物与任务引用的 sourceStatus 四态遮蔽。
 *
 * <p>产物引用与检索阶段来源都按当前账号 ACL、软删除与 source_version 重新判定；不可访问时响应不含标题、片段或
 * 定位，但 artifact_citation 中的审计快照仍保留。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class CitationStatusApiTest extends ResearchApiTestSupport {

    /** 产物详情、版本与任务来源都按同一规则遮蔽。 */
    @Test
    @DisplayName("产物引用：四态遮蔽且不泄露快照")
    void artifactCitations_computeSourceStatus() {
        Fixture fixture = fixture();
        String availableTitle = "可见资料-" + UUID.randomUUID();
        String updatedTitle = "更新资料-" + UUID.randomUUID();
        String deletedTitle = "删除资料-" + UUID.randomUUID();
        String noAccessTitle = "受限资料-" + UUID.randomUUID();
        String fileTitle = "附件.pdf";

        UUID available = seedDocument(fixture.tenant(), availableTitle, "TENANT", null, "a".repeat(64));
        UUID updated = seedDocument(fixture.tenant(), updatedTitle, "TENANT", null, "b".repeat(64));
        UUID deleted = seedDocument(fixture.tenant(), deletedTitle, "TENANT", null, "c".repeat(64));
        UUID noAccess = seedDocument(fixture.tenant(), noAccessTitle, "USERS", null, "d".repeat(64));
        seedAcl(fixture.tenant(), noAccess, actorId(fixture.admin()));
        markDeleted(fixture.tenant(), deleted);
        UUID availableChunk = seedChunk(fixture.tenant(), available, 1, 100, 200, 300, 400, "0.930");
        UUID updatedChunk = seedChunk(fixture.tenant(), updated, 1, 10, 20, 30, 40, "0.820");
        UUID deletedChunk = seedChunk(fixture.tenant(), deleted, 1, 5, 6, 7, 8, "0.700");
        UUID noAccessChunk = seedChunk(fixture.tenant(), noAccess, 1, 9, 10, 11, 12, "0.600");

        UUID artifactId = seedArtifact(
                fixture.tenant(), fixture.memberWorkspace(), null, userId(fixture.member()), "引用报告", "SAVED", 1);
        UUID versionId = seedArtifactVersion(fixture.tenant(), artifactId, 1, userId(fixture.member()), "引用版本");
        seedKnowledgeCitation(
                fixture.tenant(), versionId, 1, available, availableChunk, "a".repeat(64), availableTitle);
        seedKnowledgeCitation(fixture.tenant(), versionId, 2, updated, updatedChunk, "0".repeat(64), updatedTitle);
        seedKnowledgeCitation(fixture.tenant(), versionId, 3, deleted, deletedChunk, "c".repeat(64), deletedTitle);
        seedKnowledgeCitation(fixture.tenant(), versionId, 4, noAccess, noAccessChunk, "d".repeat(64), noAccessTitle);
        seedKnowledgeCitation(fixture.tenant(), versionId, 5, available, null, "a".repeat(64), "历史产物引用");
        seedFileCitation(fixture.tenant(), versionId, 6, fileTitle);

        ApiTestClient.ApiResponse detail =
                client.get("/api/v1/artifacts/" + artifactId, fixture.member().sessionCookie(), Map.of());
        assertThat(detail.status()).as(detail.body()).isEqualTo(200);
        JsonNode citations = client.json(detail).path("currentVersionDetail").path("citations");
        assertThat(citations).hasSize(6);
        assertThat(citations.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(0).path("bbox").path("x").asInt()).isEqualTo(100);
        assertThat(citations.get(0).path("bbox").path("y").asInt()).isEqualTo(200);
        assertThat(citations.get(0).path("bbox").path("w").asInt()).isEqualTo(300);
        assertThat(citations.get(0).path("bbox").path("h").asInt()).isEqualTo(400);
        assertThat(citations.get(0).path("ocrConfidence").asDouble()).isEqualTo(0.930);
        assertThat(citations.get(1).path("sourceStatus").asText()).isEqualTo("UPDATED");
        assertThat(citations.get(1).path("bbox").path("x").asInt()).isEqualTo(10);
        assertThat(citations.get(1).path("ocrConfidence").asDouble()).isEqualTo(0.820);
        assertThat(citations.get(1).path("title").asText()).isEqualTo(updatedTitle);
        assertMasked(citations.get(2), "DELETED");
        assertMasked(citations.get(3), "NO_ACCESS");
        assertThat(citations.get(4).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(4).path("bbox").isNull()).isTrue();
        assertThat(citations.get(4).path("ocrConfidence").isNull()).isTrue();
        assertThat(citations.get(5).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(5).path("sourceType").asText()).isEqualTo("FILE");
        assertThat(citations.get(5).path("title").asText()).isEqualTo(fileTitle);
        assertThat(detail.body()).doesNotContain(deletedTitle).doesNotContain(noAccessTitle);

        ApiTestClient.ApiResponse version = client.get(
                "/api/v1/artifacts/" + artifactId + "/versions/1",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(version.status()).as(version.body()).isEqualTo(200);
        JsonNode versionCitations = client.json(version).path("citations");
        assertThat(versionCitations.get(3).path("sourceStatus").asText()).isEqualTo("NO_ACCESS");
        assertThat(versionCitations.get(3).path("title").asText()).isEmpty();
    }

    /** 检索阶段来源在 ACL 撤销/删除后同样遮蔽。 */
    @Test
    @DisplayName("任务来源：检索阶段四态遮蔽")
    void taskSources_computeSourceStatus() {
        Fixture fixture = fixture();
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task = createTask(fixture.member(), fixture.memberWorkspace(), "引用遮蔽主题", toolId, "citation-status-01");
        String runId = runId(task);
        String availableTitle = "可见检索-" + UUID.randomUUID();
        String noAccessTitle = "受限检索-" + UUID.randomUUID();
        String deletedTitle = "删除检索-" + UUID.randomUUID();
        UUID available = seedDocument(fixture.tenant(), availableTitle, "TENANT", null, "a".repeat(64));
        UUID noAccess = seedDocument(fixture.tenant(), noAccessTitle, "USERS", null, "d".repeat(64));
        seedAcl(fixture.tenant(), noAccess, actorId(fixture.admin()));
        UUID deleted = seedDocument(fixture.tenant(), deletedTitle, "TENANT", null, "c".repeat(64));
        markDeleted(fixture.tenant(), deleted);
        UUID availableChunk = seedChunk(fixture.tenant(), available, 1, 21, 22, 23, 24, "0.770");

        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(source(1, available, availableChunk, availableTitle));
        sources.add(source(2, noAccess, null, noAccessTitle));
        sources.add(source(3, deleted, null, deletedTitle));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("query", "引用遮蔽");
        details.put("querySha256", "0".repeat(64));
        details.put("hitCount", sources.size());
        details.put("sources", sources);
        seedStage(fixture.tenant(), runId, "RETRIEVAL", "SUCCEEDED", TEST_JSON.writeValueAsString(details));

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/research-tasks/" + task.path("id").asText(),
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode responseSources = client.json(response).path("sources");
        assertThat(responseSources).hasSize(3);
        assertThat(responseSources.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(responseSources.get(0).path("title").asText()).isEqualTo(availableTitle);
        assertThat(responseSources.get(0).path("bbox").path("x").asInt()).isEqualTo(21);
        assertThat(responseSources.get(0).path("ocrConfidence").asDouble()).isEqualTo(0.770);
        assertMasked(responseSources.get(1), "NO_ACCESS");
        assertMasked(responseSources.get(2), "DELETED");
        assertThat(response.body()).doesNotContain(noAccessTitle).doesNotContain(deletedTitle);
    }

    /**
     * 构造检索阶段来源条目。
     *
     * @param ordinal 序号
     * @param documentId 文档 ID
     * @param title 标题
     * @return 来源
     */
    private static Map<String, Object> source(int ordinal, UUID documentId, UUID chunkId, String title) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("ordinal", ordinal);
        source.put("sourceType", "KNOWLEDGE");
        source.put("knowledgeDocumentId", documentId.toString());
        if (chunkId != null) {
            source.put("chunkId", chunkId.toString());
        }
        source.put("title", title);
        source.put("snippet", "检索片段");
        source.put("locatorType", "PAGE");
        source.put("pageNo", 1);
        return source;
    }

    /**
     * 断言引用已被遮蔽。
     *
     * @param citation 引用 JSON
     * @param status 期望状态
     */
    private static void assertMasked(JsonNode citation, String status) {
        assertThat(citation.path("sourceStatus").asText()).isEqualTo(status);
        assertThat(citation.path("title").asText()).isEmpty();
        assertThat(citation.path("snippet").asText()).isEmpty();
        assertThat(citation.path("locatorType").isNull()).isTrue();
        assertThat(citation.path("pageNo").isNull()).isTrue();
        assertThat(citation.path("sheetName").isNull()).isTrue();
        assertThat(citation.path("cellRange").isNull()).isTrue();
        assertThat(citation.path("paragraphNo").isNull()).isTrue();
        assertThat(citation.path("bbox").isNull()).isTrue();
        assertThat(citation.path("ocrConfidence").isNull()).isTrue();
    }

    /**
     * 种子知识库文档。
     *
     * @param tenant 租户
     * @param title 标题
     * @param visibility 可见范围
     * @param departmentId 部门
     * @param checksum checksum
     * @return 文档 ID
     */
    private UUID seedDocument(String tenant, String title, String visibility, UUID departmentId, String checksum) {
        UUID documentId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes,
                            status, created_by, created_at, updated_at, visibility, department_id
                        ) VALUES (
                            :tenantId, :id, :title, 'SEED', NULL, :checksum, 'text/plain', 4,
                            'INDEXED', NULL, NOW(6), NOW(6), :visibility, :departmentId
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("checksum", checksum)
                .param("visibility", visibility)
                .param("departmentId", departmentId == null ? null : BinaryUuids.toBytes(departmentId))
                .update();
        return documentId;
    }

    /**
     * 标记文档软删除。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     */
    private void markDeleted(String tenant, UUID documentId) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET deleted_at = NOW(6), status = 'DELETED', updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .update();
    }

    /**
     * 写入用户级 ACL。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param userId 授权账号
     */
    private void seedAcl(String tenant, UUID documentId, UUID userId) {
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document_acl (tenant_id, document_id, user_id, created_at)
                        VALUES (:tenantId, :documentId, :userId, NOW(6))
                        """)
                .param("tenantId", tenant)
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("userId", BinaryUuids.toBytes(userId))
                .update();
    }

    /**
     * 种子知识库产物引用。
     *
     * @param tenant 租户
     * @param versionId 版本 ID
     * @param ordinal 序号
     * @param documentId 文档 ID
     * @param sourceVersion 版本快照
     * @param title 标题
     */
    private void seedKnowledgeCitation(
            String tenant,
            UUID versionId,
            int ordinal,
            UUID documentId,
            UUID chunkId,
            String sourceVersion,
            String title) {
        jdbcClient
                .sql("""
                        INSERT INTO artifact_citation (
                            tenant_id, id, artifact_version_id, ordinal, source_type, file_id,
                            knowledge_document_id, chunk_id, mcp_invocation_id, title_snapshot, snippet,
                            locator_type, page_no, sheet_name, cell_range, paragraph_no, source_version,
                            created_at
                        ) VALUES (
                            :tenantId, :id, :versionId, :ordinal, 'KNOWLEDGE', NULL,
                            :documentId, :chunkId, NULL, :title, '引用片段',
                            'SHEET', NULL, '销售数据', 'B2:D10', NULL, :sourceVersion,
                            NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UuidV7.generate(Instant.now())))
                .param("versionId", BinaryUuids.toBytes(versionId))
                .param("ordinal", ordinal)
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("chunkId", chunkId == null ? null : BinaryUuids.toBytes(chunkId))
                .param("title", title)
                .param("sourceVersion", sourceVersion)
                .update();
    }

    /**
     * 种子知识库分块（含 OCR 区域与置信度）。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param ordinal 序号
     * @param x 左上角 X
     * @param y 左上角 Y
     * @param width 宽度
     * @param height 高度
     * @param confidence 置信度
     * @return 分块 ID
     */
    private UUID seedChunk(
            String tenant, UUID documentId, int ordinal, int x, int y, int width, int height, String confidence) {
        UUID chunkId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_chunk (
                            tenant_id, id, document_id, ordinal, locator_type, paragraph_no,
                            content, token_count, bbox_x, bbox_y, bbox_w, bbox_h, ocr_confidence
                        ) VALUES (
                            :tenantId, :id, :documentId, :ordinal, 'PARAGRAPH', :ordinal,
                            'OCR 分块', 3, :x, :y, :width, :height, :confidence
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(chunkId))
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("ordinal", ordinal)
                .param("x", x)
                .param("y", y)
                .param("width", width)
                .param("height", height)
                .param("confidence", new java.math.BigDecimal(confidence))
                .update();
        return chunkId;
    }

    /**
     * 种子文件产物引用。
     *
     * @param tenant 租户
     * @param versionId 版本 ID
     * @param ordinal 序号
     * @param title 标题
     */
    private void seedFileCitation(String tenant, UUID versionId, int ordinal, String title) {
        jdbcClient
                .sql("""
                        INSERT INTO artifact_citation (
                            tenant_id, id, artifact_version_id, ordinal, source_type, file_id,
                            knowledge_document_id, mcp_invocation_id, title_snapshot, snippet,
                            locator_type, page_no, sheet_name, cell_range, paragraph_no, source_version,
                            created_at
                        ) VALUES (
                            :tenantId, :id, :versionId, :ordinal, 'FILE', :fileId,
                            NULL, NULL, :title, '文件片段',
                            'PAGE', 1, NULL, NULL, NULL, NULL,
                            NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UuidV7.generate(Instant.now())))
                .param("versionId", BinaryUuids.toBytes(versionId))
                .param("ordinal", ordinal)
                .param("fileId", BinaryUuids.toBytes(UUID.randomUUID()))
                .param("title", title)
                .update();
    }
}
