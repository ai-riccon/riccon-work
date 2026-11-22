package com.riccon.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * API Actuator 来源控制的 loopback 正例（OPS-M04-02a）。
 *
 * <p>{@code RICCON_OPS_ALLOWED_CIDRS} 为空时仅允许 loopback；测试客户端从 127.0.0.1 发起，
 * 因此可以读取 metrics，而普通业务路径仍需认证。该正例与 {@code PrometheusExposureDenyTest}
 * 的非允许来源负例共同证明来源过滤真实生效（ADR-0021 §2.2/§10.6）。
 */
@TestPropertySource(
        properties = {"management.endpoints.web.exposure.include=health,info,prometheus", "riccon.ops.allowed-cidrs="})
class PrometheusLoopbackAccessTest extends ApiIntegrationTestSupport {

    /** loopback 来源在空允许列表下可读取 Prometheus 指标。 */
    @Test
    @DisplayName("OPS-M04-02a：空 allowed-cidrs 允许 loopback 抓取")
    void loopbackScrapeIsAllowedWhenAllowlistIsEmpty() {
        ApiTestClient.ApiResponse response = client.get("/actuator/prometheus");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("# HELP");
    }

    /** 指标读取例外不放宽业务路径的默认认证要求。 */
    @Test
    @DisplayName("OPS-M04-02a：loopback 指标例外不影响业务路径认证")
    void businessPathsStillRequireAuthentication() {
        ApiTestClient.ApiResponse response = client.get("/api/v1/models");

        assertThat(response.status()).isEqualTo(401);
        assertThat(client.json(response).path("code").asText()).isEqualTo("RICCON-AUTH-401");
    }
}
