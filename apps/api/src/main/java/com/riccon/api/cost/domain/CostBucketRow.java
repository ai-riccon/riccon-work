package com.riccon.api.cost.domain;

import java.math.BigDecimal;

/**
 * 聚合查询返回的单个分桶行（数据库无关）。
 *
 * <p>桶内金额只汇总 {@code PRICED} 且币种匹配的调用；未定价、用量未知与未分桶调用不进 items，
 * 只在 totals 单列（ADR-0022 §2.3/§2.4）。不同维度只填充相关字段，其余为空。
 *
 * @param key 维度键：日、模型 ID、用户 ID 或租户代码
 * @param tenantId 租户（平台 TENANT 维度）
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
public record CostBucketRow(
        String key,
        String tenantId,
        String modelId,
        String modelDisplayName,
        String userId,
        String userDisplayName,
        String day,
        BigDecimal amount,
        long pricedCalls,
        long unpricedCalls,
        long usageUnknownCalls) {}
