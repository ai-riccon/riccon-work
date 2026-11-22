package com.riccon.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 会话摘要与管理端账号列表的部门字段集成契约（M23 / M13 F-001）。
 *
 * <p>覆盖验收要求的四态：成员（已分配启用部门）、未分配部门、停用部门、租户管理员。断言真实 MySQL + HTTP
 * 链路上的 {@code SessionInfo.user} 与 {@code UserSummary}：未分配时字段为 {@code null}；部门停用后仍返回名称；
 * 跨租户不可见由既有租户行级隔离保证，本套件不重复验证。
 */
class SessionDepartmentApiTest extends ApiIntegrationTestSupport {

    /** 部门管理路径前缀。 */
    private static final String DEPARTMENTS_PATH = "/api/v1/admin/tenants/%s/departments";

    /** 成员与租户管理员都分配到启用部门时，会话下发 ID 与名称。 */
    @Test
    @DisplayName("IT-M23-001 已分配部门的成员与租户管理员会话下发 departmentId/departmentName")
    void sessionInfo_assignedActiveDepartment_returnsDepartmentFields() {
        LoginSession platform = loginPlatformAdmin();
        String tenantId = uniqueTenantCode("deptm23");
        createTenant(platform, tenantId);
        UUID departmentId = createDepartment(platform, tenantId, "研发部");
        String adminUsername = "admin" + randomSuffix();
        LoginSession tenantAdmin = seedAndLoginLocalUser(
                platform, tenantId, adminUsername, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        LoginSession member = createAndLoginMember(platform, tenantId);
        addMember(platform, tenantId, departmentId, actorId(tenantAdmin));
        addMember(platform, tenantId, departmentId, actorId(member));

        JsonNode memberUser = sessionUser(member);
        assertThat(memberUser.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(memberUser.path("departmentName").asText()).isEqualTo("研发部");

        JsonNode adminUser = sessionUser(tenantAdmin);
        assertThat(adminUser.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(adminUser.path("departmentName").asText()).isEqualTo("研发部");

        // 重新登录走 AuthService.authenticateInTenant + 会话摘要，验证登录响应同样保留部门字段。
        String memberUsername = member.body().path("user").path("username").asText();
        JsonNode reloginUser =
                login(tenantId, memberUsername, "member-password-12").body().path("user");
        assertThat(reloginUser.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(reloginUser.path("departmentName").asText()).isEqualTo("研发部");

        JsonNode adminReloginUser = login(tenantId, adminUsername, "tenant-admin-password-12")
                .body()
                .path("user");
        assertThat(adminReloginUser.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(adminReloginUser.path("departmentName").asText()).isEqualTo("研发部");
    }

    /** 未分配部门的账号两个字段都输出 null。 */
    @Test
    @DisplayName("IT-M23-001 未分配部门时 departmentId/departmentName 为 null")
    void sessionInfo_unassignedDepartment_returnsNullDepartmentFields() {
        LoginSession platform = loginPlatformAdmin();
        String tenantId = uniqueTenantCode("deptm23");
        createTenant(platform, tenantId);
        LoginSession member = createAndLoginMember(platform, tenantId);

        JsonNode user = sessionUser(member);

        assertThat(user.has("departmentId")).as(user.toString()).isTrue();
        assertThat(user.path("departmentId").isNull()).as(user.toString()).isTrue();
        assertThat(user.has("departmentName")).as(user.toString()).isTrue();
        assertThat(user.path("departmentName").isNull()).as(user.toString()).isTrue();
    }

    /** 部门停用后成员保留归属，名称仍下发。 */
    @Test
    @DisplayName("IT-M23-001 停用部门仍下发名称")
    void sessionInfo_disabledDepartment_keepsDepartmentName() {
        LoginSession platform = loginPlatformAdmin();
        String tenantId = uniqueTenantCode("deptm23");
        createTenant(platform, tenantId);
        UUID departmentId = createDepartment(platform, tenantId, "已停用部");
        LoginSession member = createAndLoginMember(platform, tenantId);
        addMember(platform, tenantId, departmentId, actorId(member));
        disableDepartment(platform, tenantId, departmentId);

        JsonNode user = sessionUser(member);

        assertThat(user.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(user.path("departmentName").asText()).isEqualTo("已停用部");
    }

    /** 租户管理员的账号列表同时覆盖启用、未分配、停用与管理员四态。 */
    @Test
    @DisplayName("IT-M23-001 租户管理员账号列表覆盖启用/未分配/停用/管理员四态")
    void tenantAdminUserList_coversActiveUnassignedDisabledAndAdminStates() {
        LoginSession platform = loginPlatformAdmin();
        String tenantId = uniqueTenantCode("deptm23");
        createTenant(platform, tenantId);
        UUID activeDepartment = createDepartment(platform, tenantId, "启用部");
        UUID disabledDepartment = createDepartment(platform, tenantId, "停用部");
        LoginSession tenantAdmin = seedAndLoginLocalUser(
                platform, tenantId, "admin" + randomSuffix(), "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        LoginSession activeMember = createAndLoginMember(platform, tenantId);
        LoginSession unassignedMember = createAndLoginMember(platform, tenantId);
        LoginSession disabledDepartmentMember = createAndLoginMember(platform, tenantId);
        addMember(platform, tenantId, activeDepartment, actorId(tenantAdmin));
        addMember(platform, tenantId, activeDepartment, actorId(activeMember));
        addMember(platform, tenantId, disabledDepartment, actorId(disabledDepartmentMember));
        disableDepartment(platform, tenantId, disabledDepartment);

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/users?size=100", tenantAdmin.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode items = client.json(response).path("items");

        JsonNode adminRow = userRow(items, actorId(tenantAdmin));
        assertThat(adminRow.path("departmentId").asText()).isEqualTo(activeDepartment.toString());
        assertThat(adminRow.path("departmentName").asText()).isEqualTo("启用部");

        JsonNode activeRow = userRow(items, actorId(activeMember));
        assertThat(activeRow.path("departmentId").asText()).isEqualTo(activeDepartment.toString());
        assertThat(activeRow.path("departmentName").asText()).isEqualTo("启用部");

        JsonNode unassignedRow = userRow(items, actorId(unassignedMember));
        assertThat(unassignedRow.path("departmentId").isNull()).isTrue();
        assertThat(unassignedRow.path("departmentName").isNull()).isTrue();

        JsonNode disabledRow = userRow(items, actorId(disabledDepartmentMember));
        assertThat(disabledRow.path("departmentId").asText()).isEqualTo(disabledDepartment.toString());
        assertThat(disabledRow.path("departmentName").asText()).isEqualTo("停用部");
    }

    /**
     * 读取当前会话中的用户摘要。
     *
     * @param session 已登录会话
     * @return {@code SessionInfo.user}
     */
    private JsonNode sessionUser(LoginSession session) {
        ApiTestClient.ApiResponse response = client.get("/api/v1/auth/session", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode user = client.json(response).path("user");
        assertThat(user.isObject()).as(user.toString()).isTrue();
        return user;
    }

    /**
     * 平台管理员创建部门并返回 ID。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param name 部门名
     * @return 部门 ID
     */
    private UUID createDepartment(LoginSession platform, String tenantId, String name) {
        ApiTestClient.ApiResponse response = client.post(
                DEPARTMENTS_PATH.formatted(tenantId),
                client.jsonBody("name", name),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(client.json(response).path("id").asText());
    }

    /**
     * 平台管理员把账号加入部门。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     * @param userId 账号 ID
     */
    private void addMember(LoginSession platform, String tenantId, UUID departmentId, UUID userId) {
        ApiTestClient.ApiResponse response = client.post(
                DEPARTMENTS_PATH.formatted(tenantId) + "/" + departmentId + "/members",
                client.jsonBody("userId", userId.toString()),
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(201);
    }

    /**
     * 平台管理员停用部门。
     *
     * @param platform 平台管理员会话
     * @param tenantId 目标租户
     * @param departmentId 部门 ID
     */
    private void disableDepartment(LoginSession platform, String tenantId, UUID departmentId) {
        ApiTestClient.ApiResponse response = client.post(
                DEPARTMENTS_PATH.formatted(tenantId) + "/" + departmentId + "/disable",
                null,
                platform.sessionCookie(),
                platform.csrfToken(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(client.json(response).path("status").asText()).isEqualTo("DISABLED");
    }

    /**
     * 在用户分页中按账号 ID 查找行。
     *
     * @param items 账号分页项
     * @param userId 账号 ID
     * @return 账号摘要
     */
    private static JsonNode userRow(JsonNode items, UUID userId) {
        for (JsonNode item : items) {
            if (userId.toString().equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("user not found in page: " + userId + " / " + items);
    }

    /**
     * 生成短随机后缀，避免同一租户内登录名冲突。
     *
     * @return 8 字符后缀
     */
    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
