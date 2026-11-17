package com.riccon.api.web;

import com.riccon.api.error.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Origin / Referer 同源校验（CSRF 纵深）。
 *
 * <p>ADR-0013 第 2 节要求除 CSRF 令牌外同时校验同源。浏览器写请求通常带 {@code Origin}；旧客户端可能只有
 * {@code Referer}。两者都缺失时放行，因为非浏览器客户端仍需提供 CSRF 令牌；一旦存在且与当前请求不同源，
 * 直接返回 403，避免 SameSite 兼容性差异扩大攻击面。
 */
@Component
public class OriginCheckFilter extends OncePerRequestFilter {

    /** 需要校验的写方法；与 CSRF 保护范围一致。 */
    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** 错误响应写入器。 */
    private final ProblemWriter problemWriter;

    /**
     * 创建同源校验过滤器。
     *
     * @param problemWriter 错误响应写入器
     */
    public OriginCheckFilter(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    /**
     * 对写请求校验 Origin/Referer。
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
        if (!UNSAFE_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String source = request.getHeader("Origin");
        if (source == null || source.isBlank()) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!isSameOrigin(request, source)) {
            problemWriter.write(
                    response,
                    HttpStatus.FORBIDDEN,
                    ErrorCodes.AUTH_FORBIDDEN,
                    "Forbidden",
                    "origin is not allowed",
                    RequestContext.traceId(request));
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 比较请求源与当前请求的 scheme/host/port。
     *
     * @param request 当前请求
     * @param source Origin 或 Referer 值
     * @return 同源时返回 {@code true}
     */
    private boolean isSameOrigin(HttpServletRequest request, String source) {
        if ("null".equalsIgnoreCase(source)) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(source);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        if (!uri.getScheme().equalsIgnoreCase(request.getScheme())) {
            return false;
        }
        if (!uri.getHost().equalsIgnoreCase(request.getServerName())) {
            return false;
        }
        int sourcePort = uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
        return sourcePort == request.getServerPort();
    }

    /**
     * 返回 scheme 的默认端口。
     *
     * @param scheme URL scheme
     * @return 默认端口
     */
    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
