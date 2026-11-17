package com.riccon.api.identity.dto;

import java.time.Instant;
import java.util.List;

/**
 * 当前会话信息，对应 OpenAPI {@code SessionInfo}。
 *
 * @param authenticated 固定为 {@code true}
 * @param user 当前账号
 * @param tenant 当前租户
 * @param roles 角色码
 * @param permissions 权限码
 * @param csrfToken 当前 CSRF 令牌
 * @param expiresAt 会话过期时间
 */
public record SessionInfoResponse(
        boolean authenticated,
        UserSummaryResponse user,
        TenantSummaryResponse tenant,
        List<String> roles,
        List<String> permissions,
        String csrfToken,
        Instant expiresAt) {}
