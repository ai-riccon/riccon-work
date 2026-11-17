package com.riccon.worker.chat.domain;

import java.util.Objects;

/**
 * 内部对话流错误。
 *
 * <p>携带 HTTP 状态与稳定错误码；错误响应不包含堆栈、提示词、凭据或内部地址。
 */
public class ChatException extends RuntimeException {

    /** 错误码。 */
    private final int status;

    /** 稳定错误码。 */
    private final String code;

    /** 错误标题。 */
    private final String title;

    /**
     * 创建对话流错误。
     *
     * @param status HTTP 状态
     * @param code 稳定错误码
     * @param title 错误标题
     * @param message 脱敏说明
     */
    public ChatException(int status, String code, String title, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
        this.title = Objects.requireNonNull(title, "title");
    }

    /**
     * 返回 HTTP 状态。
     *
     * @return 状态码
     */
    public int status() {
        return status;
    }

    /**
     * 返回错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }

    /**
     * 返回标题。
     *
     * @return 标题
     */
    public String title() {
        return title;
    }
}
