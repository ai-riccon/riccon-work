package com.riccon.api.oidc.domain;

/**
 * OIDC 发现或元数据校验失败。
 *
 * <p>只携带稳定原因码（{@link OidcLoginFailureReason}），不携带 IdP 原始响应、地址错误详情或 token；
 * 失败处理器直接用原因码重定向登录页。
 */
public class OidcDiscoveryException extends RuntimeException {

    /** 稳定失败原因。 */
    private final OidcLoginFailureReason reason;

    /**
     * 创建发现失败异常。
     *
     * @param reason 稳定失败原因
     */
    public OidcDiscoveryException(OidcLoginFailureReason reason) {
        super(reason.code());
        this.reason = reason;
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
