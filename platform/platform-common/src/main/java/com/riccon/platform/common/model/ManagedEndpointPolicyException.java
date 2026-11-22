package com.riccon.platform.common.model;

/**
 * 受管端点配置或 URL 校验失败。
 *
 * <p>{@link #reason()} 是稳定原因码，管理接口映射为 400 的 {@code reason} 字段；
 * {@link #field()} 指向具体配置项，便于管理员定位。异常消息只在服务端日志使用（ADR-0021 §2.2/§2.4）。
 */
public final class ManagedEndpointPolicyException extends IllegalArgumentException {

    /** 稳定拒绝原因。 */
    private final EndpointRejectionReason reason;

    /** 出错字段名。 */
    private final String field;

    /**
     * 创建受管端点校验异常。
     *
     * @param reason 稳定原因
     * @param field 出错字段
     * @param message 服务端可读说明
     */
    public ManagedEndpointPolicyException(EndpointRejectionReason reason, String field, String message) {
        super(message);
        this.reason = reason;
        this.field = field;
    }

    /**
     * 返回稳定原因。
     *
     * @return 原因码
     */
    public EndpointRejectionReason reason() {
        return reason;
    }

    /**
     * 返回出错字段。
     *
     * @return 字段名
     */
    public String field() {
        return field;
    }
}
