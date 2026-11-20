package com.riccon.api.cost.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 调用成本明细行（数据库无关）。
 *
 * <p>价格、币种、金额只来自调用时快照，不 join 当前模型价格；历史未定价/未知行保持空值。
 *
 * @param id 调用 ID
 * @param modelId 模型 ID
 * @param modelDisplayName 模型展示名
 * @param userId 发起人 ID，可为空
 * @param userDisplayName 发起人展示名，可为空
 * @param startedAt 开始瞬时
 * @param promptTokens 输入 token，可为空
 * @param completionTokens 输出 token，可为空
 * @param inputPricePer1M 输入价格快照，可为空
 * @param outputPricePer1M 输出价格快照，可为空
 * @param priceCurrency 币种快照，可为空
 * @param amount 金额快照，可为空
 * @param pricingStatus PRICED/UNPRICED/USAGE_UNKNOWN
 */
public record CostInvocationRow(
        UUID id,
        UUID modelId,
        String modelDisplayName,
        UUID userId,
        String userDisplayName,
        Instant startedAt,
        Integer promptTokens,
        Integer completionTokens,
        BigDecimal inputPricePer1M,
        BigDecimal outputPricePer1M,
        String priceCurrency,
        BigDecimal amount,
        String pricingStatus) {}
