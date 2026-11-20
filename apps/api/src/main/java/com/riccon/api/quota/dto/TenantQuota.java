package com.riccon.api.quota.dto;

import java.util.List;

/**
 * 租户配额，对应 OpenAPI {@code TenantQuota}。
 *
 * @param tenantId 租户 ID
 * @param tenantCode 租户代码
 * @param tenantName 租户展示名；租户端可为 {@code null}
 * @param windows 日/月窗口，固定两个
 */
public record TenantQuota(String tenantId, String tenantCode, String tenantName, List<QuotaWindowState> windows) {}
