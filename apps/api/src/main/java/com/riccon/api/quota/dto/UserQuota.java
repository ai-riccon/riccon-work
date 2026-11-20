package com.riccon.api.quota.dto;

import java.util.List;
import java.util.UUID;

/**
 * 用户配额，对应 OpenAPI {@code UserQuota}。
 *
 * @param userId 用户 ID
 * @param username 登录名
 * @param displayName 展示名
 * @param windows 日/月窗口，固定两个
 */
public record UserQuota(UUID userId, String username, String displayName, List<QuotaWindowState> windows) {}
