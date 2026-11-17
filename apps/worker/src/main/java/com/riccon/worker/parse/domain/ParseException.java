package com.riccon.worker.parse.domain;

import com.riccon.worker.run.domain.WorkerErrorCodes;

/**
 * 文件解析失败。
 *
 * <p>不可重试错误（格式损坏、无文本、跨租户）直接终止；对象存储/解析器临时故障标记可重试，
 * 由 outbox 退避后再次投递。消息不得包含原文、对象 key 或堆栈。
 */
public class ParseException extends RuntimeException {

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /**
     * 创建解析失败。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏摘要
     */
    public ParseException(String errorCode, boolean retryable, String message) {
        this(errorCode, retryable, message, null);
    }

    /**
     * 创建带原因的解析失败。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏摘要
     * @param cause 技术原因
     */
    public ParseException(String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 文件不存在或不属于当前租户。
     *
     * @return 不可重试异常
     */
    public static ParseException notFound() {
        return new ParseException(WorkerErrorCodes.TENANT_NOT_FOUND, false, "file not found");
    }

    /**
     * 文件内容不可解析。
     *
     * @param message 脱敏摘要
     * @param cause 技术原因
     * @return 不可重试异常
     */
    public static ParseException unprocessable(String message, Throwable cause) {
        return new ParseException(WorkerErrorCodes.FILE_UNPROCESSABLE, false, message, cause);
    }

    /**
     * 对象存储临时不可用。
     *
     * @param message 脱敏摘要
     * @param cause 技术原因
     * @return 可重试异常
     */
    public static ParseException storageUnavailable(String message, Throwable cause) {
        return new ParseException(WorkerErrorCodes.STORAGE_UNAVAILABLE, true, message, cause);
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
