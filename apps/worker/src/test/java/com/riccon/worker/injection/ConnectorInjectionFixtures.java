package com.riccon.worker.injection;

import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.service.OcrImagePreprocessor;
import com.riccon.worker.ocr.service.OcrService;
import com.riccon.worker.ocr.support.FakeOcrEngine;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.service.DocxDocumentParser;
import com.riccon.worker.parse.service.PdfDocumentParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * 连接器内容注入夹具：用生产解析路径把样本变成 {@link ParsedContent}。
 *
 * <p>txt/md 走连接器索引分支的真实 UTF-8 抽取器（{@code ConnectorPlainTextExtractor}）；docx/pdf 走
 * 与上传文件相同的 {@link DocxDocumentParser}/{@link PdfDocumentParser}。三类结果随后都由 B10 的
 * {@link InjectionFixtures#chunks(ParsedContent)} 与 {@link InjectionFixtures#citations} 映射为引用，
 * 保证夹具不复制生产切分/定位逻辑。
 *
 * <p>{@code ConnectorPlainTextExtractor} 是包私有类且没有公开入口，而本任务写范围限定在
 * {@code src/test/java} 下的 {@code injection} 目录。这里通过反射调用同一个生产方法，避免在测试里复制一份
 * 纯文本抽取实现；若未来生产侧提供公开入口，应改为直接调用。
 */
final class ConnectorInjectionFixtures {

    /** 工具类禁止实例化。 */
    private ConnectorInjectionFixtures() {}

    /**
     * 用生产连接器解析路径解析样本。
     *
     * @param sample 连接器注入样本
     * @return 解析结果
     */
    static ParsedContent parse(InjectionSample sample) {
        String fileName = fileName(sample);
        byte[] content = content(sample);
        return switch (sample.surface()) {
            case CONNECTOR_TXT, CONNECTOR_MD -> extractPlainText(content, fileName);
            case CONNECTOR_DOCX, CONNECTOR_DOCX_WHITE_TEXT, CONNECTOR_DOCX_TABLE ->
                new DocxDocumentParser().parse(file(FileFormat.DOCX, fileName, content), content);
            case CONNECTOR_PDF -> parsePdf(fileName, content);
            default -> throw new IllegalArgumentException("not a connector sample: " + sample.display());
        };
    }

    /**
     * 生成连接器对象字节；供解析夹具与索引链测试共用，避免两份样本内容漂移。
     *
     * @param sample 连接器注入样本
     * @return 对象字节
     */
    static byte[] content(InjectionSample sample) {
        return switch (sample.surface()) {
            case CONNECTOR_TXT, CONNECTOR_MD -> text(sample);
            case CONNECTOR_DOCX, CONNECTOR_DOCX_WHITE_TEXT, CONNECTOR_DOCX_TABLE -> docx(sample);
            case CONNECTOR_PDF -> pdf(sample);
            default -> throw new IllegalArgumentException("not a connector sample: " + sample.display());
        };
    }

    /**
     * 返回连接器样本的源文件名；只用于引用标题，不包含外部对象标识。
     *
     * @param sample 连接器注入样本
     * @return 文件名
     */
    static String fileName(InjectionSample sample) {
        return "connector-" + sample.id().toLowerCase() + "." + extension(sample.surface());
    }

    /**
     * 返回连接器样本对应的扩展名。
     *
     * @param surface 注入表面
     * @return 扩展名
     */
    private static String extension(InjectionSurface surface) {
        return switch (surface) {
            case CONNECTOR_TXT -> "txt";
            case CONNECTOR_MD -> "md";
            case CONNECTOR_DOCX, CONNECTOR_DOCX_WHITE_TEXT, CONNECTOR_DOCX_TABLE -> "docx";
            case CONNECTOR_PDF -> "pdf";
            default -> throw new IllegalArgumentException("not a connector surface: " + surface);
        };
    }

    /**
     * 生成 txt/md 字节；md 样本带 BOM 与 CRLF，覆盖连接器索引分支的归一化路径。
     *
     * @param sample 连接器注入样本
     * @return 文本字节
     */
    private static byte[] text(InjectionSample sample) {
        String document = "2026 季度销售概览\n\n" + sample.payload();
        if (sample.surface() == InjectionSurface.CONNECTOR_MD) {
            document = "# 市场调研\n\n" + sample.payload();
            return ("\uFEFF" + document.replace("\n", "\r\n")).getBytes(StandardCharsets.UTF_8);
        }
        return document.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 生成 docx 样本：普通段落、白字隐藏文本或表格公式样式文本。
     *
     * @param sample 连接器注入样本
     * @return docx 字节
     */
    private static byte[] docx(InjectionSample sample) {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph heading = document.createParagraph();
            heading.createRun().setText("2026 年度供应商清单");
            switch (sample.surface()) {
                case CONNECTOR_DOCX -> document.createParagraph().createRun().setText(sample.payload());
                case CONNECTOR_DOCX_WHITE_TEXT -> {
                    // 白字与极小字号是常见的“隐藏指令”手法；docx 解析器只读文本，不读颜色/字号。
                    XWPFParagraph hidden = document.createParagraph();
                    var run = hidden.createRun();
                    run.setText(sample.payload());
                    run.setColor("FFFFFF");
                    run.setFontSize(1);
                }
                case CONNECTOR_DOCX_TABLE -> {
                    XWPFTable table = document.createTable(2, 2);
                    XWPFTableRow header = table.getRow(0);
                    header.getCell(0).setText("项目");
                    header.getCell(1).setText("值");
                    XWPFTableRow row = table.getRow(1);
                    row.getCell(0).setText("公式");
                    row.getCell(1).setText(sample.payload());
                }
                default -> throw new IllegalArgumentException("not a docx sample: " + sample.display());
            }
            document.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create connector docx injection fixture", ex);
        }
    }

    /**
     * 生成仅含文本层的单页 pdf；样本必须为 ASCII，避免测试依赖中文字体。
     *
     * @param sample 连接器注入样本
     * @return pdf 字节
     */
    private static byte[] pdf(InjectionSample sample) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(72, 720);
                stream.showText(sample.payload());
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create connector pdf injection fixture", ex);
        }
    }

    /**
     * 用生产 PDF 解析器解析文本层；OCR 服务只用于满足构造器，不会被文本页调用。
     *
     * @param fileName 文件名
     * @param content pdf 字节
     * @return 解析结果
     */
    private static ParsedContent parsePdf(String fileName, byte[] content) {
        OcrService service = ocrService();
        try {
            PdfDocumentParser parser = new PdfDocumentParser(
                    service,
                    OcrTestSupport.defaults(),
                    Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC));
            return parser.parse(file(FileFormat.PDF, fileName, content), content);
        } finally {
            service.close();
        }
    }

    /**
     * 调用生产连接器纯文本抽取器；失败时保留原始异常，避免静默回退到测试私有实现。
     *
     * @param content 文本字节
     * @param fileName 文件名
     * @return 解析结果
     */
    private static ParsedContent extractPlainText(byte[] content, String fileName) {
        try {
            Class<?> extractor = Class.forName("com.riccon.worker.index.service.ConnectorPlainTextExtractor");
            Method extract = extractor.getDeclaredMethod("extract", byte[].class);
            extract.setAccessible(true);
            return (ParsedContent) extract.invoke(null, (Object) content);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("connector plain text extraction failed: " + fileName, cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法调用生产 ConnectorPlainTextExtractor；类名或方法签名变更需同步注入 harness", ex);
        }
    }

    /**
     * 构造文件快照；只使用文件名与样本字节，不包含连接器源对象 key。
     *
     * @param format 文件格式
     * @param fileName 文件名
     * @param content 文件字节
     * @return 文件快照
     */
    private static FileRecord file(FileFormat format, String fileName, byte[] content) {
        return new FileRecord(
                InjectionFixtures.DOCUMENT_ID,
                fileName,
                format,
                "application/octet-stream",
                content.length,
                "test/connector/" + fileName);
    }

    /**
     * 构造一次性 OCR 服务；PDF 文本层不会调用引擎，仅用于满足解析器依赖。
     *
     * @return OCR 服务
     */
    private static OcrService ocrService() {
        List<OcrBlock> blocks =
                List.of(new OcrBlock("2026 季度销售概览", new BigDecimal("0.950"), new BoundingBox(10, 10, 400, 32), 1, 1));
        OcrProperties properties = OcrTestSupport.defaults();
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(blocks, 1200, 800));
        return new OcrService(engine, properties, new OcrImagePreprocessor(properties));
    }
}
