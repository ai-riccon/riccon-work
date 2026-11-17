package com.riccon.worker;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Worker 应用上下文与健康检查测试。 */
class WorkerApplicationTests extends WorkerIntegrationTestBase {

    /** 模拟内部 HTTP 请求，经过安全过滤器链。 */
    @Autowired
    private MockMvc mockMvc;

    /** Worker 健康检查必须可用于编排系统探活。 */
    @Test
    void healthEndpointIsAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** 除健康检查外的路径默认拒绝。 */
    @Test
    void otherEndpointsAreDeniedByDefault() throws Exception {
        mockMvc.perform(get("/internal/unknown")).andExpect(status().is4xxClientError());
    }
}
