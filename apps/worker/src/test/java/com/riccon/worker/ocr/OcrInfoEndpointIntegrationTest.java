package com.riccon.worker.ocr;

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
import tools.jackson.databind.JsonNode;

/**
 * OCR `/actuator/info` 指纹集成测试（ADR-0023 第 4.6 节）。
 *
 * <p>测试 profile 关闭 OCR；端点仍应暴露非密钥的 {@code ocr.engine}/{@code ocr.lang}/
 * {@code ocr.model_hash}，且不包含 tessdata 路径或凭据。O1b 的 ocr-check 与 M04 漂移预检复用
 * 同一端点来源限制（空 allowed-cidrs 仅 loopback）。
 */
@TestPropertySource(
        properties = {
            "management.endpoints.web.exposure.include=health,info,prometheus,loggers",
            "riccon.ops.allowed-cidrs="
        })
class OcrInfoEndpointIntegrationTest extends WorkerIntegrationTestBase {

    /** 模拟经过真实安全过滤器链的内部请求。 */
    @Autowired
    private MockMvc mockMvc;

    /** info 暴露 OCR 指纹且不泄露路径/凭据。 */
    @Test
    @DisplayName("OCR info 暴露 engine/lang/model_hash 且无密钥")
    void info_exposesOcrFingerprintWithoutSecrets() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/actuator/info")).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getStatus()).as(body).isEqualTo(200);
        JsonNode ocr = objectMapper.readTree(body).path("ocr");
        assertThat(ocr.isObject()).as(body).isTrue();
        assertThat(ocr.path("enabled").asBoolean()).isFalse();
        assertThat(ocr.path("engine").asText()).isEqualTo("tesseract");
        assertThat(ocr.path("lang").asText()).isEqualTo("chi_sim+eng");
        assertThat(ocr.path("model_hash").asText()).isNotBlank();
        assertThat(body).doesNotContainIgnoringCase("tessdata").doesNotContainIgnoringCase("password");
    }
}
