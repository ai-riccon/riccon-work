package com.riccon.api.admin.identity;

import com.riccon.platform.domain.identity.AuthSource;

/**
 * 账号当前的外部身份快照。
 *
 * <p>{@code UserAccount} 只暴露 {@code authSource}，绑定/解绑需要判断“是否已是同一身份”以及审计中的
 * {@code previousAuthSource}，因此单独读取 issuer/subject；这些值只在本包内使用，不进入响应或日志。
 *
 * @param authSource 当前认证来源
 * @param issuer 已绑定的规范化 issuer；LOCAL 账号为空
 * @param subject 已绑定的 subject；LOCAL 账号为空
 */
public record OidcAccountIdentity(AuthSource authSource, String issuer, String subject) {}
