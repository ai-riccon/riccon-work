package com.riccon.api.admin.model.gateway.domain;

/**
 * 网关开关请求的字段级校验失败。
 *
 * <p>与路由保存保持一致：控制器把它转换为 400 {@code RICCON-MODEL-400}，并返回稳定的
 * {@code field}/{@code reason}，避免把字段错误降级为通用反序列化错误（ADR-0021 §7.3）。
 */
public class ModelGatewaySwitchValidationException extends RuntimeException {

    /** 出错字段。 */
    private final String field;

    /** 稳定原因码。 */
    private final String reason;

    /**
     * 创建字段级校验失败。
     *
     * @param field 出错字段
     * @param reason 稳定原因码
     * @param message 脱敏说明
     */
    public ModelGatewaySwitchValidationException(String field, String reason, String message) {
        super(message);
        this.field = field;
        this.reason = reason;
    }

    /**
     * 返回出错字段。
     *
     * @return 字段名
     */
    public String field() {
        return field;
    }

    /**
     * 返回稳定原因码。
     *
     * @return 原因码
     */
    public String reason() {
        return reason;
    }
}
