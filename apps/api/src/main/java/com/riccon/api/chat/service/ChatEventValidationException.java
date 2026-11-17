package com.riccon.api.chat.service;

/**
 * Worker SSE 事件不符合契约时抛出。
 *
 * <p>异常消息只用于服务端日志；对浏览器统一映射为脱敏的 failed 事件或 ProblemDetail，不转发原始载荷。
 */
public class ChatEventValidationException extends RuntimeException {

    /**
     * 创建校验异常。
     *
     * @param message 服务端可读原因，禁止包含提示词或原文
     */
    public ChatEventValidationException(String message) {
        super(message);
    }
}
