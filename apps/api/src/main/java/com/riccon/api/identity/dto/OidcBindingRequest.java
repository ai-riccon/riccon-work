package com.riccon.api.identity.dto;

/**
 * 管理员显式绑定 OIDC 身份请求，对应 OpenAPI {@code OidcBindingRequest}。
 *
 * <p>只接受 issuer 与 subject；不读取、不比较、不存储邮箱（ADR-0019 第 3 节），避免按邮箱自动关联造成账号接管。
 *
 * @param issuer OIDC issuer；必须等于目标租户当前启用的 IdP issuer（规范化后）
 * @param subject IdP 的 subject（sub），1–255 字符
 */
public record OidcBindingRequest(String issuer, String subject) {}
