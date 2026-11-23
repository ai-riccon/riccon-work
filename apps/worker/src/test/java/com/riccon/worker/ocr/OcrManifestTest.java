package com.riccon.worker.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.ocr.domain.OcrManifest;
import com.riccon.worker.ocr.support.OcrTestSupport;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OCR 镜像指纹测试：读取、模型哈希稳定与缺失降级。 */
class OcrManifestTest {

    /** 读取测试 manifest 并派生稳定模型哈希。 */
    @Test
    @DisplayName("OcrManifest 读取语言哈希并派生 modelHash")
    void load_readsLanguagesAndDerivesHash() {
        OcrManifest manifest = OcrManifest.load(OcrTestSupport.manifestPath());

        assertThat(manifest.engine()).isEqualTo("tesseract");
        assertThat(manifest.engineVersion()).isEqualTo("5.3.4-1build5");
        assertThat(manifest.languageHashes()).containsKeys("chi_sim", "eng");
        assertThat(manifest.modelHash()).hasSize(64);
        assertThat(manifest.modelHash()).isEqualTo(manifest.modelHash());
        assertThat(OcrManifest.load(OcrTestSupport.manifestPath()).modelHash()).isEqualTo(manifest.modelHash());
    }

    /** 缺失或空路径降级为 unknown，不阻断启动。 */
    @Test
    @DisplayName("OcrManifest 缺失降级为 unknown")
    void load_missing_returnsEmpty() {
        OcrManifest missing = OcrManifest.load("/not-exists/ocr-manifest.json");
        OcrManifest blank = OcrManifest.load(" ");

        assertThat(missing.modelHash()).isEqualTo(OcrManifest.UNKNOWN);
        assertThat(missing.engineVersion()).isEqualTo(OcrManifest.UNKNOWN);
        assertThat(blank.modelHash()).isEqualTo(OcrManifest.UNKNOWN);
        assertThat(new OcrManifest("tesseract", null, null, null, Map.of()).modelHash())
                .isEqualTo(OcrManifest.UNKNOWN);
    }
}
