package com.riccon.api.error;

import org.springframework.http.HttpStatus;

/** 登录限流触发。 */
public class RateLimitedException extends RicconException {

    /** 触发登录限流。 */
    public RateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, "Too many requests", "login rate limit exceeded");
    }
}
