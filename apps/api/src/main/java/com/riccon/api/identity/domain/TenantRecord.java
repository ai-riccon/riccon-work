package com.riccon.api.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 租户元数据。
 *
 * <p>租户表是全局表（ADR-0012），只保存识别与保留策略，不包含业务内容。
 *
 * @param id 租户代码，同时作为登录标识；{@code platform} 为保留值
 * @param name 展示名
 * @param status 状态
 * @param plan 套餐，M02 固定 {@code standard}，平台租户为 {@code internal}
 * @param retentionDays 对话与产物默认保留天数
 * @param createdAt 创建时间（UTC）
 */
public record TenantRecord(
        String id, String name, TenantStatus status, String plan, int retentionDays, Instant createdAt) {

    /** 保证关键字段存在，避免空值进入业务判断。 */
    public TenantRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
    }

    /**
     * 判断租户是否可用于登录与业务访问。
     *
     * @return 状态为 {@link TenantStatus#ACTIVE} 时返回 {@code true}
     */
    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
