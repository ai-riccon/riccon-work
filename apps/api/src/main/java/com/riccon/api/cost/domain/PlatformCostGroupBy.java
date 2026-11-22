package com.riccon.api.cost.domain;

import java.util.Locale;

/**
 * 平台成本聚合维度。
 *
 * <p>平台管理员只允许 {@code TENANT}/{@code MODEL}/{@code DAY}；不接受 {@code USER}，
 * 避免跨租户推断个人行为（ADR-0022 §2.4）。
 */
public enum PlatformCostGroupBy {
    /** 按租户聚合。 */
    TENANT,
    /** 按模型聚合。 */
    MODEL,
    /** 按 {@code Asia/Shanghai} 自然日聚合。 */
    DAY;

    /**
     * 解析维度；空值默认 {@code DAY}。
     *
     * @param value 查询参数
     * @return 维度
     * @throws CostInvalidRequestException 维度不在枚举内或为 USER
     */
    public static PlatformCostGroupBy parse(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new CostInvalidRequestException("groupBy must be TENANT, MODEL or DAY");
        }
    }
}
