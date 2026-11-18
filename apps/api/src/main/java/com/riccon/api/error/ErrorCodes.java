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

    /** 文件上传内容不合法（空文件、缺少文件名等）。 */
    public static final String FILE_INVALID = "RICCON-FILE-400";

    /** 单会话文件数量超过 5 个。 */
    public static final String FILE_LIMIT_EXCEEDED = "RICCON-FILE-409";

    /** 单文件超过 200 MB。 */
    public static final String FILE_TOO_LARGE = "RICCON-FILE-413";

    /** 产物正文超过 2 MiB 的同源代理上限。 */
    public static final String ARTIFACT_CONTENT_TOO_LARGE = "RICCON-ARTIFACT-413";

    /** 产物正文读取失败；不暴露对象存储地址、key 或凭据。 */
    public static final String ARTIFACT_CONTENT_UNAVAILABLE = "RICCON-ARTIFACT-502";

    /** 文件扩展名、Content-Type 或 magic bytes 不受支持/不一致。 */
    public static final String FILE_UNSUPPORTED = "RICCON-FILE-415";

    /** 触发登录限流。 */
    public static final String RATE_LIMITED = "RICCON-RATE-429";

    /** Worker 对话流并发已满；API 快速失败而不是排队。 */
    public static final String CHAT_QUEUE_FULL = "RICCON-QUEUE-429";

    /** Worker 内部对话流不可用或返回非法结果。 */
    public static final String CHAT_UPSTREAM_FAILED = "RICCON-CHAT-500";

    /** Worker SSE 事件或流终止方式不符合契约。 */
    public static final String CHAT_STREAM_INVALID = "RICCON-CHAT-502";

    /** 对话流空闲或总时长超过上限。 */
    public static final String CHAT_STREAM_TIMEOUT = "RICCON-CHAT-504";

    /** 未预期内部错误；响应不包含堆栈、SQL 或内部主机。 */
    public static final String INTERNAL = "RICCON-COMMON-500";

    /** 工具类禁止实例化。 */
    private ErrorCodes() {}
}
