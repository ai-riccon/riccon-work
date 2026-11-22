package com.riccon.worker.parse.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 解析结构摘要（ADR-0023 第 3.4/4.3 节）。
 *
 * <p>写入 {@code knowledge_document.parse_summary}，供文档详情展示“解析了什么/跳过了什么”，
 * 只含结构元数据（工作表名、行列范围、空表标记、OCR 置信度与页集合），不含任何单元格原文、
 * OCR 文本、对象 key 或文件名。字段可空/空列表表示该解析器不适用。
 *
 * @param sheets Excel 工作表摘要；非 Excel 或未解析为空列表
 * @param ocr OCR 摘要；非 OCR 文档为空
 * @param hiddenSheetCount 被跳过的隐藏工作表数；非 Excel 为空
 * @param indexedRanges 已索引的文档级范围，例如 {@code Sheet1!A1:F20000}
 * @param skippedRanges 未索引/截断的文档级范围，例如 {@code Sheet9!A1:F20000}
 */
public record ParseSummary(
        List<SheetSummary> sheets,
        OcrSummary ocr,
        Integer hiddenSheetCount,
        List<String> indexedRanges,
        List<String> skippedRanges) {

    /** 防御性复制并对 null 归一为空列表。 */
    public ParseSummary {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
        indexedRanges = indexedRanges == null ? List.of() : List.copyOf(indexedRanges);
        skippedRanges = skippedRanges == null ? List.of() : List.copyOf(skippedRanges);
    }

    /**
     * 单工作表摘要。
     *
     * @param sheetName 工作表名，原样保存
     * @param rowCount 实际读取行数
     * @param columnCount 实际读取列数
     * @param empty 是否空表（空表跳过且不生成分块）
     * @param indexedRange 已索引范围，例如 {@code A1:F20000}
     * @param skippedRange 未索引范围；未截断时为空
     */
    public record SheetSummary(
            String sheetName, int rowCount, int columnCount, boolean empty, String indexedRange, String skippedRange) {}

    /**
     * OCR 质量摘要。
     *
     * @param pageCount 参与 OCR 的页数（单图固定 1）
     * @param averageConfidence 文档级平均置信度，0–1，保留 3 位小数；无块时为空
     * @param lowConfidencePages 低置信页集合
     * @param failedPages 失败页集合
     * @param truncated 是否因页数/超时上限只处理了部分页
     */
    public record OcrSummary(
            int pageCount,
            BigDecimal averageConfidence,
            List<Integer> lowConfidencePages,
            List<Integer> failedPages,
            boolean truncated) {

        /** 防御性复制页集合。 */
        public OcrSummary {
            lowConfidencePages = lowConfidencePages == null ? List.of() : List.copyOf(lowConfidencePages);
            failedPages = failedPages == null ? List.of() : List.copyOf(failedPages);
            if (averageConfidence != null
                    && (averageConfidence.signum() < 0 || averageConfidence.compareTo(BigDecimal.ONE) > 0)) {
                throw new IllegalArgumentException("average confidence must be between 0 and 1");
            }
        }
    }
}
