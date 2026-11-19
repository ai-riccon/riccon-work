package com.riccon.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * IT-AUDIT-102 补充：只读审计端点的未支持方法映射（B3b F-001 P2）。
 *
 * <p>ADR-0020 第 1 节要求 AC-16 以“无路由 + 404/405”验证接口拒绝写操作。本测试使用真实 HTTP、
 * 真实安全过滤器链与真实 MySQL，断言 {@code POST/DELETE /api/v1/audit-events} 返回 405 而非 500，
 * {@code Allow} 头保留，响应无内部细节，写请求不产生审计写入；同时确认 GET 查询、匿名 401 与
 * 普通员工 403 的既有优先级不回归。
 */
class WebErrorMethodNotAllowedTest extends ApiIntegrationTestSupport {

    /** 只读审计路径。 */
    private static final String AUDIT_PATH = "/api/v1/audit-events";

    /**
     * 只读端点拒绝未支持方法：405、Allow 保留、无写入、无内部细节。
     */
    @Test
    @DisplayName("IT-AUDIT-102 POST/DELETE /api/v1/audit-events 返回 405 且无写入")
    void auditReadOnlyRoutes_rejectUnsupportedMethodsWithoutWrites() {
        String tenantId = uniqueTenantCode("m405");
        LoginSession platform = loginPlatformAdmin();
        createTenant(platform, tenantId);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenantId);
        long auditEventsBefore = auditEventCount(tenantId);

        ApiTestClient.ApiResponse post =
                client.post(AUDIT_PATH, "{}", admin.sessionCookie(), admin.csrfToken(), Map.of());
        assertMethodNotAllowed(post, "POST");

        ApiTestClient.ApiResponse delete = client.delete(AUDIT_PATH, admin.sessionCookie(), admin.csrfToken());
        assertMethodNotAllowed(delete, "DELETE");

        // 写请求在进入权限拦截器前即被拒绝，不得产生任何审计写入。
        assertThat(auditEventCount(tenantId)).as("未支持方法不得产生审计写入").isEqualTo(auditEventsBefore);
        assertThat(auditEventCount(tenantId, "audit.queried")).isZero();
    }

    /**
     * 未认证 401、普通员工 403 与管理员 GET 查询语义不回归。
     */
    @Test
    @DisplayName("IT-AUDIT-102 审计查询的认证与权限优先级不回归")
    void auditQuery_keepsAuthenticationAndPermissionPrecedence() {
        String tenantId = uniqueTenantCode("m405");
        LoginSession platform = loginPlatformAdmin();
        createTenant(platform, tenantId);
        LoginSession admin = createAndLoginTenantAdmin(platform, tenantId);
        LoginSession member = createAndLoginMember(platform, tenantId);

        ApiTestClient.ApiResponse anonymous = client.get(AUDIT_PATH);
        assertThat(anonymous.status()).as(anonymous.body()).isEqualTo(401);

        // 未认证写请求必须在安全过滤器链被拒绝，不能穿透到 MVC 的 405 映射。
        CsrfSession anonymousSession = csrfSession();
        ApiTestClient.ApiResponse anonymousWrite =
                client.post(AUDIT_PATH, "{}", anonymousSession.cookie(), anonymousSession.token(), Map.of());
        assertThat(anonymousWrite.status()).as(anonymousWrite.body()).isEqualTo(401);

        ApiTestClient.ApiResponse forbidden = client.get(AUDIT_PATH, member.sessionCookie(), Map.of());
        assertThat(forbidden.status()).as(forbidden.body()).isEqualTo(403);
        assertThat(client.json(forbidden).path("code").asText())
                .as(forbidden.body())
                .isEqualTo("RICCON-AUTH-403");
        assertThat(forbidden.body()).doesNotContain("occurredAt").doesNotContain("tenantId");

        ApiTestClient.ApiResponse page = client.get(AUDIT_PATH + "?page=1&size=1", admin.sessionCookie(), Map.of());
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        JsonNode body = client.json(page);
        assertThat(body.path("page").asInt()).isEqualTo(1);
        assertThat(body.path("size").asInt()).isEqualTo(1);
        assertThat(body.path("items").isArray()).isTrue();
    }

    /**
     * 断言方法不支持响应的状态、Allow 头、ProblemDetail 与脱敏要求。
     *
     * @param response HTTP 响应
     * @param method 请求方法
     */
    private void assertMethodNotAllowed(ApiTestClient.ApiResponse response, String method) {
        assertThat(response.status()).as(response.body()).isEqualTo(405);
        String allow = response.header("Allow");
        assertThat(allow).as("%s 响应必须保留 Allow 头", method).isNotNull();
        assertThat(allow).as("%s 响应的 Allow 头必须列出 GET", method).contains("GET");
        assertThat(response.header("Content-Type")).contains("application/problem+json");

        JsonNode problem = client.json(response);
        assertThat(problem.path("status").asInt()).isEqualTo(405);
        assertThat(problem.path("code").asText()).isEqualTo("RICCON-METHOD-405");
        assertThat(problem.path("title").asText()).isNotBlank();
        assertThat(problem.path("detail").asText()).isEqualTo("request method is not supported");
        assertThat(problem.path("traceId").asText()).isNotBlank();

        // 不泄露异常类型、框架栈、路由细节或任何审计内容。
        assertThat(response.body())
                .as("%s 响应不得泄露内部细节", method)
                .doesNotContain("HttpRequestMethodNotSupportedException")
                .doesNotContain("org.springframework")
                .doesNotContain("at com.riccon")
                .doesNotContain("eventId")
                .doesNotContain("occurredAt");
    }

    /**
     * 统计租户审计事件总数。
     *
     * @param tenantId 租户
     * @return 事件数
     */
    private long auditEventCount(String tenantId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
    }

    /**
     * 统计租户指定动作的审计事件数。
     *
     * @param tenantId 租户
     * @param action 动作名
     * @return 事件数
     */
    private long auditEventCount(String tenantId, String action) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM audit_event WHERE tenant_id = :tenantId AND action = :action")
                .param("tenantId", tenantId)
                .param("action", action)
                .query(Long.class)
                .single();
    }
}
