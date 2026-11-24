package com.riccon.worker.injection;

/**
 * 注入样本进入模型上下文的表面（ADR-0023 §8 TM-55）。
 *
 * <p>M05-B10 覆盖 Excel 与 OCR 两类本地上传解析产物；M05b-B10b 在同一 harness 上补充连接器抓取内容
 * （txt/md/docx/pdf，验收别名 {@code SEC-CONN-002}）。连接器文本与上传文件一样是不可信数据，
 * 不因来源不同而获得指令权。
 */
enum InjectionSurface {
    /** 可见工作表单元格文本。 */
    XLSX_VISIBLE_CELL,
    /** 隐藏工作表；按 ADR-0023 §3.2 整表跳过，不应进入检索或模型上下文。 */
    XLSX_HIDDEN_SHEET,
    /** 公式单元格缓存值；解析器只读缓存值，不执行公式（ADR-0023 §3.2）。 */
    XLSX_FORMULA_CELL,
    /** OCR 识别出的普通文本行。 */
    OCR_LINE,
    /** OCR 文本伪造不可信数据边界标记，试图让后续指令逃出数据块。 */
    OCR_FORGED_BOUNDARY,
    /** 连接器 txt 对象；按 UTF-8 抽取段落，索引后仍为不可信数据。 */
    CONNECTOR_TXT,
    /** 连接器 md 对象；与 txt 共用纯文本抽取路径。 */
    CONNECTOR_MD,
    /** 连接器 docx 对象普通段落。 */
    CONNECTOR_DOCX,
    /** 连接器 docx 白字/小字号隐藏文本；解析器仍会抽取，格式不是信任边界。 */
    CONNECTOR_DOCX_WHITE_TEXT,
    /** 连接器 docx 表格单元格中的公式样式文本；只作为字面量文本，不执行公式。 */
    CONNECTOR_DOCX_TABLE,
    /** 连接器 pdf 文本层内容；不触发 OCR。 */
    CONNECTOR_PDF;

    /** 判断是否属于 M05b 连接器内容表面。 */
    boolean isConnector() {
        return switch (this) {
            case CONNECTOR_TXT,
                    CONNECTOR_MD,
                    CONNECTOR_DOCX,
                    CONNECTOR_DOCX_WHITE_TEXT,
                    CONNECTOR_DOCX_TABLE,
                    CONNECTOR_PDF -> true;
            default -> false;
        };
    }
}
