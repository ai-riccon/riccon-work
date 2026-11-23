package com.riccon.worker.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrEngine;
import com.riccon.worker.ocr.domain.OcrManifest;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.support.FakeOcrEngine;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.BoundingBox;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.info.Info;

/** OCR 信息端点测试：暴露非密钥指纹与语言可用性。 */
class OcrInfoContributorTest {

    /** 关闭开关时仍暴露引擎/语言/哈希，且不泄露路径或凭据。 */
    @Test
    @DisplayName("OcrInfoContributor 关闭时暴露非密钥指纹")
    void contribute_disabled_exposesFingerprint() {
        OcrManifest manifest = OcrManifest.load(OcrTestSupport.manifestPath());
        OcrInfoContributor contributor = new OcrInfoContributor(
                OcrTestSupport.withManifest(OcrTestSupport.manifestPath(), false), manifest, provider(null));
        Info.Builder builder = new Info.Builder();

        contributor.contribute(builder);

        Map<String, Object> details = ocrDetails(builder.build().getDetails());
        assertThat(details).containsEntry("enabled", false);
        assertThat(details).containsEntry("engine", "tesseract");
        assertThat(details).containsEntry("lang", "chi_sim+eng");
        assertThat(details.get("model_hash")).isEqualTo(manifest.modelHash());
        assertThat(details).doesNotContainKeys("tessdata_path", "password", "secret", "token");
    }

    /** 启用且引擎可用时标记 enabled 与 language_available。 */
    @Test
    @DisplayName("OcrInfoContributor 启用时标记语言可用")
    void contribute_enabled_reportsLanguageAvailability() {
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(
                List.of(new OcrBlock("x", new BigDecimal("0.9"), new BoundingBox(0, 0, 1, 1), 1, 1)), 10, 10));
        OcrManifest manifest = OcrManifest.load(OcrTestSupport.manifestPath());
        OcrInfoContributor contributor = new OcrInfoContributor(
                OcrTestSupport.withManifest(OcrTestSupport.manifestPath(), true), manifest, provider(engine));
        Info.Builder builder = new Info.Builder();

        contributor.contribute(builder);

        Map<String, Object> details = ocrDetails(builder.build().getDetails());
        assertThat(details).containsEntry("enabled", true);
        assertThat(details).containsEntry("language_available", true);
    }

    /** info 使用绑定后的 language，而不是硬编码默认值。 */
    @Test
    @DisplayName("OcrInfoContributor 使用绑定语言")
    void contribute_customLanguage_reportsBoundValue() {
        OcrManifest manifest = OcrManifest.load(OcrTestSupport.manifestPath());
        OcrInfoContributor contributor = new OcrInfoContributor(
                OcrTestSupport.withLanguage("eng", OcrTestSupport.manifestPath(), false), manifest, provider(null));
        Info.Builder builder = new Info.Builder();

        contributor.contribute(builder);

        assertThat(ocrDetails(builder.build().getDetails())).containsEntry("lang", "eng");
    }

    /**
     * 取出 {@code ocr} 详情。
     *
     * @param details Info 详情
     * @return OCR 详情
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> ocrDetails(Map<String, Object> details) {
        return (Map<String, Object>) details.get("ocr");
    }

    /**
     * 构造 Bean 提供者。
     *
     * @param engine 引擎
     * @return 提供者
     */
    private static ObjectProvider<OcrEngine> provider(OcrEngine engine) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (engine != null) {
            factory.registerSingleton("ocrEngine", engine);
        }
        return factory.getBeanProvider(OcrEngine.class);
    }
}
