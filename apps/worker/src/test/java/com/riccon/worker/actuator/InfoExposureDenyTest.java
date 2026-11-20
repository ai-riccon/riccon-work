package com.riccon.worker.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.riccon.worker.WorkerIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Worker Actuator `/actuator/info` 的非允许来源负例（OPS-M04-02b）。
 *
 * <p>MockMvc 的来源地址 127.0.0.1 不在显式允许网段内，因此 info 与 Prometheus 都必须保持拒绝；
 * Worker 不得因 B10 漂移检查新增匿名入口（威胁模型 B-08、ADR-0021 §2.2/§10.6）。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "riccon.ops.allowed-cidrs=10.99.0.0/16"
        })
class InfoExposureDenyTest extends WorkerIntegrationTestBase {

    /** 模拟经过真实安全过滤器链的内部请求。 */
    @Autowired
    private MockMvc mockMvc;

    /** 非允许来源读取 info 必须返回 403，且响应不得携带指纹内容。 */
    @Test
    @DisplayName("OPS-M04-02b：非允许来源读取 /actuator/info 被拒绝")
    void nonAllowlistedSourceIsDenied() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/actuator/info")).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as(body).isEqualTo(403);
        assertThat(body).doesNotContain("deploymentMode").doesNotContain("riccon");
    }

    /** 回归保护：为 info 增加来源规则不得放宽 Prometheus 的既有拒绝。 */
    @Test
    @DisplayName("OPS-M04-02b：非允许来源读取 /actuator/prometheus 仍被拒绝")
    void prometheusStillDeniedForNonAllowlistedSource() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as(body).isEqualTo(403);
        assertThat(body).doesNotContain("# HELP").doesNotContain("# TYPE");
    }
}
