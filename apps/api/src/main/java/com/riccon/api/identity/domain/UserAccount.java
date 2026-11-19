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
 * @param externalIssuer OIDC issuer；只用于生成脱敏展示名，绝不进入响应原文。LOCAL 为 {@code null}
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
        String externalIssuer,
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
     * 兼容旧调用方的构造器：未携带 issuer 时按“未知外部身份”处理。
     *
     * <p>仅用于不关心来源 IdP 的路径（如 OIDC 登录身份命中校验）；响应 DTO 必须使用规范构造器并显式传入
     * issuer，否则来源 IdP 会缺失。
     *
     * @param id 账号 ID
     * @param tenantId 所属租户
     * @param username 登录名
     * @param displayName 展示名
     * @param email 联系方式，可为空
     * @param status 账号状态
     * @param authSource 认证来源
     * @param lastLoginAt 最近登录成功时间
     * @param createdAt 创建时间
     */
    public UserAccount(
            UUID id,
            String tenantId,
            String username,
            String displayName,
            String email,
            UserStatus status,
            AuthSource authSource,
            Instant lastLoginAt,
            Instant createdAt) {
        this(id, tenantId, username, displayName, email, status, authSource, null, lastLoginAt, createdAt);
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
