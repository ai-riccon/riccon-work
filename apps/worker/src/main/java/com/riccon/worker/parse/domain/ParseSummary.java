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
     * <p>内部字段沿用 B4 解析器已产出的行列计数；对外持久化时必须使用
     * {@link #name()}、{@link #cellRange()}、{@link #indexed()}、{@link #indexedRange()} 这一组
     * 与 OpenAPI {@code KnowledgeParseSheetSummary} 对齐的只读视图，不能直接序列化本记录
     * （否则会出现 API 不接受的 {@code sheetName/rowCount/skippedRange} 字段）。
     *
     * @param sheetName 工作表名，原样保存
     * @param rowCount 实际读取行数
     * @param columnCount 实际读取列数
     * @param empty 是否空表（空表跳过且不生成分块）
     * @param indexedRange 已索引范围，例如 {@code A1:F20000}
     * @param skippedRange 未索引范围；未截断时为空
     */
    public record SheetSummary(
            String sheetName, int rowCount, int columnCount, boolean empty, String indexedRange, String skippedRange) {

        /** 契约视图：工作表名（对应 OpenAPI {@code name}）。 */
        public String name() {
            return sheetName;
        }

        /** 契约视图：整表实际有值范围 = 已索引范围与未索引范围的并集；空表为空。 */
        public String cellRange() {
            return unionRange(indexedRange, skippedRange);
        }

        /** 契约视图：是否有任何内容进入索引；整表被上限跳过或空表为 false。 */
        public boolean indexed() {
            return indexedRange != null;
        }
    }

    /**
     * 合并两个矩形单元格范围，供 {@link SheetSummary#cellRange()} 还原整表范围。
     *
     * <p>解析器分别记录“已索引”和“未索引”两段，持久化契约只接受一个整表范围；这里按行列取
     * 最小/最大边界合并。范围格式固定为 {@code A1:F20}，解析失败时退回非空的一方，避免把
     * 不可信/异常字符串拼进摘要。
     *
     * @param first 第一个范围，可为空
     * @param second 第二个范围，可为空
     * @return 合并后的范围；两者都空时返回 {@code null}
     */
    public static String unionRange(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        CellAddress firstStart = CellAddress.parse(first, true);
        CellAddress firstEnd = CellAddress.parse(first, false);
        CellAddress secondStart = CellAddress.parse(second, true);
        CellAddress secondEnd = CellAddress.parse(second, false);
        if (firstStart == null || firstEnd == null || secondStart == null || secondEnd == null) {
            return first;
        }
        int minRow = Math.min(firstStart.row, secondStart.row);
        int minColumn = Math.min(firstStart.column, secondStart.column);
        int maxRow = Math.max(firstEnd.row, secondEnd.row);
        int maxColumn = Math.max(firstEnd.column, secondEnd.column);
        return CellAddress.format(minColumn, minRow) + ":" + CellAddress.format(maxColumn, maxRow);
    }

    /**
     * 单元格地址（列字母 + 行号）解析辅助。
     *
     * @param column 从 1 开始的列号
     * @param row 从 1 开始的行号
     */
    private record CellAddress(int column, int row) {

        /**
         * 解析范围内的一端。
         *
         * @param range 形如 {@code A1:F20}
         * @param start 是否取起点（否则取终点）
         * @return 地址；格式非法时为空
         */
        static CellAddress parse(String range, boolean start) {
            if (range == null) {
                return null;
            }
            String[] parts = range.split(":");
            if (parts.length != 2) {
                return null;
            }
            String token = start ? parts[0] : parts[1];
            int split = 0;
            while (split < token.length() && Character.isLetter(token.charAt(split))) {
                split++;
            }
            if (split == 0 || split == token.length()) {
                return null;
            }
            try {
                int column = 0;
                for (int index = 0; index < split; index++) {
                    char letter = Character.toUpperCase(token.charAt(index));
                    if (letter < 'A' || letter > 'Z') {
                        return null;
                    }
                    column = column * 26 + (letter - 'A' + 1);
                }
                int row = Integer.parseInt(token.substring(split));
                if (row < 1) {
                    return null;
                }
                return new CellAddress(column, row);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        /**
         * 把列号/行号格式化回单元格地址。
         *
         * @param column 从 1 开始的列号
         * @param row 从 1 开始的行号
         * @return 形如 {@code B2}
         */
        static String format(int column, int row) {
            StringBuilder letters = new StringBuilder();
            int remaining = column;
            while (remaining > 0) {
                int digit = (remaining - 1) % 26;
                letters.insert(0, (char) ('A' + digit));
                remaining = (remaining - 1) / 26;
            }
            return letters + Integer.toString(row);
        }
    }

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
