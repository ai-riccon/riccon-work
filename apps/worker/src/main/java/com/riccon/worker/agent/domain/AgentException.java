package com.riccon.worker.agent.domain;

/**
 * Agent 运行时错误。
 *
 * <p>错误码沿用 {@code RICCON-<DOMAIN>-<NNN>}；消息不含原文、提示词或凭据。
 */
public class AgentException extends RuntimeException {

    /** 租户/对象不存在或不属于当前租户。 */
    public static final String NOT_FOUND = "RICCON-TENANT-404";

    /** 请求或事件参数不合法。 */
    public static final String INVALID = "RICCON-WORKER-422";

    /** 审批未通过或已失效。 */
    public static final String APPROVAL_CONFLICT = "RICCON-APPROVAL-409";

    /** 模型或工具运行失败。 */
    public static final String RUNTIME = "RICCON-WORKER-500";

    /** 审批参数加密/解密失败；失败关闭，禁止降级为明文。 */
    public static final String CRYPTO = "RICCON-CRYPTO-500";

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /**
     * 创建 Agent 错误。
     *
     * @param errorCode 错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     */
    public AgentException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 创建带技术原因的错误。
     *
     * @param errorCode 错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     * @param cause 技术原因
     */
    public AgentException(String errorCode, boolean retryable, String message, Throwable cause) {
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
