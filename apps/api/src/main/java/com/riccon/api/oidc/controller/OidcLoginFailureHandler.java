package com.riccon.api.oidc.controller;

import com.riccon.api.auth.domain.LoginContext;
import com.riccon.api.auth.service.LoginRateLimiter;
import com.riccon.api.oidc.domain.OidcLoginFailureReason;
import com.riccon.api.oidc.service.OidcIdpRegistry;
import com.riccon.api.oidc.service.OidcLoginService;
import com.riccon.api.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * OIDC 登录失败处理器。
 *
 * <p>无论协议错误还是业务拒绝，都先执行 {@link OidcLoginCleanup#reject}，再写失败审计、按需累计登录失败，
 * 最后 302 到 {@code /login?oidcError=<原因码>}。响应与日志不包含 IdP 原始错误、code、state、token、
 * 内部地址或堆栈（ADR-0019 第 2.1/4 节）。
 */
@Component
public class OidcLoginFailureHandler implements AuthenticationFailureHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(OidcLoginFailureHandler.class);

    /** 登录失败重定向前缀。 */
    public static final String ERROR_REDIRECT = "/login?oidcError=";

    /** 清理器。 */
    private final OidcLoginCleanup cleanup;

    /** 登录业务与审计。 */
    private final OidcLoginService loginService;

    /** IdP 注册表。 */
    private final OidcIdpRegistry registry;

    /** 登录失败限流。 */
    private final LoginRateLimiter loginRateLimiter;

    /**
     * 创建失败处理器。
     *
     * @param cleanup 清理器
     * @param loginService 登录业务与审计
     * @param registry IdP 注册表
     * @param loginRateLimiter 登录失败限流
     */
    public OidcLoginFailureHandler(
            OidcLoginCleanup cleanup,
            OidcLoginService loginService,
            OidcIdpRegistry registry,
            LoginRateLimiter loginRateLimiter) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup must not be null");
        this.loginService = Objects.requireNonNull(loginService, "loginService must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.loginRateLimiter = Objects.requireNonNull(loginRateLimiter, "loginRateLimiter must not be null");
    }

    /**
     * 认证过滤器失败回调。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param exception 认证异常
     * @throws IOException 重定向失败
     */
    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String registrationId = registrationId(request);
        OidcLoginFailureReason reason = OidcFailureReasonMapper.map(exception, registry, registrationId);
        respond(request, response, registrationId, reason);
    }

    /**
     * 统一失败响应：清理会话、写审计、按需累计失败并重定向。
     *
     * <p>成功处理器内部异常也走这里，确保不会留下半登录会话。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param registrationId 注册 ID；可为空
     * @param reason 稳定原因码
     * @throws IOException 重定向失败
     */
    public void respond(
            HttpServletRequest request,
            HttpServletResponse response,
            String registrationId,
            OidcLoginFailureReason reason)
            throws IOException {
        LoginContext context = loginContext(request);
        cleanup.reject(request, response);
        try {
            loginService.recordLoginFailure(registrationId, reason, context);
        } catch (RuntimeException auditFailure) {
            // 审计失败不得掩盖原始登录错误；只记录脱敏日志（不输出异常 message，避免夹带 IdP 响应）。
            LOG.error(
                    "oidc.login.failed.audit.error reason={} errorType={}",
                    reason.code(),
                    auditFailure.getClass().getSimpleName());
        }
        if (reason.countsAsRateLimitFailure()) {
            registry.tenantCodeOf(registrationId)
                    .ifPresent(tenantCode -> loginRateLimiter.recordFailure(request.getRemoteAddr(), tenantCode));
        }
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(ERROR_REDIRECT + reason.code());
    }

    /**
     * 从回调路径读取注册 ID；非回调路径返回 {@code null}。
     *
     * @param request HTTP 请求
     * @return 注册 ID
     */
    private static String registrationId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int index = uri.lastIndexOf('/');
        if (index < 0 || index == uri.length() - 1) {
            return null;
        }
        return uri.substring(index + 1);
    }

    /**
     * 组装请求上下文；会话 ID 只以哈希进入审计。
     *
     * @param request HTTP 请求
     * @return 登录上下文
     */
    private static LoginContext loginContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return new LoginContext(
                RequestContext.traceId(request),
                RequestContext.requestId(request),
                request.getRemoteAddr(),
                session == null ? null : session.getId());
    }
}
