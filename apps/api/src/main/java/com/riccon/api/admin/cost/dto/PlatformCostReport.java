package com.riccon.api.admin.cost.dto;

import com.riccon.api.cost.dto.CostTotals;
import java.util.List;

/**
 * 平台跨租户成本报告，对应 OpenAPI {@code PlatformCostReport}。
 *
 * @param page 当前页
 * @param size 每页大小
 * @param total 桶总数
 * @param totalPages 总页数
 * @param from 起始日
 * @param to 结束日
 * @param groupBy 聚合维度
 * @param currency 部署币种
 * @param currencyMinorUnit 币种最小单位
 * @param disclaimer 固定免责文案
 * @param totals 合计
 * @param items 当前页桶（不含用户字段）
 */
public record PlatformCostReport(
        int page,
        int size,
        long total,
        int totalPages,
        String from,
        String to,
        String groupBy,
        String currency,
        int currencyMinorUnit,
        String disclaimer,
        CostTotals totals,
        List<PlatformCostBucket> items) {}
