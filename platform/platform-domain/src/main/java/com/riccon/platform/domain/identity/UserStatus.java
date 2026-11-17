package com.riccon.platform.domain.identity;

/**
 * 账号状态。
 *
 * <p>M02 只允许禁用账号，不物理删除；禁用立即阻止新会话，已有会话在下一次请求时失效。
 */
public enum UserStatus {
    /** 可登录、可被授予角色。 */
    ACTIVE,
    /** 禁止登录；历史审计与引用保留。 */
    DISABLED
}
