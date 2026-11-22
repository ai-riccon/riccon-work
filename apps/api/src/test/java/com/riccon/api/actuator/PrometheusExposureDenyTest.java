package com.riccon.api.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * M04 可观测性负向基线：Prometheus 端点不得因“暴露配置”放宽默认拒绝。
 *
 * <p>B1c（TASK-0102）只引入 registry 依赖并冻结负向基线；`/actuator/prometheus` 的暴露与来源控制归
 * B2（TASK-0103，ADR-0021 §10.6）。为了让断言在 B2 落地前后都成立，本测试刻意把端点强制暴露，
 * 并把 `RICCON_OPS_ALLOWED_CIDRS`（配置键 `riccon.ops.allowed-cidrs`）设为不含 loopback 的网段，
 * 模拟“非允许来源”的抓取方：
 *
 * <ul>
 *   <li>B2 之前：安全链没有任何 `/actuator/prometheus` 放行规则，请求被默认拒绝；
 *   <li>B2 之后：来源不在允许列表，请求仍必须被拒绝（403/404）。
 * </ul>
 *
 * <p>断言只要求“非 200 且响应不含指标样本”，不锁定具体状态码，避免把 B2 的实现细节写进 B1c 测试；
 * 但同时覆盖匿名与已认证平台管理员两种调用方，证明指标读取不是普通业务权限。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus",
            // 明确的非 loopback 网段：测试进程从 127.0.0.1 发起，等同于“不在允许列表的抓取源”。
            "riccon.ops.allowed-cidrs=10.99.0.0/16"
        })
class PrometheusExposureDenyTest extends ApiIntegrationTestSupport {

    /** 依赖注入的 registry；存在即证明 micrometer-registry-prometheus 在 classpath 上。 */
    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    /** M04 依赖基线：Prometheus registry 必须由 Spring Boot 自动装配。 */
    @Test
    @DisplayName("OPS-M04-02a：API 的 Prometheus registry 已装配")
    void prometheusRegistryIsAutoConfigured() {
        assertThat(prometheusMeterRegistry).isNotNull();
        assertThat(prometheusMeterRegistry.getClass().getName()).startsWith("io.micrometer.prometheusmetrics");
    }

    /** 匿名抓取即使端点被暴露也必须被拒绝，且响应不得携带任何指标样本。 */
    @Test
    @DisplayName("OPS-M04-02a：匿名请求 /actuator/prometheus 被拒绝")
    void anonymousScrapeIsDenied() {
        ApiTestClient.ApiResponse response = client.get("/actuator/prometheus");

        assertThat(response.status()).as(response.body()).isIn(401, 403, 404);
        assertThat(response.body())
                .doesNotContain("# HELP")
                .doesNotContain("# TYPE")
                .doesNotContain("riccon_model_requests_total")
                .doesNotContain("riccon_agent_runs_inflight");
    }

    /** 已认证的平台管理员也不能读取指标：观测端点不是业务 RBAC 的一部分。 */
    @Test
    @DisplayName("OPS-M04-02a：平台管理员读取 /actuator/prometheus 仍被拒绝")
    void platformAdminScrapeIsDenied() {
        LoginSession admin = loginPlatformAdmin();

        ApiTestClient.ApiResponse response = client.get("/actuator/prometheus", admin.sessionCookie(), Map.of());

        assertThat(response.status()).as(response.body()).isIn(403, 404);
        assertThat(response.body()).doesNotContain("# HELP").doesNotContain("riccon_model_requests_total");
    }

    /** 回归保护：为观测端点加过滤器不得误伤公开健康检查。 */
    @Test
    @DisplayName("OPS-M04-02a：健康检查保持公开")
    void healthEndpointStaysPublic() {
        ApiTestClient.ApiResponse response = client.get("/actuator/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(client.json(response).path("status").asText()).isEqualTo("UP");
    }
}
