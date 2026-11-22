package com.riccon.api.cost.repository;

import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostInvocationRow;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.TenantCostGroupBy;
import com.riccon.api.cost.persistence.CostJdbc;
import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 租户成本只读仓储。
 *
 * <p>只做查询转发；金额口径与权限边界在服务层。所有 SQL 位于 {@code ..persistence..} 并经过
 * {@code TenantJdbc}。
 */
@Repository
public class CostRepository implements CostQueryRepository {

    /** 成本 SQL。 */
    private final CostJdbc costJdbc;

    /**
     * 创建仓储。
     *
     * @param costJdbc 成本 SQL
     */
    public CostRepository(CostJdbc costJdbc) {
        this.costJdbc = Objects.requireNonNull(costJdbc, "costJdbc must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public CostTotalsRow totals(TenantId tenantId, CostRange range, String currency) {
        return costJdbc.totals(tenantId, range, currency);
    }

    /** {@inheritDoc} */
    @Override
    public List<CostBucketRow> buckets(
            TenantId tenantId, CostRange range, TenantCostGroupBy groupBy, String currency, int limit, int offset) {
        return costJdbc.buckets(tenantId, range, groupBy, currency, limit, offset);
    }

    /** {@inheritDoc} */
    @Override
    public long bucketCount(TenantId tenantId, CostRange range, TenantCostGroupBy groupBy) {
        return costJdbc.bucketCount(tenantId, range, groupBy);
    }

    /** {@inheritDoc} */
    @Override
    public List<CostInvocationRow> invocations(
            TenantId tenantId, CostRange range, UUID userId, UUID modelId, int limit, int offset) {
        return costJdbc.invocations(tenantId, range, userId, modelId, limit, offset);
    }

    /** {@inheritDoc} */
    @Override
    public long invocationCount(TenantId tenantId, CostRange range, UUID userId, UUID modelId) {
        return costJdbc.invocationCount(tenantId, range, userId, modelId);
    }

    /** {@inheritDoc} */
    @Override
    public boolean userExists(TenantId tenantId, UUID userId) {
        return costJdbc.userExists(tenantId, userId);
    }
}
