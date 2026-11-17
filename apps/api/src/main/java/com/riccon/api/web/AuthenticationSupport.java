package com.riccon.api.web;

import com.riccon.api.error.UnauthenticatedException;
import com.riccon.api.identity.domain.RicconPrincipal;
import org.springframework.security.core.Authentication;

/**
 * 从 Spring Security 认证对象读取 Riccon 主体。
 */
public final class AuthenticationSupport {

    /** 工具类禁止实例化。 */
    private AuthenticationSupport() {}

    /**
     * 校验并返回会话主体。
     *
     * @param authentication 当前认证
     * @return Riccon 会话主体
     * @throws UnauthenticatedException 未认证或主体类型不符
     */
    public static RicconPrincipal requirePrincipal(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof RicconPrincipal principal) {
            return principal;
        }
        throw new UnauthenticatedException();
    }
}
