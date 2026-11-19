package com.riccon.api.identity.dto;

/**
 * 解除 OIDC 绑定并恢复本地密码请求，对应 OpenAPI {@code OidcUnbindRequest}。
 *
 * <p>没有“无凭据解绑”：必须同时设置新本地密码，防止把账号恢复成不可登录状态（ADR-0019 第 3 节）。
 *
 * @param newPassword 恢复本地账号时设置的新密码，12–200 字符
 */
public record OidcUnbindRequest(String newPassword) {}
