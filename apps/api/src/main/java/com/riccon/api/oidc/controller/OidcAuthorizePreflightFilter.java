package com.riccon.api.oidc.controller;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.oidc.domain.OidcDiscoveryException;
import com.riccon.api.oidc.domain.OidcIdpConfig;
import com.riccon.api.oidc.service.OidcIdpRegistry;
import com.riccon.api.web.ProblemWriter;
import com.riccon.api.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 授权入口预检。
 *
 * <p>在 {@code OAuth2AuthorizationRequestRedirectFilter} 之前完成两件事（ADR-0019 第 2/4 节）：
 *
 * <ul>
 *   <li>未配置/已禁用/非法注册 ID 返回 404，不进入 Spring Security 的授权解析（其默认行为是抛异常并返回 500）；
 *   <li>发现文档失败时直接 302 到 {@code /login?oidcError=idp_unavailable|issuer_mismatch} 并写失败审计，
 *       不把 IdP 地址或原始错误暴露给浏览器。发现结果会进入缓存，随后标准解析器直接复用。
 * </ul>
 */
@Component
public class OidcAuthorizePreflightFilter extends OncePerRequestFilter {

    /** 授权入口前缀。 */
    static final String PATH_PREFIX = "/api/v1/auth/oidc/authorize/";

    /** IdP 注册表。 */
    private final OidcIdpRegistry registry;

    /** 失败响应。 */
    private final OidcLoginFailureHandler failureHandler;

    /** 错误响应写入器。 */
    private final ProblemWriter problemWriter;

    /**
     * 创建预检过滤器。
     *
     * @param registry IdP 注册表
     * @param failureHandler 失败响应
     * @param problemWriter 错误响应写入器
     */
    public OidcAuthorizePreflightFilter(
            OidcIdpRegistry registry, OidcLoginFailureHandler failureHandler, ProblemWriter problemWriter) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler must not be null");
        this.problemWriter = Objects.requireNonNull(problemWriter, "problemWriter must not be null");
    }

    /**
     * 只处理 GET 授权入口。
     *
     * @param request HTTP 请求
     * @return 不处理时返回 {@code true}
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !"GET".equalsIgnoreCase(request.getMethod())
                || !uri.startsWith(PATH_PREFIX)
                || uri.length() == PATH_PREFIX.length();
    }

    /**
     * 检查注册与发现，再交给标准授权重定向过滤器。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException 过滤器失败
     * @throws IOException 读写失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String registrationId = request.getRequestURI().substring(PATH_PREFIX.length());
        Optional<OidcIdpConfig> idp = registry.findEnabledByRegistrationId(registrationId);
        if (idp.isEmpty()) {
            // 未知/未配置/已停用统一 404，不返回租户或 IdP 差异。
            problemWriter.write(
                    response,
                    HttpStatus.NOT_FOUND,
                    ErrorCodes.REQUEST_NOT_FOUND,
                    "Not found",
                    "oidc provider is not configured",
                    RequestContext.traceId(request));
            return;
        }
        try {
            // 触发发现并写入缓存；失败时统一清理并重定向登录页。
            registry.clientRegistration(registrationId);
        } catch (OidcDiscoveryException exception) {
            failureHandler.respond(request, response, registrationId, exception.reason());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
