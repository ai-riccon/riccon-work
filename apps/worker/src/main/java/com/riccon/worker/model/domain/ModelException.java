package com.riccon.worker.model.domain;

/**
 * 模型调用错误。
 *
 * <p>错误码映射为 {@code RICCON-MODEL-xxx}，消息只包含脱敏说明；禁止把上游响应正文、
 * 请求头或凭据放入异常。{@code retryable} 只描述当前失败，是否重试由调用方按尝试次数决定。
 */
public class ModelException extends RuntimeException {

    /** 上游认证失败。 */
    public static final String UNAUTHORIZED = "RICCON-MODEL-401";

    /** 上游拒绝访问。 */
    public static final String FORBIDDEN = "RICCON-MODEL-403";

    /** 上游限流。 */
    public static final String RATE_LIMITED = "RICCON-MODEL-429";

    /** 请求或响应不符合 OpenAI 兼容协议。 */
    public static final String INVALID_RESPONSE = "RICCON-MODEL-502";

    /** 上游不可用或连接失败。 */
    public static final String UNAVAILABLE = "RICCON-MODEL-503";

    /** 超时。 */
    public static final String TIMEOUT = "RICCON-MODEL-504";

    /** 配置或凭据不可用。 */
    public static final String CONFIGURATION = "RICCON-MODEL-500";

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /**
     * 创建模型错误。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     */
    public ModelException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 创建带技术原因的模型错误；原因只进入受控日志，不进入错误事件。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     * @param cause 技术原因
     */
    public ModelException(String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 返回稳定错误码。
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
