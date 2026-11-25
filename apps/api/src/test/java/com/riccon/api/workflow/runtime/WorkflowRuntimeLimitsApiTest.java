package com.riccon.api.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiTestClient;
import com.riccon.api.workflow.run.WorkflowRunTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行限制只读端点集成测试（IT-WF-109，AC-17/ADR-0024 §10.1）。
 *
 * <p>真实 MySQL（Testcontainers）+ Flyway V15 + 真实会话/CSRF/权限链。覆盖：普通员工可读部署限制、
 * 保留策略归因、无 {@code workflow.read} 返回 403、平台租户 404、端点只读（PUT 405）。
 */
class WorkflowRuntimeLimitsApiTest extends WorkflowRunTestSupport {

    /** 读取运行限制，断言 200。 */
    @Test
    @DisplayName("IT-WF-109 普通员工只读查看部署运行限制")
    void member_readsDeploymentLimits() {
        Fixture fixture = fixture("wflimits");

        ApiTestClient.ApiResponse response = getLimits(fixture.member());

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        var limits = client.json(response);
        assertThat(limits.path("featureEnabled").asBoolean()).isTrue();
        assertThat(limits.has("disabledReason")).isTrue();
        assertThat(limits.path("disabledReason").isNull()).isTrue();
        assertThat(limits.path("maxNodes").asInt()).isEqualTo(50);
        assertThat(limits.path("maxSteps").asInt()).isEqualTo(200);
        assertThat(limits.path("nodeTimeoutSeconds").path("model").asInt()).isEqualTo(300);
        assertThat(limits.path("nodeTimeoutSeconds").path("retrieval").asInt()).isEqualTo(120);
        assertThat(limits.path("nodeTimeoutSeconds").path("mcp").asInt()).isEqualTo(60);
        assertThat(limits.path("runTimeoutSeconds").asInt()).isEqualTo(1_800);
        assertThat(limits.path("tenantConcurrency").asInt()).isEqualTo(10);
        assertThat(limits.path("workflowConcurrency").asInt()).isEqualTo(5);
        assertThat(limits.path("resultMaxBytes").asLong()).isEqualTo(5_242_880L);
        assertThat(limits.path("retentionDays").asInt()).isEqualTo(180);
        assertThat(limits.path("retentionPolicy").asText()).isEqualTo("DEPLOYMENT_DEFAULT");
        // 合同要求带偏移的 ISO 8601；UI 与日志统一北京时间。
        assertThat(limits.path("generatedAt").asText()).endsWith("+08:00");
    }

    /** 租户保留天数与部署默认不同时，标记 TENANT_POLICY。 */
    @Test
    @DisplayName("IT-WF-109 租户保留策略优先于部署默认")
    void tenantRetention_marksTenantPolicy() {
        Fixture fixture = fixture("wflimitsret");
        jdbcClient
                .sql("UPDATE tenant SET retention_days = 90 WHERE id = :tenant")
                .param("tenant", fixture.tenant())
                .update();

        var limits = client.json(getLimits(fixture.tenantAdmin()));

        assertThat(limits.path("retentionDays").asInt()).isEqualTo(90);
        assertThat(limits.path("retentionPolicy").asText()).isEqualTo("TENANT_POLICY");
    }

    /** 没有 workflow.read 的账号被拒绝，不能借运行限制读取部署配置。 */
    @Test
    @DisplayName("IT-WF-109 无 workflow.read 返回 403")
    void withoutReadPermission_returns403() {
        Fixture fixture = fixture("wflimits403");
        LoginSession noRole = seedAndLoginLocalUser(
                fixture.platform(),
                fixture.tenant(),
                "norole" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "no-role-password-12",
                List.of());

        ApiTestClient.ApiResponse response = getLimits(noRole);

        assertThat(response.status()).as(response.body()).isEqualTo(403);
        assertThat(client.json(response).path("code").asText()).isEqualTo(AUTH_403);
    }

    /** 平台租户在任何工作流端点统一 404，不泄露端点存在性。 */
    @Test
    @DisplayName("IT-WF-109 平台租户访问运行限制返回 404")
    void platformTenant_returns404() {
        Fixture fixture = fixture("wflimitsplat");

        ApiTestClient.ApiResponse response = getLimits(fixture.platform());

        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(client.json(response).path("code").asText()).isEqualTo(TENANT_404);
    }

    /** 契约只提供 GET，租户不能通过 PUT/POST 修改部署配置。 */
    @Test
    @DisplayName("IT-WF-109 运行限制没有写端点")
    void writeMethods_areNotAllowed() {
        Fixture fixture = fixture("wflimitsro");

        ApiTestClient.ApiResponse put = client.put(
                "/api/v1/workflows/runtime-limits",
                "{\"maxNodes\":999}",
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken());
        assertThat(put.status()).isEqualTo(405);

        ApiTestClient.ApiResponse post = client.post(
                "/api/v1/workflows/runtime-limits",
                "{\"maxNodes\":999}",
                fixture.tenantAdmin().sessionCookie(),
                fixture.tenantAdmin().csrfToken(),
                Map.of());
        assertThat(post.status()).isEqualTo(405);

        // 写请求不改变只读值。
        assertThat(client.json(getLimits(fixture.member())).path("maxNodes").asInt())
                .isEqualTo(50);
    }

    /**
     * 读取运行限制端点。
     *
     * @param session 会话
     * @return 原始响应
     */
    private ApiTestClient.ApiResponse getLimits(LoginSession session) {
        return client.get("/api/v1/workflows/runtime-limits", session.sessionCookie(), Map.of());
    }
}
