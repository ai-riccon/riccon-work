package com.riccon.api.quota.domain;

import java.util.Objects;

/**
 * {@code quota_limit} 的一行。
 *
 * <p>{@code limit} 为 {@code null} 表示数据库显式不限；与“没有行”区分，后者由上层决定是否取部署默认值。
 *
 * @param tenantId 所属租户
 * @param scopeType 维度
 * @param scopeId 租户代码或用户 UUID 字符串
 * @param window 窗口
 * @param limit 显式限额；{@code null} 表示不限
 */
public record QuotaLimitRow(String tenantId, QuotaScope scopeType, String scopeId, QuotaWindow window, Integer limit) {

    /** 保证行字段完整。 */
    public QuotaLimitRow {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(scopeType, "scopeType must not be null");
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        Objects.requireNonNull(window, "window must not be null");
    }
}
