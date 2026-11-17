package com.riccon.platform.domain.identity;

/**
 * 认证来源。
 *
 * <p>{@link #LOCAL} 使用内置账号密码；{@link #OIDC} 为 M03 预留，要求外部 issuer 与 subject 同时存在。
 */
public enum AuthSource {
    /** 内置账号 + 密码哈希。 */
    LOCAL,
    /** 外部 OIDC 身份，M03 启用。 */
    OIDC
}
