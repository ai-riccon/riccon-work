package com.riccon.api.web;

import com.riccon.platform.domain.contract.HttpHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求生成或透传 traceId。
 *
 * <p>外部传入的 {@code X-Trace-Id}/{@code X-Request-Id} 只接受长度与字符集受限的值，避免日志注入；
 * 非法值一律替换为新 UUID。响应头回写 traceId，错误响应与审计使用同一值。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 允许的追踪 ID 字符集与长度。 */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    /**
     * 写入请求属性、MDC 与响应头。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 过滤器失败
     * @throws IOException 读写失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = safeOrNew(request.getHeader(HttpHeaders.TRACE_ID));
        String requestId = safeOrNull(request.getHeader(HttpHeaders.REQUEST_ID));
        request.setAttribute(RequestContext.TRACE_ID_ATTRIBUTE, traceId);
        if (requestId != null) {
            request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, requestId);
            MDC.put("requestId", requestId);
        }
        MDC.put("traceId", traceId);
        response.setHeader(HttpHeaders.TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("requestId");
        }
    }

    /**
     * 校验外部追踪 ID；非法时生成新值。
     *
     * @param value 请求头值
     * @return 安全追踪 ID
     */
    private String safeOrNew(String value) {
        if (value != null && SAFE_ID.matcher(value).matches()) {
            return value;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 校验可选请求 ID；非法时忽略。
     *
     * @param value 请求头值
     * @return 安全请求 ID 或 {@code null}
     */
    private String safeOrNull(String value) {
        if (value != null && SAFE_ID.matcher(value).matches()) {
            return value;
        }
        return null;
    }
}
