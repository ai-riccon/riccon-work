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

    /**
     * 请求方法不被该路径支持；响应在 {@code Allow} 头列出受支持方法。
     *
     * <p>路径不存在仍是 {@link #REQUEST_NOT_FOUND}，已认证但无权限仍是 {@link #AUTH_FORBIDDEN}；
     * 该码不用于掩盖对象存在性。
     */
    public static final String RICCON_METHOD_405 = "RICCON-METHOD-405";

    /** 请求媒体类型不受支持。 */
    public static final String REQUEST_UNSUPPORTED_MEDIA_TYPE = "RICCON-REQUEST-415";

    /** 对象不存在或属于其他租户；不泄露存在性。 */
    public static final String TENANT_NOT_FOUND = "RICCON-TENANT-404";

    /** 状态冲突或唯一键冲突。 */
    public static final String CONFLICT = "RICCON-CONFLICT-409";

    /** 部门名重复、账号已在其他/本部门、重复空间成员或移除当前负责人（ADR-0018 第 7.3 节）。 */
    public static final String RICCON_DEPT_409 = "RICCON-DEPT-409";

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

    /** OIDC 绑定请求非法：issuer 与租户配置不一致、请求体缺字段或 subject 非法（M10，ADR-0019 第 3/7 节）。 */
    public static final String OIDC_INVALID_REQUEST = "RICCON-OIDC-400";

    /** OIDC 身份已被其他账号绑定、账号已绑定其他身份或唯一键冲突（M10，ADR-0019 第 3/7 节）。 */
    public static final String OIDC_BINDING_CONFLICT = "RICCON-OIDC-409";

    /** 审计查询时间范围、过滤器或分页非法，或预计导出行数超过上限（M10，ADR-0020 第 6.2 节）。 */
    public static final String AUDIT_INVALID_REQUEST = "RICCON-AUDIT-400";

    /** 有审计权限但业务拒绝：自批、非申请人下载、平台租户导出、平台审批多管理员租户（M10）。 */
    public static final String AUDIT_FORBIDDEN = "RICCON-AUDIT-403";

    /** 审计导出状态不允许该操作、并发审批冲突、下载时实际行数超限（M10）。 */
    public static final String AUDIT_CONFLICT = "RICCON-AUDIT-409";

    /** 审计导出已过期（M10）。 */
    public static final String AUDIT_EXPORT_EXPIRED = "RICCON-AUDIT-410";

    /** 读取审计时发现白名单外或敏感的 details 键，fail-closed（M10）。 */
    public static final String AUDIT_DETAILS_INVALID = "RICCON-AUDIT-500";

    /** 模型端点不允许、演示端点用于生产、只读字段被写、链为空、未知模型或字段非法（M04，ADR-0021 §7.3）。 */
    public static final String MODEL_INVALID_REQUEST = "RICCON-MODEL-400";

    /** 路由版本冲突、凭据重复、凭据状态冲突或环境变量覆盖生效时修改开关（M04，ADR-0021 §7.3）。 */
    public static final String MODEL_CONFLICT = "RICCON-MODEL-409";

    /** 首字后断流；已生成内容保留，客户端可重试但不能自动重放（M04，ADR-0021 §5.3）。 */
    public static final String MODEL_STREAM_INTERRUPTED = "RICCON-MODEL-502";

    /** 全链不可用；员工可见文案统一为“模型服务暂不可用，请稍后重试。”（M04，ADR-0021 §5.3）。 */
    public static final String MODEL_UNAVAILABLE = "RICCON-MODEL-503";

    /** 首字后空闲超时；已生成内容保留，员工可重试（M04，ADR-0021 §5.3）。 */
    public static final String MODEL_STREAM_TIMEOUT = "RICCON-MODEL-504";

    /** 配额请求非法：负数、缺键或窗口非法（M04，ADR-0022 §3.3）。 */
    public static final String QUOTA_INVALID_REQUEST = "RICCON-QUOTA-400";

    /** 并发修改同一配额（M04 用整体替换，暂不需要；保留稳定码避免未来复用语义）（ADR-0022 §3.3）。 */
    public static final String QUOTA_CONFLICT = "RICCON-QUOTA-409";

    /** 配额超限；使用专用 QuotaExceededProblem，可区分“模型不可用”（M04，ADR-0022 §1.3）。 */
    public static final String QUOTA_EXCEEDED = "RICCON-QUOTA-429";

    /** 配额存储不可用，fail-closed 拒绝新 run（M04，ADR-0022 §1.7）。 */
    public static final String QUOTA_UNAVAILABLE = "RICCON-QUOTA-503";

    /** 成本查询时间范围、维度或分页非法（M04，ADR-0022 §3.3）。 */
    public static final String COST_INVALID_REQUEST = "RICCON-COST-400";

    /** 成本查询存储超时或不可用；不返回部分结果（M04，ADR-0022 §2.5）。 */
    public static final String COST_UNAVAILABLE = "RICCON-COST-503";

    /** 未预期内部错误；响应不包含堆栈、SQL 或内部主机。 */
    public static final String INTERNAL = "RICCON-COMMON-500";

    /** 工具类禁止实例化。 */
    private ErrorCodes() {}
}
