package com.riccon.worker.injection;

/**
 * 注入样本进入模型上下文的表面（ADR-0023 §8 TM-55）。
 *
 * <p>M05-B10 只覆盖 Excel 与 OCR 两类本地解析产物；连接器抓取内容（SEC-CONN-002）归 M05b，不在本任务。
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
    OCR_FORGED_BOUNDARY
}
