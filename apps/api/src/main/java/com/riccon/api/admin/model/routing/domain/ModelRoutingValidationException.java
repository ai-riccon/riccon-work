package com.riccon.api.admin.model.routing.domain;

/**
 * 路由保存的字段级校验失败。
 *
 * <p>控制器捕获后转换为 {@code RICCON-MODEL-400} + {@code field}/{@code reason}，避免落入通用 400；
 * 消息只描述规则，不包含 baseUrl、供应商地址或密钥（ADR-0021 §7.3）。
 */
public class ModelRoutingValidationException extends RuntimeException {

    /** 出错字段。 */
    private final String field;

    /** 稳定原因码。 */
    private final String reason;

    /**
     * 创建校验失败。
     *
     * @param field 出错字段
     * @param reason 稳定原因码
     * @param detail 脱敏说明
     */
    public ModelRoutingValidationException(String field, String reason, String detail) {
        super(detail);
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
