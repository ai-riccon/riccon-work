package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.common.UuidV7;
import com.riccon.api.persistence.BinaryUuids;
import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 会话 API 集成测试。
 *
 * <p>覆盖 list/create/get/patch/delete、分页、创建人私有、跨租户 404、保留期、软删除保留消息与审计。
 */
class ConversationApiTest extends ApiIntegrationTestSupport {

    /** 新建会话返回 201、标题规范化、读取与修改一致。 */
    @Test
    @DisplayName("会话创建、读取与标题修改")
    void createGetAndUpdateConversation() {
        MemberFixture fixture = memberFixture();
        String conversationId = createConversation(fixture.session(), fixture.workspaceId(), "  项目讨论  ");
        assertThat(conversationId).isNotBlank();

        ApiTestClient.ApiResponse detail = client.get(
                "/api/v1/conversations/" + conversationId, fixture.session().sessionCookie(), Map.of());
        assertThat(detail.status()).as(detail.body()).isEqualTo(200);
        JsonNode body = client.json(detail);
        assertThat(body.path("title").asText()).isEqualTo("项目讨论");
        assertThat(body.path("workspaceId").asText()).isEqualTo(fixture.workspaceId());
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("messages").isArray()).isTrue();

        ApiTestClient.ApiResponse patched = client.patch(
                "/api/v1/conversations/" + conversationId,
                client.jsonBody("title", "季度复盘"),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken());
        assertThat(patched.status()).as(patched.body()).isEqualTo(200);
        assertThat(client.json(patched).path("title").asText()).isEqualTo("季度复盘");

        JsonNode list = listConversations(fixture.session(), fixture.workspaceId(), 1, 20);
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("id").asText()).isEqualTo(conversationId);
        assertThat(list.path("items").get(0).path("title").asText()).isEqualTo("季度复盘");

        Long retentionDays = jdbcClient
                .sql("""
                        SELECT TIMESTAMPDIFF(DAY, created_at, retention_expires_at)
                          FROM conversation
                         WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(UUID.fromString(conversationId)))
                .query(Long.class)
                .single();
        assertThat(retentionDays).isEqualTo(180L);

        assertThat(auditRows("session.created"))
                .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                .anySatisfy(row -> assertThat(row.details()).contains(conversationId));
    }

    /** 分页与创建人私有：同租户管理员看不到普通员工的会话。 */
    @Test
    @DisplayName("会话列表分页且仅创建人可见")
    void listIsPagedAndOwnerScoped() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        UUID departmentId = createDepartment(platform, tenant, "会话部门" + randomSuffix());
        addDepartmentMember(platform, tenant, departmentId, userId(admin));
        addDepartmentMember(platform, tenant, departmentId, userId(member));
        String teamId = createTeamWorkspace(admin, departmentId, "共享空间");
        for (int index = 0; index < 3; index++) {
            createConversation(admin, teamId, "会话 " + index);
        }
        String otherTeamId = createTeamWorkspace(admin, departmentId, "另一个空间");
        createConversation(admin, otherTeamId, "其他空间的会话");
        createConversation(member, teamId, "员工自己的会话");

        JsonNode firstPage = listConversations(admin, teamId, 1, 2);
        assertThat(firstPage.path("total").asLong()).isEqualTo(3);
        assertThat(firstPage.path("items")).hasSize(2);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(2);

        JsonNode secondPage = listConversations(admin, teamId, 2, 2);
        assertThat(secondPage.path("items")).hasSize(1);

        JsonNode otherWorkspaceList = listConversations(admin, otherTeamId, 1, 20);
        assertThat(otherWorkspaceList.path("total").asLong()).isEqualTo(1);
        assertThat(otherWorkspaceList.path("items").get(0).path("title").asText())
                .isEqualTo("其他空间的会话");
        assertThat(otherWorkspaceList.path("items").toString()).doesNotContain("会话 0");

        JsonNode personalList = listConversations(admin, personalWorkspaceId(admin), 1, 20);
        assertThat(personalList.path("total").asLong()).isZero();

        JsonNode memberList = listConversations(member, teamId, 1, 20);
        assertThat(memberList.path("total").asLong()).isEqualTo(1);
        assertThat(memberList.path("items").get(0).path("title").asText()).isEqualTo("员工自己的会话");

        JsonNode adminList = listConversations(admin, teamId, 1, 20);
        assertThat(adminList.path("items").toString()).doesNotContain("员工自己的会话");

        ApiTestClient.ApiResponse invalidSize =
                client.get("/api/v1/workspaces/" + teamId + "/conversations?size=101", admin.sessionCookie(), Map.of());
        assertThat(invalidSize.status()).isEqualTo(400);
    }

    /** 跨租户读取/修改/删除统一 404。 */
    @Test
    @DisplayName("跨租户会话访问返回统一 404")
    void crossTenantAccessReturnsNotFound() {
        MemberFixture tenantA = memberFixture();
        String conversationId = createConversation(tenantA.session(), tenantA.workspaceId(), "机密");
        MemberFixture tenantB = memberFixture();

        ApiTestClient.ApiResponse get = client.get(
                "/api/v1/conversations/" + conversationId, tenantB.session().sessionCookie(), Map.of());
        assertThat(get.status()).isEqualTo(404);
        assertThat(client.json(get).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        ApiTestClient.ApiResponse messages = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                tenantB.session().sessionCookie(),
                Map.of());
        assertThat(messages.status()).isEqualTo(404);

        ApiTestClient.ApiResponse patch = client.patch(
                "/api/v1/conversations/" + conversationId,
                client.jsonBody("title", "hijack"),
                tenantB.session().sessionCookie(),
                tenantB.session().csrfToken());
        assertThat(patch.status()).isEqualTo(404);

        ApiTestClient.ApiResponse delete = client.delete(
                "/api/v1/conversations/" + conversationId,
                tenantB.session().sessionCookie(),
                tenantB.session().csrfToken());
        assertThat(delete.status()).isEqualTo(404);

        String title = jdbcClient
                .sql("SELECT title FROM conversation WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantA.tenant())
                .param("id", BinaryUuids.toBytes(UUID.fromString(conversationId)))
                .query(String.class)
                .single();
        assertThat(title).isEqualTo("机密");
    }

    /** 软删除保留消息并写审计，之后读取统一 404。 */
    @Test
    @DisplayName("会话软删除保留消息并写审计")
    void deleteSoftDeletesAndKeepsMessages() {
        MemberFixture fixture = memberFixture();
        String conversationId = createConversation(fixture.session(), fixture.workspaceId(), "待删除");
        UUID messageId = UuidV7.generate();
        jdbcClient
                .sql("""
                        INSERT INTO message
                            (tenant_id, id, conversation_id, seq, role, content, status, created_at, updated_at)
                        VALUES
                            (:tenantId, :id, :conversationId, 1, 'USER', '历史消息', 'COMPLETED', NOW(6), NOW(6))
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(messageId))
                .param("conversationId", BinaryUuids.toBytes(UUID.fromString(conversationId)))
                .update();

        ApiTestClient.ApiResponse deleted = client.delete(
                "/api/v1/conversations/" + conversationId,
                fixture.session().sessionCookie(),
                fixture.session().csrfToken());
        assertThat(deleted.status()).isEqualTo(204);

        ApiTestClient.ApiResponse get = client.get(
                "/api/v1/conversations/" + conversationId, fixture.session().sessionCookie(), Map.of());
        assertThat(get.status()).isEqualTo(404);
        ApiTestClient.ApiResponse messages = client.get(
                "/api/v1/conversations/" + conversationId + "/messages",
                fixture.session().sessionCookie(),
                Map.of());
        assertThat(messages.status()).isEqualTo(404);

        Long deletedAt = jdbcClient
                .sql("""
                        SELECT COUNT(*) FROM conversation
                         WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NOT NULL
                        """)
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(UUID.fromString(conversationId)))
                .query(Long.class)
                .single();
        assertThat(deletedAt).isEqualTo(1L);
        Long messagesRemaining = jdbcClient
                .sql("SELECT COUNT(*) FROM message WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.tenant())
                .param("id", BinaryUuids.toBytes(messageId))
                .query(Long.class)
                .single();
        assertThat(messagesRemaining).isEqualTo(1L);
        assertThat(auditRows("session.deleted"))
                .filteredOn(row -> fixture.tenant().equals(row.tenantId()))
                .anySatisfy(row -> assertThat(row.details()).contains(conversationId));
    }

    /** 参数与认证边界。 */
    @Test
    @DisplayName("会话参数校验与未认证拒绝")
    void validationAndAuthentication() {
        MemberFixture fixture = memberFixture();
        ApiTestClient.ApiResponse tooLong = client.post(
                "/api/v1/workspaces/" + fixture.workspaceId() + "/conversations",
                client.jsonBody("title", "x".repeat(201)),
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of());
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(client.json(tooLong).path("code").asText()).isEqualTo("RICCON-REQUEST-400");

        ApiTestClient.ApiResponse unknownWorkspace = client.post(
                "/api/v1/workspaces/" + UUID.randomUUID() + "/conversations",
                "{}",
                fixture.session().sessionCookie(),
                fixture.session().csrfToken(),
                Map.of());
        assertThat(unknownWorkspace.status()).isEqualTo(404);

        ApiTestClient.ApiResponse unknownConversation = client.get(
                "/api/v1/conversations/" + UUID.randomUUID(), fixture.session().sessionCookie(), Map.of());
        assertThat(unknownConversation.status()).isEqualTo(404);

        ApiTestClient.ApiResponse unauthenticated =
                client.get("/api/v1/workspaces/" + fixture.workspaceId() + "/conversations");
        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(client.json(unauthenticated).path("code").asText()).isEqualTo("RICCON-AUTH-401");
    }

    /**
     * 创建带成员的租户并返回个人空间。
     *
     * @return 测试夹具
     */
    private MemberFixture memberFixture() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);
        return new MemberFixture(tenant, member, personalWorkspaceId(member));
    }

    /**
     * 读取个人空间 ID。
     *
     * @param session 登录会话
     * @return 工作空间 ID
     */
    private String personalWorkspaceId(LoginSession session) {
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/workspaces?page=1&size=100", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode items = client.json(response).path("items");
        assertThat(items).isNotEmpty();
        return items.get(0).path("id").asText();
    }

    /**
     * 创建团队工作空间。
     *
     * @param session 租户管理员
     * @param name 名称
     * @return 工作空间 ID
     */
    private String createTeamWorkspace(LoginSession admin, UUID departmentId, String name) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces",
                client.jsonBody("name", name, "departmentId", departmentId.toString()),
                admin.sessionCookie(),
                admin.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response).path("id").asText();
    }

    /**
     * 读取会话中的账号 ID。
     *
     * @param session 登录会话
     * @return 账号 ID
     */
    private static String userId(LoginSession session) {
        return session.body().path("user").path("id").asText();
    }

    /**
     * 通过平台管理员创建部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param name 部门名
     * @return 部门 ID
     */
    private UUID createDepartment(LoginSession platform, String tenant, String name) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + tenant + "/departments",
                client.jsonBody("name", name),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(client.json(response).path("id").asText());
    }

    /**
     * 把账号加入部门。
     *
     * @param platform 平台管理员会话
     * @param tenant 租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     */
    private void addDepartmentMember(LoginSession platform, String tenant, UUID departmentId, String userId) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/admin/tenants/" + tenant + "/departments/" + departmentId + "/members",
                client.jsonBody("userId", userId),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    /**
     * 生成唯一后缀。
     *
     * @return 8 位后缀
     */
    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * 创建会话。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @param title 标题
     * @return 会话 ID
     */
    private String createConversation(LoginSession session, String workspaceId, String title) {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/workspaces/" + workspaceId + "/conversations",
                title == null ? "{}" : client.jsonBody("title", title),
                session.sessionCookie(),
                session.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return client.json(response).path("id").asText();
    }

    /**
     * 列出会话。
     *
     * @param session 登录会话
     * @param workspaceId 工作空间 ID
     * @param page 页码
     * @param size 每页大小
     * @return 响应 JSON
     */
    private JsonNode listConversations(LoginSession session, String workspaceId, int page, int size) {
        ApiTestClient.ApiResponse response = client.get(
                "/api/v1/workspaces/" + workspaceId + "/conversations?page=" + page + "&size=" + size,
                session.sessionCookie(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return client.json(response);
    }

    /**
     * 成员测试夹具。
     *
     * @param tenant 租户
     * @param session 成员会话
     * @param workspaceId 个人空间
     */
    private record MemberFixture(String tenant, LoginSession session, String workspaceId) {}
}
