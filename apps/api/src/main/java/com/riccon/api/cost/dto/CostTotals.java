package com.riccon.api.cost.dto;

/**
 * 成本合计，对应 OpenAPI {@code CostTotals}。
 *
 * @param currency 部署币种
 * @param amount 6 位小数金额字符串；只汇总 PRICED 且币种匹配的调用
 * @param pricedCalls 已定价调用数
 * @param unpricedCalls 未定价调用数
 * @param usageUnknownCalls 用量未知调用数
 * @param currencyMismatchCalls 币种不一致调用数
 * @param unbucketedCalls 未分桶调用数
 */
public record CostTotals(
        String currency,
        String amount,
        long pricedCalls,
        long unpricedCalls,
        long usageUnknownCalls,
        long currencyMismatchCalls,
        long unbucketedCalls) {}
