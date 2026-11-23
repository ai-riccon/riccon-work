package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.service.OcrImagePreprocessor;
import com.riccon.worker.ocr.service.OcrService;
import com.riccon.worker.ocr.support.FakeOcrEngine;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.support.TestDocuments;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 文档解析器单元测试（文本型格式不受 OCR 影响）。 */
class DocumentParserTest {

    /** 文本型 PDF 按页输出定位信息，不触发 OCR。 */
    @Test
    void pdfParser_extractsPages() {
        byte[] content = TestDocuments.pdf(2);
        OcrProperties properties = OcrTestSupport.defaults();
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(List.of(), 100, 100));
        OcrService ocrService = new OcrService(engine, properties, new OcrImagePreprocessor(properties));
        try {
            ParsedContent parsed = new PdfDocumentParser(ocrService, properties, java.time.Clock.systemUTC())
                    .parse(file(FileFormat.PDF, content), content);

            assertThat(parsed.pageCount()).isEqualTo(2);
            assertThat(parsed.segments()).hasSize(2);
            assertThat(parsed.segments())
                    .extracting(segment -> segment.pageNo())
                    .containsExactly(1, 2);
            assertThat(parsed.segments()).allSatisfy(segment -> {
                assertThat(segment.locatorType()).isEqualTo(LocatorType.PAGE);
                assertThat(segment.content()).contains("revenue analysis");
            });
            assertThat(engine.callCount()).isZero();
        } finally {
            ocrService.close();
        }
    }

    /** DOCX 按段落序号输出，表格也占用一个段落号。 */
    @Test
    void docxParser_extractsParagraphsAndTable() {
        byte[] content = TestDocuments.docx();
        ParsedContent parsed = new DocxDocumentParser().parse(file(FileFormat.DOCX, content), content);

        assertThat(parsed.pageCount()).isNull();
        assertThat(parsed.sheetCount()).isNull();
        assertThat(parsed.segments()).hasSize(3);
        assertThat(parsed.segments())
                .allSatisfy(segment -> assertThat(segment.locatorType()).isEqualTo(LocatorType.PARAGRAPH));
        assertThat(parsed.segments())
                .extracting(segment -> segment.paragraphNo())
                .containsExactly(1, 2, 3);
        assertThat(parsed.segments().get(2).content()).contains("APAC").contains("120");
    }

    /** XLSX 按工作表输出并记录单元格范围。 */
    @Test
    void xlsxParser_extractsSheetsAndCellRange() {
        byte[] content = TestDocuments.xlsx();
        ParsedContent parsed = new XlsxDocumentParser().parse(file(FileFormat.XLSX, content), content);

        assertThat(parsed.sheetCount()).isEqualTo(2);
        assertThat(parsed.segments()).hasSize(2);
        assertThat(parsed.segments()).extracting(segment -> segment.sheetName()).containsExactly("Budget", "Summary");
        assertThat(parsed.segments())
                .allSatisfy(segment -> assertThat(segment.locatorType()).isEqualTo(LocatorType.SHEET));
        assertThat(parsed.segments().get(0).cellRange()).isEqualTo("A1:B2");
    }

    /** 损坏 PDF 必须产生不可重试的稳定错误码。 */
    @Test
    void pdfParser_corruptContent_throwsUnprocessable() {
        byte[] content = TestDocuments.corruptPdf();
        OcrProperties properties = OcrTestSupport.disabled();
        OcrService ocrService = new OcrService(
                (com.riccon.worker.ocr.domain.OcrEngine) null, properties, new OcrImagePreprocessor(properties));
        try {
            assertThatThrownBy(() -> new PdfDocumentParser(ocrService, properties, java.time.Clock.systemUTC())
                            .parse(file(FileFormat.PDF, content), content))
                    .isInstanceOf(ParseException.class)
                    .satisfies(ex -> {
                        ParseException parseException = (ParseException) ex;
                        assertThat(parseException.errorCode()).isEqualTo("RICCON-FILE-422");
                        assertThat(parseException.retryable()).isFalse();
                    });
        } finally {
            ocrService.close();
        }
    }

    /** 未注册格式必须拒绝，不能猜测解析器。 */
    @Test
    void registry_unsupportedFormat_throws() {
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of());
        byte[] content = TestDocuments.pdf(1);
        assertThat(registry.size()).isZero();
        assertThatThrownBy(() -> registry.parse(file(FileFormat.PDF, content), content))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("unsupported file format");
    }

    /** 重复解析器在启动期失败。 */
    @Test
    void registry_duplicateParser_throws() {
        OcrProperties properties = OcrTestSupport.disabled();
        OcrService ocrService = new OcrService(
                (com.riccon.worker.ocr.domain.OcrEngine) null, properties, new OcrImagePreprocessor(properties));
        try {
            assertThatThrownBy(() -> new DocumentParserRegistry(List.of(
                            new PdfDocumentParser(ocrService, properties, java.time.Clock.systemUTC()),
                            new PdfDocumentParser(ocrService, properties, java.time.Clock.systemUTC()))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("duplicate document parser");
        } finally {
            ocrService.close();
        }
    }

    /** 构造仅用于解析器的文件快照。 */
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
