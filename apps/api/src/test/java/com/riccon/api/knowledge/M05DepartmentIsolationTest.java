package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
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
 * M05 部门维度独立越权矩阵：DEPT-011..DEPT-014。
 *
 * <p>验证 DEPT 文档四处一致、部门停用/成员移出下一请求生效、部门切换为指定用户后新成员不再自动可见，
 * 以及知识库下载不放宽原工作空间文件路径。检索面由 worker {@code AgentContextJdbcAclTest} 覆盖。
 */
@Tag("dept-isolation")
@TestPropertySource(properties = "riccon.kb.acl.enforcement-enabled=true")
class M05DepartmentIsolationTest extends M05AclMatrixTestSupport {

    /** DEPT-011：DEPT 文档本部门可见、他部门 404，列表/详情/下载/引用四处一致。 */
    @Test
    @DisplayName("DEPT-011 DEPT 文档四处一致与本部门/他部门边界")
    void dept011_departmentDocumentFourSurfacesAreConsistent() {
        TenantFixture fixture = tenantFixture();
        UUID sales = createDepartment(fixture.platform(), fixture.tenant(), "销售部");
        UUID market = createDepartment(fixture.platform(), fixture.tenant(), "市场部");
        addDepartmentMember(fixture.platform(), fixture.tenant(), sales, actorId(fixture.member()));
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "other-dept", List.of("MEMBER"));
        addDepartmentMember(fixture.platform(), fixture.tenant(), market, actorId(outsider));

        String title = "部门可见文档-" + UUID.randomUUID();
        String snippet = "部门可见片段-" + UUID.randomUUID();
        JsonNode document = createDocument(fixture, title, "DEPT", sales, null);
        String documentId = document.path("id").asText();

        // 本部门成员：列表/详情/下载/引用四面一致可见。
        assertThat(visibleDocumentIds(fixture.member())).contains(documentId);
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);
        assertThat(download(fixture.member(), documentId).status()).isEqualTo(200);
        UUID memberConversation =
                seedConversation(fixture.tenant(), personalWorkspace(fixture.member()), actorId(fixture.member()));
        UUID memberMessage = seedAssistantMessage(fixture.tenant(), memberConversation);
        seedCitation(fixture.tenant(), memberMessage, 1, UUID.fromString(documentId), null, title, snippet);
        JsonNode memberCitations = citations(fixture.member(), memberConversation);
        assertThat(memberCitations.get(0).path("sourceStatus").asText()).isEqualTo("AVAILABLE");

        // 他部门成员：列表/详情/下载 404，引用 NO_ACCESS 且不泄露标题/片段/定位。
        assertThat(visibleDocumentIds(outsider)).doesNotContain(documentId);
        assertTenant404(getDocument(outsider, documentId), title);
        assertTenant404(download(outsider, documentId), title);
        UUID outsiderConversation = seedConversation(fixture.tenant(), personalWorkspace(outsider), actorId(outsider));
        UUID outsiderMessage = seedAssistantMessage(fixture.tenant(), outsiderConversation);
        seedCitation(fixture.tenant(), outsiderMessage, 1, UUID.fromString(documentId), null, title, snippet);
        JsonNode outsiderCitations = citations(outsider, outsiderConversation);
        assertMaskedCitation(outsiderCitations.get(0), "NO_ACCESS");
        assertThat(outsiderCitations.toString()).doesNotContain(title).doesNotContain(snippet);

        // 检索面：worker AgentContextJdbcAclTest#search_deptDocument_onlyActiveDepartmentMembers 复跑。
    }

    /** DEPT-012：部门停用与成员移出后，下一次请求 404。 */
    @Test
    @DisplayName("DEPT-012 部门停用/成员移出下一次请求 404")
    void dept012_departmentDisableAndMemberRemovalTakeEffectOnNextRequest() {
        TenantFixture fixture = tenantFixture();
        UUID sales = createDepartment(fixture.platform(), fixture.tenant(), "销售部");
        addDepartmentMember(fixture.platform(), fixture.tenant(), sales, actorId(fixture.member()));
        String title = "部门停用文档-" + UUID.randomUUID();
        JsonNode document = createDocument(fixture, title, "DEPT", sales, null);
        String documentId = document.path("id").asText();

        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);
        assertThat(download(fixture.member(), documentId).status()).isEqualTo(200);

        removeDepartmentMember(fixture.platform(), fixture.tenant(), sales, actorId(fixture.member()));
        assertThat(visibleDocumentIds(fixture.member())).doesNotContain(documentId);
        assertTenant404(getDocument(fixture.member(), documentId), title);
        assertTenant404(download(fixture.member(), documentId), title);

        addDepartmentMember(fixture.platform(), fixture.tenant(), sales, actorId(fixture.member()));
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);

        disableDepartment(fixture.platform(), fixture.tenant(), sales);
        assertThat(visibleDocumentIds(fixture.member())).doesNotContain(documentId);
        assertTenant404(getDocument(fixture.member(), documentId), title);
        assertTenant404(download(fixture.member(), documentId), title);
    }

    /** DEPT-013：部门范围切换为指定用户后，后加入部门者不再自动可见。 */
    @Test
    @DisplayName("DEPT-013 部门切换为指定用户后新部门成员不再自动可见")
    void dept013_switchingToUsersStopsAutomaticDepartmentVisibility() {
        TenantFixture fixture = tenantFixture();
        UUID sales = createDepartment(fixture.platform(), fixture.tenant(), "销售部");
        addDepartmentMember(fixture.platform(), fixture.tenant(), sales, actorId(fixture.member()));
        LoginSession lateJoiner = seedLogin(fixture.platform(), fixture.tenant(), "late", List.of("MEMBER"));

        String title = "部门转名单文档-" + UUID.randomUUID();
        JsonNode document = createDocument(fixture, title, "DEPT", sales, null);
        String documentId = document.path("id").asText();
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);

        // 切换到 USERS：只保留原成员；后加入部门者不自动获得访问。
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
        addDepartmentMember(fixture.platform(), fixture.tenant(), sales, actorId(lateJoiner));
        assertThat(visibleDocumentIds(lateJoiner)).doesNotContain(documentId);
        assertTenant404(getDocument(lateJoiner, documentId), title);
        assertTenant404(download(lateJoiner, documentId), title);

        // 原成员保留；创建人始终可见。
        assertThat(getDocument(fixture.member(), documentId).status()).isEqualTo(200);
        assertThat(getDocument(fixture.admin(), documentId).status()).isEqualTo(200);
    }

    /** DEPT-014：知识库下载按文档 ACL，原工作空间文件路径仍按空间谓词，二者互不放宽。 */
    @Test
    @DisplayName("DEPT-014 知识库下载不放宽原工作空间文件路径")
    void dept014_knowledgeDownloadDoesNotRelaxWorkspaceFilePath() {
        TenantFixture fixture = tenantFixture();
        UUID sales = createDepartment(fixture.platform(), fixture.tenant(), "销售部");
        UUID market = createDepartment(fixture.platform(), fixture.tenant(), "市场部");
        addDepartmentMember(fixture.platform(), fixture.tenant(), sales, actorId(fixture.member()));
        LoginSession outsider = seedLogin(fixture.platform(), fixture.tenant(), "market2", List.of("MEMBER"));
        addDepartmentMember(fixture.platform(), fixture.tenant(), market, actorId(outsider));

        // 源文件由租户管理员上传到管理员个人空间；DEPT 成员不是该空间成员。
        JsonNode document = createDocument(fixture, "部门下载文档-" + UUID.randomUUID(), "DEPT", sales, null);
        String documentId = document.path("id").asText();
        String fileId = document.path("fileId").asText();
        assertThat(fileId).isNotBlank();

        // 知识库下载按文档 ACL 允许本部门成员。
        assertThat(download(fixture.member(), documentId).status()).isEqualTo(200);

        // 原工作空间文件路径仍按空间谓词：非空间成员 404，不能借知识库 ACL 绕过。
        assertTenant404(get(fixture.member(), "/api/v1/files/" + fileId));
        assertTenant404(get(fixture.member(), "/api/v1/files/" + fileId + "/download-url"));

        // 他部门成员连知识库下载也 404。
        assertTenant404(download(outsider, documentId));
    }

    /** DEPT-011..DEPT-014 套件完整性清单：每个 ID 必须有实际测试方法，且运行零 skip。 */
    @Test
    @DisplayName("DEPT-011..DEPT-014 套件完整性清单")
    void deptM05SuiteIntegrityManifest() {
        List<String> methodNames = Arrays.stream(getClass().getDeclaredMethods())
                .map(Method::getName)
                .toList();
        for (int id = 11; id <= 14; id += 1) {
            String prefix = String.format("dept%03d_", id);
            assertThat(methodNames.stream().anyMatch(name -> name.startsWith(prefix)))
                    .as("missing test method for DEPT-%03d", id)
                    .isTrue();
        }
        System.out.println("SUITE-MANIFEST DEPT-011 DEPT-012 DEPT-013 DEPT-014");
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
     * 发送 GET 请求。
     *
     * @param session 会话
     * @param path 路径
     * @return 响应
     */
    private ApiTestClient.ApiResponse get(LoginSession session, String path) {
        return client.get(path, session.sessionCookie(), Map.of());
    }
}
