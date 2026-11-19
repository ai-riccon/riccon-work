package com.riccon.api.identity.dto;

import com.riccon.platform.domain.identity.AuthSource;
import com.riccon.platform.domain.identity.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 账号摘要，对应 OpenAPI {@code UserSummary}。
 *
 * @param id 账号 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param email 联系方式，可为空
 * @param status 账号状态
 * @param roles 角色码
 * @param authSource 认证来源，管理界面据此显示“企业账号”/“内置账号”
 * @param lastLoginAt 最近登录时间，可为空
 * @param createdAt 创建时间
 */
public record UserSummaryResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        UserStatus status,
        List<String> roles,
        AuthSource authSource,
        Instant lastLoginAt,
        Instant createdAt) {}
