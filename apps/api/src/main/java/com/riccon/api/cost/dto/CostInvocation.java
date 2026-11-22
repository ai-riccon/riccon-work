package com.riccon.api.cost.dto;

/**
 * 调用成本明细，对应 OpenAPI {@code CostInvocation}。
 *
 * <p>金额与价格使用字符串避免 JSON 浮点误差；{@code UNPRICED}/{@code USAGE_UNKNOWN} 时
 * 价格、币种与金额可为空。
 *
 * @param id 调用 ID
 * @param modelId 模型 ID
 * @param modelDisplayName 模型展示名
 * @param userId 发起人 ID，可为空
 * @param userDisplayName 发起人展示名，可为空
 * @param startedAt 带偏移的 ISO 8601 开始时间
 * @param promptTokens 输入 token，可为空
 * @param completionTokens 输出 token，可为空
 * @param inputPricePer1M 输入价格快照，可为空
 * @param outputPricePer1M 输出价格快照，可为空
 * @param priceCurrency 币种快照，可为空
 * @param amount 金额快照，可为空
 * @param pricingStatus PRICED/UNPRICED/USAGE_UNKNOWN
 */
public record CostInvocation(
        String id,
        String modelId,
        String modelDisplayName,
        String userId,
        String userDisplayName,
        String startedAt,
        Integer promptTokens,
        Integer completionTokens,
        String inputPricePer1M,
        String outputPricePer1M,
        String priceCurrency,
        String amount,
        String pricingStatus) {}
