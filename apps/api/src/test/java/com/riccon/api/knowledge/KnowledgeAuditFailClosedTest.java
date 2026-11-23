package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.riccon.api.audit.domain.AuditEvent;
import com.riccon.api.audit.repository.AuditRepository;
import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * 知识库审计 fail-closed：管理读取、删除与 ACL 变更审计失败时业务必须失败并回滚。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class KnowledgeAuditFailClosedTest extends KnowledgeAclTestSupport {

    /** 审计仓储 spy；启动与租户夹具走真实实现，只让目标动作抛错。 */
    @MockitoSpyBean
    private AuditRepository auditRepository;

    /** 管理列表审计失败 -> 500，不返回列表。 */
    @Test
    @DisplayName("admin_read(LIST) 审计失败使列表失败")
    void adminList_auditFailureFailsRequest() {
        failAction(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ);
        TenantFixture fixture = tenantFixture();
        createDocument(fixture, "审计失败列表", null, null, null);

        ApiTestClient.ApiResponse response = listDocuments(fixture.admin());
        assertThat(response.status()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-COMMON-500");
    }

    /** 删除审计失败 -> 500，文档不软删除、不投递索引 DELETE。 */
    @Test
    @DisplayName("deleted 审计失败回滚删除")
    void delete_auditFailureRollsBack() {
        failAction(AuditActions.KNOWLEDGE_DOCUMENT_DELETED);
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "审计失败删除", null, null, null);
        String documentId = document.path("id").asText();

        ApiTestClient.ApiResponse response = client.delete(
                "/api/v1/knowledge/documents/" + documentId,
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken());
        assertThat(response.status()).isEqualTo(500);
        Map<String, Object> row = documentRow(fixture.tenant(), documentId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("deleted_at")).isNull();
        assertThat(documentEvents(fixture.tenant(), documentId).stream()
                        .filter(event -> event.idempotencyKey().contains(":delete:"))
                        .count())
                .isZero();
    }

    /** ACL 变更审计失败 -> 500，可见范围与版本不变。 */
    @Test
    @DisplayName("acl.changed 审计失败回滚 ACL")
    void aclChanged_auditFailureRollsBack() {
        failAction(AuditActions.KNOWLEDGE_ACL_CHANGED);
        TenantFixture fixture = tenantFixture();
        JsonNode document = createDocument(fixture, "审计失败 ACL", null, null, null);
        String documentId = document.path("id").asText();

        ApiTestClient.ApiResponse response = putAcl(
                fixture.admin(),
                documentId,
                Map.of(
                        "visibility",
                        "USERS",
                        "userIds",
                        List.of(actorId(fixture.member()).toString())));
        assertThat(response.status()).isEqualTo(500);
        Map<String, Object> row = documentRow(fixture.tenant(), documentId);
        assertThat(row.get("visibility")).isEqualTo("TENANT");
        assertThat(row.get("acl_version")).isEqualTo(1);
        assertThat(aclRowCount(fixture.tenant(), documentId)).isZero();
        assertThat(documentEvents(fixture.tenant(), documentId).stream()
                        .filter(event -> event.idempotencyKey().contains(":acl:"))
                        .count())
                .isZero();
    }

    /** 管理员下载审计失败 -> 500，不打开源文件流。 */
    @Test
    @DisplayName("admin_read(DOWNLOAD) 审计失败使下载失败")
    void adminDownload_auditFailureFailsRequest() {
        failAction(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ);
        TenantFixture fixture = tenantFixture();
        UUID dept = createDepartment(fixture.platform(), fixture.tenant(), "AUDIT-DEPT");
        LoginSession creator = seedLogin(fixture.platform(), fixture.tenant(), "creator", List.of("TENANT_ADMIN"));
        JsonNode document = createDocumentAs(creator, personalWorkspace(creator), "审计失败下载", "DEPT", dept, null);

        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/knowledge/documents/" + document.path("id").asText() + "/download",
                fixture.admin().sessionCookie(),
                Map.of());
        assertThat(response.status()).isEqualTo(500);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-COMMON-500");
    }

    /**
     * 让指定审计动作抛错，其余动作走真实实现。
     *
     * @param action 动作
     */
    private void failAction(String action) {
        doAnswer(invocation -> {
                    AuditEvent event = invocation.getArgument(0);
                    if (action.equals(event.action())) {
                        throw new DataAccessResourceFailureException("audit storage unavailable");
                    }
                    return invocation.callRealMethod();
                })
                .when(auditRepository)
                .append(any(AuditEvent.class));
    }
}
