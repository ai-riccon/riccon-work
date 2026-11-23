package com.riccon.api.knowledge.connector.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 连接器冲突（名称重复、并发同步等），HTTP 409 {@code RICCON-KB-409}。
 */
public class ConnectorConflictException extends RicconException {

    /**
     * 创建冲突异常。
     *
     * @param detail 脱敏说明
     */
    public ConnectorConflictException(String detail) {
        super(HttpStatus.CONFLICT, "RICCON-KB-409", "Knowledge connector conflict", detail);
    }
}
