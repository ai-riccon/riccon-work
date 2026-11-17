package com.riccon.api.auth.controller;

import com.riccon.api.auth.service.AuthService;
import com.riccon.api.error.UnauthenticatedException;
import com.riccon.api.identity.domain.RequirePermission;
import com.riccon.api.identity.domain.RicconPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 方法级 RBAC 校验。
 *
 * <p>与安全过滤器链的 URL 级 {@code authenticated()} 形成双重校验；每次请求从数据库重新加载权限，
 * 不信任会话中的旧权限。权限码来自 {@link RequirePermission}，由 controller 显式声明。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    /** 认证服务。 */
    private final AuthService authService;

    /**
     * 创建权限拦截器。
     *
     * @param authService 认证服务
     */
    public PermissionInterceptor(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 在 controller 方法执行前校验权限。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param handler 处理器
     * @return 是否继续执行
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePermission permission =
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequirePermission.class);
        if (permission == null) {
            permission =
                    AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequirePermission.class);
        }
        if (permission == null) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof RicconPrincipal principal)) {
            throw new UnauthenticatedException();
        }
        authService.requirePermission(principal, permission.value());
        return true;
    }
}
