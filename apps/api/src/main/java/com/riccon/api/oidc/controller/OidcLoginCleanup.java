package com.riccon.api.oidc.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * OIDC 登录失败统一清理。
 *
 * <p>ADR-0019 第 2.1 节：任何失败都必须
 * 清空 {@code SecurityContextHolder}、保存空上下文、删除授权请求（state/nonce/PKCE verifier）、失效会话；
 * 不允许“半登录”会话残留，也不允许 OidcUser/ID Token 留在会话属性中。清理顺序先删属性再失效会话，
 * 因为失效后无法再访问会话属性；清理本身不吞异常。
 */
@Component
public class OidcLoginCleanup {

    /** 会话安全上下文仓储（OIDC 感知包装）。 */
    private final SecurityContextRepository securityContextRepository;

    /** 会话内授权请求仓储。 */
    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository;

    /**
     * 创建清理器。
     *
     * @param securityContextRepository 安全上下文仓储
     * @param authorizationRequestRepository 授权请求仓储
     */
    public OidcLoginCleanup(
            SecurityContextRepository securityContextRepository,
            AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequestRepository) {
        this.securityContextRepository =
                Objects.requireNonNull(securityContextRepository, "securityContextRepository must not be null");
        this.authorizationRequestRepository = Objects.requireNonNull(
                authorizationRequestRepository, "authorizationRequestRepository must not be null");
    }

    /**
     * 清理失败登录的会话状态。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    public void reject(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 先显式删除上下文属性，避免 saveContext(empty) 重新创建会话。
            session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        }
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        authorizationRequestRepository.removeAuthorizationRequest(request, response);
        session = request.getSession(false);
        if (session != null) {
            // 失效会话同时让 Spring Session 过期会话 Cookie；不触碰其他并发会话。
            session.invalidate();
        }
    }
}
