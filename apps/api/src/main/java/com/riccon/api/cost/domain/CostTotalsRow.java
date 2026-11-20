package com.riccon.api.cost.domain;

import java.math.BigDecimal;

/**
 * 成本报告合计。
 *
 * <p>五个计数互斥且覆盖查询范围内所有终态调用：
 * {@code pricedCalls}（PRICED 且币种匹配）、{@code currencyMismatchCalls}（PRICED 但快照币种不一致）、
 * {@code unpricedCalls}、{@code usageUnknownCalls}、{@code unbucketedCalls}（{@code cost_day IS NULL}）。
 * 未分桶调用与币种不一致调用都不计入金额（ADR-0022 §2.3）。
 *
 * @param amount 6 位小数金额
 * @param pricedCalls 已定价调用数
 * @param unpricedCalls 未定价调用数
 * @param usageUnknownCalls 用量未知调用数
 * @param currencyMismatchCalls 币种不一致调用数
 * @param unbucketedCalls 未分桶调用数
 */
public record CostTotalsRow(
        BigDecimal amount,
        long pricedCalls,
        long unpricedCalls,
        long usageUnknownCalls,
        long currencyMismatchCalls,
        long unbucketedCalls) {}
