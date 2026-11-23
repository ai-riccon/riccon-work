package com.riccon.worker.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrManifest;
import com.riccon.worker.ocr.domain.OcrOptions;
import com.riccon.worker.ocr.domain.OcrResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Tesseract 原生引擎集成测试（ADR-0023 第 4.7 节 IT-KB-104/SEC-KB-001）。
 *
 * <p>默认 surefire 不执行 {@code *IT}；只有显式 {@code RICCON_OCR_IT=true} 且宿主机/CI 安装
 * Tesseract + chi_sim/eng 时才运行。默认全量测试关闭 OCR，不依赖宿主原生库。镜像内零出网
 * 验证由 OcrCli 在 {@code --network none} 容器中完成（O1b 复用）。
 */
@Tag("ocr")
@EnabledIfEnvironmentVariable(named = "RICCON_OCR_IT", matches = "true")
class TesseractOcrEngineIT {

    /** 识别 O1a 固定夹具并输出非空文本/置信度。 */
    @Test
    @DisplayName("TesseractOcrEngine 识别 O1a 夹具")
    void recognize_samplePng_producesTextAndConfidence() throws Exception {
        OcrProperties properties = OcrProperties.cliDefaults("chi_sim+eng", "", "");
        TesseractOcrEngine engine = new TesseractOcrEngine(properties, OcrManifest.empty());
        try {
            Path sample = Path.of("..", "..", "deploy", "ocr", "sample.png").normalize();

            OcrResult result = engine.recognize(Files.readAllBytes(sample), new OcrOptions(1, 200));

            assertThat(engine.languageAvailable()).isTrue();
            assertThat(result.blocks()).isNotEmpty();
            assertThat(result.averageConfidence()).isGreaterThan(new java.math.BigDecimal("0.400"));
            String text = result.blocks().stream().map(OcrBlock::text).reduce("", (left, right) -> left + " " + right);
            assertThat(text.toUpperCase()).contains("RICCON").contains("12345");
        } finally {
            engine.close();
        }
    }
}
