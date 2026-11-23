package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.service.OcrImagePreprocessor;
import com.riccon.worker.ocr.service.OcrService;
import com.riccon.worker.ocr.support.FakeOcrEngine;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.support.TestDocuments;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 图片/JPEG 解析器 OCR 测试：成功、低置信、关闭开关、语言缺失与损坏。 */
class ImageDocumentParserTest {

    /** PNG OCR 成功时输出页码/bbox/置信度。 */
    @Test
    @DisplayName("ImageDocumentParser PNG OCR 成功")
    void parse_pngOcrSuccess_carriesBboxAndConfidence() {
        OcrBlock block =
                new OcrBlock("RICCON OCR 12345", new BigDecimal("0.930"), new BoundingBox(10, 20, 300, 40), 1, 1);
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(block), 1100, 220));
        OcrProperties properties = OcrTestSupport.defaults();
        OcrService service = service(engine, properties);
        try {
            ImageDocumentParser parser = new ImageDocumentParser(service);
            ParsedContent parsed = parser.parse(file(FileFormat.PNG, TestDocuments.png()), TestDocuments.png());

            assertThat(parsed.pageCount()).isEqualTo(1);
            assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.TESSERACT);
            assertThat(parsed.segments()).hasSize(1);
            assertThat(parsed.segments().get(0).content()).isEqualTo("RICCON OCR 12345");
            assertThat(parsed.segments().get(0).locatorType()).isEqualTo(LocatorType.PAGE);
            assertThat(parsed.segments().get(0).pageNo()).isEqualTo(1);
            assertThat(parsed.segments().get(0).bbox()).isEqualTo(new BoundingBox(10, 20, 300, 40));
            assertThat(parsed.segments().get(0).ocrConfidence()).isEqualByComparingTo("0.930");
            assertThat(parsed.warnings()).isEmpty();
            assertThat(parsed.summary().ocr().averageConfidence()).isEqualByComparingTo("0.930");
        } finally {
            service.close();
        }
    }

    /** 低置信仍成功并写 LOW_CONFIDENCE 警告。 */
    @Test
    @DisplayName("ImageDocumentParser 低置信仍成功并告警")
    void parse_lowConfidence_succeedsWithWarning() {
        OcrBlock block = new OcrBlock("模糊", new BigDecimal("0.400"), new BoundingBox(0, 0, 20, 10), 1, 1);
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(block), 100, 100));
        OcrProperties properties = OcrTestSupport.defaults();
        OcrService service = service(engine, properties);
        try {
            ParsedContent parsed = new ImageDocumentParser(service)
                    .parse(file(FileFormat.PNG, TestDocuments.png()), TestDocuments.png());

            assertThat(parsed.warnings()).contains("LOW_CONFIDENCE", "LOW_CONFIDENCE_PAGES:[1]");
            assertThat(parsed.summary().ocr().lowConfidencePages()).containsExactly(1);
        } finally {
            service.close();
        }
    }

    /** OCR 关闭时抛 KB_OCR_DISABLED，不生成元数据或伪引用。 */
    @Test
    @DisplayName("ImageDocumentParser OCR 关闭返回 KB_OCR_DISABLED")
    void parse_ocrDisabled_throwsDisabled() {
        OcrProperties properties = OcrTestSupport.disabled();
        OcrService service = service(null, properties);
        try {
            assertThatThrownBy(() -> new ImageDocumentParser(service)
                            .parse(file(FileFormat.PNG, TestDocuments.png()), TestDocuments.png()))
                    .isInstanceOf(ParseException.class)
                    .satisfies(exception -> {
                        ParseException parseException = (ParseException) exception;
                        assertThat(parseException.errorCode()).isEqualTo(ParseErrorCodes.OCR_DISABLED);
                        assertThat(parseException.retryable()).isFalse();
                        assertThat(parseException.getMessage()).contains("OCR 未启用");
                    });
        } finally {
            service.close();
        }
    }

    /** 语言包缺失映射为 KB_OCR_LANG_UNAVAILABLE。 */
    @Test
    @DisplayName("ImageDocumentParser 语言缺失返回 KB_OCR_LANG_UNAVAILABLE")
    void parse_languageUnavailable_throwsLanguageUnavailable() {
        FakeOcrEngine engine = new FakeOcrEngine(options -> {
            throw new OcrException(ParseErrorCodes.OCR_LANG_UNAVAILABLE, false, "language unavailable");
        });
        OcrProperties properties = OcrTestSupport.defaults();
        OcrService service = service(engine, properties);
        try {
            assertThatThrownBy(() -> new ImageDocumentParser(service)
                            .parse(file(FileFormat.JPEG, TestDocuments.jpeg()), TestDocuments.jpeg()))
                    .isInstanceOf(ParseException.class)
                    .satisfies(exception -> assertThat(((ParseException) exception).errorCode())
                            .isEqualTo(ParseErrorCodes.OCR_LANG_UNAVAILABLE));
        } finally {
            service.close();
        }
    }

    /** JPEG 使用同一 OCR 策略。 */
    @Test
    @DisplayName("JpegDocumentParser 报告 JPEG 并执行 OCR")
    void jpegParser_reportsJpegAndOcrs() {
        OcrBlock block = new OcrBlock("jpeg", new BigDecimal("0.900"), new BoundingBox(0, 0, 10, 10), 1, 1);
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(block), 60, 30));
        OcrProperties properties = OcrTestSupport.defaults();
        OcrService service = service(engine, properties);
        try {
            JpegDocumentParser parser = new JpegDocumentParser(service);
            ParsedContent parsed = parser.parse(file(FileFormat.JPEG, TestDocuments.jpeg()), TestDocuments.jpeg());

            assertThat(parser.format()).isEqualTo(FileFormat.JPEG);
            assertThat(parsed.segments()).hasSize(1);
            assertThat(parsed.segments().get(0).content()).isEqualTo("jpeg");
        } finally {
            service.close();
        }
    }

    /**
     * 构造 OCR 服务。
     *
     * @param engine 引擎
     * @param properties 配置
     * @return 服务
     */
    private static OcrService service(com.riccon.worker.ocr.domain.OcrEngine engine, OcrProperties properties) {
        return new OcrService(engine, properties, new OcrImagePreprocessor(properties));
    }

    /**
     * 构造仅用于解析器的文件快照。
     *
     * @param format 格式
     * @param content 内容
     * @return 文件快照
     */
    private static FileRecord file(FileFormat format, byte[] content) {
        return new FileRecord(
                UUID.randomUUID(),
                "sample." + format.name().toLowerCase(),
                format,
                "application/octet-stream",
                content.length,
                "tenants/test/file/2026/object");
    }
}
