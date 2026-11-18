package com.riccon.api.oidc.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * 不保存任何 OAuth2 授权客户端的仓储。
 *
 * <p>ADR-0019 第 2 节要求 access/refresh/ID token 不写入会话、数据库或日志。Spring Security 默认的
 * {@code AuthenticatedPrincipalOAuth2AuthorizedClientRepository} 会把 token 写入 HttpSession；本实现让
 * {@code saveAuthorizedClient} 成为空操作，从持久化路径上消除 token 泄露面。
 */
public class NoopOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    /**
     * 永远不返回已保存的授权客户端。
     *
     * @param clientRegistrationId 注册 ID
     * @param principal 当前主体
     * @param request HTTP 请求
     * @param <T> 客户端类型
     * @return 永远 {@code null}
     */
    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        return null;
    }

    /**
     * 丢弃授权客户端，不写会话或数据库。
     *
     * @param authorizedClient 授权客户端（含 token）
     * @param principal 当前主体
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    @Override
    public void saveAuthorizedClient(
            OAuth2AuthorizedClient authorizedClient,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 有意为空：不持久化任何 token。
    }

    /**
     * 无客户端可删除。
     *
     * @param clientRegistrationId 注册 ID
     * @param principal 当前主体
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    @Override
    public void removeAuthorizedClient(
            String clientRegistrationId,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 有意为空：从不存在 token。
    }
}
