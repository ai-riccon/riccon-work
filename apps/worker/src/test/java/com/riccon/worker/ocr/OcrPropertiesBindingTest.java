package com.riccon.worker.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.ocr.config.OcrProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** OCR 配置绑定测试：确保 YAML `language` 键真正映射到 OcrProperties（F-002）。 */
class OcrPropertiesBindingTest {

    /** 仅注册配置绑定的最小上下文。 */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingConfiguration.class);

    /** `riccon.worker.ocr.language` 覆盖默认语言。 */
    @Test
    @DisplayName("language 键绑定到 OcrProperties.language")
    void languageKey_bindsToLanguage() {
        runner.withPropertyValues("riccon.worker.ocr.language=eng")
                .run(context -> assertThat(context.getBean(OcrProperties.class).language())
                        .isEqualTo("eng"));
    }

    /** 未配置语言时使用 ADR 默认 chi_sim+eng。 */
    @Test
    @DisplayName("未配置 language 使用默认 chi_sim+eng")
    void languageKey_absent_usesDefault() {
        runner.run(context ->
                assertThat(context.getBean(OcrProperties.class).language()).isEqualTo("chi_sim+eng"));
    }

    /** 64 MiB / 4B 与最长边平方共同收紧解码像素预算。 */
    @Test
    @DisplayName("effectiveDecodePixels 受 64MB 与 4000px 约束")
    void effectiveDecodePixels_respectsByteAndEdgeBudget() {
        assertThat(com.riccon.worker.ocr.support.OcrTestSupport.defaults().effectiveDecodePixels())
                .isEqualTo(16_000_000L);
        assertThat(com.riccon.worker.ocr.support.OcrTestSupport.properties(
                                true,
                                java.time.Duration.ofSeconds(5),
                                java.time.Duration.ofSeconds(30),
                                100,
                                50_000_000L,
                                200_000_000L,
                                1_000_000L,
                                4000)
                        .effectiveDecodePixels())
                .isEqualTo(250_000L);
    }

    /** 测试用配置类。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OcrProperties.class)
    static class BindingConfiguration {}
}
