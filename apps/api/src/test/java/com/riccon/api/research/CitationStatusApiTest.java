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
     * 连接器产物引用：源更新、软删除与 ACL 收窄按当前 MySQL 重新判定，且不泄露完整 externalId 或对象 key。
     */
    @Test
    @DisplayName("连接器产物引用：四态遮蔽且不泄露完整 externalId/对象 key")
    void connectorArtifactCitations_computeStatusAndHideExternalId() {
        Fixture fixture = fixture();
        LoginSession outsider = createAndLoginMember(fixture.platform(), fixture.tenant());
        UUID connectorId = seedConnector(fixture.tenant());
        String keyPrefix = "reports/finance/2026/";

        UUID available = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "available-quarterly.pdf",
                "available-quarterly.pdf",
                "TENANT",
                "a".repeat(64));
        UUID updated = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "updated-summary.pdf",
                "updated-summary.pdf",
                "TENANT",
                "b".repeat(64));
        UUID deleted = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "deleted-draft.pdf",
                "deleted-draft.pdf",
                "TENANT",
                "c".repeat(64));
        UUID noAccess = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "restricted-budget.pdf",
                "restricted-budget.pdf",
                "USERS",
                "d".repeat(64));
        seedAcl(fixture.tenant(), noAccess, actorId(outsider));
        markDeleted(fixture.tenant(), deleted);
        // 源更新后索引尚未追平：文档 status 仍为 PENDING，但 checksum 已变化；引用按 MySQL 版本判 UPDATED。
        setDocumentStatus(fixture.tenant(), updated, "PENDING");

        UUID availableChunk = seedChunk(fixture.tenant(), available, 1, 100, 200, 300, 400, "0.930");
        UUID updatedChunk = seedChunk(fixture.tenant(), updated, 1, 10, 20, 30, 40, "0.820");

        UUID artifactId = seedArtifact(
                fixture.tenant(), fixture.memberWorkspace(), null, userId(fixture.member()), "连接器引用报告", "SAVED", 1);
        UUID versionId = seedArtifactVersion(fixture.tenant(), artifactId, 1, userId(fixture.member()), "连接器引用版本");
        seedKnowledgeCitation(
                fixture.tenant(), versionId, 1, available, availableChunk, "a".repeat(64), "available-quarterly.pdf");
        seedKnowledgeCitation(
                fixture.tenant(), versionId, 2, updated, updatedChunk, "0".repeat(64), "updated-summary.pdf");
        seedKnowledgeCitation(fixture.tenant(), versionId, 3, deleted, null, "c".repeat(64), "deleted-draft.pdf");
        seedKnowledgeCitation(fixture.tenant(), versionId, 4, noAccess, null, "d".repeat(64), "restricted-budget.pdf");

        ApiTestClient.ApiResponse detail =
                client.get("/api/v1/artifacts/" + artifactId, fixture.member().sessionCookie(), Map.of());
        assertThat(detail.status()).as(detail.body()).isEqualTo(200);
        JsonNode citations = client.json(detail).path("currentVersionDetail").path("citations");
        assertThat(citations).hasSize(4);
        assertThat(citations.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(0).path("title").asText()).isEqualTo("available-quarterly.pdf");
        assertThat(citations.get(0).path("snippet").asText()).isEqualTo("引用片段");
        assertThat(citations.get(0).path("bbox").path("x").asInt()).isEqualTo(100);
        assertThat(citations.get(1).path("sourceStatus").asText()).isEqualTo("UPDATED");
        assertThat(citations.get(1).path("title").asText()).isEqualTo("updated-summary.pdf");
        assertThat(citations.get(1).path("cellRange").asText()).isEqualTo("B2:D10");
        assertMasked(citations.get(2), "DELETED");
        assertMasked(citations.get(3), "NO_ACCESS");

        assertThat(detail.body())
                .doesNotContain(keyPrefix)
                .doesNotContain("tenants/" + fixture.tenant() + "/connector/")
                .doesNotContain(connectorId.toString())
                .doesNotContain("deleted-draft.pdf")
                .doesNotContain("restricted-budget.pdf");
    }

    /**
     * 连接器检索阶段来源：软删除/ACL 收窄与产物引用同样遮蔽，且不把完整 externalId 写入阶段详情响应。
     */
    @Test
    @DisplayName("连接器任务来源：四态遮蔽且不泄露完整 externalId")
    void connectorTaskSources_maskHiddenSources() {
        Fixture fixture = fixture();
        LoginSession outsider = createAndLoginMember(fixture.platform(), fixture.tenant());
        UUID toolId = createMcpTool(fixture.tenant());
        JsonNode task =
                createTask(fixture.member(), fixture.memberWorkspace(), "连接器来源遮蔽主题", toolId, "connector-citation-01");
        String runId = runId(task);
        UUID connectorId = seedConnector(fixture.tenant());
        String keyPrefix = "reports/finance/2026/";
        String availableTitle = "available-source.pdf";
        String noAccessTitle = "restricted-source.pdf";
        String deletedTitle = "deleted-source.pdf";
        UUID available = seedConnectorDocument(
                fixture.tenant(), connectorId, keyPrefix + availableTitle, availableTitle, "TENANT", "a".repeat(64));
        UUID noAccess = seedConnectorDocument(
                fixture.tenant(), connectorId, keyPrefix + noAccessTitle, noAccessTitle, "USERS", "d".repeat(64));
        seedAcl(fixture.tenant(), noAccess, actorId(outsider));
        UUID deleted = seedConnectorDocument(
                fixture.tenant(), connectorId, keyPrefix + deletedTitle, deletedTitle, "TENANT", "c".repeat(64));
        markDeleted(fixture.tenant(), deleted);
        UUID availableChunk = seedChunk(fixture.tenant(), available, 1, 21, 22, 23, 24, "0.770");

        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(source(1, available, availableChunk, availableTitle));
        sources.add(source(2, noAccess, null, noAccessTitle));
        sources.add(source(3, deleted, null, deletedTitle));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("query", "连接器引用遮蔽");
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
        assertMasked(responseSources.get(1), "NO_ACCESS");
        assertMasked(responseSources.get(2), "DELETED");
        assertThat(response.body())
                .doesNotContain(keyPrefix)
                .doesNotContain("tenants/" + fixture.tenant() + "/connector/")
                .doesNotContain(connectorId.toString())
                .doesNotContain(noAccessTitle)
                .doesNotContain(deletedTitle);
    }

    /**
     * J1 与 J2 对同一批连接器文档必须给出相同的 sourceStatus，不允许一处 AVAILABLE、另一处遮蔽。
     */
    @Test
    @DisplayName("J1/J2 同判：同一连接器文档在消息与产物引用中状态一致")
    void j1AndJ2_agreeOnConnectorStatuses() {
        Fixture fixture = fixture();
        LoginSession outsider = createAndLoginMember(fixture.platform(), fixture.tenant());
        UUID connectorId = seedConnector(fixture.tenant());
        String keyPrefix = "reports/finance/2026/";

        UUID available = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "same-available.pdf",
                "same-available.pdf",
                "TENANT",
                "a".repeat(64));
        UUID updated = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "same-updated.pdf",
                "same-updated.pdf",
                "TENANT",
                "b".repeat(64));
        UUID deleted = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "same-deleted.pdf",
                "same-deleted.pdf",
                "TENANT",
                "c".repeat(64));
        UUID noAccess = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "same-restricted.pdf",
                "same-restricted.pdf",
                "USERS",
                "d".repeat(64));
        seedAcl(fixture.tenant(), noAccess, actorId(outsider));
        markDeleted(fixture.tenant(), deleted);

        // J1：消息引用快照。
        UUID conversationId = seedConversation(fixture.tenant(), fixture.memberWorkspace(), actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        seedMessageCitation(fixture.tenant(), messageId, 1, available, "a".repeat(64), "same-available.pdf");
        seedMessageCitation(fixture.tenant(), messageId, 2, updated, "0".repeat(64), "same-updated.pdf");
        seedMessageCitation(fixture.tenant(), messageId, 3, deleted, "c".repeat(64), "same-deleted.pdf");
        seedMessageCitation(fixture.tenant(), messageId, 4, noAccess, "d".repeat(64), "same-restricted.pdf");

        // J2：产物引用快照，与 J1 使用同一批文档与版本。
        UUID artifactId = seedArtifact(
                fixture.tenant(), fixture.memberWorkspace(), null, userId(fixture.member()), "同判报告", "SAVED", 1);
        UUID versionId = seedArtifactVersion(fixture.tenant(), artifactId, 1, userId(fixture.member()), "同判版本");
        seedKnowledgeCitation(fixture.tenant(), versionId, 1, available, null, "a".repeat(64), "same-available.pdf");
        seedKnowledgeCitation(fixture.tenant(), versionId, 2, updated, null, "0".repeat(64), "same-updated.pdf");
        seedKnowledgeCitation(fixture.tenant(), versionId, 3, deleted, null, "c".repeat(64), "same-deleted.pdf");
        seedKnowledgeCitation(fixture.tenant(), versionId, 4, noAccess, null, "d".repeat(64), "same-restricted.pdf");

        ApiTestClient.ApiResponse messages = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(messages.status()).as(messages.body()).isEqualTo(200);
        JsonNode chatCitations = client.json(messages).path("items").get(0).path("citations");

        ApiTestClient.ApiResponse artifact =
                client.get("/api/v1/artifacts/" + artifactId, fixture.member().sessionCookie(), Map.of());
        assertThat(artifact.status()).as(artifact.body()).isEqualTo(200);
        JsonNode artifactCitations =
                client.json(artifact).path("currentVersionDetail").path("citations");

        assertThat(chatCitations).hasSameSizeAs(artifactCitations);
        for (int index = 0; index < chatCitations.size(); index++) {
            JsonNode chat = chatCitations.get(index);
            JsonNode produced = artifactCitations.get(index);
            assertThat(chat.path("knowledgeDocumentId").asText())
                    .isEqualTo(produced.path("knowledgeDocumentId").asText());
            assertThat(chat.path("sourceStatus").asText())
                    .as("J1/J2 对文档 %s 的判定必须一致", chat.path("knowledgeDocumentId").asText())
                    .isEqualTo(produced.path("sourceStatus").asText());
            assertThat(chat.path("title").asText())
                    .isEqualTo(produced.path("title").asText());
            assertThat(chat.path("snippet").asText())
                    .isEqualTo(produced.path("snippet").asText());
        }
        assertThat(chatCitations.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(chatCitations.get(1).path("sourceStatus").asText()).isEqualTo("UPDATED");
        assertThat(chatCitations.get(2).path("sourceStatus").asText()).isEqualTo("DELETED");
        assertThat(chatCitations.get(3).path("sourceStatus").asText()).isEqualTo("NO_ACCESS");

        assertThat(messages.body())
                .doesNotContain(keyPrefix)
                .doesNotContain("tenants/" + fixture.tenant() + "/connector/")
                .doesNotContain(connectorId.toString());
        assertThat(artifact.body())
                .doesNotContain(keyPrefix)
                .doesNotContain("tenants/" + fixture.tenant() + "/connector/")
                .doesNotContain(connectorId.toString());
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
     * 种子连接器行；端点 ID 与配置只用于满足约束，不参与引用状态判定。
     *
     * @param tenant 租户
     * @return 连接器 ID
     */
    private UUID seedConnector(String tenant) {
        UUID connectorId = UuidV7.generate(Instant.now());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector (
                            tenant_id, id, name, type, status, endpoint_id, config, default_visibility,
                            schedule_enabled, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :name, 'S3', 'ACTIVE', 'test-endpoint',
                            '{"bucket":"docs","prefix":"reports/"}', 'TENANT', FALSE, NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(connectorId))
                .param("name", "连接器引用测试-" + suffix)
                .update();
        return connectorId;
    }

    /**
     * 种子连接器文档：完整 externalId 只落库，标题按 B6 规则保存末段。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @param externalId 完整源对象 key
     * @param title 文档标题（对象 key 末段）
     * @param visibility 可见范围
     * @param checksum 内容 checksum
     * @return 文档 ID
     */
    private UUID seedConnectorDocument(
            String tenant, UUID connectorId, String externalId, String title, String visibility, String checksum) {
        UUID documentId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO knowledge_document (
                            tenant_id, id, title, source_type, file_id, connector_id, external_id, checksum,
                            mime_type, size_bytes, status, created_by, created_at, updated_at, visibility,
                            department_id, parse_engine, acl_version
                        ) VALUES (
                            :tenantId, :id, :title, 'CONNECTOR', NULL, :connectorId, :externalId, :checksum,
                            'application/pdf', 4, 'INDEXED', NULL, NOW(6), NOW(6), :visibility,
                            NULL, 'NONE', 1
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("connectorId", BinaryUuids.toBytes(connectorId))
                .param("externalId", externalId)
                .param("checksum", checksum)
                .param("visibility", visibility)
                .update();
        return documentId;
    }

    /**
     * 覆盖文档索引状态，模拟源更新后索引尚未追平。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param status 新状态
     */
    private void setDocumentStatus(String tenant, UUID documentId, String status) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET status = :status, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("status", status)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .update();
    }

    /**
     * 种子会话。
     *
     * @param tenant 租户
     * @param workspaceId 工作空间 ID
     * @param createdBy 创建人
     * @return 会话 ID
     */
    private UUID seedConversation(String tenant, String workspaceId, UUID createdBy) {
        UUID conversationId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO conversation (
                            tenant_id, id, workspace_id, created_by, title, status, created_at, updated_at
                        ) VALUES (:tenantId, :id, :workspaceId, :createdBy, '连接器同判会话', 'ACTIVE', NOW(6), NOW(6))
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(conversationId))
                .param("workspaceId", BinaryUuids.toBytes(UUID.fromString(workspaceId)))
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .update();
        return conversationId;
    }

    /**
     * 种子 assistant 消息。
     *
     * @param tenant 租户
     * @param conversationId 会话 ID
     * @return 消息 ID
     */
    private UUID seedAssistantMessage(String tenant, UUID conversationId) {
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
     * 种子消息引用快照，用于 J1/J2 同判对比。
     *
     * @param tenant 租户
     * @param messageId 消息 ID
     * @param ordinal 序号
     * @param documentId 文档 ID
     * @param sourceVersion 版本快照
     * @param title 标题快照
     */
    private void seedMessageCitation(
            String tenant, UUID messageId, int ordinal, UUID documentId, String sourceVersion, String title) {
        jdbcClient
                .sql("""
                        INSERT INTO message_citation (
                            tenant_id, id, message_id, ordinal, source_type, file_id, knowledge_document_id,
                            chunk_id, locator_type, page_no, sheet_name, cell_range, paragraph_no,
                            title_snapshot, snippet, score, created_at, source_version
                        ) VALUES (
                            :tenantId, :id, :messageId, :ordinal, 'KNOWLEDGE', NULL, :documentId,
                            NULL, 'SHEET', 2, '销售数据', 'B2:D10', 3,
                            :title, '引用片段', 0.5, NOW(6), :sourceVersion
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UuidV7.generate(Instant.now())))
                .param("messageId", BinaryUuids.toBytes(messageId))
                .param("ordinal", ordinal)
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("sourceVersion", sourceVersion)
                .update();
    }

    /**
     * 计算 SHA-256 十六进制串。
     *
     * @param value 原值
     * @return 64 位十六进制
     */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
