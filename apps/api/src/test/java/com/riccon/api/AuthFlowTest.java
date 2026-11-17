package com.riccon.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 认证、会话、CSRF 与登录防护集成测试。
 *
 * <p>覆盖 ADR-0013 的登录、会话轮换、CSRF、登出、锁定、限流、禁用与登录审计。测试使用真实 MySQL 与
 * Tomcat，不 sleep、不轮询。
 */
class AuthFlowTest extends ApiIntegrationTestSupport {

    /** CSRF 端点会建立匿名会话并返回头名与令牌。 */
    @Test
    void csrfEndpointReturnsTokenAndAnonymousSession() {
        ApiTestClient.ApiResponse response = client.get("/api/v1/auth/csrf");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
        assertThat(body.path("token").asText()).hasSizeGreaterThanOrEqualTo(16);

        List<String> cookies = response.headers().allValues("set-cookie");
        assertThat(cookies).anyMatch(value -> value.startsWith("RICCON_SESSION="));
        assertThat(cookies).anySatisfy(value -> {
            assertThat(value).contains("HttpOnly");
            assertThat(value).contains("SameSite=Lax");
            assertThat(value).contains("Path=/");
        });
        assertThat(cookies).noneMatch(value -> value.contains("Secure"));
    }

    /** 登录同样受 CSRF 保护；缺失令牌返回 403 与稳定错误码。 */
    @Test
    void loginWithoutCsrfTokenIsForbidden() {
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/auth/login",
                client.jsonBody(
                        "tenantCode", "platform",
                        "username", "platform-admin",
                        "password", "test-bootstrap-password-12"),
                null,
                null,
                Map.of());
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.header("content-type")).startsWith("application/problem+json");
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** 登录后旧 CSRF 令牌必须失效，新令牌才能继续写操作。 */
    @Test
    void previousCsrfTokenIsRejectedAfterLogin() {
        CsrfSession anonymous = csrfSession();
        ApiTestClient.ApiResponse login = client.post(
                "/api/v1/auth/login",
                client.jsonBody(
                        "tenantCode", "platform",
                        "username", "platform-admin",
                        "password", "test-bootstrap-password-12"),
                anonymous.cookie(),
                anonymous.token(),
                Map.of());
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        String authenticatedCookie = client.sessionCookie(login).orElseThrow();

        ApiTestClient.ApiResponse stale =
                client.post("/api/v1/auth/logout", null, authenticatedCookie, anonymous.token(), Map.of());
        assertThat(stale.status()).isEqualTo(403);
        assertThat(client.json(stale).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** 登录成功轮换会话 ID 与 CSRF 令牌，并返回完整会话信息。 */
    @Test
    void loginSucceedsAndRotatesSessionAndCsrf() {
        CsrfSession anonymous = csrfSession();
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/auth/login",
                client.jsonBody(
                        "tenantCode", "platform",
                        "username", "platform-admin",
                        "password", "test-bootstrap-password-12"),
                anonymous.cookie(),
                anonymous.token(),
                Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("authenticated").asBoolean()).isTrue();
        assertThat(body.path("tenant").path("id").asText()).isEqualTo("platform");
        assertThat(body.path("user").path("username").asText()).isEqualTo("platform-admin");
        assertThat(body.path("roles").toString()).contains("PLATFORM_ADMIN");
        assertThat(body.path("permissions").toString()).contains("platform.tenant.manage");

        String authenticatedCookie = client.sessionCookie(response).orElseThrow();
        assertThat(authenticatedCookie).isNotEqualTo(anonymous.cookie());
        String rotatedToken = body.path("csrfToken").asText();
        assertThat(rotatedToken).isNotEqualTo(anonymous.token());

        List<String> cookies = response.headers().allValues("set-cookie");
        assertThat(cookies).anySatisfy(value -> {
            assertThat(value).contains("RICCON_SESSION=");
            assertThat(value).contains("HttpOnly");
            assertThat(value).contains("SameSite=Lax");
        });

        String userId = body.path("user").path("id").asText();
        assertThat(sessionCount("platform:" + userId)).isPositive();
    }

    /** 已登录会话可以通过 /session 读取当前用户与令牌。 */
    @Test
    void sessionReturnsCurrentUserAndToken() {
        LoginSession session = loginPlatformAdmin();
        ApiTestClient.ApiResponse response = client.get("/api/v1/auth/session", session.sessionCookie(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = client.json(response);
        assertThat(body.path("user").path("username").asText()).isEqualTo("platform-admin");
        assertThat(body.path("csrfToken").asText()).isEqualTo(session.csrfToken());
        assertThat(body.path("expiresAt").asText()).isNotBlank();
    }

    /** 登出使会话失效并写 auth.logout 审计。 */
    @Test
    void logoutInvalidatesSessionAndWritesAudit() {
        LoginSession session = loginPlatformAdmin();
        ApiTestClient.ApiResponse logout =
                client.post("/api/v1/auth/logout", null, session.sessionCookie(), session.csrfToken(), Map.of());
        assertThat(logout.status()).isEqualTo(204);

        ApiTestClient.ApiResponse after = client.get("/api/v1/auth/session", session.sessionCookie(), Map.of());
        assertThat(after.status()).isEqualTo(401);
        assertThat(auditRows("auth.logout")).isNotEmpty();
    }

    /** 未知租户与错误密码都返回同一个 401 错误码与通用说明。 */
    @Test
    void loginFailuresAreUniformAndAudited() {
        LoginSession admin = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(admin, tenant);
        createUser(admin, tenant, "member01", "member-password-12", List.of("MEMBER"));

        ApiTestClient.ApiResponse unknownTenant =
                attemptLogin(uniqueTenantCode("x"), "no-such-user", "whatever-password");
        assertThat(unknownTenant.status()).isEqualTo(401);
        assertThat(client.json(unknownTenant).path("code").asText()).isEqualTo("RICCON-AUTH-001");
        assertThat(client.json(unknownTenant).path("detail").asText())
                .isEqualTo("Invalid tenant code, username or password.");

        ApiTestClient.ApiResponse badPassword = attemptLogin(tenant, "member01", "wrong-password-12");
        assertThat(badPassword.status()).isEqualTo(401);
        assertThat(client.json(badPassword).path("code").asText()).isEqualTo("RICCON-AUTH-001");

        assertThat(auditRows("auth.login.failed")).anySatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(tenant);
            assertThat(row.details()).contains("bad_password");
        });
        assertThat(auditRows("auth.login.failed"))
                .anySatisfy(row -> assertThat(row.details()).contains("unknown_tenant"));
    }

    /** 连续 5 次失败后锁定，正确密码也返回 401。 */
    @Test
    void accountLocksAfterFiveFailures() {
        LoginSession admin = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(admin, tenant);
        createUser(admin, tenant, "locked01", "correct-password-12", List.of("MEMBER"));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(attemptLogin(tenant, "locked01", "wrong-password-12").status())
                    .isEqualTo(401);
        }
        ApiTestClient.ApiResponse locked = attemptLogin(tenant, "locked01", "correct-password-12");
        assertThat(locked.status()).isEqualTo(401);
        assertThat(auditRows("auth.login.failed")).anySatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(tenant);
            assertThat(row.details()).contains("account_locked");
        });
    }

    /** 同一 IP + 租户窗口内失败 10 次后触发 429。 */
    @Test
    void loginRateLimitedAfterTenFailures() {
        LoginSession admin = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(admin, tenant);
        createUser(admin, tenant, "ratelimit01", "correct-password-12", List.of("MEMBER"));

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(attemptLogin(tenant, "ratelimit01", "wrong-password-12").status())
                    .isEqualTo(401);
        }
        ApiTestClient.ApiResponse limited = attemptLogin(tenant, "ratelimit01", "correct-password-12");
        assertThat(limited.status()).isEqualTo(429);
        assertThat(client.json(limited).path("code").asText()).isEqualTo("RICCON-RATE-429");
    }

    /** 禁用账号后无法登录，错误与普通失败一致。 */
    @Test
    void disabledAccountCannotLogin() {
        LoginSession admin = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(admin, tenant);
        JsonNode user = createUser(admin, tenant, "disabled01", "member-password-12", List.of("MEMBER"));
        LoginSession tenantAdmin = createAndLoginTenantAdmin(admin, tenant);
        disableUser(tenantAdmin, user.path("id").asText());

        ApiTestClient.ApiResponse response = attemptLogin(tenant, "disabled01", "member-password-12");
        assertThat(response.status()).isEqualTo(401);
        assertThat(auditRows("auth.login.failed")).anySatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(tenant);
            assertThat(row.details()).contains("account_disabled");
        });
    }

    /** 账号被禁用后，已有会话在下一请求失效。 */
    @Test
    void existingSessionRejectedAfterAccountDisabled() {
        LoginSession admin = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(admin, tenant);
        JsonNode user = createUser(admin, tenant, "disabled02", "member-password-12", List.of("MEMBER"));
        LoginSession member = login(tenant, "disabled02", "member-password-12");
        LoginSession tenantAdmin = createAndLoginTenantAdmin(admin, tenant);
        disableUser(tenantAdmin, user.path("id").asText());

        ApiTestClient.ApiResponse response = client.get("/api/v1/auth/session", member.sessionCookie(), Map.of());
        assertThat(response.status()).isEqualTo(401);
    }

    /** 角色撤销后，下一请求立即失去权限。 */
    @Test
    void roleChangeTakesEffectOnNextRequest() {
        LoginSession admin = loginPlatformAdmin();
        String tenant = uniqueTenantCode("t");
        createTenant(admin, tenant);
        String username = "adminrole01";
        JsonNode user = createUser(admin, tenant, username, "tenant-admin-password-12", List.of("TENANT_ADMIN"));
        LoginSession tenantAdmin = login(tenant, username, "tenant-admin-password-12");

        assertThat(client.get("/api/v1/users", tenantAdmin.sessionCookie(), Map.of())
                        .status())
                .isEqualTo(200);

        ApiTestClient.ApiResponse replaced = client.put(
                "/api/v1/admin/tenants/" + tenant + "/users/" + user.path("id").asText() + "/roles",
                client.jsonBody("roles", List.of("MEMBER")),
                admin.sessionCookie(),
                admin.csrfToken());
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);

        ApiTestClient.ApiResponse denied = client.get("/api/v1/users", tenantAdmin.sessionCookie(), Map.of());
        assertThat(denied.status()).isEqualTo(403);
        assertThat(client.json(denied).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** 跨源写请求即使带 CSRF 令牌也必须拒绝。 */
    @Test
    void crossOriginLoginIsRejected() {
        CsrfSession csrf = csrfSession();
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/auth/login",
                client.jsonBody(
                        "tenantCode", "platform",
                        "username", "platform-admin",
                        "password", "test-bootstrap-password-12"),
                csrf.cookie(),
                csrf.token(),
                Map.of("Origin", "http://evil.example"));
        assertThat(response.status()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-403");
    }

    /** 同源 Origin 必须放行，证明拒绝条件不是“存在 Origin 即拒绝”。 */
    @Test
    void sameOriginLoginIsAllowed() {
        CsrfSession csrf = csrfSession();
        ApiTestClient.ApiResponse response = client.post(
                "/api/v1/auth/login",
                client.jsonBody(
                        "tenantCode", "platform",
                        "username", "platform-admin",
                        "password", "test-bootstrap-password-12"),
                csrf.cookie(),
                csrf.token(),
                Map.of("Origin", "http://127.0.0.1:" + port));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    /** 密码哈希必须使用 {bcrypt} 前缀与强度 12；会话空闲超时 30 分钟。 */
    @Test
    void credentialsUseBcryptTwelveAndSessionTimeoutIsThirtyMinutes() {
        LoginSession session = loginPlatformAdmin();
        String hash = jdbcClient.sql("""
                        SELECT c.password_hash FROM user_credential c
                        JOIN app_user u ON u.tenant_id = c.tenant_id AND u.id = c.user_id
                        WHERE u.tenant_id = 'platform' AND u.username = 'platform-admin'
                        """).query(String.class).single();
        assertThat(hash).startsWith("{bcrypt}$2a$12$");

        String principalName =
                "platform:" + session.body().path("user").path("id").asText();
        Integer maxInactive = jdbcClient
                .sql("""
                        SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION
                        WHERE PRINCIPAL_NAME = :principalName
                        ORDER BY LAST_ACCESS_TIME DESC LIMIT 1
                        """)
                .param("principalName", principalName)
                .query(Integer.class)
                .single();
        assertThat(maxInactive).isEqualTo(1800);
    }

    /**
     * 执行一次登录尝试。
     *
     * @param tenantCode 租户代码
     * @param username 登录名
     * @param password 密码
     * @return 登录响应
     */
    /**
     * 执行一次登录尝试。
     *
     * @param tenantCode 租户代码
     * @param username 登录名
     * @param password 密码
     * @return 登录响应
     */
    private ApiTestClient.ApiResponse attemptLogin(String tenantCode, String username, String password) {
        CsrfSession csrf = csrfSession();
        return client.post(
                "/api/v1/auth/login",
                client.jsonBody("tenantCode", tenantCode, "username", username, "password", password),
                csrf.cookie(),
                csrf.token(),
                Map.of());
    }

    /**
     * 通过租户管理员禁用账号。
     *
     * @param tenantAdmin 租户管理员会话
     * @param userId 账号 ID
     */
    private void disableUser(LoginSession tenantAdmin, String userId) {
        ApiTestClient.ApiResponse response = client.patch(
                "/api/v1/users/" + userId,
                client.jsonBody("status", "DISABLED"),
                tenantAdmin.sessionCookie(),
                tenantAdmin.csrfToken());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }
}
