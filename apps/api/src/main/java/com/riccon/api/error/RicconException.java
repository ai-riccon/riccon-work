package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 可安全映射为 HTTP 响应的业务失败。
 *
 * <p>业务代码用异常表达失败，禁止返回 {@code null} 或布尔值表示多种原因。异常消息只用于服务端日志与
 * 内部排障，对外响应只暴露稳定的 {@link ErrorCodes}、标题与脱敏说明。
 */
public class RicconException extends RuntimeException {

    /** 对外错误码。 */
    private final String code;

    /** 对外 HTTP 状态。 */
    private final HttpStatus status;

    /** 对外标题。 */
    private final String title;

    /**
     * 创建业务异常。
     *
     * @param status HTTP 状态
     * @param code 稳定错误码
     * @param title 对外标题
     * @param detail 服务端可读的失败原因，禁止包含密钥或完整 PII
     */
    public RicconException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
    }

    /**
     * 返回对外错误码。
     *
     * @return 稳定错误码
     */
    public String code() {
        return code;
    }

    /**
     * 返回对外 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 返回对外标题。
     *
     * @return 标题
     */
    public String title() {
        return title;
    }
}
