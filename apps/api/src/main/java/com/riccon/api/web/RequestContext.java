package com.riccon.api.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求级上下文属性。
 *
 * <p>traceId 与 requestId 由 {@link TraceIdFilter} 在链路最前端写入请求属性，错误响应、日志与审计统一读取，
 * 避免各层重复生成导致对不上账。
 */
public final class RequestContext {

    /** traceId 请求属性名。 */
    public static final String TRACE_ID_ATTRIBUTE = "riccon.traceId";

    /** requestId 请求属性名。 */
    public static final String REQUEST_ID_ATTRIBUTE = "riccon.requestId";

    /** 工具类禁止实例化。 */
    private RequestContext() {}

    /**
     * 读取当前请求 traceId。
     *
     * @param request HTTP 请求
     * @return traceId；过滤器未执行时返回 {@code unknown}
     */
    public static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TRACE_ID_ATTRIBUTE);
        return value instanceof String text && !text.isBlank() ? text : "unknown";
    }

    /**
     * 读取当前请求 requestId。
     *
     * @param request HTTP 请求
     * @return requestId；未提供时返回 {@code null}
     */
    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
