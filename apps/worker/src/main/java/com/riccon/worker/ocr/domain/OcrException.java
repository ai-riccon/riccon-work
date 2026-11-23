package com.riccon.worker.ocr.domain;

/**
 * OCR 失败。
 *
 * <p>错误码复用 {@code ParseErrorCodes} 的 {@code KB_*} 稳定码；消息必须是可读中文摘要，
 * 不得包含原文、对象 key、Tessdata 路径或引擎堆栈。
 */
public class OcrException extends RuntimeException {

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /**
     * 创建 OCR 失败。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 可读脱敏摘要
     */
    public OcrException(String errorCode, boolean retryable, String message) {
        this(errorCode, retryable, message, null);
    }

    /**
     * 创建带技术原因的 OCR 失败。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 可读脱敏摘要
     * @param cause 技术原因（只进日志，不返回客户端）
     */
    public OcrException(String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /** 返回稳定错误码。 */
    public String errorCode() {
        return errorCode;
    }

    /** 返回是否可重试。 */
    public boolean retryable() {
        return retryable;
    }
}
