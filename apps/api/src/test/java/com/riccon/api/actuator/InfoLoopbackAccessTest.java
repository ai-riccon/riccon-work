package com.riccon.api.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * API Actuator `/actuator/info` 的 loopback 正例（OPS-M04-02b）。
 *
 * <p>B10 的 `seed-model-catalog --check` 通过 `/actuator/info` 比对 API 与 Worker 的 M04 指纹。
 * 本测试固定 `RICCON_OPS_ALLOWED_CIDRS` 为空（仅 loopback），证明漂移检查所需的端点可读、
 * 指纹键存在；同时验证该来源例外不会把其他 Actuator 端点带出默认拒绝（ADR-0021 §2.2/§10.6）。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus,loggers",
            "riccon.ops.allowed-cidrs="
        })
class InfoLoopbackAccessTest extends ApiIntegrationTestSupport {

    /** loopback 来源在空允许列表下可读取 B10 漂移检查使用的 info 指纹。 */
    @Test
    @DisplayName("OPS-M04-02b：空 allowed-cidrs 允许 loopback 读取 /actuator/info")
    void loopbackReadsInfoWhenAllowlistIsEmpty() {
        ApiTestClient.ApiResponse response = client.get("/actuator/info");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode riccon = client.json(response).path("riccon");
        assertThat(riccon.isObject()).as(response.body()).isTrue();
        // B10 必须以字符串比较的指纹键：部署模式、主密钥 ID、币种与四个默认配额。
        assertThat(riccon.path("model").path("deploymentMode").asText()).isNotEmpty();
        assertThat(riccon.path("masterKeyId").asText()).isNotEmpty();
        assertThat(riccon.path("billing").path("currency").asText()).matches("[A-Z]{3}");
        assertThat(riccon.path("quota").path("tenantDaily").asText()).isNotEmpty();
        assertThat(riccon.path("quota").path("tenantMonthly").asText()).isNotEmpty();
        assertThat(riccon.path("quota").path("userDaily").asText()).isNotEmpty();
        assertThat(riccon.path("quota").path("userMonthly").asText()).isNotEmpty();
    }

    /** 运维来源例外只覆盖 info/prometheus，其他已暴露的 Actuator 端点仍被默认拒绝。 */
    @Test
    @DisplayName("OPS-M04-02b：info 来源例外不放开其他 Actuator 端点")
    void otherActuatorEndpointsStayDenied() {
        ApiTestClient.ApiResponse response = client.get("/actuator/loggers");

        assertThat(response.status()).as(response.body()).isIn(401, 403, 404);
        assertThat(response.body()).doesNotContain("levels");
    }
}
