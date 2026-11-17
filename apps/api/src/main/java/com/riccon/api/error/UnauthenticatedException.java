package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/** 未认证或会话已失效。 */
public class UnauthenticatedException extends RicconException {

    /** 使用统一的未认证文案创建异常。 */
    public UnauthenticatedException() {
        super(
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_UNAUTHENTICATED,
                "Authentication required",
                "session is missing or expired");
    }
}
