package com.riccon.api.oidc.domain;

import java.util.Objects;

/**
 * OIDC 发现文档中 M10 需要的最小字段。
 *
 * <p>只保留授权端点、token 端点与 JWKS 地址；**不保留 userinfo 端点**，从构建 ClientRegistration 的源头杜绝
 * userinfo 调用（ADR-0019 第 2 节 F-011）。
 *
 * @param issuer 规范化后的 issuer（已与租户配置比对）
 * @param authorizationEndpoint 授权端点
 * @param tokenEndpoint token 端点
 * @param jwkSetUri JWKS 地址
 */
public record OidcProviderMetadata(
        String issuer, String authorizationEndpoint, String tokenEndpoint, String jwkSetUri) {

    /** 保证字段存在。 */
    public OidcProviderMetadata {
        Objects.requireNonNull(issuer, "issuer must not be null");
        Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint must not be null");
        Objects.requireNonNull(tokenEndpoint, "tokenEndpoint must not be null");
        Objects.requireNonNull(jwkSetUri, "jwkSetUri must not be null");
    }
}
