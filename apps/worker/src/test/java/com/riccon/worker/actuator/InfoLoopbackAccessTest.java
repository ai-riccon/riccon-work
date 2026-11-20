package com.riccon.worker.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.riccon.worker.WorkerIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Worker Actuator `/actuator/info` 的 loopback 正例（OPS-M04-02b）。
 *
 * <p>B10 的 `seed-model-catalog --check` 需要同时读取 API 与 Worker 的 info 指纹；本测试固定
 * `RICCON_OPS_ALLOWED_CIDRS` 为空（仅 loopback），证明 Worker 侧端点可读且指纹键存在，
 * 并验证来源例外不会放开其他 Actuator 端点（ADR-0021 §2.2/§10.6）。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus,loggers",
            "riccon.ops.allowed-cidrs="
        })
class InfoLoopbackAccessTest extends WorkerIntegrationTestBase {

    /** 模拟经过真实安全过滤器链的内部请求。 */
    @Autowired
    private MockMvc mockMvc;

    /** MockMvc 的来源地址是 127.0.0.1，空允许列表下应可读取 info 指纹。 */
    @Test
    @DisplayName("OPS-M04-02b：空 allowed-cidrs 允许 loopback 读取 /actuator/info")
    void loopbackReadsInfoWhenAllowlistIsEmpty() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/actuator/info")).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as(body).isEqualTo(200);
        JsonNode riccon = objectMapper.readTree(body).path("riccon");
        assertThat(riccon.isObject()).as(body).isTrue();
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
    void otherActuatorEndpointsStayDenied() throws Exception {
        mockMvc.perform(get("/actuator/loggers")).andExpect(status().isForbidden());
    }
}
