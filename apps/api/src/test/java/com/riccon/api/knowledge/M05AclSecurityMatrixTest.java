package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * M05 ACL 安全矩阵：SEC-KB-002（403/404 与无存在性泄露）和 SEC-KB-003（四处一致与旧引用遮蔽）。
 *
 * <p>覆盖“前端隐藏入口但直接调用 API”的负向路径；检索面由 worker
 * {@code AgentContextJdbcAclTest} 覆盖。SEC-KB-001/004/005 分别由 O1b {@code ocr-check}、
 * {@code InjectionHarnessTest} 与 parser/OCR 资源上限测试覆盖，见 V2 评审映射表。
 */
@Tag("security")
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class M05AclSecurityMatrixTest extends M05AclMatrixTestSupport {

    /** SEC-KB-002：可见但角色不足 403；不可见/跨租户/平台管理员 404，无标题泄露。 */
    @Test
    @DisplayName("SEC-KB-002 可见但无权 403，不可见 404 且无存在性泄露")
    void secKb002_forbiddenVersusNotFoundWithoutExistenceLeak() {
        TenantFixture fixture = tenantFixture();
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "sec-kb", List.of("MEMBER"));

        String tenantTitle = "可见租户文档-" + UUID.randomUUID();
        JsonNode tenantDocument = createDocument(fixture, tenantTitle, "TENANT", null, null);
        String tenantDocumentId = tenantDocument.path("id").asText();

        // 可见但非创建人/非 knowledge.acl.manage：读 ACL、改 ACL、删除、重索引均 403。
        assertThat(getDocument(fixture.member(), tenantDocumentId).status()).isEqualTo(200);
        assertAuth403(getAcl(fixture.member(), tenantDocumentId));
        assertAuth403(putAcl(fixture.member(), tenantDocumentId, Map.of("visibility", "TENANT")));
        assertAuth403(delete(fixture.member(), tenantDocumentId));
        assertAuth403(reindex(fixture.member(), tenantDocumentId));

        // 403 不得产生副作用。
        Map<String, Object> tenantRow = documentRow(fixture.tenant(), tenantDocumentId);
        assertThat(tenantRow.get("visibility")).isEqualTo("TENANT");
        assertThat(tenantRow.get("acl_version")).isEqualTo(1);
        assertThat(tenantRow.get("deleted_at")).isNull();
        assertThat(documentAudit(AuditActions.KNOWLEDGE_ACL_CHANGED, fixture.tenant(), tenantDocumentId))
                .isEmpty();
        assertThat(documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, fixture.tenant(), tenantDocumentId))
                .isEmpty();

        String usersTitle = "不可见用户文档-" + UUID.randomUUID();
        JsonNode usersDocument = createDocument(fixture, usersTitle, "USERS", null, List.of(actorId(fixture.member())));
        String usersDocumentId = usersDocument.path("id").asText();

        // 不可见：详情、下载、ACL 读/写、删除、重索引统一 404，不泄露标题。
        assertTenant404(getDocument(outsider, usersDocumentId), usersTitle);
        assertTenant404(download(outsider, usersDocumentId), usersTitle);
        assertTenant404(getAcl(outsider, usersDocumentId), usersTitle);
        assertTenant404(putAcl(outsider, usersDocumentId, Map.of("visibility", "TENANT")), usersTitle);
        assertTenant404(delete(outsider, usersDocumentId), usersTitle);
        assertTenant404(reindex(outsider, usersDocumentId), usersTitle);

        // 平台管理员仍统一 404，不读取租户文档。
        assertTenant404(getDocument(fixture.platform(), usersDocumentId), usersTitle);
        assertTenant404(getAcl(fixture.platform(), usersDocumentId), usersTitle);
        assertTenant404(download(fixture.platform(), usersDocumentId), usersTitle);

        // 负向操作未产生删除/ACL 审计。
        assertThat(documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, fixture.tenant(), usersDocumentId))
                .isEmpty();
        assertThat(documentAudit(AuditActions.KNOWLEDGE_ACL_CHANGED, fixture.tenant(), usersDocumentId))
                .isEmpty();
    }

    /** SEC-KB-003：ACL 收紧后列表、详情、下载、旧引用四处一致失效。 */
    @Test
    @DisplayName("SEC-KB-003 ACL 收紧后列表/详情/下载/旧引用四处一致")
    void secKb003_fourSurfacesConsistentAfterAclTightening() {
        TenantFixture fixture = tenantFixture();
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "sec-four", List.of("MEMBER"));
        String title = "四处一致文档-" + UUID.randomUUID();
        String snippet = "四处一致片段-" + UUID.randomUUID();
        JsonNode document = createDocument(fixture, title, "TENANT", null, null);
        String documentId = document.path("id").asText();

        // 收紧前：成员可访问四处，旧引用可见并保留标题/片段。
        assertThat(visibleDocumentIds(fixture.member())).contains(documentId);
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);
        assertThat(download(fixture.member(), documentId).status()).isEqualTo(200);
        UUID conversationId =
                seedConversation(fixture.tenant(), personalWorkspace(fixture.member()), actorId(fixture.member()));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        seedCitation(fixture.tenant(), messageId, 1, UUID.fromString(documentId), null, title, snippet);
        JsonNode before = citations(fixture.member(), conversationId);
        assertThat(before.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(before.get(0).path("title").asText()).isEqualTo(title);
        assertThat(before.get(0).path("snippet").asText()).isEqualTo(snippet);

        // 收紧为仅 outsider 可见：成员下一次请求列表/详情/下载/引用四处同时失效。
        assertThat(putAcl(
                                fixture.admin(),
                                documentId,
                                Map.of(
                                        "visibility",
                                        "USERS",
                                        "userIds",
                                        List.of(actorId(outsider).toString())))
                        .status())
                .isEqualTo(200);
        assertThat(visibleDocumentIds(fixture.member())).doesNotContain(documentId);
        assertTenant404(getDocument(fixture.member(), documentId), title);
        assertTenant404(download(fixture.member(), documentId), title);
        JsonNode after = citations(fixture.member(), conversationId);
        assertMaskedCitation(after.get(0), "NO_ACCESS");
        assertThat(after.toString()).doesNotContain(title).doesNotContain(snippet);

        // 检索面：worker AgentContextJdbcAclTest#search_usersDocument_onlyAuthorizedAndCreator 复跑，
        // 断言撤销后检索无命中；API 没有独立检索端点，不在此伪造通过。
    }

    /**
     * 删除文档。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return 响应
     */
    private ApiTestClient.ApiResponse delete(LoginSession session, String documentId) {
        return client.delete("/api/v1/knowledge/documents/" + documentId, session.sessionCookie(), session.csrfToken());
    }

    /**
     * 下载文档源文件。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return 响应
     */
    private ApiTestClient.ApiResponse download(LoginSession session, String documentId) {
        return client.get("/api/v1/knowledge/documents/" + documentId + "/download", session.sessionCookie(), Map.of());
    }

    /**
     * 重索引文档。
     *
     * @param session 会话
     * @param documentId 文档 ID
     * @return 响应
     */
    private ApiTestClient.ApiResponse reindex(LoginSession session, String documentId) {
        return client.post(
                "/api/v1/knowledge/documents/" + documentId + "/reindex",
                client.jsonBody(),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
    }
}
