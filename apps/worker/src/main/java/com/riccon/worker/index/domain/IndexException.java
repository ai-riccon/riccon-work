package com.riccon.worker.index.domain;

import com.riccon.worker.run.domain.WorkerErrorCodes;

/**
 * 知识库索引失败。
 *
 * <p>OpenSearch 5xx/连接失败可重试；文档缺少来源或映射错误不可重试。错误消息不包含
 * 分块原文、索引名以外的租户信息或堆栈。
 */
public class IndexException extends RuntimeException {

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /**
     * 创建索引失败。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏摘要
     */
    public IndexException(String errorCode, boolean retryable, String message) {
        this(errorCode, retryable, message, null);
    }

    /**
     * 创建带原因的索引失败。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏摘要
     * @param cause 技术原因
     */
    public IndexException(String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * OpenSearch 临时不可用。
     *
     * @param message 脱敏摘要
     * @param cause 技术原因
     * @return 可重试异常
     */
    public static IndexException unavailable(String message, Throwable cause) {
        return new IndexException(WorkerErrorCodes.INDEX_UNAVAILABLE, true, message, cause);
    }

    /**
     * 索引请求被拒绝（映射/参数错误）。
     *
     * @param message 脱敏摘要
     * @return 不可重试异常
     */
    public static IndexException rejected(String message) {
        return new IndexException(WorkerErrorCodes.INDEX_REJECTED, false, message);
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
