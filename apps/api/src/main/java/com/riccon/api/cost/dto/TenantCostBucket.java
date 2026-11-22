package com.riccon.api.cost.dto;

/**
 * 租户成本桶，对应 OpenAPI {@code TenantCostBucket}。
 *
 * <p>{@code userId}/{@code userDisplayName} 只出现在租户报告；平台报告使用
 * {@link com.riccon.api.admin.cost.dto.PlatformCostBucket}，不含用户字段。
 *
 * @param key 维度键
 * @param tenantId 租户代码
 * @param tenantCode 租户代码（与 tenantId 相同）
 * @param modelId 模型 ID（MODEL 维度）
 * @param modelDisplayName 模型展示名（MODEL 维度）
 * @param userId 用户 ID（USER 维度）
 * @param userDisplayName 用户展示名（USER 维度）
 * @param day 北京时间自然日（DAY 维度）
 * @param amount 6 位小数金额
 * @param pricedCalls 已定价调用数
 * @param unpricedCalls 未定价调用数
 * @param usageUnknownCalls 用量未知调用数
 */
public record TenantCostBucket(
        String key,
        String tenantId,
        String tenantCode,
        String modelId,
        String modelDisplayName,
        String userId,
        String userDisplayName,
        String day,
        String amount,
        long pricedCalls,
        long unpricedCalls,
        long usageUnknownCalls) {}
