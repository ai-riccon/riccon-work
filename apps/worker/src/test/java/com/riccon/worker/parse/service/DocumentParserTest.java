package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.support.TestDocuments;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 文档解析器单元测试。 */
class DocumentParserTest {

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

    /** PDF 按页输出定位信息。 */
    @Test
    void pdfParser_extractsPages() {
        byte[] content = TestDocuments.pdf(2);
        ParsedContent parsed = new PdfDocumentParser().parse(file(FileFormat.PDF, content), content);

        assertThat(parsed.pageCount()).isEqualTo(2);
        assertThat(parsed.segments()).hasSize(2);
        assertThat(parsed.segments()).extracting(segment -> segment.pageNo()).containsExactly(1, 2);
        assertThat(parsed.segments()).allSatisfy(segment -> {
            assertThat(segment.locatorType()).isEqualTo(LocatorType.PAGE);
            assertThat(segment.content()).contains("revenue analysis");
        });
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

    /** 图片 M02 只输出元数据，不做 OCR。 */
    @Test
    void imageParser_returnsMetadataOnly() {
        byte[] content = TestDocuments.png();
        ParsedContent parsed = new ImageDocumentParser().parse(file(FileFormat.PNG, content), new byte[0]);

        assertThat(parsed.pageCount()).isEqualTo(1);
        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content())
                .contains("图片元数据")
                .contains("M02 未执行 OCR")
                .contains("sample.png");
    }

    /** JPEG 使用同一元数据策略。 */
    @Test
    void jpegParser_reportsJpegFormat() {
        assertThat(new JpegDocumentParser().format()).isEqualTo(FileFormat.JPEG);
        assertThat(new JpegDocumentParser().parse(file(FileFormat.JPEG, new byte[0]), new byte[0]))
                .isNotNull();
    }

    /** 损坏 PDF 必须产生不可重试的稳定错误码。 */
    @Test
    void pdfParser_corruptContent_throwsUnprocessable() {
        byte[] content = TestDocuments.corruptPdf();
        assertThatThrownBy(() -> new PdfDocumentParser().parse(file(FileFormat.PDF, content), content))
                .isInstanceOf(ParseException.class)
                .satisfies(ex -> {
                    ParseException parseException = (ParseException) ex;
                    assertThat(parseException.errorCode()).isEqualTo("RICCON-FILE-422");
                    assertThat(parseException.retryable()).isFalse();
                });
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
        assertThatThrownBy(() -> new DocumentParserRegistry(List.of(new PdfDocumentParser(), new PdfDocumentParser())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate document parser");
    }
}
