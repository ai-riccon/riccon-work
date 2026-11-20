package com.riccon.worker.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.riccon.worker.WorkerIntegrationTestBase;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M04 可观测性负向基线：Worker 的 Prometheus 端点默认拒绝。
 *
 * <p>B1c（TASK-0102）只引入 registry 依赖并冻结负向断言；暴露与来源控制归 B2（TASK-0103）。
 * 本测试强制暴露 `/actuator/prometheus` 并把 `riccon.ops.allowed-cidrs` 设为不含 loopback 的网段，
 * 因此无论 B2 是否已实现 `RICCON_OPS_ALLOWED_CIDRS`，结论都必须是“非 200 且无指标样本”：
 *
 * <ul>
 *   <li>B2 之前：Worker 安全链只有健康检查与两个内部端点，其余 denyAll；
 *   <li>B2 之后：MockMvc 的来源地址 127.0.0.1 不在允许网段内，必须被来源过滤拒绝。
 * </ul>
 *
 * <p>Worker 只应被集群内部调用（威胁模型 B-08），指标端点不得成为新的匿名入口。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "riccon.ops.allowed-cidrs=10.99.0.0/16"
        })
class PrometheusExposureDenyTest extends WorkerIntegrationTestBase {

    /** 依赖注入的 registry；存在即证明 micrometer-registry-prometheus 在 classpath 上。 */
    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    /** 模拟内部 HTTP 请求，经过真实安全过滤器链。 */
    @Autowired
    private MockMvc mockMvc;

    /** M04 依赖基线：Worker 的 Prometheus registry 必须由 Spring Boot 自动装配。 */
    @Test
    @DisplayName("OPS-M04-02a：Worker 的 Prometheus registry 已装配")
    void prometheusRegistryIsAutoConfigured() {
        assertThat(prometheusMeterRegistry).isNotNull();
        assertThat(prometheusMeterRegistry.getClass().getName()).startsWith("io.micrometer.prometheusmetrics");
    }

    /** 非允许来源的抓取请求必须被拒绝，且响应不得携带指标样本。 */
    @Test
    @DisplayName("OPS-M04-02a：非允许来源请求 /actuator/prometheus 被拒绝")
    void scrapeFromNonAllowlistedSourceIsDenied() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as(body).isIn(401, 403, 404);
        assertThat(body)
                .doesNotContain("# HELP")
                .doesNotContain("# TYPE")
                .doesNotContain("riccon_model_requests_total")
                .doesNotContain("riccon_agent_runs_inflight");
    }

    /** 回归保护：为指标端点加来源过滤不得误伤健康检查。 */
    @Test
    @DisplayName("OPS-M04-02a：健康检查保持公开")
    void healthEndpointStaysAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
