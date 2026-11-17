package com.riccon.worker.research.domain;

/**
 * 产物写入错误。
 *
 * <p>错误码固定为 {@code RICCON-ARTIFACT-xxx}；消息不得包含正文、对象 key 或凭据。
 */
public class ArtifactException extends RuntimeException {

    /** 对象存储暂时不可用，可重试。 */
    public static final String STORAGE_UNAVAILABLE = "RICCON-ARTIFACT-503";

    /** 产物元数据写入冲突或唯一键竞争，可重试。 */
    public static final String CONFLICT = "RICCON-ARTIFACT-409";

    /** 产物正文为空或元数据非法，不可重试。 */
    public static final String INVALID = "RICCON-ARTIFACT-422";

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /**
     * 创建产物错误。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     */
    public ArtifactException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 创建带技术原因的产物错误。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     * @param cause 技术原因
     */
    public ArtifactException(String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 返回错误码。
     *
     * @return 错误码
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 返回是否可重试。
     *
     * @return 是否可重试
     */
    public boolean retryable() {
        return retryable;
    }
}
