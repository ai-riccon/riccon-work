package com.riccon.api.admin.cost.repository;

import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.PlatformCostGroupBy;
import java.util.List;

/**
 * 平台跨租户成本只读查询端口。
 *
 * <p>只提供聚合，不提供调用明细；实现使用 {@code PlatformJdbc}，且不 join {@code app_user}，
 * 从结构上保证不返回用户字段（ADR-0022 §2.4）。
 */
public interface PlatformCostQueryRepository {

    /**
     * 查询范围内合计。
     *
     * @param range 时间范围
     * @param currency 部署币种
     * @param tenantId 可选租户过滤
     * @return 合计
     */
    CostTotalsRow totals(CostRange range, String currency, String tenantId);

    /**
     * 查询分页桶。
     *
     * @param range 时间范围
     * @param groupBy 聚合维度
     * @param currency 部署币种
     * @param tenantId 可选租户过滤
     * @param limit 每页大小
     * @param offset 偏移
     * @return 桶列表
     */
    List<CostBucketRow> buckets(
            CostRange range, PlatformCostGroupBy groupBy, String currency, String tenantId, int limit, int offset);

    /**
     * 统计桶总数。
     *
     * @param range 时间范围
     * @param groupBy 聚合维度
     * @param tenantId 可选租户过滤
     * @return 桶总数
     */
    long bucketCount(CostRange range, PlatformCostGroupBy groupBy, String tenantId);
}
