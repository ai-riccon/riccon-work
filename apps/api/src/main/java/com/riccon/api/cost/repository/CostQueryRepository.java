package com.riccon.api.cost.repository;

import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostInvocationRow;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.TenantCostGroupBy;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.UUID;

/**
 * 租户成本只读查询端口。
 *
 * <p>服务层依赖本接口以便对查询失败/超时映射做单元测试；生产实现只通过 {@code TenantJdbc} 访问，
 * 所有 SQL 带 {@code tenant_id}，不存在跨租户读取（ADR-0009）。
 */
public interface CostQueryRepository {

    /**
     * 查询范围内合计。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param currency 部署币种
     * @return 合计
     */
    CostTotalsRow totals(TenantId tenantId, CostRange range, String currency);

    /**
     * 查询分页桶。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param groupBy 聚合维度
     * @param currency 部署币种
     * @param limit 每页大小
     * @param offset 偏移
     * @return 桶列表
     */
    List<CostBucketRow> buckets(
            TenantId tenantId, CostRange range, TenantCostGroupBy groupBy, String currency, int limit, int offset);

    /**
     * 统计桶总数。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param groupBy 聚合维度
     * @return 桶总数
     */
    long bucketCount(TenantId tenantId, CostRange range, TenantCostGroupBy groupBy);

    /**
     * 分页查询调用明细。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param userId 用户过滤，可为空
     * @param modelId 模型过滤，可为空
     * @param limit 每页大小
     * @param offset 偏移
     * @return 明细行
     */
    List<CostInvocationRow> invocations(
            TenantId tenantId, CostRange range, UUID userId, UUID modelId, int limit, int offset);

    /**
     * 统计明细总数。
     *
     * @param tenantId 当前租户
     * @param range 时间范围
     * @param userId 用户过滤，可为空
     * @param modelId 模型过滤，可为空
     * @return 总行数
     */
    long invocationCount(TenantId tenantId, CostRange range, UUID userId, UUID modelId);

    /**
     * 判断用户是否属于本租户；跨租户用户返回 false，调用方统一 404。
     *
     * @param tenantId 当前租户
     * @param userId 用户 ID
     * @return 是否存在
     */
    boolean userExists(TenantId tenantId, UUID userId);
}
