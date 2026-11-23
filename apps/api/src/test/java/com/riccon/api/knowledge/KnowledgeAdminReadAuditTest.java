package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M05 U8 管理性读取审计：LIST/DETAIL 字段、按 ACL 可见不写、普通员工不写。
 */
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class KnowledgeAdminReadAuditTest extends KnowledgeAclTestSupport {

    /** JSON 解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 管理员列表每次写 scope=LIST 与 resultCount；普通员工列表不写。 */
    @Test
    @DisplayName("管理列表写 admin_read(scope=LIST)")
    void adminList_writesListAudit() throws Exception {
        TenantFixture fixture = tenantFixture();
        createDocument(fixture, "列表审计", null, null, null);

        ApiTestClient.ApiResponse list = listDocuments(fixture.admin());
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        long total = client.json(list).path("total").asLong();
        List<AuditRow> audits = adminReads(fixture.tenant(), actorId(fixture.admin()));
        assertThat(audits).hasSize(1);
        JsonNode details = JSON.readTree(audits.get(0).details());
        assertThat(details.path("scope").asText()).isEqualTo("LIST");
        assertThat(details.path("reason").asText()).isEqualTo("MANAGEMENT_READ");
        assertThat(details.path("resultCount").asLong()).isEqualTo(total);
        assertThat(details.path("documentId").isNull()).isTrue();
        assertThat(details.path("visibility").isNull()).isTrue();

        assertThat(listDocuments(fixture.member()).status()).isEqualTo(200);
        assertThat(adminReads(fixture.tenant(), actorId(fixture.member()))).isEmpty();
    }

    /** 详情管理性读取只在文档按 ACL 不可见时写 DETAIL；按 ACL 可见不写。 */
    @Test
    @DisplayName("详情管理读取写 admin_read(scope=DETAIL)")
    void adminDetail_writesDetailAuditOnlyWhenAclInvisible() throws Exception {
        TenantFixture fixture = tenantFixture();
        UUID dept = createDepartment(fixture.platform(), fixture.tenant(), "ADMIN-DEPT");
        LoginSession creator = seedLogin(fixture.platform(), fixture.tenant(), "creator", List.of("TENANT_ADMIN"));
        JsonNode deptDoc = createDocumentAs(creator, personalWorkspace(creator), "部门详情", "DEPT", dept, null);
        String deptId = deptDoc.path("id").asText();

        assertThat(getDocument(fixture.admin(), deptId).status()).isEqualTo(200);
        List<AuditRow> audits = adminReads(fixture.tenant(), actorId(fixture.admin()));
        assertThat(audits).hasSize(1);
        JsonNode details = JSON.readTree(audits.get(0).details());
        assertThat(details.path("scope").asText()).isEqualTo("DETAIL");
        assertThat(details.path("documentId").asText()).isEqualTo(deptId);
        assertThat(details.path("visibility").asText()).isEqualTo("DEPT");
        assertThat(details.path("aclUserCount").asLong()).isZero();

        JsonNode tenantDoc = createDocument(fixture, "租户详情", null, null, null);
        assertThat(getDocument(fixture.admin(), tenantDoc.path("id").asText()).status())
                .isEqualTo(200);
        assertThat(adminReads(fixture.tenant(), actorId(fixture.admin()))).hasSize(1);

        assertThat(getDocument(fixture.member(), tenantDoc.path("id").asText()).status())
                .isEqualTo(200);
        assertThat(adminReads(fixture.tenant(), actorId(fixture.member()))).isEmpty();
    }

    /**
     * 读取指定租户/操作者的 admin_read 审计。
     *
     * @param tenant 租户
     * @param actorId 操作者
     * @return 审计行
     */
    private List<AuditRow> adminReads(String tenant, UUID actorId) {
        return auditRows(AuditActions.KNOWLEDGE_DOCUMENT_ADMIN_READ).stream()
                .filter(row -> tenant.equals(row.tenantId()))
                .filter(row -> actorId.toString().equals(row.actorId()))
                .toList();
    }
}
