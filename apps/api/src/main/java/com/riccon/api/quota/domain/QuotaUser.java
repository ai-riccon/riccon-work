package com.riccon.api.quota.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 配额用户明细中的账号投影。
 *
 * <p>只包含租户内账号的 ID、登录名与展示名；不读取邮箱或凭据（ADR-0022 第 3.2 节）。
 *
 * @param userId 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 */
public record QuotaUser(UUID userId, String username, String displayName) {

    /** 保证字段完整。 */
    public QuotaUser {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }
}
