package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 工作空间 API 集成测试。
 *
 * <p>使用真实 MySQL 8.4（Testcontainers）与随机端口 Tomcat，覆盖个人空间自动创建且唯一、M03 部门可见性、
 * 创建必须归属启用部门、跨租户统一 404 与 RBAC。更细的 DEPT/CUSTOM/历史 TENANT/成员矩阵见
 * {@link WorkspaceScopeTest}。
 */
class WorkspaceApiTest extends DepartmentTestSupport {

    /** 首次列出自动创建个人空间，重复列出返回同一条记录。 */
    @Test
    @DisplayName("个人空间自动创建且唯一")
    void listWorkspaces_createsSinglePersonalWorkspace() {
        TenantFixture fixture = createFixture("t");

        JsonNode first = listWorkspaces(fixture.tenantAdmin());
        assertThat(first.path("total").asLong()).isEqualTo(1);
        JsonNode personal = first.path("items").get(0);
        assertThat(personal.path("kind").asText()).isEqualTo("PERSONAL");
        assertThat(personal.path("visibility").asText()).isEqualTo("PRIVATE");
        assertThat(personal.path("departmentId").isNull()).isTrue();
        assertThat(personal.path("ownerUserId").asText())
                .isEqualTo(fixture.tenantAdminId().toString());
        assertThat(personal.path("status").asText()).isEqualTo("ACTIVE");

        JsonNode second = listWorkspaces(fixture.tenantAdmin());
        assertThat(second.path("total").asLong()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("id").asText())
                .isEqualTo(personal.path("id").asText());

        Long count = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM workspace
                         WHERE tenant_id = :tenantId AND kind = 'PERSONAL' AND deleted_at IS NULL
                        """)
                .param("tenantId", fixture.tenantId())
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);
    }

    /** 部门空间只对本部门启用成员可见，个人空间仅本人可见。 */
    @Test
    @DisplayName("部门空间限本部门可见，个人空间仅本人可见")
    void workspaceVisibility_departmentSharedPersonalPrivate() {
        TenantFixture fixture = createFixture("t");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "研发部");
        addMember(fixture.platform(), fixture.tenantId(), departmentId, fixture.tenantAdminId());
        addMember(fixture.platform(), fixture.tenantId(), departmentId, fixture.memberId());

        JsonNode created = createWorkspace(fixture.tenantAdmin(), departmentId, "调研空间", "J2 报告与知识库", "DEPT");
        String teamId = created.path("id").asText();
        assertThat(created.path("kind").asText()).isEqualTo("TEAM");
        assertThat(created.path("visibility").asText()).isEqualTo("DEPT");
        assertThat(created.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(created.path("departmentName").asText()).isEqualTo("研发部");

        JsonNode memberList = listWorkspaces(fixture.member());
        assertThat(memberList.toString()).contains(teamId);

        JsonNode adminPersonal =
                listWorkspaces(fixture.tenantAdmin(), "PERSONAL").path("items").get(0);
        String adminPersonalId = adminPersonal.path("id").asText();

        ApiTestClient.ApiResponse team =
                client.get("/api/v1/workspaces/" + teamId, fixture.member().sessionCookie(), Map.of());
        assertThat(team.status()).as(team.body()).isEqualTo(200);

        ApiTestClient.ApiResponse foreignPersonal = client.get(
                "/api/v1/workspaces/" + adminPersonalId, fixture.member().sessionCookie(), Map.of());
        assertThat(foreignPersonal.status()).isEqualTo(404);
        assertThat(client.json(foreignPersonal).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        JsonNode teamOnly = listWorkspaces(fixture.member(), "TEAM");
        assertThat(teamOnly.path("total").asLong()).isEqualTo(1);
        assertThat(teamOnly.path("items").get(0).path("id").asText()).isEqualTo(teamId);
    }

    /** 跨租户读取工作空间统一 404，列表不泄露。 */
    @Test
    @DisplayName("跨租户工作空间读取返回统一 404")
    void getWorkspace_crossTenant_returnsNotFound() {
        TenantFixture tenantA = createFixture("a");
        TenantFixture tenantB = createFixture("b");
        UUID departmentA = createDepartmentId(tenantA.platform(), tenantA.tenantId(), "A 部门");
        UUID departmentB = createDepartmentId(tenantB.platform(), tenantB.tenantId(), "B 部门");
        addMember(tenantA.platform(), tenantA.tenantId(), departmentA, tenantA.tenantAdminId());
        addMember(tenantB.platform(), tenantB.tenantId(), departmentB, tenantB.tenantAdminId());
        String teamA = createWorkspace(tenantA.tenantAdmin(), departmentA, "A 团队空间", null, "DEPT")
                .path("id")
                .asText();
        createWorkspace(tenantB.tenantAdmin(), departmentB, "B 团队空间", null, "DEPT");

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces/" + teamA, tenantB.tenantAdmin().sessionCookie(), Map.of());
        assertThat(response.status()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        JsonNode listB = listWorkspaces(tenantB.tenantAdmin());
        assertThat(listB.path("items").toString()).doesNotContain(teamA);
    }

    /** 普通员工没有 workspace.write，不能创建团队空间。 */
    @Test
    @DisplayName("普通员工创建团队空间返回 403")
    void createWorkspace_memberWithoutPermission_isForbidden() {
        TenantFixture fixture = createFixture("t");

        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", "member-team"),
                fixture.member().sessionCookie(),
                fixture.member().csrfToken(),
                Map.of());
        assertThat(response.status()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** 新建团队空间必须提供启用部门。 */
    @Test
    @DisplayName("创建团队空间缺少部门返回 400")
    void createWorkspace_missingDepartment_returnsBadRequest() {
        TenantFixture fixture = createFixture("t");

        ApiTestClient.ApiResponse missing = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", "无部门空间"),
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertThat(missing.status()).isEqualTo(400);
        assertThat(client.json(missing).path("code").asText()).isEqualTo("RICCON-REQUEST-400");
    }

    /** TENANT/PRIVATE 不允许新建，只有 DEPT/CUSTOM。 */
    @Test
    @DisplayName("新建团队空间拒绝 TENANT 与 PRIVATE 可见性")
    void createWorkspace_rejectsNonDeptVisibility() {
        TenantFixture fixture = createFixture("t");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "研发部");
        addMember(fixture.platform(), fixture.tenantId(), departmentId, fixture.tenantAdminId());

        ApiTestClient.ApiResponse tenant = createWorkspaceRaw(fixture.tenantAdmin(), "租户空间", departmentId, "TENANT");
        assertThat(tenant.status()).isEqualTo(400);
        assertThat(client.json(tenant).path("code").asText()).isEqualTo("RICCON-REQUEST-400");

        ApiTestClient.ApiResponse privateVisibility =
                createWorkspaceRaw(fixture.tenantAdmin(), "私有空间", departmentId, "PRIVATE");
        assertThat(privateVisibility.status()).isEqualTo(400);
    }

    /** 停用部门不允许创建空间。 */
    @Test
    @DisplayName("停用部门创建空间返回 400")
    void createWorkspace_disabledDepartment_returnsBadRequest() {
        TenantFixture fixture = createFixture("t");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "将停用部门");
        addMember(fixture.platform(), fixture.tenantId(), departmentId, fixture.tenantAdminId());
        ApiTestClient.ApiResponse disabled = client.post(
                departmentsPath(fixture.tenantId()) + "/" + departmentId + "/disable",
                null,
                fixture.platform().sessionCookie(),
                fixture.platform().csrfToken(),
                Map.of());
        assertThat(disabled.status()).as(disabled.body()).isEqualTo(200);

        ApiTestClient.ApiResponse response = createWorkspaceRaw(fixture.tenantAdmin(), "停用部门空间", departmentId, "DEPT");
        assertThat(response.status()).isEqualTo(400);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-REQUEST-400");
    }

    /** 创建参数在 controller/service 边界校验。 */
    @Test
    @DisplayName("工作空间名称与描述校验")
    void createWorkspace_invalidPayload_returnsBadRequest() {
        TenantFixture fixture = createFixture("t");
        UUID departmentId = createDepartmentId(fixture.platform(), fixture.tenantId(), "研发部");
        addMember(fixture.platform(), fixture.tenantId(), departmentId, fixture.tenantAdminId());

        ApiTestClient.ApiResponse blank = createWorkspaceRaw(fixture.tenantAdmin(), "  ", departmentId, "DEPT");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(client.json(blank).path("code").asText()).isEqualTo("RICCON-REQUEST-400");

        ApiTestClient.ApiResponse tooLong = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", "x", "departmentId", departmentId.toString(), "description", "d".repeat(1001)),
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertThat(tooLong.status()).isEqualTo(400);

        ApiTestClient.ApiResponse unknown = client.get(
                "/api/v1/workspaces/" + UUID.randomUUID(), fixture.tenantAdmin().sessionCookie(), Map.of());
        assertThat(unknown.status()).isEqualTo(404);

        ApiTestClient.ApiResponse unauthenticated = client.get("/api/v1/workspaces");
        assertThat(unauthenticated.status()).isEqualTo(401);
    }

    /**
     * 通过 POST /workspaces 创建部门空间并断言 201。
     *
     * @param session 租户管理员会话
     * @param departmentId 部门 ID
     * @param name 名称
     * @param description 说明
     * @param visibility 可见性
     * @return 响应 JSON
     */
    private JsonNode createWorkspace(
            LoginSession session, UUID departmentId, String name, String description, String visibility) {
        ApiTestClient.ApiResponse response = createWorkspaceRaw(session, name, departmentId, visibility, description);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }

    /**
     * 调用 POST /workspaces。
     *
     * @param session 会话
     * @param name 名称
     * @param departmentId 部门 ID
     * @param visibility 可见性
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse createWorkspaceRaw(
            LoginSession session, String name, UUID departmentId, String visibility) {
        return createWorkspaceRaw(session, name, departmentId, visibility, null);
    }

    /**
     * 调用 POST /workspaces，可选说明。
     *
     * @param session 会话
     * @param name 名称
     * @param departmentId 部门 ID
     * @param visibility 可见性
     * @param description 说明
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse createWorkspaceRaw(
            LoginSession session, String name, UUID departmentId, String visibility, String description) {
        String body = description == null
                ? client.jsonBody("name", name, "departmentId", departmentId.toString(), "visibility", visibility)
                : client.jsonBody(
                        "name",
                        name,
                        "departmentId",
                        departmentId.toString(),
                        "visibility",
                        visibility,
                        "description",
                        description);
        return client.post("/api/v1/workspaces", body, session.sessionCookie(), session.csrfToken(), Map.of());
    }

    /**
     * 列出全部工作空间。
     *
     * @param session 会话
     * @return 响应 JSON
     */
    private JsonNode listWorkspaces(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 按类型列出工作空间。
     *
     * @param session 会话
     * @param kind 类型
     * @return 响应 JSON
     */
    private JsonNode listWorkspaces(LoginSession session, String kind) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100&kind=" + kind, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }
}
