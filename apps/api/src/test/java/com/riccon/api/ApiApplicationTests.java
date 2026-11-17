package com.riccon.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 应用上下文与安全基线测试。
 *
 * <p>验证两件事：应用能够启动；在未接入认证时健康检查公开、其它路径默认拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiApplicationTests {

    /** 模拟 HTTP 请求，经过完整的 Spring Security 过滤器链。 */
    @Autowired
    private MockMvc mockMvc;

    /** 健康检查端点必须对未认证请求开放。 */
    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** 未接入认证前，任何业务路径都必须被默认拒绝。 */
    @Test
    void businessEndpointIsDeniedByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/unknown")).andExpect(status().is4xxClientError());
    }
}
