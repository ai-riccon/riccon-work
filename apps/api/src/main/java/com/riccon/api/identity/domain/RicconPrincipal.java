package com.riccon.api.identity.domain;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * 会话中保存的已认证主体。
 *
 * <p>ADR-0013 要求会话只存主体 ID、租户与用户名等非敏感字段，不存密码哈希或权限全集；权限在每次请求
 * 从数据库重新计算。实现 {@link Principal} 后，Spring Session 的 {@code PRINCIPAL_NAME} 索引使用
 * {@link #getName()}，格式为 {@code tenantId:userId}，跨租户唯一。
 *
 * @param userId 账号 ID
 * @param tenantId 所属租户
 * @param username 登录名
 * @param displayName 展示名
 */
public record RicconPrincipal(UUID userId, String tenantId, String username, String displayName)
        implements Principal, Serializable {

    /** JDK 序列化版本；修改字段时必须评估会话兼容性。 */
    @Serial
    private static final long serialVersionUID = 1L;

    /** 保证会话主体完整。 */
    public RicconPrincipal {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
    }

    /**
     * 返回跨租户唯一的主体名，供 Spring Session 索引。
     *
     * @return {@code tenantId:userId}
     */
    @Override
    public String getName() {
        return tenantId + ":" + userId;
    }
}
