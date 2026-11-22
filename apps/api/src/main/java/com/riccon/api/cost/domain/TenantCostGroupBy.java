package com.riccon.api.cost.domain;

import java.util.Locale;

/**
 * 租户成本聚合维度。
 *
 * <p>租户管理员可切换 {@code USER}/{@code MODEL}/{@code DAY}；默认 {@code DAY}。
 * 平台管理员不接受 {@code USER}（{@link PlatformCostGroupBy}）。
 */
public enum TenantCostGroupBy {
    /** 按本租户用户聚合（含显示名）。 */
    USER,
    /** 按模型聚合。 */
    MODEL,
    /** 按 {@code Asia/Shanghai} 自然日聚合。 */
    DAY;

    /**
     * 解析维度；空值默认 {@code DAY}。
     *
     * @param value 查询参数
     * @return 维度
     * @throws CostInvalidRequestException 维度不在枚举内
     */
    public static TenantCostGroupBy parse(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new CostInvalidRequestException("groupBy must be USER, MODEL or DAY");
        }
    }
}
