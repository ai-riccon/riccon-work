package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/**
 * 登录凭据错误。
 *
 * <p>返回统一 401，不区分租户不存在、账号不存在、密码错误、账号锁定或禁用；登录失败计数与审计必须在
 * 同一事务内提交，因此 AuthService 显式声明“此异常不回滚”。
 */
public class InvalidCredentialsException extends RicconException {

    /** 使用统一对外文案创建凭据错误。 */
    public InvalidCredentialsException() {
        super(
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_INVALID_CREDENTIALS,
                "Authentication failed",
                "invalid credentials");
    }

    /**
     * 创建带内部原因的凭据错误；原因只进入日志，不进入响应。
     *
     * @param internalReason 内部原因码，如 {@code bad_password}
     */
    public InvalidCredentialsException(String internalReason) {
        super(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_INVALID_CREDENTIALS, "Authentication failed", internalReason);
    }
}
