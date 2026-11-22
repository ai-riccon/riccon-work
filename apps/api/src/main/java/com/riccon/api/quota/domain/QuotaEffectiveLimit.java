package com.riccon.api.quota.domain;

import java.util.Objects;

/**
 * 解析后的生效限额。
 *
 * <p>{@code limit == null} 表示不限；此时准入跳过条件比较但仍计数（ADR-0022 第 1.3 节）。
 *
 * @param limit 生效限额；{@code null} 表示不限
 * @param source 限额来源
 */
public record QuotaEffectiveLimit(Integer limit, QuotaLimitSource source) {

    /** 保证来源存在，避免响应缺少 source 字段。 */
    public QuotaEffectiveLimit {
        Objects.requireNonNull(source, "source must not be null");
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
    }

    /**
     * 判断是否不限量。
     *
     * @return 限额为 {@code null} 时返回 {@code true}
     */
    public boolean unlimited() {
        return limit == null;
    }
}
