package com.riccon.api.oidc.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * OIDC 感知的安全上下文仓储。
 *
 * <p>Spring Security 的 {@code AbstractAuthenticationProcessingFilter.successfulAuthentication} 会在调用
 * successHandler **之前**执行 {@code saveContext}；如果直接使用 HttpSession 仓储，业务失败（未绑定/禁用/
 * 租户不可用）可能把 {@code OidcUser}/{@code OAuth2AuthenticationToken}（含 ID Token）留在会话里
 * （ADR-0019 第 2.1 节）。本类：
 *
 * <ul>
 *   <li>{@code saveContext} 遇到 OIDC 主体只更新当前线程上下文，不写会话；
 *   <li>{@code loadContext} 遇到历史/异常残留的 OIDC 主体时删除会话属性并返回空上下文（F-029）；
 *   <li>只有成功处理器构造的 {@code RicconPrincipal} 允许落会话。
 * </ul>
 */
public class OidcAwareSecurityContextRepository implements SecurityContextRepository {

    /** 实际会话仓储。 */
    private final SecurityContextRepository delegate;

    /**
     * 创建 OIDC 感知仓储。
     *
     * @param delegate 实际会话仓储
     */
    public OidcAwareSecurityContextRepository(SecurityContextRepository delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * 加载上下文；发现 OIDC 主体时清理并返回空上下文。
     *
     * @param holder 请求/响应持有者
     * @return 安全上下文
     */
    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        SecurityContext context = delegate.loadContext(holder);
        if (isOidcAuthentication(context.getAuthentication())) {
            // F-029：历史版本或异常路径可能留下 OIDC 主体；读取时立即清掉，绝不恢复成登录态。
            HttpSession session = holder.getRequest().getSession(false);
            if (session != null) {
                session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            }
            return SecurityContextHolder.createEmptyContext();
        }
        return context;
    }

    /**
     * 保存上下文；OIDC 主体只留在当前线程。
     *
     * @param context 安全上下文
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        if (isOidcAuthentication(context.getAuthentication())) {
            SecurityContextHolder.setContext(context);
            return;
        }
        delegate.saveContext(context, request, response);
    }

    /**
     * 判断会话是否已有安全上下文。
     *
     * @param request HTTP 请求
     * @return 存在时返回 {@code true}
     */
    @Override
    public boolean containsContext(HttpServletRequest request) {
        return delegate.containsContext(request);
    }

    /**
     * 判断认证是否属于 OIDC 流程。
     *
     * @param authentication 认证
     * @return OIDC 主体时返回 {@code true}
     */
    private static boolean isOidcAuthentication(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return authentication.getPrincipal() instanceof OidcUser
                || authentication instanceof OAuth2AuthenticationToken
                || authentication instanceof OAuth2LoginAuthenticationToken;
    }
}
