package com.riccon.api.oidc.controller;

import com.riccon.api.auth.service.LoginRateLimiter;
import com.riccon.api.error.RateLimitedException;
import com.riccon.api.oidc.domain.OidcLoginFailureReason;
import com.riccon.api.oidc.service.OidcIdpRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * OIDC 回调限流检查。
 *
 * <p>ADR-0019 第 4 节要求回调处理前检查 {@code LoginRateLimiter}（键为客户端 IP + tenantCode）；
 * 超限直接走失败清理并 302 {@code /login?oidcError=rate_limited}，不进入 state/token 交换，避免放大滥用成本。
 * 只有 {@code account_not_bound}/{@code account_disabled}/{@code issuer_mismatch} 由失败处理器累计失败。
 */
@Component
public class OidcLoginRateLimitFilter extends OncePerRequestFilter {

    /** 回调入口前缀。 */
    static final String PATH_PREFIX = "/api/v1/auth/oidc/callback/";

    /** IdP 注册表。 */
    private final OidcIdpRegistry registry;

    /** 登录失败限流。 */
    private final LoginRateLimiter loginRateLimiter;

    /** 失败响应。 */
    private final OidcLoginFailureHandler failureHandler;

    /**
     * 创建回调限流过滤器。
     *
     * @param registry IdP 注册表
     * @param loginRateLimiter 登录失败限流
     * @param failureHandler 失败响应
     */
    public OidcLoginRateLimitFilter(
            OidcIdpRegistry registry, LoginRateLimiter loginRateLimiter, OidcLoginFailureHandler failureHandler) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.loginRateLimiter = Objects.requireNonNull(loginRateLimiter, "loginRateLimiter must not be null");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler must not be null");
    }

    /**
     * 只处理 GET 回调入口。
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
     * 检查限流后放行。
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
        Optional<String> tenantCode = registry.tenantCodeOf(registrationId);
        if (tenantCode.isPresent()) {
            try {
                loginRateLimiter.checkAllowed(request.getRemoteAddr(), tenantCode.get());
            } catch (RateLimitedException exception) {
                failureHandler.respond(request, response, registrationId, OidcLoginFailureReason.RATE_LIMITED);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
