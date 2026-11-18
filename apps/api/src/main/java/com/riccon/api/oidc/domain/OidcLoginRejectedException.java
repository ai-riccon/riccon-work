package com.riccon.api.oidc.domain;

import java.util.Objects;

/**
 * OIDC 业务校验拒绝。
 *
 * <p>在认证成功前抛出，由认证提供者包装为 {@code OAuth2AuthenticationException}；失败处理器只读取
 * {@link #reason()} 映射到登录页原因码，不向客户端或日志输出底层细节。
 */
public class OidcLoginRejectedException extends RuntimeException {

    /** 稳定失败原因。 */
    private final OidcLoginFailureReason reason;

    /**
     * 创建拒绝异常。
     *
     * @param reason 稳定失败原因
     */
    public OidcLoginRejectedException(OidcLoginFailureReason reason) {
        super(reason.code());
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * 返回稳定失败原因。
     *
     * @return 失败原因
     */
    public OidcLoginFailureReason reason() {
        return reason;
    }
}
