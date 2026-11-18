package com.riccon.api.oidc.domain;

import java.util.List;
import java.util.Objects;

/**
 * 单个租户的 OIDC IdP 配置。
 *
 * <p>配置只来自部署 Secret（{@code RICCON_OIDC_IDPS}），不入库、不提供写接口（ADR-0019 第 1 节）。
 * {@code issuer} 已经规范化（小写 scheme/host、去默认端口、去尾部斜杠）；{@code registrationId} 固定为
 * {@code tenant-{tenantCode}}。{@link #toString()} 对 {@code clientSecret} 做掩码，任何日志与异常都不得输出明文。
 *
 * @param tenantCode 目标租户代码，与 {@code tenant.id} 一致
 * @param issuer 规范化后的 OIDC issuer
 * @param clientId IdP 注册的客户端 ID
 * @param clientSecret 客户端密钥；只允许从 Secret 注入
 * @param providerName 登录页展示名
 * @param scopes 授权 scope，必须包含 {@code openid}
 * @param endSessionEndpoint RP-Initiated Logout 端点；{@code null} 表示不支持联合登出
 * @param enabled 是否启用；{@code false} 等同未配置，但保留配置便于排查
 */
public record OidcIdpConfig(
        String tenantCode,
        String issuer,
        String clientId,
        String clientSecret,
        String providerName,
        List<String> scopes,
        String endSessionEndpoint,
        boolean enabled) {

    /** 保证关键字段存在且不可变。 */
    public OidcIdpConfig {
        Objects.requireNonNull(tenantCode, "tenantCode must not be null");
        Objects.requireNonNull(issuer, "issuer must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(clientSecret, "clientSecret must not be null");
        Objects.requireNonNull(providerName, "providerName must not be null");
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes must not be null"));
    }

    /**
     * 返回固定格式的注册 ID。
     *
     * @return {@code tenant-{tenantCode}}
     */
    public String registrationId() {
        return OidcRegistrationIds.registrationId(tenantCode);
    }

    /**
     * 掩码后的字符串表示；禁止泄露客户端密钥。
     *
     * @return 不含 secret 的调试字符串
     */
    @Override
    public String toString() {
        return "OidcIdpConfig[tenantCode=" + tenantCode
                + ", issuer=" + issuer
                + ", clientId=" + clientId
                + ", clientSecret=***"
                + ", providerName=" + providerName
                + ", scopes=" + scopes
                + ", endSessionEndpoint=" + endSessionEndpoint
                + ", enabled=" + enabled
                + "]";
    }
}
