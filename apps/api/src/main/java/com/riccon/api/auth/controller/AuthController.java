package com.riccon.api.auth.controller;

import com.riccon.api.auth.domain.LoginContext;
import com.riccon.api.auth.service.AuthService;
import com.riccon.api.error.UnauthenticatedException;
import com.riccon.api.identity.domain.CurrentUser;
import com.riccon.api.identity.domain.RicconPrincipal;
import com.riccon.api.identity.domain.TenantRecord;
import com.riccon.api.identity.domain.UserAccount;
import com.riccon.api.identity.dto.CsrfTokenResponse;
import com.riccon.api.identity.dto.LoginRequest;
import com.riccon.api.identity.dto.SessionInfoResponse;
import com.riccon.api.identity.dto.TenantSummaryResponse;
import com.riccon.api.identity.dto.UserSummaryResponse;
import com.riccon.api.oidc.service.OidcLogoutService;
import com.riccon.api.web.RequestContext;
import com.riccon.api.web.Validation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与会话接口。
 *
 * <p>会话存 MySQL（Spring Session JDBC），不使用 JWT。登录成功后轮换会话 ID 与 CSRF 令牌，防止会话固定；
 * 登出使会话失效并写审计（ADR-0013）。所有写操作由 Spring Security 统一校验 {@code X-CSRF-TOKEN}。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 认证服务。 */
    private final AuthService authService;

    /** 会话安全上下文仓储，用于显式保存登录态。 */
    private final SecurityContextRepository securityContextRepository;

    /** CSRF 令牌仓储，用于登录后轮换令牌。 */
    private final CsrfTokenRepository csrfTokenRepository;

    /** OIDC 联合登出地址（不调用 IdP）。 */
    private final OidcLogoutService oidcLogoutService;

    /**
     * 创建认证控制器。
     *
     * @param authService 认证服务
     * @param securityContextRepository 安全上下文仓储
     * @param csrfTokenRepository CSRF 令牌仓储
     * @param oidcLogoutService OIDC 联合登出地址
     */
    public AuthController(
            AuthService authService,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            OidcLogoutService oidcLogoutService) {
        this.authService = authService;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.oidcLogoutService = oidcLogoutService;
    }

    /**
     * 获取当前 CSRF 令牌；匿名请求会建立仅承载令牌的匿名会话。
     *
     * @param csrfToken 当前令牌（由 Spring Security 解析并触发持久化）
     * @return CSRF 令牌响应
     */
    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken csrfToken) {
        return new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    /**
     * 内置账号登录。
     *
     * @param body 登录请求
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @return 会话信息
     */
    @PostMapping("/login")
    public SessionInfoResponse login(
            @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        Validation.requireBody(body);
        // 登录阶段只校验长度：格式错误也走统一 401，不向攻击者暴露租户代码规则。
        String tenantCode = Validation.requireText(body.tenantCode(), "tenantCode", 1, 64);
        String username = Validation.requireText(body.username(), "username", 1, 100);
        String password = requirePassword(body.password());
        CurrentUser currentUser = authService.login(tenantCode, username, password, loginContext(request));
        RicconPrincipal principal = new RicconPrincipal(
                currentUser.user().id(),
                currentUser.tenant().id(),
                currentUser.user().username(),
                currentUser.user().displayName());

        establishSession(principal, request, response);
        CsrfToken rotated = rotateCsrfToken(request, response);
        return sessionInfo(currentUser, rotated.getToken(), request);
    }

    /**
     * 读取当前会话。
     *
     * @param authentication 当前认证
     * @param csrfToken 当前 CSRF 令牌
     * @param request HTTP 请求
     * @return 会话信息
     */
    @GetMapping("/session")
    public SessionInfoResponse session(Authentication authentication, CsrfToken csrfToken, HttpServletRequest request) {
        CurrentUser currentUser = authService.loadCurrentUser(requirePrincipal(authentication));
        return sessionInfo(currentUser, csrfToken.getToken(), request);
    }

    /**
     * 登出：写审计后使本地会话失效。
     *
     * <p>DEC-0017 A：本地登出先执行且是权威结果，不依赖 IdP 可达性；若当前账号为 OIDC 且租户配置了
     * end-session，响应头返回联合登出地址，SPA 随后做顶层导航。不保存 ID Token，因此不使用 id_token_hint。
     *
     * @param authentication 当前认证
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        RicconPrincipal principal = requirePrincipal(authentication);
        // 先计算联登地址（只读本地配置/账号来源）；即使 IdP 不可达也照常返回，由浏览器负责导航。
        oidcLogoutService
                .federatedLogoutUrl(principal)
                .ifPresent(url -> response.setHeader(OidcLogoutService.FEDERATED_LOGOUT_HEADER, url));
        authService.logout(principal, loginContext(request));
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 只失效当前会话：其他并发会话按 ADR-0019 第 5 节保留。
            session.invalidate();
        }
    }

    /**
     * 建立登录态：轮换会话 ID、写 SecurityContext 到会话。
     *
     * @param principal 会话主体
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    private void establishSession(RicconPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        HttpSession existing = request.getSession(false);
        if (existing == null) {
            request.getSession(true);
        } else {
            // 登录成功后轮换会话 ID，阻止攻击者预先固定的会话继续有效（ADR-0013 第 1 节）。
            request.changeSessionId();
        }
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);
    }

    /**
     * 登录后轮换 CSRF 令牌；旧令牌随会话属性删除。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @return 新令牌
     */
    private CsrfToken rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(null, request, response);
        CsrfToken csrfToken = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(csrfToken, request, response);
        return csrfToken;
    }

    /**
     * 组装会话响应。
     *
     * @param currentUser 当前授权快照
     * @param csrfToken 当前 CSRF 令牌
     * @param request HTTP 请求
     * @return 会话信息
     */
    private SessionInfoResponse sessionInfo(CurrentUser currentUser, String csrfToken, HttpServletRequest request) {
        UserAccount user = currentUser.user();
        TenantRecord tenant = currentUser.tenant();
        UserSummaryResponse userSummary = new UserSummaryResponse(
                user.id(),
                user.username(),
                user.displayName(),
                user.email(),
                user.status(),
                currentUser.roles(),
                user.authSource(),
                user.lastLoginAt(),
                user.createdAt());
        TenantSummaryResponse tenantSummary = new TenantSummaryResponse(
                tenant.id(), tenant.name(), tenant.status(), tenant.plan(), tenant.retentionDays(), tenant.createdAt());
        HttpSession session = request.getSession(false);
        Instant expiresAt = session == null
                ? Instant.now()
                : Instant.ofEpochMilli(session.getLastAccessedTime() + session.getMaxInactiveInterval() * 1000L);
        return new SessionInfoResponse(
                true, userSummary, tenantSummary, currentUser.roles(), currentUser.permissions(), csrfToken, expiresAt);
    }

    /**
     * 从认证对象读取 Riccon 主体。
     *
     * @param authentication 当前认证
     * @return 会话主体
     * @throws UnauthenticatedException 未认证或主体类型错误
     */
    private RicconPrincipal requirePrincipal(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof RicconPrincipal principal) {
            return principal;
        }
        throw new UnauthenticatedException();
    }

    /**
     * 组装登录审计上下文；会话 ID 由服务层哈希后写入审计。
     *
     * @param request HTTP 请求
     * @return 登录上下文
     */
    private LoginContext loginContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return new LoginContext(
                RequestContext.traceId(request),
                RequestContext.requestId(request),
                request.getRemoteAddr(),
                session == null ? null : session.getId());
    }

    /**
     * 校验登录密码长度；密码不做 trim，避免改变用户凭据。
     *
     * @param password 明文密码
     * @return 原密码
     */
    private String requirePassword(String password) {
        if (password == null || password.isEmpty() || password.length() > 200) {
            throw new com.riccon.api.error.BadRequestException("password length must be between 1 and 200");
        }
        return password;
    }
}
