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
 * Worker Actuator 运维端点在显式允许 CIDR 下的正/负例（OPS-M04-02b）。
 *
 * <p>MockMvc 默认来源 127.0.0.1 不在 `10.99.0.0/16` 内，因此 info 与 Prometheus 都必须保持拒绝；
 * 把来源改写为 `10.99.0.5` 后两者必须可读，证明 `RICCON_OPS_ALLOWED_CIDRS` 的网段放行与默认拒绝
 * 同时生效。Worker 不得因 B10 漂移检查新增匿名入口（威胁模型 B-08、ADR-0021 §2.2/§10.6）。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "riccon.ops.allowed-cidrs=10.99.0.0/16"
        })
class InfoCidrAccessTest extends WorkerIntegrationTestBase {

    /** 模拟经过真实安全过滤器链的内部请求。 */
    @Autowired
    private MockMvc mockMvc;

    /** 来源命中允许网段时可读取 info，且漂移指纹键完整。 */
    @Test
    @DisplayName("OPS-M04-02b：显式允许 CIDR 可读取 /actuator/info")
    void allowedCidrReadsInfo() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/actuator/info").with(request -> {
                    request.setRemoteAddr("10.99.0.5");
                    return request;
                }))
                .andReturn()
                .getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as(body).isEqualTo(200);
        assertThat(body).contains("deploymentMode").contains("currency").contains("tenantDaily");
    }

    /** 回归保护：同一允许来源下 Prometheus 仍可抓取，来源门禁未被 info 改动破坏。 */
    @Test
    @DisplayName("OPS-M04-02b：显式允许 CIDR 可抓取 /actuator/prometheus")
    void allowedCidrScrapesPrometheus() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        get("/actuator/prometheus").with(request -> {
                            request.setRemoteAddr("10.99.0.5");
                            return request;
                        }))
                .andReturn()
                .getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as(body).isEqualTo(200);
        assertThat(body).contains("# HELP");
    }

    /** 非允许来源（默认 MockMvc 来源 127.0.0.1）读取 info 必须返回 403，且不携带指纹内容。 */
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
