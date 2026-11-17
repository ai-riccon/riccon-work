package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 对象不存在或属于其他租户。
 *
 * <p>跨租户访问必须与普通 404 使用相同状态码、错误码与响应体结构，不泄露对象是否存在（ADR-0009 第 5 节）。
 */
public class NotFoundException extends RicconException {

    /**
     * 创建统一 404 异常。
     *
     * @param resource 资源类型，只用于服务端日志，不返回给客户端
     */
    public NotFoundException(String resource) {
        super(HttpStatus.NOT_FOUND, ErrorCodes.TENANT_NOT_FOUND, "Not found", resource + " not found");
    }
}
