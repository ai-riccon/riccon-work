package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 工作空间 API 集成测试。
 *
 * <p>覆盖个人空间自动创建且唯一、团队空间对本租户可见、个人空间仅本人可见、跨租户统一 404 与 RBAC。
 */
class WorkspaceApiTest extends ApiIntegrationTestSupport {

    /** 首次列出自动创建个人空间，重复列出返回同一条记录。 */
    @Test
    @DisplayName("个人空间自动创建且唯一")
    void listWorkspaces_createsSinglePersonalWorkspace() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);

        JsonNode first = listWorkspaces(admin);
        assertThat(first.path("total").asLong()).isEqualTo(1);
        JsonNode personal = first.path("items").get(0);
        assertThat(personal.path("kind").asText()).isEqualTo("PERSONAL");
        assertThat(personal.path("ownerUserId").asText())
                .isEqualTo(admin.body().path("user").path("id").asText());
        assertThat(personal.path("status").asText()).isEqualTo("ACTIVE");

        JsonNode second = listWorkspaces(admin);
        assertThat(second.path("total").asLong()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("id").asText())
                .isEqualTo(personal.path("id").asText());

        Long count =
                jdbcClient.sql("""
                        SELECT COUNT(*) FROM workspace
                         WHERE tenant_id = :tenantId AND kind = 'PERSONAL' AND deleted_at IS NULL
                        """).param("tenantId", tenant).query(Long.class).single();
        assertThat(count).isEqualTo(1L);
    }

    /** 团队空间对同租户员工可见，个人空间不跨用户泄露。 */
    @Test
    @DisplayName("团队空间对租户可见，个人空间仅本人可见")
    void workspaceVisibility_teamSharedPersonalPrivate() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);

        JsonNode adminList = listWorkspaces(admin);
        String adminPersonalId = adminList.path("items").get(0).path("id").asText();
        JsonNode created = createTeam(admin, "调研空间", "J2 报告与知识库");
        String teamId = created.path("id").asText();
        assertThat(created.path("kind").asText()).isEqualTo("TEAM");
        assertThat(created.path("ownerUserId").asText())
                .isEqualTo(admin.body().path("user").path("id").asText());

        JsonNode memberList = listWorkspaces(member);
        assertThat(memberList.path("items").toString()).contains(teamId);
        assertThat(memberList.path("items").toString()).doesNotContain(adminPersonalId);

        JsonNode memberPersonal =
                listWorkspaces(member, "PERSONAL").path("items").get(0);
        assertThat(memberPersonal.path("ownerUserId").asText())
                .isEqualTo(member.body().path("user").path("id").asText());

        ApiTestClient.ApiResponse team = client.get("/api/v1/workspaces/" + teamId, member.sessionCookie(), Map.of());
        assertThat(team.status()).as(team.body()).isEqualTo(200);

        ApiTestClient.ApiResponse adminPersonal =
                client.get("/api/v1/workspaces/" + adminPersonalId, member.sessionCookie(), Map.of());
        assertThat(adminPersonal.status()).isEqualTo(404);
        assertThat(client.json(adminPersonal).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        JsonNode teamOnly = listWorkspaces(member, "TEAM");
        assertThat(teamOnly.path("total").asLong()).isEqualTo(1);
        assertThat(teamOnly.path("items").get(0).path("id").asText()).isEqualTo(teamId);
    }

    /** 跨租户读取工作空间统一 404，列表不泄露。 */
    @Test
    @DisplayName("跨租户工作空间读取返回统一 404")
    void getWorkspace_crossTenant_returnsNotFound() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("a");
        String tenantB = uniqueTenantCode("b");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        LoginSession adminA = createAndLoginTenantAdmin(platform, tenantA);
        LoginSession adminB = createAndLoginTenantAdmin(platform, tenantB);
        String teamA = createTeam(adminA, "A 团队空间", null).path("id").asText();
        createTeam(adminB, "B 团队空间", null);

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces/" + teamA, adminB.sessionCookie(), Map.of());
        assertThat(response.status()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        JsonNode listB = listWorkspaces(adminB);
        assertThat(listB.path("items").toString()).doesNotContain(teamA);
    }

    /** 普通员工没有 workspace.write，不能创建团队空间。 */
    @Test
    @DisplayName("普通员工创建团队空间返回 403")
    void createWorkspace_memberWithoutPermission_isForbidden() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);

        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", "member-team"),
                member.sessionCookie(),
                member.csrfToken(),
                Map.of());
        assertThat(response.status()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** 创建参数在 controller/service 边界校验。 */
    @Test
    @DisplayName("工作空间名称与描述校验")
    void createWorkspace_invalidPayload_returnsBadRequest() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);

        ApiTestClient.ApiResponse blank = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", "  "),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(blank.status()).isEqualTo(400);
        assertThat(client.json(blank).path("code").asText()).isEqualTo("RICCON-REQUEST-400");

        ApiTestClient.ApiResponse tooLong = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", "x", "description", "d".repeat(1001)),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(tooLong.status()).isEqualTo(400);

        ApiTestClient.ApiResponse unknown =
                client.get("/api/v1/workspaces/" + UUID.randomUUID(), admin.sessionCookie(), Map.of());
        assertThat(unknown.status()).isEqualTo(404);

        ApiTestClient.ApiResponse unauthenticated = client.get("/api/v1/workspaces");
        assertThat(unauthenticated.status()).isEqualTo(401);
    }

    /**
     * 列出工作空间。
     *
     * @param session 登录会话
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
     * @param session 登录会话
     * @param kind 类型
     * @return 响应 JSON
     */
    private JsonNode listWorkspaces(LoginSession session, String kind) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100&kind=" + kind, session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 创建团队空间。
     *
     * @param session 登录会话
     * @param name 名称
     * @param description 说明
     * @return 响应 JSON
     */
    private JsonNode createTeam(LoginSession session, String name, String description) {
        String body = description == null
                ? client.jsonBody("name", name)
                : client.jsonBody("name", name, "description", description);
        ApiTestClient.ApiResponse response =
                client.post("/api/v1/workspaces", body, session.sessionCookie(), session.csrfToken(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response);
    }
}
