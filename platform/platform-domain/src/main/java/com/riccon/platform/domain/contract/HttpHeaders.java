package com.riccon.platform.domain.contract;

/**
 * 内部与公开 HTTP 头常量。
 *
 * <p>禁止把租户身份放在客户端可控的请求头里；这里只列 trace、幂等、CSRF 与服务身份相关头。
 */
public interface HttpHeaders {

    /** 贯穿 API、Worker、日志与审计的追踪 ID。 */
    String TRACE_ID = "X-Trace-Id";

    /** 客户端请求 ID，用于错误响应对账。 */
    String REQUEST_ID = "X-Request-Id";

    /** 写操作幂等键；同一主体、同一资源的重复请求返回既有结果。 */
    String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** CSRF 头名；令牌通过 {@code GET /api/v1/auth/csrf} 获取。 */
    String CSRF_TOKEN = "X-CSRF-TOKEN";

    /** API → Worker 的 HMAC 服务身份令牌。 */
    String SERVICE_TOKEN = "X-Riccon-Service-Token";

    /** 会话 Cookie 名。 */
    String SESSION_COOKIE = "RICCON_SESSION";
}
