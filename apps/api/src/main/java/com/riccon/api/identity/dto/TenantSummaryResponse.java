package com.riccon.api.identity.dto;

import com.riccon.api.identity.domain.TenantStatus;
import java.time.Instant;

/**
 * 租户摘要，对应 OpenAPI {@code TenantSummary}。
 *
 * @param id 租户代码
 * @param name 展示名
 * @param status 状态
 * @param plan 套餐
 * @param retentionDays 保留天数
 * @param createdAt 创建时间
 */
public record TenantSummaryResponse(
        String id, String name, TenantStatus status, String plan, int retentionDays, Instant createdAt) {}
