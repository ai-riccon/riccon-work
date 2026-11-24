package com.riccon.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.chat.service.ChatEventRecorder;
import com.riccon.api.chat.service.ChatEventValidator;
import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.common.tenant.TenantContext;
import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * M05-B9：聊天引用 source_version 写入与 sourceStatus 四态遮蔽。
 *
 * <p>使用真实 MySQL 与登录链路；知识文档、消息与引用用 SQL 精确构造四种来源状态（ACL 撤销、软删除、内容更新、
 * 历史空版本），避免依赖 Worker 流式生成。覆盖跨租户、跨部门、批量读取与“不可访问时不泄露标题/片段/定位”。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class CitationStatusApiTest extends ApiIntegrationTestSupport {

    /** 引用写入记录器。 */
    @Autowired
    private ChatEventRecorder chatEventRecorder;

    /** 四种来源状态在同一响应中按各自规则展示，且不可访问来源不泄露快照。 */
    @Test
    @DisplayName("消息引用：四态、历史空版本、跨租户/跨部门与无泄露")
    void listMessages_computesSourceStatusAndMasks() {
        Fixture fixture = fixture();
        LoginSession outsider = createAndLoginMember(fixture.platform(), fixture.tenant());
        UUID memberDepartment = seedDepartment(fixture.tenant(), fixture.member());
        assertThat(memberDepartment).isNotNull();
        UUID otherDepartment = seedDepartmentOnly(fixture.tenant(), actorId(fixture.member()));
        String workspace = personalWorkspace(fixture.member());
        UUID conversationId = seedConversation(fixture.tenant(), workspace, actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);

        String availableTitle = "可见制度-" + UUID.randomUUID();
        String updatedTitle = "已更新制度-" + UUID.randomUUID();
        String deletedTitle = "已删除制度-" + UUID.randomUUID();
        String noAccessTitle = "用户限制制度-" + UUID.randomUUID();
        String deptTitle = "其他部门制度-" + UUID.randomUUID();
        String historyTitle = "历史引用-" + UUID.randomUUID();
        String crossTenantTitle = "跨租户制度-" + UUID.randomUUID();

        UUID available = seedDocument(fixture.tenant(), availableTitle, "TENANT", null, "a".repeat(64));
        UUID updated = seedDocument(fixture.tenant(), updatedTitle, "TENANT", null, "b".repeat(64));
        UUID deleted = seedDocument(fixture.tenant(), deletedTitle, "TENANT", null, "c".repeat(64));
        UUID noAccess = seedDocument(fixture.tenant(), noAccessTitle, "USERS", null, "d".repeat(64));
        seedAcl(fixture.tenant(), noAccess, actorId(outsider));
        UUID deptOnly = seedDocument(fixture.tenant(), deptTitle, "DEPT", otherDepartment, "e".repeat(64));
        UUID history = seedDocument(fixture.tenant(), historyTitle, "TENANT", null, "f".repeat(64));
        Fixture otherTenant = emptyTenant();
        UUID crossTenant = seedDocument(otherTenant.tenant(), crossTenantTitle, "TENANT", null, "1".repeat(64));

        markDeleted(fixture.tenant(), deleted);
        UUID availableChunk = seedChunk(fixture.tenant(), available, 1, 100, 200, 300, 400, "0.950");
        UUID updatedChunk = seedChunk(fixture.tenant(), updated, 1, 10, 20, 30, 40, "0.880");
        UUID deletedChunk = seedChunk(fixture.tenant(), deleted, 1, 7, 8, 9, 10, "0.700");
        UUID noAccessChunk = seedChunk(fixture.tenant(), noAccess, 1, 11, 12, 13, 14, "0.600");
        UUID deptChunk = seedChunk(fixture.tenant(), deptOnly, 1, 15, 16, 17, 18, "0.500");
        seedCitation(fixture.tenant(), messageId, 1, available, availableChunk, "a".repeat(64), availableTitle, "可见片段");
        seedCitation(fixture.tenant(), messageId, 2, updated, updatedChunk, "0".repeat(64), updatedTitle, "更新片段");
        seedCitation(fixture.tenant(), messageId, 3, deleted, deletedChunk, "c".repeat(64), deletedTitle, "删除片段");
        seedCitation(fixture.tenant(), messageId, 4, noAccess, noAccessChunk, "d".repeat(64), noAccessTitle, "受限片段");
        seedCitation(fixture.tenant(), messageId, 5, deptOnly, deptChunk, "e".repeat(64), deptTitle, "部门片段");
        seedCitation(fixture.tenant(), messageId, 6, history, null, null, historyTitle, "历史片段");
        seedCitation(fixture.tenant(), messageId, 7, crossTenant, null, "1".repeat(64), crossTenantTitle, "跨租户片段");
        seedFileCitation(fixture.tenant(), messageId, 8, "文件片段");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode citations = client.json(response).path("items").get(0).path("citations");
        assertThat(citations).hasSize(8);

        assertThat(citations.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(0).path("title").asText()).isEqualTo(availableTitle);
        assertThat(citations.get(0).path("snippet").asText()).isEqualTo("可见片段");
        assertThat(citations.get(0).path("bbox").path("x").asInt()).isEqualTo(100);
        assertThat(citations.get(0).path("bbox").path("y").asInt()).isEqualTo(200);
        assertThat(citations.get(0).path("bbox").path("w").asInt()).isEqualTo(300);
        assertThat(citations.get(0).path("bbox").path("h").asInt()).isEqualTo(400);
        assertThat(citations.get(0).path("ocrConfidence").asDouble()).isEqualTo(0.950);

        assertThat(citations.get(1).path("sourceStatus").asText()).isEqualTo("UPDATED");
        assertThat(citations.get(1).path("title").asText()).isEqualTo(updatedTitle);
        assertThat(citations.get(1).path("snippet").asText()).isEqualTo("更新片段");
        assertThat(citations.get(1).path("bbox").path("x").asInt()).isEqualTo(10);
        assertThat(citations.get(1).path("ocrConfidence").asDouble()).isEqualTo(0.880);

        assertMasked(citations.get(2), "DELETED");
        assertMasked(citations.get(3), "NO_ACCESS");
        assertMasked(citations.get(4), "NO_ACCESS");
        assertThat(citations.get(5).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(5).path("title").asText()).isEqualTo(historyTitle);
        assertThat(citations.get(5).path("bbox").isNull()).isTrue();
        assertThat(citations.get(5).path("ocrConfidence").isNull()).isTrue();
        assertMasked(citations.get(6), "NO_ACCESS");
        assertThat(citations.get(7).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(7).path("sourceType").asText()).isEqualTo("FILE");
        assertThat(citations.get(7).path("snippet").asText()).isEqualTo("文件片段");

        // 不可访问来源不得以任何形式泄露标题或片段。
        assertThat(response.body())
                .doesNotContain(deletedTitle)
                .doesNotContain(noAccessTitle)
                .doesNotContain(deptTitle)
                .doesNotContain(crossTenantTitle);
    }

    /** 引用写入只给知识库来源记录 checksum 快照，FILE 引用保持空版本。 */
    @Test
    @DisplayName("写入：KNOWLEDGE 写 source_version、FILE 保持 NULL")
    void recordCitations_writesVersionOnlyForKnowledge() {
        Fixture fixture = fixture();
        String workspace = personalWorkspace(fixture.member());
        UUID conversationId = seedConversation(fixture.tenant(), workspace, actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        String checksum = "9".repeat(64);
        UUID documentId = seedDocument(fixture.tenant(), "版本快照", "TENANT", null, checksum);

        ChatEventValidator.ParsedCitation knowledge = new ChatEventValidator.ParsedCitation(
                1,
                CitationSourceType.KNOWLEDGE,
                null,
                documentId,
                null,
                LocatorType.PARAGRAPH,
                null,
                null,
                null,
                1,
                "版本快照",
                "片段",
                new BigDecimal("0.8"));
        ChatEventValidator.ParsedCitation file = new ChatEventValidator.ParsedCitation(
                2,
                CitationSourceType.FILE,
                UUID.randomUUID(),
                null,
                null,
                LocatorType.PAGE,
                1,
                null,
                null,
                null,
                "附件.pdf",
                "附件片段",
                null);

        TenantContext.callWithTenant(TenantId.of(fixture.tenant()), () -> {
            chatEventRecorder.recordCitations(TenantId.of(fixture.tenant()), messageId, List.of(knowledge, file));
            return null;
        });

        List<String> versions = jdbcClient
                .sql("""
                        SELECT source_version FROM message_citation
                         WHERE tenant_id = :tenantId AND message_id = :messageId
                         ORDER BY ordinal
                        """)
                .param("tenantId", fixture.tenant())
                .param("messageId", BinaryUuids.toBytes(messageId))
                .query(String.class)
                .list();
        assertThat(versions).containsExactly(checksum, null);
    }

    /** J1 写入：连接器来源同样写入 checksum 快照；快照标题保持对象 key 末段，不携带完整 externalId。 */
    @Test
    @DisplayName("连接器写入：chat 引用写入 checksum 快照且不泄露完整 key")
    void recordCitations_connectorWritesVersionSnapshot() {
        Fixture fixture = fixture();
        String workspace = personalWorkspace(fixture.member());
        UUID conversationId = seedConversation(fixture.tenant(), workspace, actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        UUID connectorId = seedConnector(fixture.tenant());
        String keyPrefix = "reports/finance/2026/";
        String title = "snapshot-source.pdf";
        String checksum = "9".repeat(64);
        UUID documentId = seedConnectorDocument(
                fixture.tenant(), connectorId, keyPrefix + title, title, "TENANT", null, checksum);

        ChatEventValidator.ParsedCitation citation = new ChatEventValidator.ParsedCitation(
                1,
                CitationSourceType.KNOWLEDGE,
                null,
                documentId,
                null,
                LocatorType.PARAGRAPH,
                null,
                null,
                null,
                1,
                title,
                "连接器片段",
                new BigDecimal("0.8"));
        TenantContext.callWithTenant(TenantId.of(fixture.tenant()), () -> {
            chatEventRecorder.recordCitations(TenantId.of(fixture.tenant()), messageId, List.of(citation));
            return null;
        });

        Map<String, Object> row = jdbcClient
                .sql("""
                        SELECT source_version, title_snapshot, snippet
                          FROM message_citation
                         WHERE tenant_id = :tenantId AND message_id = :messageId
                        """)
                .param("tenantId", fixture.tenant())
                .param("messageId", BinaryUuids.toBytes(messageId))
                .query()
                .singleRow();
        assertThat(row.get("source_version")).isEqualTo(checksum);
        assertThat(row.get("title_snapshot")).isEqualTo(title);
        assertThat((String) row.get("title_snapshot")).doesNotContain(keyPrefix);
        assertThat(row.get("snippet")).isEqualTo("连接器片段");
    }

    /** 历史引用（source_version 为空）在文档仍可见时按 AVAILABLE，不做 UPDATED 判定。 */
    @Test
    @DisplayName("读取：历史空版本按 AVAILABLE")
    void listMessages_nullVersionStaysAvailable() {
        Fixture fixture = fixture();
        String workspace = personalWorkspace(fixture.member());
        UUID conversationId = seedConversation(fixture.tenant(), workspace, actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        UUID documentId = seedDocument(fixture.tenant(), "历史文档", "TENANT", null, "2".repeat(64));
        seedCitation(fixture.tenant(), messageId, 1, documentId, null, null, "历史文档", "历史片段");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                fixture.member().sessionCookie(),
                Map.of());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode citation =
                client.json(response).path("items").get(0).path("citations").get(0);
        assertThat(citation.path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citation.path("title").asText()).isEqualTo("历史文档");
    }

    /**
     * 连接器来源：源更新、软删除与 ACL 收窄都按当前 MySQL 行重新判定，且响应不泄露完整 externalId 或对象 key。
     *
     * <p>源更新后文档索引状态可能仍是 {@code PENDING}，引用状态不能用索引字段推断；这里按 checksum 判定
     * {@code UPDATED}，并保留快照标题、片段与定位（ADR-0023 第 5.4 节）。
     */
    @Test
    @DisplayName("连接器引用：四态遮蔽且不泄露完整 externalId/对象 key")
    void connectorCitations_computeStatusAndHideExternalId() {
        Fixture fixture = fixture();
        LoginSession outsider = createAndLoginMember(fixture.platform(), fixture.tenant());
        String workspace = personalWorkspace(fixture.member());
        UUID conversationId = seedConversation(fixture.tenant(), workspace, actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        UUID connectorId = seedConnector(fixture.tenant());
        String keyPrefix = "reports/finance/2026/";

        UUID available = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "available-quarterly.pdf",
                "available-quarterly.pdf",
                "TENANT",
                null,
                "a".repeat(64));
        UUID updated = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "updated-summary.pdf",
                "updated-summary.pdf",
                "TENANT",
                null,
                "b".repeat(64));
        UUID deleted = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "deleted-draft.pdf",
                "deleted-draft.pdf",
                "TENANT",
                null,
                "c".repeat(64));
        UUID noAccess = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "restricted-budget.pdf",
                "restricted-budget.pdf",
                "USERS",
                null,
                "d".repeat(64));
        UUID history = seedConnectorDocument(
                fixture.tenant(),
                connectorId,
                keyPrefix + "history-plan.pdf",
                "history-plan.pdf",
                "TENANT",
                null,
                "e".repeat(64));
        seedAcl(fixture.tenant(), noAccess, actorId(outsider));
        markDeleted(fixture.tenant(), deleted);
        // 源更新后索引尚未追平：status 仍是 PENDING，但文档 checksum 已变化；引用必须按 checksum 判 UPDATED。
        setDocumentStatus(fixture.tenant(), updated, "PENDING");

        UUID availableChunk = seedChunk(fixture.tenant(), available, 1, 100, 200, 300, 400, "0.930");
        UUID updatedChunk = seedChunk(fixture.tenant(), updated, 1, 10, 20, 30, 40, "0.820");
        UUID deletedChunk = seedChunk(fixture.tenant(), deleted, 1, 5, 6, 7, 8, "0.700");
        UUID noAccessChunk = seedChunk(fixture.tenant(), noAccess, 1, 9, 10, 11, 12, "0.600");
        seedCitation(
                fixture.tenant(),
                messageId,
                1,
                available,
                availableChunk,
                "a".repeat(64),
                "available-quarterly.pdf",
                "可见连接器片段");
        seedCitation(
                fixture.tenant(),
                messageId,
                2,
                updated,
                updatedChunk,
                "0".repeat(64),
                "updated-summary.pdf",
                "更新连接器片段");
        seedCitation(
                fixture.tenant(), messageId, 3, deleted, deletedChunk, "c".repeat(64), "deleted-draft.pdf", "删除连接器片段");
        seedCitation(
                fixture.tenant(),
                messageId,
                4,
                noAccess,
                noAccessChunk,
                "d".repeat(64),
                "restricted-budget.pdf",
                "受限连接器片段");
        // 历史引用没有版本快照：仍受当前 ACL/删除状态约束，但不做 UPDATED 判定（ADR-0023 第 5.1 节）。
        seedCitation(fixture.tenant(), messageId, 5, history, null, null, "history-plan.pdf", "历史连接器片段");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode citations = client.json(response).path("items").get(0).path("citations");
        assertThat(citations).hasSize(5);

        assertThat(citations.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(0).path("title").asText()).isEqualTo("available-quarterly.pdf");
        assertThat(citations.get(0).path("snippet").asText()).isEqualTo("可见连接器片段");
        assertThat(citations.get(0).path("bbox").path("x").asInt()).isEqualTo(100);

        assertThat(citations.get(1).path("sourceStatus").asText()).isEqualTo("UPDATED");
        assertThat(citations.get(1).path("title").asText()).isEqualTo("updated-summary.pdf");
        assertThat(citations.get(1).path("snippet").asText()).isEqualTo("更新连接器片段");
        assertThat(citations.get(1).path("sheetName").asText()).isEqualTo("销售数据");

        assertMasked(citations.get(2), "DELETED");
        assertMasked(citations.get(3), "NO_ACCESS");
        assertThat(citations.get(4).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(4).path("title").asText()).isEqualTo("history-plan.pdf");

        // 引用快照与响应都不得携带完整 externalId、对象 key 或连接器 ID。
        assertThat(response.body())
                .doesNotContain(keyPrefix)
                .doesNotContain("tenants/" + fixture.tenant() + "/connector/")
                .doesNotContain(connectorId.toString());
        assertThat(response.body()).doesNotContain("deleted-draft.pdf").doesNotContain("restricted-budget.pdf");
    }

    /**
     * 连接器历史引用（空版本）仍按当前 MySQL ACL 判定：收窄后下一次请求立即 NO_ACCESS，不因旧快照误判可见。
     */
    @Test
    @DisplayName("连接器历史引用：ACL 收窄后下一次请求即 NO_ACCESS")
    void connectorHistoryCitation_followsAclNarrowing() {
        Fixture fixture = fixture();
        String workspace = personalWorkspace(fixture.member());
        UUID conversationId = seedConversation(fixture.tenant(), workspace, actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        UUID connectorId = seedConnector(fixture.tenant());
        String title = "narrowed-history.pdf";
        UUID documentId = seedConnectorDocument(
                fixture.tenant(), connectorId, "reports/finance/2026/" + title, title, "TENANT", null, "1".repeat(64));
        seedCitation(fixture.tenant(), messageId, 1, documentId, null, null, title, "历史连接器片段");

        ApiTestClient.ApiResponse before = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(before.status()).as(before.body()).isEqualTo(200);
        JsonNode beforeCitation =
                client.json(before).path("items").get(0).path("citations").get(0);
        assertThat(beforeCitation.path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(beforeCitation.path("title").asText()).isEqualTo(title);

        // 管理员把可见范围收窄为 USERS 且未授权该成员；引用读取不使用缓存，下一次请求必须重新判定。
        narrowToUsers(fixture.tenant(), documentId);

        ApiTestClient.ApiResponse after = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(after.status()).as(after.body()).isEqualTo(200);
        JsonNode afterCitation =
                client.json(after).path("items").get(0).path("citations").get(0);
        assertMasked(afterCitation, "NO_ACCESS");
        assertThat(after.body()).doesNotContain(title);
    }

    /**
     * 连接器源端删除-重现：tombstone 复用同一 item 行并绑定新文档，旧文档引用保持 DELETED，新文档引用 AVAILABLE。
     */
    @Test
    @DisplayName("连接器源重现：旧引用 DELETED、新引用 AVAILABLE")
    void connectorReappearance_keepsOldCitationDeleted() {
        Fixture fixture = fixture();
        String workspace = personalWorkspace(fixture.member());
        UUID conversationId = seedConversation(fixture.tenant(), workspace, actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        UUID connectorId = seedConnector(fixture.tenant());
        String externalId = "reports/finance/2026/reappeared.pdf";
        String title = "reappeared.pdf";

        UUID oldDocument =
                seedConnectorDocument(fixture.tenant(), connectorId, externalId, title, "TENANT", null, "a".repeat(64));
        markDeleted(fixture.tenant(), oldDocument);
        // 源端重现复用 tombstone（generation+1、document_id 指向新文档），旧文档保留在历史/审计中。
        UUID newDocument =
                seedConnectorDocument(fixture.tenant(), connectorId, externalId, title, "TENANT", null, "b".repeat(64));
        seedConnectorItem(fixture.tenant(), connectorId, newDocument, "SYNCED", 2, externalId, "b".repeat(64));
        seedCitation(fixture.tenant(), messageId, 1, oldDocument, null, "a".repeat(64), title, "重现前片段");
        seedCitation(fixture.tenant(), messageId, 2, newDocument, null, "b".repeat(64), title, "重现后片段");

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                fixture.member().sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode citations = client.json(response).path("items").get(0).path("citations");
        assertMasked(citations.get(0), "DELETED");
        assertThat(citations.get(1).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(citations.get(1).path("title").asText()).isEqualTo(title);
        assertThat(citations.get(0).path("knowledgeDocumentId").asText())
                .isNotEqualTo(citations.get(1).path("knowledgeDocumentId").asText());

        Map<String, Object> itemRow = jdbcClient
                .sql("""
                        SELECT generation, status, document_id
                          FROM kb_connector_item
                         WHERE tenant_id = :tenantId AND connector_id = :connectorId
                        """)
                .param("tenantId", fixture.tenant())
                .param("connectorId", BinaryUuids.toBytes(connectorId))
                .query()
                .singleRow();
        assertThat(((Number) itemRow.get("generation")).intValue()).isEqualTo(2);
        assertThat(itemRow.get("status")).isEqualTo("SYNCED");
        assertThat(BinaryUuids.fromBytes((byte[]) itemRow.get("document_id"))).isEqualTo(newDocument);
        assertThat(response.body()).doesNotContain(externalId);
    }

    /**
     * 构造租户、普通成员个人空间夹具。
     *
     * @return 夹具
     */
    private Fixture fixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        return new Fixture(platform, tenant, member);
    }

    /**
     * 创建一个无成员的第二租户，用于跨租户文档。
     *
     * @return 第二租户夹具
     */
    private Fixture emptyTenant() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("x");
        createTenant(platform, tenant);
        return new Fixture(platform, tenant, null);
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
     * 读取当前会话的个人空间；列表首次调用会自动创建。
     *
     * @param session 登录会话
     * @return 个人空间 ID
     */
    private String personalWorkspace(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100&kind=PERSONAL", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode items = client.json(response).path("items");
        assertThat(items).isNotEmpty();
        return items.get(0).path("id").asText();
    }

    /**
     * 种子部门并把成员加入该部门。
     *
     * @param tenant 租户
     * @param member 成员会话
     * @return 部门 ID
     */
    private UUID seedDepartment(String tenant, LoginSession member) {
        UUID departmentId = seedDepartmentOnly(tenant, actorId(member));
        jdbcClient
                .sql("UPDATE app_user SET department_id = :departmentId WHERE tenant_id = :tenantId AND id = :id")
                .param("departmentId", BinaryUuids.toBytes(departmentId))
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(actorId(member)))
                .update();
        return departmentId;
    }

    /**
     * 只创建部门，不调整成员归属；用于构造“其他部门”文档。
     *
     * @param tenant 租户
     * @param createdBy 创建人
     * @return 部门 ID
     */
    private UUID seedDepartmentOnly(String tenant, UUID createdBy) {
        UUID departmentId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO department (tenant_id, id, name, status, created_by, created_at, updated_at)
                        VALUES (:tenantId, :id, :name, 'ACTIVE', :createdBy, NOW(6), NOW(6))
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(departmentId))
                .param("name", "引用测试部门-" + departmentId)
                .param("createdBy", BinaryUuids.toBytes(createdBy))
                .update();
        return departmentId;
    }

    /**
     * 种子会话。
     *
     * @param tenant 租户
     * @param workspaceId 工作空间
     * @param createdBy 创建人
     * @return 会话 ID
     */
    private UUID seedConversation(String tenant, String workspaceId, UUID createdBy) {
        UUID conversationId = UuidV7.generate(Instant.now());
        jdbcClient
                .sql("""
                        INSERT INTO conversation (
                            tenant_id, id, workspace_id, created_by, title, status, created_at, updated_at
                        ) VALUES (:tenantId, :id, :workspaceId, :createdBy, '引用测试会话', 'ACTIVE', NOW(6), NOW(6))
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
     * 种子连接器行；端点 ID 与配置只用于满足外键/约束，不参与引用状态判定。
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
                .param("name", "引用测试连接器-" + suffix)
                .update();
        return connectorId;
    }

    /**
     * 种子连接器文档：完整 externalId 只落库，标题按 B6 规则保存末段，不进入引用快照标题。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @param externalId 完整源对象 key
     * @param title 文档标题（对象 key 末段）
     * @param visibility 可见范围
     * @param departmentId 部门；非 DEPT 为空
     * @param checksum 内容 checksum
     * @return 文档 ID
     */
    private UUID seedConnectorDocument(
            String tenant,
            UUID connectorId,
            String externalId,
            String title,
            String visibility,
            UUID departmentId,
            String checksum) {
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
                            :departmentId, 'NONE', 1
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .param("title", title)
                .param("connectorId", BinaryUuids.toBytes(connectorId))
                .param("externalId", externalId)
                .param("checksum", checksum)
                .param("visibility", visibility)
                .param("departmentId", departmentId == null ? null : BinaryUuids.toBytes(departmentId))
                .update();
        return documentId;
    }

    /**
     * 种子连接器条目；同一 externalId 只有一行，重现时递增 generation 并改绑新文档。
     *
     * @param tenant 租户
     * @param connectorId 连接器 ID
     * @param documentId 绑定的文档 ID
     * @param status 条目状态
     * @param generation 代数
     * @param externalId 完整源对象 key
     * @param checksum 内容 checksum
     */
    private void seedConnectorItem(
            String tenant,
            UUID connectorId,
            UUID documentId,
            String status,
            int generation,
            String externalId,
            String checksum) {
        jdbcClient
                .sql("""
                        INSERT INTO kb_connector_item (
                            tenant_id, id, connector_id, external_id, external_id_hash, external_version,
                            checksum, document_id, generation, status, last_seen_at, created_at, updated_at
                        ) VALUES (
                            :tenantId, :id, :connectorId, :externalId, :externalIdHash, 'etag-1',
                            :checksum, :documentId, :generation, :status, NOW(6), NOW(6), NOW(6)
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UuidV7.generate(Instant.now())))
                .param("connectorId", BinaryUuids.toBytes(connectorId))
                .param("externalId", externalId)
                .param("externalIdHash", sha256Hex(externalId))
                .param("checksum", checksum)
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("generation", generation)
                .param("status", status)
                .update();
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
     * 把文档可见范围收窄为 USERS 且不保留任何用户授权；下一次请求即按当前 ACL 判定。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     */
    private void narrowToUsers(String tenant, UUID documentId) {
        jdbcClient
                .sql("""
                        UPDATE knowledge_document
                           SET visibility = 'USERS', department_id = NULL,
                               acl_version = acl_version + 1, updated_at = NOW(6)
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(documentId))
                .update();
    }

    /**
     * 计算 SHA-256 十六进制串；用于 external_id_hash 唯一键。
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
     * 种子知识库分块（含 OCR 区域与置信度）。
     *
     * @param tenant 租户
     * @param documentId 文档 ID
     * @param ordinal 分块序号
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
     * 种子知识库引用。
     *
     * @param tenant 租户
     * @param messageId 消息 ID
     * @param ordinal 序号
     * @param documentId 文档 ID
     * @param sourceVersion 版本快照
     * @param title 标题
     * @param snippet 片段
     */
    private void seedCitation(
            String tenant,
            UUID messageId,
            int ordinal,
            UUID documentId,
            UUID chunkId,
            String sourceVersion,
            String title,
            String snippet) {
        jdbcClient
                .sql("""
                        INSERT INTO message_citation (
                            tenant_id, id, message_id, ordinal, source_type, file_id, knowledge_document_id,
                            chunk_id, locator_type, page_no, sheet_name, cell_range, paragraph_no,
                            title_snapshot, snippet, score, created_at, source_version
                        ) VALUES (
                            :tenantId, :id, :messageId, :ordinal, 'KNOWLEDGE', NULL, :documentId,
                            :chunkId, 'SHEET', 2, '销售数据', 'B2:D10', 3,
                            :title, :snippet, 0.5, NOW(6), :sourceVersion
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UuidV7.generate(Instant.now())))
                .param("messageId", BinaryUuids.toBytes(messageId))
                .param("ordinal", ordinal)
                .param("documentId", BinaryUuids.toBytes(documentId))
                .param("chunkId", chunkId == null ? null : BinaryUuids.toBytes(chunkId))
                .param("title", title)
                .param("snippet", snippet)
                .param("sourceVersion", sourceVersion)
                .update();
    }

    /**
     * 种子文件引用。
     *
     * @param tenant 租户
     * @param messageId 消息 ID
     * @param ordinal 序号
     * @param snippet 片段
     */
    private void seedFileCitation(String tenant, UUID messageId, int ordinal, String snippet) {
        jdbcClient
                .sql("""
                        INSERT INTO message_citation (
                            tenant_id, id, message_id, ordinal, source_type, file_id, knowledge_document_id,
                            chunk_id, locator_type, page_no, sheet_name, cell_range, paragraph_no,
                            title_snapshot, snippet, score, created_at, source_version
                        ) VALUES (
                            :tenantId, :id, :messageId, :ordinal, 'FILE', :fileId, NULL,
                            NULL, 'PAGE', 1, NULL, NULL, NULL,
                            '附件.pdf', :snippet, NULL, NOW(6), NULL
                        )
                        """)
                .param("tenantId", tenant)
                .param("id", BinaryUuids.toBytes(UuidV7.generate(Instant.now())))
                .param("messageId", BinaryUuids.toBytes(messageId))
                .param("ordinal", ordinal)
                .param("fileId", BinaryUuids.toBytes(UUID.randomUUID()))
                .param("snippet", snippet)
                .update();
    }

    /**
     * 租户夹具。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户代码
     * @param member 普通成员会话；第二租户为空
     */
    private record Fixture(LoginSession platform, String tenant, LoginSession member) {}
}
