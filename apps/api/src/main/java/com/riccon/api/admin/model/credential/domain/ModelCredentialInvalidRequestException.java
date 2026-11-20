package com.riccon.api.admin.model.credential.domain;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 模型凭据请求不合法（字段级 400）。
 *
 * <p>携带 {@code field}/{@code reason}，由控制器局部异常处理映射为契约允许的
 * {@code ProblemDetail} 字段；消息固定且不含 apiKey、密文或 provider 地址。
 */
public class ModelCredentialInvalidRequestException extends RicconException {

    /** 出错字段。 */
    private final String field;

    /** 稳定原因码。 */
    private final String reason;

    /**
     * 创建字段级 400。
     *
     * @param field 出错字段
     * @param reason 稳定原因码
     * @param detail 脱敏说明
     */
    public ModelCredentialInvalidRequestException(String field, String reason, String detail) {
        super(HttpStatus.BAD_REQUEST, ErrorCodes.MODEL_INVALID_REQUEST, "Invalid request", detail);
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
