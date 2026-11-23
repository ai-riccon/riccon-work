package com.riccon.worker.index.domain;

import com.riccon.worker.parse.domain.ParseSummary;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code knowledge_document.parse_summary} 的持久化视图。
 *
 * <p>字段名与 OpenAPI {@code KnowledgeParseSummary} / {@code KnowledgeParseSheetSummary} /
 * {@code KnowledgeParseOcrSummary} 完全一致（{@code name/cellRange/empty/indexed/indexedRange}），
 * 不能直接序列化 worker 内部的 {@link ParseSummary}：后者包含 API 不接受的
 * {@code sheetName/rowCount/columnCount/skippedRange/indexedRanges} 等字段。
 * 只写结构元数据，不写单元格原文、OCR 文本或对象 key（ADR-0023 第 3.4/4.3 节）。
 *
 * @param sheets 工作表摘要；非 Excel 为空列表
 * @param hiddenSheetCount 被跳过的隐藏工作表数；非 Excel 为 0
 * @param ocr OCR 摘要；非 OCR 文档为 {@code null}
 */
public record ParseSummaryJson(List<Sheet> sheets, int hiddenSheetCount, Ocr ocr) {

    /** 防御性复制并对 null 归一为空列表。 */
    public ParseSummaryJson {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
        if (hiddenSheetCount < 0) {
            throw new IllegalArgumentException("hiddenSheetCount must be >= 0");
        }
    }

    /**
     * 从内部解析摘要生成契约视图。
     *
     * @param summary 解析器产出的摘要；{@code null} 表示该解析器不适用
     * @return 可持久化的契约视图；入参为空时返回 {@code null}
     */
    public static ParseSummaryJson from(ParseSummary summary) {
        if (summary == null) {
            return null;
        }
        List<Sheet> sheets = summary.sheets().stream()
                .map(sheet -> new Sheet(
                        sheet.name(), sheet.cellRange(), sheet.empty(), sheet.indexed(), sheet.indexedRange()))
                .toList();
        Ocr ocr = summary.ocr() == null ? null : Ocr.from(summary.ocr());
        int hiddenSheetCount = summary.hiddenSheetCount() == null ? 0 : summary.hiddenSheetCount();
        return new ParseSummaryJson(sheets, hiddenSheetCount, ocr);
    }

    /**
     * 工作表摘要（对应 OpenAPI {@code KnowledgeParseSheetSummary}）。
     *
     * @param name 工作表名
     * @param cellRange 整表实际有值范围；空表可为空
     * @param empty 是否空表
     * @param indexed 是否有内容进入索引
     * @param indexedRange 已索引范围；与 {@code cellRange} 不同表示部分索引
     */
    public record Sheet(String name, String cellRange, boolean empty, boolean indexed, String indexedRange) {

        /** 校验工作表名非空。 */
        public Sheet {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("sheet name must not be blank");
            }
        }
    }

    /**
     * OCR 摘要（对应 OpenAPI {@code KnowledgeParseOcrSummary}）。
     *
     * @param averageConfidence 文档级平均置信度 0–1；无块时为 0
     * @param lowConfidencePages 低置信页集合
     * @param failedPages 识别失败页集合
     */
    public record Ocr(double averageConfidence, List<Integer> lowConfidencePages, List<Integer> failedPages) {

        /** 校验置信度范围并复制页集合。 */
        public Ocr {
            if (!Double.isFinite(averageConfidence) || averageConfidence < 0 || averageConfidence > 1) {
                throw new IllegalArgumentException("average confidence must be between 0 and 1");
            }
            lowConfidencePages = lowConfidencePages == null ? List.of() : List.copyOf(lowConfidencePages);
            failedPages = failedPages == null ? List.of() : List.copyOf(failedPages);
        }

        /**
         * 从内部 OCR 摘要转换。
         *
         * <p>契约要求 {@code averageConfidence} 必填；当解析器没有任何可计算块时内部值为
         * {@code null}，这里落为 0 并保留失败页集合，让 API 能展示“识别失败/低置信”，而不是
         * 因为 null 反序列化失败丢掉整个摘要。
         *
         * @param summary 内部 OCR 摘要
         * @return 契约视图
         */
        static Ocr from(ParseSummary.OcrSummary summary) {
            BigDecimal confidence = summary.averageConfidence();
            return new Ocr(
                    confidence == null ? 0.0 : confidence.doubleValue(),
                    summary.lowConfidencePages(),
                    summary.failedPages());
        }
    }
}
