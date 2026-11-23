package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrException;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.support.FakeOcrEngine;
import com.riccon.worker.ocr.support.MutableClock;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.support.TestDocuments;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PDF 解析器 OCR 测试：文本页不变、扫描页、混合页、部分失败、资源上限与超时。 */
class PdfDocumentParserTest {

    /** 构造 PDF 解析器。 */
    private static PdfDocumentParser parser(
            com.riccon.worker.ocr.service.OcrService service, OcrProperties properties) {
        return new PdfDocumentParser(service, properties, Clock.systemUTC());
    }

    /** 构造带测试时钟的 PDF 解析器。 */
    private static PdfDocumentParser parser(
            com.riccon.worker.ocr.service.OcrService service, OcrProperties properties, Clock clock) {
        return new PdfDocumentParser(service, properties, clock);
    }

    /** 构造 OCR 服务。 */
    private static com.riccon.worker.ocr.service.OcrService service(
            com.riccon.worker.ocr.domain.OcrEngine engine, OcrProperties properties) {
        return new com.riccon.worker.ocr.service.OcrService(
                engine, properties, new com.riccon.worker.ocr.service.OcrImagePreprocessor(properties));
    }

    /** 文本型 PDF 不触发 OCR，解析结果与 M02 一致。 */
    @Test
    @DisplayName("parse 文本型 PDF 不重复 OCR")
    void parse_textPdf_doesNotOcr() {
        byte[] content = TestDocuments.pdf(2);
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(), 100, 100));
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            ParsedContent parsed = parser(service, properties).parse(file(content), content);

            assertThat(parsed.pageCount()).isEqualTo(2);
            assertThat(parsed.segments()).hasSize(2);
            assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.PDFBOX);
            assertThat(parsed.warnings()).isEmpty();
            assertThat(parsed.summary()).isNull();
            assertThat(engine.callCount()).isZero();
        } finally {
            service.close();
        }
    }

    /** 文本页 + 空白页且 OCR 关闭：只索引文本页并警告无文本页。 */
    @Test
    @DisplayName("parse 文本页+空白页且 OCR 关闭保留文本")
    void parse_textAndBlank_ocrDisabled_keepsText() throws IOException {
        byte[] content = textAndBlankPdf();
        OcrProperties properties = OcrTestSupport.disabled();
        com.riccon.worker.ocr.service.OcrService service = service(null, properties);
        try {
            ParsedContent parsed = parser(service, properties).parse(file(content), content);

            assertThat(parsed.segments()).hasSize(1);
            assertThat(parsed.warnings()).anyMatch(warning -> warning.startsWith("PAGES_WITHOUT_TEXT:"));
            assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.PDFBOX);
        } finally {
            service.close();
        }
    }

    /** 扫描件 PDF 每页 OCR，段落带页码、bbox 与置信度。 */
    @Test
    @DisplayName("parse 扫描件 PDF 逐页 OCR 并带 bbox")
    void parse_scannedPdf_ocrsEachPage() throws IOException {
        byte[] content = imagePdf(2);
        FakeOcrEngine engine = new FakeOcrEngine(options -> new OcrResult(
                List.of(new OcrBlock(
                        "page-" + options.pageNo(),
                        new java.math.BigDecimal("0.950"),
                        new BoundingBox(10, 20, 100, 30),
                        1,
                        options.pageNo())),
                1200,
                1600));
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            ParsedContent parsed = parser(service, properties).parse(file(content), content);

            assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.TESSERACT);
            assertThat(parsed.pageCount()).isEqualTo(2);
            assertThat(parsed.segments()).hasSize(2);
            assertThat(parsed.segments())
                    .extracting(segment -> segment.pageNo())
                    .containsExactly(1, 2);
            assertThat(parsed.segments()).allSatisfy(segment -> {
                assertThat(segment.bbox()).isEqualTo(new BoundingBox(10, 20, 100, 30));
                assertThat(segment.ocrConfidence()).isEqualByComparingTo("0.950");
            });
            assertThat(parsed.summary()).isNotNull();
            assertThat(parsed.summary().ocr().pageCount()).isEqualTo(2);
            assertThat(parsed.summary().ocr().averageConfidence()).isEqualByComparingTo("0.950");
            assertThat(parsed.summary().ocr().failedPages()).isEmpty();
        } finally {
            service.close();
        }
    }

    /** 混合页：有文本页不 OCR，仅无文本页 OCR。 */
    @Test
    @DisplayName("parse 混合页只 OCR 无文本页")
    void parse_mixedPdf_onlyOcrsBlankPages() throws IOException {
        byte[] content = mixedPdf();
        FakeOcrEngine engine = new FakeOcrEngine(options -> new OcrResult(
                List.of(new OcrBlock(
                        "scan", new java.math.BigDecimal("0.900"), new BoundingBox(0, 0, 50, 20), 1, options.pageNo())),
                100,
                100));
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            ParsedContent parsed = parser(service, properties).parse(file(content), content);

            assertThat(parsed.segments()).hasSize(2);
            assertThat(engine.callCount()).isEqualTo(1);
            assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.TESSERACT);
            assertThat(parsed.warnings()).isEmpty();
        } finally {
            service.close();
        }
    }

    /** 部分页 OCR 失败：成功页入索引，失败页告警且不产生分块。 */
    @Test
    @DisplayName("parse 部分页失败产生 FAILED_PAGES 警告")
    void parse_partialFailure_indexesSuccessfulPages() throws IOException {
        byte[] content = imagePdf(2);
        FakeOcrEngine engine = new FakeOcrEngine(options -> {
            if (options.pageNo() == 2) {
                throw new OcrException(ParseErrorCodes.OCR_FAILED, true, "engine failed");
            }
            return new OcrResult(
                    List.of(new OcrBlock(
                            "page-1", new java.math.BigDecimal("0.900"), new BoundingBox(0, 0, 50, 20), 1, 1)),
                    100,
                    100);
        });
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            ParsedContent parsed = parser(service, properties).parse(file(content), content);

            assertThat(parsed.segments()).hasSize(1);
            assertThat(parsed.segments().get(0).pageNo()).isEqualTo(1);
            assertThat(parsed.warnings()).contains("FAILED_PAGES:[2]", "PAGES_WITHOUT_TEXT:1");
            assertThat(parsed.summary().ocr().failedPages()).containsExactly(2);
        } finally {
            service.close();
        }
    }

    /** 全部页 OCR 失败：整本失败并使用可重试错误码。 */
    @Test
    @DisplayName("parse 全部页失败整本失败")
    void parse_allPagesFailed_fails() throws IOException {
        byte[] content = imagePdf(2);
        FakeOcrEngine engine = new FakeOcrEngine(options -> {
            throw new OcrException(ParseErrorCodes.OCR_TIMEOUT, true, "timeout");
        });
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            assertThatThrownBy(() -> parser(service, properties).parse(file(content), content))
                    .isInstanceOf(ParseException.class)
                    .satisfies(exception -> {
                        ParseException parseException = (ParseException) exception;
                        assertThat(parseException.errorCode()).isEqualTo(ParseErrorCodes.OCR_TIMEOUT);
                        assertThat(parseException.retryable()).isTrue();
                    });
        } finally {
            service.close();
        }
    }

    /** 全部页无文本：应按 ADR 返回不可重试的 KB_NO_TEXT。 */
    @Test
    @DisplayName("parse 全部页无文本返回 KB_NO_TEXT")
    void parse_allPagesNoText_returnsNoText() throws IOException {
        byte[] content = imagePdf(2);
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(), 100, 100));
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            assertThatThrownBy(() -> parser(service, properties).parse(file(content), content))
                    .isInstanceOf(ParseException.class)
                    .satisfies(exception -> {
                        ParseException parseException = (ParseException) exception;
                        assertThat(parseException.errorCode()).isEqualTo(ParseErrorCodes.NO_TEXT);
                        assertThat(parseException.retryable()).isFalse();
                    });
        } finally {
            service.close();
        }
    }

    /** OCR 关闭的纯扫描 PDF：失败并返回 KB_OCR_UNAVAILABLE。 */
    @Test
    @DisplayName("parse OCR 关闭的扫描件返回 KB_OCR_UNAVAILABLE")
    void parse_scannedPdf_ocrDisabled_failsUnavailable() throws IOException {
        byte[] content = imagePdf(1);
        OcrProperties properties = OcrTestSupport.disabled();
        com.riccon.worker.ocr.service.OcrService service = service(null, properties);
        try {
            assertThatThrownBy(() -> parser(service, properties).parse(file(content), content))
                    .isInstanceOf(ParseException.class)
                    .satisfies(exception -> assertThat(((ParseException) exception).errorCode())
                            .isEqualTo(ParseErrorCodes.OCR_UNAVAILABLE));
        } finally {
            service.close();
        }
    }

    /** 超过 OCR 页数上限：只处理前 N 页并告警。 */
    @Test
    @DisplayName("parse 超过 OCR 页数上限告警并截断")
    void parse_pageLimit_truncates() throws IOException {
        byte[] content = imagePdf(2);
        FakeOcrEngine engine = new FakeOcrEngine(options -> new OcrResult(
                List.of(new OcrBlock(
                        "page", new java.math.BigDecimal("0.900"), new BoundingBox(0, 0, 50, 20), 1, options.pageNo())),
                100,
                100));
        OcrProperties properties = OcrTestSupport.properties(
                true, Duration.ofSeconds(5), Duration.ofSeconds(30), 1, 50_000_000L, 200_000_000L, 4000);
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            ParsedContent parsed = parser(service, properties).parse(file(content), content);

            assertThat(engine.callCount()).isEqualTo(1);
            assertThat(parsed.segments()).hasSize(1);
            assertThat(parsed.warnings()).contains("OCR_PAGE_LIMIT:1", "PAGES_WITHOUT_TEXT:1");
            assertThat(parsed.summary().ocr().truncated()).isTrue();
        } finally {
            service.close();
        }
    }

    /** 超大 PDF 页在渲染前按资源上限拒绝，避免 OOM。 */
    @Test
    @DisplayName("parse 超大 PDF 页按资源上限拒绝")
    void parse_hugePage_resourceLimit() throws IOException {
        byte[] content = hugePagePdf();
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(), 100, 100));
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            assertThatThrownBy(() -> parser(service, properties).parse(file(content), content))
                    .isInstanceOf(ParseException.class)
                    .satisfies(exception -> assertThat(((ParseException) exception).errorCode())
                            .isEqualTo(ParseErrorCodes.PARSE_RESOURCE_LIMIT));
            assertThat(engine.callCount()).isZero();
        } finally {
            service.close();
        }
    }

    /** 5000×5000pt 页在 200 DPI 下请求 192.9MP：必须在渲染前降采样到 64MB/4000px 预算内。 */
    @Test
    @DisplayName("parse 近上限 PDF 页降采样后渲染")
    void parse_nearLimitPage_downscalesBeforeRender() throws Exception {
        byte[] content = nearLimitPagePdf();
        FakeOcrEngine engine = new FakeOcrEngine(options -> new OcrResult(
                List.of(new OcrBlock("page", new java.math.BigDecimal("0.900"), new BoundingBox(0, 0, 50, 20), 1, 1)),
                100,
                100));
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            parser(service, properties).parse(file(content), content);

            assertThat(engine.receivedImages()).hasSize(1);
            BufferedImage received = ImageIO.read(
                    new ByteArrayInputStream(engine.receivedImages().get(0)));
            assertThat(received).isNotNull();
            assertThat(Math.max(received.getWidth(), received.getHeight())).isLessThanOrEqualTo(4000);
            assertThat((long) received.getWidth() * received.getHeight())
                    .isLessThanOrEqualTo(properties.effectiveDecodePixels());
            // 降采样后仍应保留可 OCR 的页面（约 57 DPI），不是 579MB 的原始渲染。
            assertThat(received.getWidth()).isBetween(3900, 4000);
        } finally {
            service.close();
        }
    }

    /** 单文档预算耗尽：保留已成功页，后续页跳过并截断。 */
    @Test
    @DisplayName("parse 单文档超时保留部分结果")
    void parse_documentTimeout_keepsPartial() throws IOException {
        byte[] content = imagePdf(3);
        MutableClock clock = new MutableClock(ZoneOffset.UTC);
        FakeOcrEngine engine = new FakeOcrEngine(options -> {
            if (options.pageNo() == 1) {
                // 第一页成功后把时钟推过文档预算，后续页必须在渲染/OCR 前被跳过。
                clock.advance(Duration.ofMillis(200));
                return new OcrResult(
                        List.of(new OcrBlock(
                                "page-1", new java.math.BigDecimal("0.900"), new BoundingBox(0, 0, 50, 20), 1, 1)),
                        100,
                        100);
            }
            throw new OcrException(ParseErrorCodes.OCR_FAILED, true, "page should have been skipped");
        });
        OcrProperties properties = OcrTestSupport.properties(
                true, Duration.ofSeconds(5), Duration.ofMillis(100), 100, 50_000_000L, 200_000_000L, 4000);
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            ParsedContent parsed = parser(service, properties, clock).parse(file(content), content);

            assertThat(parsed.segments()).hasSize(1);
            assertThat(parsed.segments().get(0).pageNo()).isEqualTo(1);
            assertThat(parsed.warnings()).contains("PAGES_WITHOUT_TEXT:2", "OCR_PAGE_LIMIT:2");
            assertThat(parsed.summary().ocr().truncated()).isTrue();
            assertThat(engine.callCount()).isEqualTo(1);
        } finally {
            service.close();
        }
    }

    /** PDF 页按 200 DPI 渲染（A4 ≈ 1654×2339 像素）。 */
    @Test
    @DisplayName("parse 按 200 DPI 渲染 PDF 页")
    void parse_rendersAtConfiguredDpi() throws Exception {
        byte[] content = imagePdf(1);
        FakeOcrEngine engine = new FakeOcrEngine(options -> new OcrResult(
                List.of(new OcrBlock("page", new java.math.BigDecimal("0.900"), new BoundingBox(0, 0, 50, 20), 1, 1)),
                100,
                100));
        OcrProperties properties = OcrTestSupport.defaults();
        com.riccon.worker.ocr.service.OcrService service = service(engine, properties);
        try {
            parser(service, properties).parse(file(content), content);

            assertThat(engine.receivedImages()).hasSize(1);
            BufferedImage received = ImageIO.read(
                    new ByteArrayInputStream(engine.receivedImages().get(0)));
            assertThat(received).isNotNull();
            assertThat(received.getWidth()).isBetween(1650, 1658);
            assertThat(received.getHeight()).isBetween(2335, 2343);
        } finally {
            service.close();
        }
    }

    /**
     * 构造仅用于解析器的文件快照。
     *
     * @param content 内容
     * @return 文件快照
     */
    private static FileRecord file(byte[] content) {
        return new FileRecord(
                UUID.randomUUID(),
                "scan.pdf",
                com.riccon.platform.domain.file.FileFormat.PDF,
                "application/pdf",
                content.length,
                "tenants/test/file/2026/object");
    }

    /**
     * 生成两页 PDF：第一页有文本，第二页空白。
     *
     * @return PDF 字节
     */
    private static byte[] textAndBlankPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.A4);
            document.addPage(first);
            try (PDPageContentStream stream = new PDPageContentStream(document, first)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("text layer content");
                stream.endText();
            }
            document.addPage(new PDPage(PDRectangle.A4));
            return save(document);
        }
    }

    /**
     * 生成图片型 PDF。
     *
     * @param pages 页数
     * @return PDF 字节
     */
    private static byte[] imagePdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            BufferedImage image = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, 1200, 1600);
            } finally {
                graphics.dispose();
            }
            PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
            for (int pageNo = 1; pageNo <= pages; pageNo++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.drawImage(xObject, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            return save(document);
        }
    }

    /**
     * 生成第一页文本、第二页图片的混合 PDF。
     *
     * @return PDF 字节
     */
    private static byte[] mixedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.A4);
            document.addPage(first);
            try (PDPageContentStream stream = new PDPageContentStream(document, first)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("mixed pdf text layer");
                stream.endText();
            }
            BufferedImage image = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
            PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
            PDPage second = new PDPage(PDRectangle.A4);
            document.addPage(second);
            try (PDPageContentStream stream = new PDPageContentStream(document, second)) {
                stream.drawImage(xObject, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            return save(document);
        }
    }

    /**
     * 生成一页超大尺寸 PDF；按 200 DPI 渲染超过解码硬上限。
     *
     * @return PDF 字节
     */
    private static byte[] hugePagePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(10000f, 10000f)));
            return save(document);
        }
    }

    /**
     * 生成一页 5000×5000pt PDF；200 DPI 请求约 192.9MP，低于硬上限但超过 64MB 预算。
     *
     * @return PDF 字节
     */
    private static byte[] nearLimitPagePdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(5000f, 5000f)));
            return save(document);
        }
    }

    /**
     * 保存 PDF 为字节。
     *
     * @param document PDF 文档
     * @return 字节
     */
    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }
}
