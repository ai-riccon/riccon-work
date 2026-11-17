package com.riccon.platform.domain.chat;

/**
 * 引用定位方式。
 *
 * <p>PDF 用页码，Excel 用工作表名（可附单元格范围），Word 用段落序号；图片 M02 不做 OCR，
 * 只引用文件名与整图说明，不生成页码。
 */
public enum LocatorType {
    /** PDF/图片页码，1 开始。 */
    PAGE,
    /** Excel 工作表。 */
    SHEET,
    /** Word 段落序号，1 开始。 */
    PARAGRAPH
}
