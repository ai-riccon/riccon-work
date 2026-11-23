package com.riccon.api.knowledge.domain;

import java.util.Objects;

/**
 * 租户保留配置投影。
 *
 * @param tenantId 租户代码
 * @param retentionDays 保留天数
 */
public record KnowledgeTenantRetention(String tenantId, int retentionDays) {

    /** 校验租户与天数。 */
    public KnowledgeTenantRetention {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be >= 1");
        }
    }
}
