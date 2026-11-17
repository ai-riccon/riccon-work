package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/** 请求参数或请求体不合法。 */
public class BadRequestException extends RicconException {

    /**
     * 创建参数错误异常。
     *
     * @param detail 服务端可读原因
     */
    public BadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, ErrorCodes.REQUEST_INVALID, "Invalid request", detail);
    }
}
