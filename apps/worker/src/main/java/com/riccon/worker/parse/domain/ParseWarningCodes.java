package com.riccon.worker.parse.domain;

/**
 * 解析警告码（ADR-0023 第 3.3/3.4/4.5 节）。
 *
 * <p>警告写入 {@code knowledge_document.parse_warnings}（白名单字符串数组），只描述结构/质量，
 * 不含单元格原文、OCR 文本或对象 key。带参数的警告统一用 {@code CODE:参数}，例如
 * {@code ROWS_TRUNCATED:Sheet1}、{@code FAILED_PAGES:[2,5]}、{@code HIDDEN_SHEETS_SKIPPED:3}。
 */
public final class ParseWarningCodes {

    /** 工作表数超过上限，只索引前 N 个。 */
    public static final String SHEETS_TRUNCATED = "SHEETS_TRUNCATED";

    /** 单表行数超过上限，截断；参数为工作表名。 */
    public static final String ROWS_TRUNCATED = "ROWS_TRUNCATED";

    /** 单表列数超过上限，截断；参数为工作表名。 */
    public static final String COLUMNS_TRUNCATED = "COLUMNS_TRUNCATED";

    /** 单文档字符数超过上限，截断。 */
    public static final String CHARS_TRUNCATED = "CHARS_TRUNCATED";

    /** 单文档段落数超过上限，截断。 */
    public static final String SEGMENTS_TRUNCATED = "SEGMENTS_TRUNCATED";

    /** 单文档分块数超过上限，截断。 */
    public static final String CHUNKS_TRUNCATED = "CHUNKS_TRUNCATED";

    /** 隐藏工作表被跳过；参数为数量。 */
    public static final String HIDDEN_SHEETS_SKIPPED = "HIDDEN_SHEETS_SKIPPED";

    /** 公式单元格没有缓存值，已跳过；禁止使用 FormulaEvaluator。 */
    public static final String FORMULA_NOT_CACHED = "FORMULA_NOT_CACHED";

    /** OCR 块置信度偏低但仍入索引。 */
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";

    /** 单文档 OCR 页数超过上限，超出页只保留文本层。 */
    public static final String OCR_PAGE_LIMIT = "OCR_PAGE_LIMIT";

    /** 部分页没有可抽取文本；参数为数量。 */
    public static final String PAGES_WITHOUT_TEXT = "PAGES_WITHOUT_TEXT";

    /** OCR 失败页集合；参数为页号列表。 */
    public static final String FAILED_PAGES = "FAILED_PAGES";

    /** 低置信页集合；参数为页号列表。 */
    public static final String LOW_CONFIDENCE_PAGES = "LOW_CONFIDENCE_PAGES";

    /** 工具类禁止实例化。 */
    private ParseWarningCodes() {}

    /**
     * 追加字符串参数。
     *
     * @param code 警告码
     * @param suffix 参数（工作表名、原因等），不得为空
     * @return 形如 {@code CODE:suffix} 的警告
     */
    public static String withSuffix(String code, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            throw new IllegalArgumentException("parse warning suffix must not be blank");
        }
        return code + ":" + suffix;
    }

    /**
     * 追加计数参数。
     *
     * @param code 警告码
     * @param count 非负计数
     * @return 形如 {@code CODE:3} 的警告
     */
    public static String withCount(String code, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("parse warning count must not be negative");
        }
        return code + ":" + count;
    }

    /**
     * 追加页号列表参数，使用无空格 JSON 风格，例如 {@code FAILED_PAGES:[2,5]}。
     *
     * @param code 警告码
     * @param pages 页号集合，必须从 1 开始且非空
     * @return 形如 {@code CODE:[2,5]} 的警告
     */
    public static String withPages(String code, java.util.List<Integer> pages) {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("parse warning pages must not be empty");
        }
        for (Integer page : pages) {
            if (page == null || page < 1) {
                throw new IllegalArgumentException("parse warning page numbers must start from 1");
            }
        }
        return code + ":"
                + pages.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
