package com.riccon.api.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * API Actuator `/actuator/info` 的允许 CIDR 正例（OPS-M04-02b）。
 *
 * <p>与空配置的 loopback 正例不同，这里显式配置 `127.0.0.1/32`，证明 `RICCON_OPS_ALLOWED_CIDRS`
 * 的网段解析真正生效，而不是只有 loopback 分支可用；B10 漂移检查在部署侧可用同样的显式网段读取 info
 * （ADR-0021 §2.2/§10.6）。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "riccon.ops.allowed-cidrs=127.0.0.1/32"
        })
class InfoAllowedCidrAccessTest extends ApiIntegrationTestSupport {

    /** 来源命中显式允许网段时可读取 info，且漂移指纹键完整。 */
    @Test
    @DisplayName("OPS-M04-02b：显式允许 CIDR 可读取 /actuator/info")
    void allowedCidrReadsInfo() {
        ApiTestClient.ApiResponse response = client.get("/actuator/info");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode riccon = client.json(response).path("riccon");
        assertThat(riccon.isObject()).as(response.body()).isTrue();
        assertThat(riccon.path("model").path("deploymentMode").asText()).isNotEmpty();
        assertThat(riccon.path("billing").path("currency").asText()).matches("[A-Z]{3}");
        assertThat(riccon.path("quota").path("userMonthly").asText()).isNotEmpty();
    }
}
