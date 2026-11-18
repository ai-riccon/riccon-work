package com.riccon.api.oidc.domain;

/**
 * 企业账号登录可用性。
 *
 * <p>未知租户、未配置 IdP、已停用与租户不存在统一返回 {@code enabled=false}，不返回租户名、IdP 地址或错误差异，
 * 避免租户枚举（ADR-0019 第 4 节）。
 *
 * @param enabled 是否展示企业账号登录入口
 * @param providerName 登录页展示名；仅 {@code enabled=true} 时返回
 */
public record OidcProviderAvailability(boolean enabled, String providerName) {

    /** 不可用响应。 */
    private static final OidcProviderAvailability DISABLED = new OidcProviderAvailability(false, null);

    /**
     * 返回统一的不可用响应。
     *
     * @return {@code enabled=false}
     */
    public static OidcProviderAvailability disabled() {
        return DISABLED;
    }

    /**
     * 返回可用响应。
     *
     * @param providerName 展示名
     * @return {@code enabled=true}
     */
    public static OidcProviderAvailability enabled(String providerName) {
        return new OidcProviderAvailability(true, providerName);
    }
}
