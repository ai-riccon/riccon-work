package com.riccon.api.oidc.controller;

import com.riccon.api.auth.domain.LoginContext;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.oidc.domain.OidcLoginFailureReason;
import com.riccon.api.oidc.domain.OidcLoginRejectedException;
import com.riccon.api.oidc.service.OidcLoginService;
import com.riccon.api.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * OIDC 登录成功处理器。
 *
 * <p>成功会话只保存 {@link RicconPrincipal}（ADR-0019 第 2 节）：
 *
 * <ol>
 *   <li>轮换会话 ID（Spring 的 session fixation 策略已轮换一次，这里再做一次纵深）；
 *   <li>用 {@link RicconPrincipal} 替换 OIDC 主体并显式保存安全上下文（OIDC 主体被仓储拒绝持久化）；
 *   <li>轮换 CSRF 令牌（旧令牌失效，前端通过 {@code GET /auth/session} 取新值）；
 *   <li>写成功审计（fail-closed：审计失败则清理会话并按 {@code login_failed} 失败）。
 * </ol>
 *
 * <p>access/refresh/ID token 从未写入会话或数据库，处理器也不接触它们。
 */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(OidcLoginSuccessHandler.class);

    /** 登录业务与审计。 */
    private final OidcLoginService loginService;

    /** 失败响应（内部异常统一走清理路径）。 */
    private final OidcLoginFailureHandler failureHandler;

    /** 安全上下文仓储（OIDC 感知包装）。 */
    private final SecurityContextRepository securityContextRepository;

    /** CSRF 令牌仓储。 */
    private final CsrfTokenRepository csrfTokenRepository;

    /**
     * 创建成功处理器。
     *
     * @param loginService 登录业务与审计
     * @param failureHandler 失败响应
     * @param securityContextRepository 安全上下文仓储
     * @param csrfTokenRepository CSRF 令牌仓储
     */
    public OidcLoginSuccessHandler(
            OidcLoginService loginService,
            OidcLoginFailureHandler failureHandler,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository) {
        this.loginService = Objects.requireNonNull(loginService, "loginService must not be null");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler must not be null");
        this.securityContextRepository =
                Objects.requireNonNull(securityContextRepository, "securityContextRepository must not be null");
        this.csrfTokenRepository = Objects.requireNonNull(csrfTokenRepository, "csrfTokenRepository must not be null");
    }

    /**
     * 建立既有会话层登录态。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param authentication OIDC 认证结果
     * @throws IOException 重定向失败
     */
    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        String registrationId = authentication instanceof OAuth2AuthenticationToken token
                ? token.getAuthorizedClientRegistrationId()
                : null;
        OidcUser oidcUser = authentication.getPrincipal() instanceof OidcUser user ? user : null;
        try {
            if (registrationId == null || oidcUser == null) {
                throw new OidcLoginRejectedException(OidcLoginFailureReason.LOGIN_FAILED);
            }
            String issuer = oidcUser.getIdToken().getIssuer() == null
                    ? null
                    : oidcUser.getIdToken().getIssuer().toExternalForm();
            UserAccount user = loginService.validateLogin(
                    registrationId, issuer, oidcUser.getIdToken().getSubject());
            RicconPrincipal principal =
                    new RicconPrincipal(user.id(), user.tenantId(), user.username(), user.displayName());
            establishSession(request, response, principal);
            rotateCsrfToken(request, response);
            loginService.recordLoginSuccess(user, loginContext(request));
        } catch (OidcLoginRejectedException rejected) {
            failureHandler.respond(request, response, registrationId, rejected.reason());
            return;
        } catch (Exception exception) {
            // 成功处理器内任何异常都必须清理，不能留下半登录会话；只记录异常类型，避免消息夹带敏感数据。
            LOG.error(
                    "oidc.login.completion.failed registrationId={} errorType={}",
                    registrationId,
                    exception.getClass().getSimpleName());
            failureHandler.respond(request, response, registrationId, OidcLoginFailureReason.LOGIN_FAILED);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect("/");
    }

    /**
     * 建立会话：确保会话存在、轮换 ID、保存 RicconPrincipal 上下文。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param principal 会话主体
     */
    private void establishSession(HttpServletRequest request, HttpServletResponse response, RicconPrincipal principal) {
        HttpSession existing = request.getSession(false);
        if (existing == null) {
            request.getSession(true);
        } else {
            // 防御性二次轮换：即使未来替换 session fixation 策略，登录后也不会沿用旧会话 ID。
            request.changeSessionId();
        }
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);
    }

    /**
     * 轮换 CSRF 令牌；旧令牌随会话属性删除。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(null, request, response);
        CsrfToken csrfToken = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(csrfToken, request, response);
    }

    /**
     * 组装登录上下文；会话 ID 由审计层哈希。
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
