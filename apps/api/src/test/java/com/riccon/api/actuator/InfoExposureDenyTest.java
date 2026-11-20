package com.riccon.api.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.support.ApiIntegrationTestSupport;
import com.riccon.api.support.ApiTestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * API Actuator `/actuator/info` 的非允许来源负例（OPS-M04-02b）。
 *
 * <p>把 `RICCON_OPS_ALLOWED_CIDRS` 设为不含 loopback 的网段；测试进程从 127.0.0.1 发起，
 * 等同于“不在允许列表的读取方”。B10 漂移检查不得依赖匿名全网可读，端点必须继续默认拒绝；
 * 同一上下文同时回归 Prometheus 的既有来源控制（ADR-0021 §2.2/§10.6）。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "riccon.ops.allowed-cidrs=10.99.0.0/16"
        })
class InfoExposureDenyTest extends ApiIntegrationTestSupport {

    /** 非允许来源读取 info 必须被拒绝，响应不得泄露任何指纹内容。 */
    @Test
    @DisplayName("OPS-M04-02b：非允许来源读取 /actuator/info 被拒绝")
    void nonAllowlistedSourceIsDenied() {
        ApiTestClient.ApiResponse response = client.get("/actuator/info");

        assertThat(response.status()).as(response.body()).isIn(401, 403, 404);
        assertThat(response.body()).doesNotContain("deploymentMode").doesNotContain("\"riccon\"");
    }

    /** 回归保护：为 info 增加来源规则不得放宽 Prometheus 的既有拒绝。 */
    @Test
    @DisplayName("OPS-M04-02b：非允许来源读取 /actuator/prometheus 仍被拒绝")
    void prometheusStillDeniedForNonAllowlistedSource() {
        ApiTestClient.ApiResponse response = client.get("/actuator/prometheus");

        assertThat(response.status()).as(response.body()).isIn(401, 403, 404);
        assertThat(response.body()).doesNotContain("# HELP").doesNotContain("# TYPE");
    }
}
