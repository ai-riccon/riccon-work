package com.riccon.api.admin.cost.dto;

/**
 * 平台成本桶，对应 OpenAPI {@code PlatformCostBucket}。
 *
 * <p>**不包含** {@code userId}/{@code userDisplayName}：平台管理员只做租户/模型/日聚合，
 * 避免跨租户推断个人行为（ADR-0022 §2.4）。字段缺失是结构性的，不依赖前端隐藏。
 *
 * @param key 维度键
 * @param tenantId 租户代码（TENANT 维度）
 * @param tenantCode 租户代码（TENANT 维度）
 * @param modelId 模型 ID（MODEL 维度）
 * @param modelDisplayName 模型展示名（MODEL 维度）
 * @param day 北京时间自然日（DAY 维度）
 * @param amount 6 位小数金额
 * @param pricedCalls 已定价调用数
 * @param unpricedCalls 未定价调用数
 * @param usageUnknownCalls 用量未知调用数
 */
public record PlatformCostBucket(
        String key,
        String tenantId,
        String tenantCode,
        String modelId,
        String modelDisplayName,
        String day,
        String amount,
        long pricedCalls,
        long unpricedCalls,
        long usageUnknownCalls) {}
