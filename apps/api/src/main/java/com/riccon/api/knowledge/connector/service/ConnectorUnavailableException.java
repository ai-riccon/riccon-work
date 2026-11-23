package com.riccon.api.knowledge.connector.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 连接器功能未启用或依赖服务不可用，HTTP 503 {@code RICCON-KB-503}。
 */
public class ConnectorUnavailableException extends RicconException {

    /**
     * 创建 503 异常。
     *
     * @param detail 脱敏说明
     */
    public ConnectorUnavailableException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "RICCON-KB-503", "Knowledge connector unavailable", detail);
    }
}
