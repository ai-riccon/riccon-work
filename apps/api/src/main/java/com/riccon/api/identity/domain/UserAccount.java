package com.riccon.api.identity.domain;

import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 账号领域模型。
 *
 * <p>密码哈希不在这里：它与账号分离存放在 {@code user_credential}，避免账号查询意外带出凭据。
 *
 * @param id 账号 ID（UUIDv7）
 * @param tenantId 所属租户
 * @param username 登录名
 * @param displayName 展示名
 * @param email 可选联系方式；M02 不用于登录
 * @param status 账号状态
 * @param authSource 认证来源
 * @param lastLoginAt 最近登录成功时间
 * @param createdAt 创建时间
 */
public record UserAccount(
        UUID id,
        String tenantId,
        String username,
        String displayName,
        String email,
        UserStatus status,
        AuthSource authSource,
        Instant lastLoginAt,
        Instant createdAt) {

    /** 保证关键字段存在。 */
    public UserAccount {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(authSource, "authSource must not be null");
    }

    /**
     * 判断账号是否可登录、可访问业务。
     *
     * @return 状态为 {@link UserStatus#ACTIVE} 时返回 {@code true}
     */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
