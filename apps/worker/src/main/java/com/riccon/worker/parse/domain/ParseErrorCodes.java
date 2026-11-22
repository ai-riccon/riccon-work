package com.riccon.worker.parse.domain;

/**
 * 解析失败稳定错误码（ADR-0023 第 3.4/4.5 节）。
 *
 * <p>写入 {@code knowledge_document.error_code} 或 {@code file_parse.error_code}；错误消息必须脱敏，
 * 不得包含原文、对象 key 或引擎堆栈。与 {@link ParseWarningCodes} 不同，这些码表示文档失败态。
 */
public final class ParseErrorCodes {

    /** 加密/受密码保护的 Office/PDF。 */
    public static final String PARSE_ENCRYPTED = "KB_PARSE_ENCRYPTED";

    /** 损坏或非法 OOXML/PDF。 */
    public static final String PARSE_CORRUPT = "KB_PARSE_CORRUPT";

    /** ZIP/OOXML 资源超限（ZIP 炸弹、超大 entry）。 */
    public static final String PARSE_RESOURCE_LIMIT = "KB_PARSE_RESOURCE_LIMIT";

    /** 文档级解析超时。 */
    public static final String PARSE_TIMEOUT = "KB_PARSE_TIMEOUT";

    /** 完全无文本可索引。 */
    public static final String NO_TEXT = "KB_NO_TEXT";

    /** OCR 开关关闭时收到图片/扫描件。 */
    public static final String OCR_DISABLED = "KB_OCR_DISABLED";

    /** OCR 语言包缺失。 */
    public static final String OCR_LANG_UNAVAILABLE = "KB_OCR_LANG_UNAVAILABLE";

    /** 单页 OCR 超时（继续下一页，通常只是警告级失败页）。 */
    public static final String OCR_TIMEOUT = "KB_OCR_TIMEOUT";

    /** OCR 引擎失败（native 崩溃/内存不足）。 */
    public static final String OCR_FAILED = "KB_OCR_FAILED";

    /** 扫描件 PDF 没有文本层且 OCR 不可用。 */
    public static final String OCR_UNAVAILABLE = "KB_OCR_UNAVAILABLE";

    /** 工具类禁止实例化。 */
    private ParseErrorCodes() {}
}
