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
