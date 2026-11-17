package com.riccon.worker.mcp.domain;

/**
 * MCP 工具错误。
 *
 * <p>错误码固定为 {@code RICCON-MCP-xxx}；消息不包含工具入参、结果原文、端点或凭据。
 */
public class McpException extends RuntimeException {

    /** 租户未授权、工具不可用或未审批。 */
    public static final String DENIED = "RICCON-MCP-403";

    /** 端点不可达、初始化失败或调用失败。 */
    public static final String UNAVAILABLE = "RICCON-MCP-503";

    /** 工具返回格式不符合 MCP 协议或超出预算。 */
    public static final String INVALID = "RICCON-MCP-422";

    /** 稳定错误码。 */
    private final String errorCode;

    /** 是否可重试。 */
    private final boolean retryable;

    /**
     * 创建 MCP 错误。
     *
     * @param errorCode 错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     */
    public McpException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 创建带技术原因的 MCP 错误；原因只用于服务端日志。
     *
     * @param errorCode 错误码
     * @param retryable 是否可重试
     * @param message 脱敏说明
     * @param cause 技术原因
     */
    public McpException(String errorCode, boolean retryable, String message, Throwable cause) {
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
