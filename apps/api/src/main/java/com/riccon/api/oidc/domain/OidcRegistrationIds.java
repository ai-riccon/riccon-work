package com.riccon.api.oidc.domain;

import java.util.Optional;

/**
 * OIDC 注册 ID 规则。
 *
 * <p>ADR-0019 第 2 节固定 registrationId 为 {@code tenant-{tenantCode}}，授权入口与回调路径都使用它；
 * 解析时只去掉固定前缀，租户代码本身允许包含 {@code -}。
 */
public final class OidcRegistrationIds {

    /** 注册 ID 前缀。 */
    public static final String PREFIX = "tenant-";

    /** 工具类禁止实例化。 */
    private OidcRegistrationIds() {}

    /**
     * 由租户代码构造注册 ID。
     *
     * @param tenantCode 租户代码
     * @return {@code tenant-{tenantCode}}
     */
    public static String registrationId(String tenantCode) {
        return PREFIX + tenantCode;
    }

    /**
     * 从注册 ID 解析租户代码。
     *
     * @param registrationId 注册 ID
     * @return 合法时返回租户代码，否则返回空
     */
    public static Optional<String> tenantCode(String registrationId) {
        if (registrationId == null || !registrationId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String tenantCode = registrationId.substring(PREFIX.length());
        if (tenantCode.isBlank() || tenantCode.length() > 64) {
            return Optional.empty();
        }
        return Optional.of(tenantCode);
    }
}
