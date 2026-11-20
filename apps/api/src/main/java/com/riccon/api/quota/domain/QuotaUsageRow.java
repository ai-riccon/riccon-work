package com.riccon.api.quota.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code quota_usage} 的一行。
 *
 * @param tenantId 所属租户
 * @param scopeType 维度
 * @param scopeId 租户代码或用户 UUID 字符串
 * @param window 窗口
 * @param windowStart 窗口起点（UTC）
 * @param used 已准入请求数
 */
public record QuotaUsageRow(
        String tenantId, QuotaScope scopeType, String scopeId, QuotaWindow window, Instant windowStart, int used) {

    /** 保证行字段完整。 */
    public QuotaUsageRow {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(scopeType, "scopeType must not be null");
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        if (used < 0) {
            throw new IllegalArgumentException("used must not be negative");
        }
    }
}
