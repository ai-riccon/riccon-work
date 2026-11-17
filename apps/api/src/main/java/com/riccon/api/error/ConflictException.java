package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/** 状态或唯一键冲突。 */
public class ConflictException extends RicconException {

    /**
     * 创建冲突异常。
     *
     * @param detail 服务端可读原因，禁止包含完整 PII
     */
    public ConflictException(String detail) {
        super(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, "Conflict", detail);
    }
}
