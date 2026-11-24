package com.riccon.worker.injection;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.config.ParseProperties;
import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.service.OcrImagePreprocessor;
import com.riccon.worker.ocr.service.OcrService;
import com.riccon.worker.ocr.support.FakeOcrEngine;
import com.riccon.worker.ocr.support.OcrTestSupport;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.service.ImageDocumentParser;
import com.riccon.worker.parse.service.TextChunker;
import com.riccon.worker.parse.service.XlsxDocumentParser;
import com.riccon.worker.support.TestDocuments;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 注入 harness 的输入夹具：用真实 POI/OCR 解析器生成解析结果，再按生产切分器映射为引用。
 *
 * <p>夹具全部在代码内生成，不新增测试资源文件：Excel 用 POI 写出 XLSX；OCR 用 {@link FakeOcrEngine}
 * 返回文本块，经 {@link ImageDocumentParser} 走与生产一致的 OCR → {@code ParsedSegment} 映射。
 * 解析、切分、定位字段都是生产实现，harness 只替换模型、检索仓储与外部网络。
 */
final class InjectionFixtures {

    /** 知识库文档 ID；同一样本内稳定。 */
    static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-4000-8000-00000000b10a");

    /** 与生产默认一致的切分配置，避免夹具使用私有参数。 */
    static final ParseProperties PARSE_PROPERTIES = new ParseProperties(52_428_800L, 800, 80);

    /** 可见工作表在隐藏表样本中的名称。 */
    private static final String VISIBLE_SHEET = "可见数据";

    /** 工具类禁止实例化。 */
    private InjectionFixtures() {}

    /**
     * 用真实解析器解析样本，返回生产 {@link ParsedContent}。
     *
     * @param sample 注入样本
     * @return 解析结果
     */
    static ParsedContent parse(InjectionSample sample) {
        if (sample.surface().isConnector()) {
            return ConnectorInjectionFixtures.parse(sample);
        }
        return switch (sample.surface()) {
            case XLSX_VISIBLE_CELL, XLSX_HIDDEN_SHEET, XLSX_FORMULA_CELL -> {
                byte[] content = workbook(sample);
                yield new XlsxDocumentParser().parse(file(FileFormat.XLSX, content), content);
            }
            case OCR_LINE, OCR_FORGED_BOUNDARY -> {
                byte[] content = TestDocuments.png();
                yield parseImage(sample, content);
            }
            default -> throw new IllegalArgumentException("unexpected surface " + sample.surface());
        };
    }

    /**
     * 按生产切分器切分解析结果，保留工作表/单元格/页码定位。
     *
     * @param parsed 解析结果
     * @return 文本块
     */
    static List<ParsedChunk> chunks(ParsedContent parsed) {
        return new TextChunker(PARSE_PROPERTIES).chunk(parsed);
    }

    /**
     * 把切分结果映射为检索引用；生产环境由 MySQL 仓储返回同样的字段形状。
     *
     * @param sample 注入样本
     * @param chunks 切分结果
     * @return 引用列表
     */
    static List<RetrievedChunk> citations(InjectionSample sample, List<ParsedChunk> chunks) {
        List<RetrievedChunk> citations = new ArrayList<>(chunks.size());
        int ordinal = 1;
        String title =
                sample.surface().isConnector() ? ConnectorInjectionFixtures.fileName(sample) : "注入样本 " + sample.id();
        for (ParsedChunk chunk : chunks) {
            citations.add(new RetrievedChunk(
                    ordinal,
                    CitationSourceType.KNOWLEDGE,
                    null,
                    DOCUMENT_ID,
                    UUID.nameUUIDFromBytes((sample.id() + ":" + ordinal).getBytes(StandardCharsets.UTF_8)),
                    chunk.locatorType(),
                    chunk.pageNo(),
                    chunk.sheetName(),
                    chunk.cellRange(),
                    chunk.paragraphNo(),
                    title,
                    chunk.content(),
                    0.9));
            ordinal++;
        }
        return List.copyOf(citations);
    }

    /**
     * 构造并关闭一次性 OCR 服务，确保测试不残留 OCR 线程池。
     *
     * @param sample 注入样本
     * @param content 图片字节
     * @return 解析结果
     */
    private static ParsedContent parseImage(InjectionSample sample, byte[] content) {
        OcrService service = ocrService(sample);
        try {
            return new ImageDocumentParser(service).parse(file(FileFormat.PNG, content), content);
        } finally {
            service.close();
        }
    }

    /**
     * 构造 OCR 服务；引擎按样本返回文本块，不加载原生 Tesseract。
     *
     * @param sample 注入样本
     * @return OCR 服务
     */
    private static OcrService ocrService(InjectionSample sample) {
        List<OcrBlock> blocks = List.of(
                new OcrBlock("2026 季度销售概览", new BigDecimal("0.950"), new BoundingBox(10, 10, 400, 32), 1, 1),
                new OcrBlock(sample.payload(), new BigDecimal("0.940"), new BoundingBox(10, 52, 900, 48), 2, 1));
        FakeOcrEngine engine = new FakeOcrEngine(new OcrResult(blocks, 1200, 800));
        OcrProperties properties = OcrTestSupport.defaults();
        return new OcrService(engine, properties, new OcrImagePreprocessor(properties));
    }

    /**
     * 构造文件快照。
     *
     * @param format 文件格式
     * @param content 文件字节
     * @return 文件快照
     */
    private static FileRecord file(FileFormat format, byte[] content) {
        return new FileRecord(
                DOCUMENT_ID,
                "injection." + format.name().toLowerCase(),
                format,
                "application/octet-stream",
                content.length,
                "test/" + DOCUMENT_ID);
    }

    /**
     * 用 POI 生成样本工作簿。
     *
     * @param sample 注入样本
     * @return XLSX 字节
     */
    private static byte[] workbook(InjectionSample sample) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            switch (sample.surface()) {
                case XLSX_VISIBLE_CELL -> visibleCell(workbook, sample);
                case XLSX_HIDDEN_SHEET -> hiddenSheet(workbook, sample);
                case XLSX_FORMULA_CELL -> formulaCell(workbook, sample);
                default -> throw new IllegalArgumentException("not an xlsx sample: " + sample.display());
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create xlsx injection fixture", exception);
        }
    }

    /**
     * 可见单元格：正常标题 + 注入文本，模拟可被检索的业务表格。
     *
     * @param workbook 工作簿
     * @param sample 样本
     */
    private static void visibleCell(XSSFWorkbook workbook, InjectionSample sample) {
        Sheet sheet = workbook.createSheet(sample.sheetName());
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("2026 季度销售概览");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(sample.payload());
        row.createCell(1).setCellValue(128);
    }

    /**
     * 隐藏表：可见表保留业务内容，恶意指令放在隐藏表；解析器必须整表跳过。
     *
     * @param workbook 工作簿
     * @param sample 样本
     */
    private static void hiddenSheet(XSSFWorkbook workbook, InjectionSample sample) {
        Sheet visible = workbook.createSheet(VISIBLE_SHEET);
        visible.createRow(0).createCell(0).setCellValue("可见数据：季度销售增长 18%");
        Sheet hidden = workbook.createSheet(sample.sheetName());
        hidden.createRow(0).createCell(0).setCellValue(sample.payload());
        workbook.setSheetVisibility(1, SheetVisibility.HIDDEN);
    }

    /**
     * 公式单元格：用 CONCATENATE 生成缓存值，解析器只允许读缓存值，不允许执行公式。
     *
     * @param workbook 工作簿
     * @param sample 样本
     */
    private static void formulaCell(XSSFWorkbook workbook, InjectionSample sample) {
        Sheet sheet = workbook.createSheet(sample.sheetName());
        Row row = sheet.createRow(0);
        Cell formula = row.createCell(0);
        formula.setCellFormula(concatenateFormula(sample.payload()));
        workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
        Row benign = sheet.createRow(1);
        benign.createCell(0).setCellValue("2026 季度销售概览");
    }

    /**
     * 把注入文本拆成两个字符串字面量再 CONCATENATE，确保公式文本与缓存值不同。
     *
     * @param payload 注入文本
     * @return 公式
     */
    private static String concatenateFormula(String payload) {
        int split = Math.max(1, payload.length() / 2);
        String first = escape(payload.substring(0, split));
        String second = escape(payload.substring(split));
        return "CONCATENATE(\"" + first + "\",\"" + second + "\")";
    }

    /**
     * 转义 Excel 字符串字面量中的双引号。
     *
     * @param value 原文
     * @return 转义文本
     */
    private static String escape(String value) {
        return value.replace("\"", "\"\"");
    }
}
