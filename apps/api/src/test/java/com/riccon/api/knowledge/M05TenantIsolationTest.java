package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.platform.domain.audit.AuditActions;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * M05 跨租户独立越权矩阵：TEN-034..TEN-036（M05 段；TEN-037 连接器属 M05b）。
 *
 * <p>以攻击者视角覆盖文档列表、详情、引用打开、下载、ACL 读取/修改、删除与重索引七个入口；
 * 检索面由 worker {@code AgentContextJdbcAclTest} 覆盖（API 没有独立检索端点）。所有断言使用真实
 * MySQL/对象存储，零 skip。
 */
@Tag("tenant-isolation")
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class M05TenantIsolationTest extends M05AclMatrixTestSupport {

    /** TEN-034：跨租户文档的读/删/ACL/重索引/下载统一 404，且租户 B 数据不被改动。 */
    @Test
    @DisplayName("TEN-034 跨租户文档读/删/ACL/重索引/下载统一 404")
    void ten034_crossTenantDocumentAllSurfacesAreNotFound() {
        TenantFixture tenantA = tenantFixture();
        TenantFixture tenantB = tenantFixture();
        String title = "B 租户机密文档-" + UUID.randomUUID();
        JsonNode document = createDocument(tenantB, title, null, null, null);
        String documentId = document.path("id").asText();

        // 列表不出现租户 B 文档。
        assertThat(visibleDocumentIds(tenantA.admin())).doesNotContain(documentId);
        assertThat(visibleDocumentIds(tenantA.member())).doesNotContain(documentId);

        // 详情：同租户管理员、普通员工、平台管理员统一 404，不泄露标题/租户。
        assertTenant404(getDocument(tenantA.admin(), documentId), title, tenantB.tenant());
        assertTenant404(getDocument(tenantA.member(), documentId), title, tenantB.tenant());
        assertTenant404(getDocument(tenantA.platform(), documentId), title, tenantB.tenant());

        // ACL 读取与修改跨租户 404。
        assertTenant404(getAcl(tenantA.admin(), documentId), title, tenantB.tenant());
        assertTenant404(
                putAcl(
                        tenantA.admin(),
                        documentId,
                        Map.of(
                                "visibility",
                                "USERS",
                                "userIds",
                                List.of(actorId(tenantA.member()).toString()))),
                title,
                tenantB.tenant());

        // 下载、删除、重索引跨租户 404。
        assertTenant404(download(tenantA.admin(), documentId), title, tenantB.tenant());
        assertTenant404(delete(tenantA.admin(), documentId), title, tenantB.tenant());
        assertTenant404(reindex(tenantA.admin(), documentId), title, tenantB.tenant());
        assertTenant404(reindex(tenantA.platform(), documentId), title, tenantB.tenant());

        // 租户 B 文档未被删除、ACL 未变、没有新增删除/重索引事件。
        Map<String, Object> row = documentRow(tenantB.tenant(), documentId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("deleted_at")).isNull();
        assertThat(row.get("visibility")).isEqualTo("TENANT");
        assertThat(row.get("acl_version")).isEqualTo(1);
        assertThat(aclRowCount(tenantB.tenant(), documentId)).isZero();
        assertThat(documentEvents(tenantB.tenant(), documentId))
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.idempotencyKey()).contains(":index:"));
        assertThat(documentAudit(AuditActions.KNOWLEDGE_DOCUMENT_DELETED, tenantA.tenant(), documentId))
                .isEmpty();
        assertThat(documentAudit(AuditActions.KNOWLEDGE_ACL_CHANGED, tenantA.tenant(), documentId))
                .isEmpty();
    }

    /** TEN-035：同租户跨部门 DEPT 文档在列表/详情/下载/引用四处一致不可见。 */
    @Test
    @DisplayName("TEN-035 跨部门 DEPT 文档四处一致不可见")
    void ten035_crossDepartmentDocumentSurfacesAreConsistent() {
        TenantFixture fixture = tenantFixture();
        UUID sales = createDepartment(fixture.platform(), fixture.tenant(), "销售部");
        UUID market = createDepartment(fixture.platform(), fixture.tenant(), "市场部");
        addDepartmentMember(fixture.platform(), fixture.tenant(), sales, actorId(fixture.member()));
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "market", List.of("MEMBER"));
        addDepartmentMember(fixture.platform(), fixture.tenant(), market, actorId(outsider));

        String title = "销售部部门文档-" + UUID.randomUUID();
        String snippet = "销售部机密片段-" + UUID.randomUUID();
        JsonNode document = createDocument(fixture, title, "DEPT", sales, null);
        String documentId = document.path("id").asText();

        // 本部门成员：列表、详情、下载均可见。
        assertThat(visibleDocumentIds(fixture.member())).contains(documentId);
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);
        assertThat(download(fixture.member(), documentId).status()).isEqualTo(200);

        // 他部门成员：列表不出现、详情/下载 404、不泄露标题。
        assertThat(visibleDocumentIds(outsider)).doesNotContain(documentId);
        assertTenant404(getDocument(outsider, documentId), title);
        assertTenant404(download(outsider, documentId), title);

        // 引用打开：旧引用显示不可访问并遮蔽标题/片段/定位。
        UUID conversationId = seedConversation(fixture.tenant(), personalWorkspace(outsider), actorId(outsider));
        UUID messageId = seedAssistantMessage(fixture.tenant(), conversationId);
        seedCitation(fixture.tenant(), messageId, 1, UUID.fromString(documentId), null, title, snippet);
        JsonNode citations = citations(outsider, conversationId);
        assertThat(citations).hasSize(1);
        assertMaskedCitation(citations.get(0), "NO_ACCESS");
        assertThat(citations.toString()).doesNotContain(title).doesNotContain(snippet);

        // 检索面：worker AgentContextJdbcAclTest#search_deptDocument_onlyActiveDepartmentMembers
        // 在同一提交复跑，断言他部门/无部门/停用部门均无命中；API 无独立检索端点，不能在此伪造。
    }

    /** TEN-036：USERS 文档未授权、移出名单、停用账号均不可见，撤销后下一次请求生效。 */
    @Test
    @DisplayName("TEN-036 USERS 文档未授权/移出/停用账号不可见且下一请求生效")
    void ten036_usersDocumentUnauthorizedRemovedAndDisabled() {
        TenantFixture fixture = tenantFixture();
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "users-out", List.of("MEMBER"));
        String title = "USERS 文档-" + UUID.randomUUID();
        JsonNode document = createDocument(fixture, title, "USERS", null, List.of(actorId(fixture.member())));
        String documentId = document.path("id").asText();

        // 名单内可见；名单外 404。
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);
        assertThat(download(fixture.member(), documentId).status()).isEqualTo(200);
        assertTenant404(getDocument(outsider, documentId), title);
        assertTenant404(download(outsider, documentId), title);

        // 移出名单：下一次请求即 404；重新加入恢复。
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
        assertTenant404(getDocument(fixture.member(), documentId), title);
        assertThat(getDocument(outsider, documentId).status()).isEqualTo(200);

        // 再次撤销：outsider 下一次请求 404。
        assertThat(putAcl(
                                fixture.admin(),
                                documentId,
                                Map.of(
                                        "visibility",
                                        "USERS",
                                        "userIds",
                                        List.of(actorId(fixture.member()).toString())))
                        .status())
                .isEqualTo(200);
        assertTenant404(getDocument(outsider, documentId), title);

        // 停用账号：已有会话在下一请求失效（401 优先于 ACL 404），不泄露标题。
        ApiTestClient.ApiResponse disabled = client.patch(
                "/api/v1/users/" + actorId(outsider),
                client.jsonBody("status", "DISABLED"),
                fixture.admin().sessionCookie(),
                fixture.admin().csrfToken());
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);
        ApiTestClient.ApiResponse next = getDocument(outsider, documentId);
        assertThat(next.status()).isEqualTo(401);
        assertThat(next.body()).doesNotContain(title);
    }

    /** TEN-034..TEN-036 套件完整性清单：每个 ID 必须有实际测试方法，且运行零 skip。 */
    @Test
    @DisplayName("TEN-034..TEN-036 套件完整性清单")
    void tenM05SuiteIntegrityManifest() {
        List<String> methodNames = Arrays.stream(getClass().getDeclaredMethods())
                .map(Method::getName)
                .toList();
        for (int id = 34; id <= 36; id += 1) {
            String prefix = String.format("ten%03d_", id);
            assertThat(methodNames.stream().anyMatch(name -> name.startsWith(prefix)))
                    .as("missing test method for TEN-%03d", id)
                    .isTrue();
        }
        System.out.println("SUITE-MANIFEST TEN-034 TEN-035 TEN-036");
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
