package com.riccon.worker.run.domain;

/**
 * 事件处理失败。
 *
 * <p>可重试错误由 outbox 退避后再次投递；不可重试错误直接进入 DEAD。错误码必须是稳定值，
 * 消息只包含脱敏摘要，不包含原文、路径、凭据或堆栈。
 */
public class EventProcessingException extends RuntimeException {

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /**
     * 创建事件处理失败。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏摘要
     */
    public EventProcessingException(String errorCode, boolean retryable, String message) {
        this(errorCode, retryable, message, null);
    }

    /**
     * 创建带原因的事件处理失败。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏摘要
     * @param cause 技术原因，仅用于日志
     */
    public EventProcessingException(String errorCode, boolean retryable, String message, Throwable cause) {
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
