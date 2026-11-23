package com.riccon.worker.ocr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.support.FakeOcrEngine;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** OcrCli 测试：参数校验、成功输出文本/置信度与稳定退出码。 */
class OcrCliTest {

    /** 临时目录。 */
    @TempDir
    Path tempDir;

    /** 成功时输出文本、置信度与 bbox，退出码 0。 */
    @Test
    @DisplayName("OcrCli 成功输出文本与置信度")
    void execute_success_printsTextAndConfidence() throws Exception {
        Path image = writePng();
        OcrBlock block = new OcrBlock("RICCON OCR 12345", new BigDecimal("0.930"), new BoundingBox(1, 2, 3, 4), 1, 1);
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(block), 1100, 220));
        OcrProperties properties = OcrTestSupport.defaults();
        OcrImagePreprocessor preprocessor = new OcrImagePreprocessor(properties);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = OcrCli.execute(engine, preprocessor, image, 200, new PrintStream(out), new PrintStream(err));

        assertThat(exitCode).isZero();
        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("engine=fake")
                .contains("text=RICCON OCR 12345")
                .contains("averageConfidence=0.930");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /** 无文本时退出码 3。 */
    @Test
    @DisplayName("OcrCli 无文本退出码 3")
    void execute_noText_exitCode3() throws Exception {
        Path image = writePng();
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(), 10, 10));
        OcrProperties properties = OcrTestSupport.defaults();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = OcrCli.execute(
                engine, new OcrImagePreprocessor(properties), image, 200, new PrintStream(out), new PrintStream(err));

        assertThat(exitCode).isEqualTo(3);
    }

    /** 引擎失败时输出稳定错误码，退出码 3。 */
    @Test
    @DisplayName("OcrCli 引擎失败输出错误码")
    void execute_engineFailure_exitCode3() throws Exception {
        Path image = writePng();
        FakeOcrEngine engine = new FakeOcrEngine(options -> {
            throw new OcrException(ParseErrorCodes.PARSE_CORRUPT, false, "bad image");
        });
        OcrProperties properties = OcrTestSupport.defaults();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = OcrCli.execute(
                engine, new OcrImagePreprocessor(properties), image, 200, new PrintStream(out), new PrintStream(err));

        assertThat(exitCode).isEqualTo(3);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("errorCode=KB_PARSE_CORRUPT");
    }

    /** 缺少图片路径返回用法错误。 */
    @Test
    @DisplayName("OcrCli 缺少图片路径返回 2")
    void run_missingImage_exitCode2() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = OcrCli.run(new String[] {"--lang", "eng"}, new PrintStream(out), new PrintStream(err));

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage: OcrCli");
    }

    /** 未知参数返回用法错误。 */
    @Test
    @DisplayName("OcrCli 未知参数返回 2")
    void run_unknownOption_exitCode2() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = OcrCli.run(new String[] {"--unknown", "x"}, new PrintStream(out), new PrintStream(err));

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("未知参数");
    }

    /**
     * 写入 1×1 PNG。
     *
     * @return 路径
     */
    private Path writePng() throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        Path path = tempDir.resolve("sample.png");
        Files.write(path, output.toByteArray());
        return path;
    }
}
