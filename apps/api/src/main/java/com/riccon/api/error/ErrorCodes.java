package com.riccon.api.error;

/**
 * 对外稳定错误码。
 *
 * <p>只使用合同中已定义的错误码；未在 OpenAPI 中列出的类别沿用同一 {@code RICCON-<DOMAIN>-<NNN>} 格式，
 * 并通过 {@code ProblemDetail.code} 暴露。错误码一旦发布不得复用或改变语义。
 */
public final class ErrorCodes {

    /** 登录凭据错误；不区分租户不存在、账号不存在、密码错误或锁定。 */
    public static final String AUTH_INVALID_CREDENTIALS = "RICCON-AUTH-001";

    /** 未认证或会话失效。 */
    public static final String AUTH_UNAUTHENTICATED = "RICCON-AUTH-401";

    /** 已认证但无权限，或 CSRF/同源校验失败。 */
    public static final String AUTH_FORBIDDEN = "RICCON-AUTH-403";

    /** 请求参数或请求体不合法。 */
    public static final String REQUEST_INVALID = "RICCON-REQUEST-400";

    /** 请求路径不存在。 */
    public static final String REQUEST_NOT_FOUND = "RICCON-REQUEST-404";

    /** 请求媒体类型不受支持。 */
    public static final String REQUEST_UNSUPPORTED_MEDIA_TYPE = "RICCON-REQUEST-415";

    /** 对象不存在或属于其他租户；不泄露存在性。 */
    public static final String TENANT_NOT_FOUND = "RICCON-TENANT-404";

    /** 状态冲突或唯一键冲突。 */
    public static final String CONFLICT = "RICCON-CONFLICT-409";

    /** 触发登录限流。 */
    public static final String RATE_LIMITED = "RICCON-RATE-429";

    /** 未预期内部错误；响应不包含堆栈、SQL 或内部主机。 */
    public static final String INTERNAL = "RICCON-COMMON-500";

    /** 工具类禁止实例化。 */
    private ErrorCodes() {}
}
