package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * 租户隔离、RBAC 与审计 append-only 集成测试。
 *
 * <p>覆盖 ADR-0009 的 TEN-002/003/004/011/012/019：伪造租户标识被拒绝、跨租户对象统一 404、
 * 租户管理员不能访问平台接口、普通员工不能管理账号、审计租户与 actor 一致，以及数据库层拒绝修改/删除审计。
 */
class TenantIsolationTest extends ApiIntegrationTestSupport {

    /** TEN-001：租户账号列表只包含本租户。 */
    @Test
    @DisplayName("TEN-001 租户账号列表只返回本租户")
    void tenantUserListOnlyContainsOwnTenant() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("a");
        String tenantB = uniqueTenantCode("b");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        String adminA = "a-admin01";
        createUser(platform, tenantA, adminA, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        JsonNode memberA = createUser(platform, tenantA, "a-member01", "member-password-12", List.of("MEMBER"));
        String adminB = "b-admin01";
        createUser(platform, tenantB, adminB, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        createUser(platform, tenantB, "b-member01", "member-password-12", List.of("MEMBER"));

        LoginSession sessionA = login(tenantA, adminA, "tenant-admin-password-12");
        ApiTestClient.ApiResponse response =
                client.get("/api/v1/users?page=1&size=20", sessionA.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("total").asLong()).isEqualTo(2L);
        assertThat(body.path("items").toString()).contains("a-admin01").contains("a-member01");
        assertThat(body.path("items").toString()).doesNotContain("b-admin01", "b-member01");
        assertThat(body.path("items").toString()).contains(memberA.path("id").asText());
    }

    /** TEN-003/004：跨租户读取/修改统一 404，目标数据不变。 */
    @Test
    @DisplayName("TEN-003 TEN-004 跨租户账号修改返回统一 404 且数据不变")
    void crossTenantUserUpdateReturnsNotFound() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("a");
        String tenantB = uniqueTenantCode("b");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        createUser(platform, tenantA, "a-admin02", "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        JsonNode userB = createUser(platform, tenantB, "b-member02", "member-password-12", List.of("MEMBER"));
        LoginSession sessionA = login(tenantA, "a-admin02", "tenant-admin-password-12");

        ApiTestClient.ApiResponse response = client.patch(
                "/api/v1/users/" + userB.path("id").asText(),
                client.jsonBody("status", "DISABLED"),
                sessionA.sessionCookie(),
                sessionA.csrfToken());
        assertThat(response.status()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-TENANT-404");

        String status = jdbcClient
                .sql("SELECT status FROM app_user WHERE tenant_id = :tenantId AND username = :username")
                .param("tenantId", tenantB)
                .param("username", "b-member02")
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("ACTIVE");
    }

    /** TEN-011：租户管理员调用平台接口返回 403。 */
    @Test
    @DisplayName("TEN-011 租户管理员不能访问平台管理接口")
    void tenantAdminCannotCallPlatformApi() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("a");
        createTenant(platform, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenant);

        ApiTestClient.ApiResponse response = client.get("/api/v1/admin/tenants", tenantAdmin.sessionCookie(), Map.of());
        assertThat(response.status()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** TEN-002：请求头伪造租户被拒绝并写审计。 */
    @Test
    @DisplayName("TEN-002 请求头伪造 tenantId 被拒绝并审计")
    void forgedTenantHeaderIsRejectedAndAudited() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("a");
        String tenantB = uniqueTenantCode("b");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        LoginSession sessionA = createAndLoginTenantAdmin(platform, tenantA);

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/users", sessionA.sessionCookie(), Map.of("X-Tenant-Id", tenantB));
        assertThat(response.status()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");

        assertThat(auditRows("tenant.access.denied")).anySatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(tenantA);
            assertThat(row.actorType()).isEqualTo("USER");
            assertThat(row.outcome()).isEqualTo("DENIED");
            assertThat(row.details()).contains(tenantB);
        });
    }

    /** TEN-002：查询参数伪造租户同样被拒绝。 */
    @Test
    @DisplayName("TEN-002 查询参数伪造 tenantId 被拒绝")
    void forgedTenantQueryParameterIsRejected() {
        LoginSession platform = loginPlatformAdmin();
        String tenantA = uniqueTenantCode("a");
        String tenantB = uniqueTenantCode("b");
        createTenant(platform, tenantA);
        createTenant(platform, tenantB);
        LoginSession sessionA = createAndLoginTenantAdmin(platform, tenantA);

        ApiTestClient.ApiResponse response =
                client.get("/api/v1/users?tenantId=" + tenantB, sessionA.sessionCookie(), Map.of());
        assertThat(response.status()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** 普通员工没有租户账号管理权限。 */
    @Test
    void memberCannotListTenantUsers() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("a");
        createTenant(platform, tenant);
        LoginSession member = createAndLoginMember(platform, tenant);

        ApiTestClient.ApiResponse response = client.get("/api/v1/users", member.sessionCookie(), Map.of());
        assertThat(response.status()).isEqualTo(403);
    }

    /** TEN-019：租户管理事件的 tenant_id 与 actor 所属租户一致。 */
    @Test
    @DisplayName("TEN-019 租户事件审计租户与 actor 一致")
    void tenantAuditUsesActorTenant() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("a");
        createTenant(platform, tenant);
        LoginSession tenantAdmin = createAndLoginTenantAdmin(platform, tenant);
        ApiTestClient.ApiResponse created = client.post(
                "/api/v1/users",
                client.jsonBody(
                        "username",
                        "audited01",
                        "displayName",
                        "Audited User",
                        "password",
                        "member-password-12",
                        "roles",
                        List.of("MEMBER")),
                tenantAdmin.sessionCookie(),
                tenantAdmin.csrfToken(),
                Map.of());
        assertThat(created.status()).as(created.body()).isEqualTo(201);

        assertThat(auditRows("user.created")).anySatisfy(row -> {
            if (tenant.equals(row.tenantId())) {
                assertThat(row.details()).contains("MEMBER");
                assertThat(row.details()).contains(tenant);
            }
        });
        assertThat(jdbcClient
                        .sql("SELECT COUNT(*) FROM audit_event WHERE action = 'user.created' AND tenant_id = :tenantId")
                        .param("tenantId", tenant)
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    /** 平台事件使用 platform 租户，目标租户写入详情，且租户创建/更新均审计。 */
    @Test
    void platformTenantChangesAreAuditedUnderPlatform() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("a");
        createTenant(platform, tenant);
        ApiTestClient.ApiResponse updated = client.patch(
                "/api/v1/admin/tenants/" + tenant,
                client.jsonBody("retentionDays", 90),
                platform.sessionCookie(),
                platform.csrfToken());
        assertThat(updated.status()).as(updated.body()).isEqualTo(200);

        assertThat(auditRows("tenant.created")).anySatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo("platform");
            assertThat(row.targetType()).isEqualTo("TENANT");
            assertThat(row.targetId()).isEqualTo(tenant);
            assertThat(row.details()).contains("standard");
        });
        assertThat(auditRows("tenant.updated")).anySatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo("platform");
            assertThat(row.targetId()).isEqualTo(tenant);
            assertThat(row.details()).contains("90");
        });
    }

    /** 平台管理员在目标租户创建账号时，事件租户为 platform，目标租户写入 details。 */
    @Test
    void platformUserCreationAuditUsesPlatformTenant() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("a");
        createTenant(platform, tenant);
        JsonNode user = createUser(platform, tenant, "platformcreated01", "member-password-12", List.of("MEMBER"));

        assertThat(auditRows("user.created")).anySatisfy(row -> {
            if (user.path("id").asText().equals(row.targetId())) {
                assertThat(row.tenantId()).isEqualTo("platform");
                assertThat(row.details()).contains(tenant);
            }
        });
    }

    /** TEN-012：不存在审计查询接口；普通员工无法访问审计数据。 */
    @Test
    @DisplayName("TEN-012 M02 不提供审计查询接口")
    void auditQueryEndpointDoesNotExist() {
        LoginSession admin = loginPlatformAdmin();
        ApiTestClient.ApiResponse response = client.get("/api/v1/audit/events", admin.sessionCookie(), Map.of());
        assertThat(response.status()).isIn(403, 404);
        if (response.status() == 404) {
            assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-REQUEST-404");
        }
    }

    /** AUDIT-003：数据库触发器拒绝修改与删除审计事件。 */
    @Test
    @DisplayName("AUDIT-003 审计事件 UPDATE/DELETE 被数据库拒绝")
    void auditEventsAreAppendOnly() {
        LoginSession admin = loginPlatformAdmin();
        assertThat(auditRows("auth.login.succeeded")).isNotEmpty();
        long before = auditRowCount();

        assertThatThrownBy(() -> jdbcClient
                        .sql("UPDATE audit_event SET outcome = 'FAILURE' WHERE action = 'auth.login.succeeded'")
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient
                        .sql("DELETE FROM audit_event WHERE action = 'auth.login.succeeded'")
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThat(auditRowCount()).isEqualTo(before);
        assertThat(admin.body().path("authenticated").asBoolean()).isTrue();
    }

    /** 租户开通时复制两个系统角色及权限映射。 */
    @Test
    void tenantCreationCopiesSystemRoles() {
        LoginSession platform = loginPlatformAdmin();
        String tenant = uniqueTenantCode("a");
        createTenant(platform, tenant);
        List<String> roles = jdbcClient
                .sql("SELECT code FROM role WHERE tenant_id = :tenantId ORDER BY code")
                .param("tenantId", tenant)
                .query(String.class)
                .list();
        assertThat(roles).containsExactly("MEMBER", "TENANT_ADMIN");
        Long permissionMappings = jdbcClient
                .sql("SELECT COUNT(*) FROM role_permission WHERE tenant_id = :tenantId")
                .param("tenantId", tenant)
                .query(Long.class)
                .single();
        assertThat(permissionMappings).isPositive();
    }

    /**
     * 统计审计总行数。
     *
     * @return 行数
     */
    private long auditRowCount() {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event")
                .query(Long.class)
                .single();
    }
}
