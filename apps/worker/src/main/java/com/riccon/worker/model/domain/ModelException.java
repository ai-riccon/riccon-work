package com.riccon.worker.model.domain;

/**
 * 模型调用错误。
 *
 * <p>错误码映射为 {@code RICCON-MODEL-xxx}，消息只包含脱敏说明；禁止把上游响应正文、
 * 请求头或凭据放入异常。{@code retryable} 描述该错误对客户端是否可重试；
 * {@code failureKind} 描述降级状态机需要的物理分类（同 key 重试、key 失效等）。
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

    /** 上游不可用或全链不可用。 */
    public static final String UNAVAILABLE = "RICCON-MODEL-503";

    /** 超时。 */
    public static final String TIMEOUT = "RICCON-MODEL-504";

    /** 配置或凭据不可用。 */
    public static final String CONFIGURATION = "RICCON-MODEL-500";

    /** 链空失败审计的稳定原因码（ADR-0021 §4.1 第 5 步）。 */
    public static final String REASON_CHAIN_EMPTY = "CHAIN_EMPTY";

    /** 员工可见的全链失败统一文案（ADR-0021 §5.3）。 */
    public static final String CHAIN_UNAVAILABLE_MESSAGE = "模型服务暂不可用，请稍后重试。";

    /** 首字后中断统一文案；已生成内容保留，可重试（ADR-0021 §5.3）。 */
    public static final String STREAM_INTERRUPTED_MESSAGE = "回答已中断，已保留已生成内容，可重试。";

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /** 物理失败分类；旧构造器默认未分类。 */
    private final ModelFailureKind failureKind;

    /**
     * 创建未分类的模型错误。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     */
    public ModelException(String errorCode, boolean retryable, String message) {
        this(errorCode, retryable, message, ModelFailureKind.UNKNOWN, null);
    }

    /**
     * 创建带技术原因的未分类模型错误；原因只进入受控日志，不进入错误事件。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     * @param cause 技术原因
     */
    public ModelException(String errorCode, boolean retryable, String message, Throwable cause) {
        this(errorCode, retryable, message, ModelFailureKind.UNKNOWN, cause);
    }

    /**
     * 创建带物理分类的模型错误。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     * @param failureKind 物理失败分类
     */
    public ModelException(String errorCode, boolean retryable, String message, ModelFailureKind failureKind) {
        this(errorCode, retryable, message, failureKind, null);
    }

    /**
     * 创建带物理分类与技术原因的模型错误。
     *
     * @param errorCode 稳定错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     * @param failureKind 物理失败分类
     * @param cause 技术原因
     */
    public ModelException(
            String errorCode, boolean retryable, String message, ModelFailureKind failureKind, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.failureKind = failureKind == null ? ModelFailureKind.UNKNOWN : failureKind;
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

    /**
     * 返回物理失败分类。
     *
     * @return 分类
     */
    public ModelFailureKind failureKind() {
        return failureKind;
    }
}
