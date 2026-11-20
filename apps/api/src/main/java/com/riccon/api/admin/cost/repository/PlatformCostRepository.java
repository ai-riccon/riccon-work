package com.riccon.api.admin.cost.repository;

import com.riccon.api.admin.cost.persistence.PlatformCostJdbc;
import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.PlatformCostGroupBy;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * 平台跨租户成本仓储。
 *
 * <p>只做聚合查询转发；不提供明细方法，防止后续误加用户维度。
 */
@Repository
public class PlatformCostRepository implements PlatformCostQueryRepository {

    /** 平台成本 SQL。 */
    private final PlatformCostJdbc platformCostJdbc;

    /**
     * 创建仓储。
     *
     * @param platformCostJdbc 平台成本 SQL
     */
    public PlatformCostRepository(PlatformCostJdbc platformCostJdbc) {
        this.platformCostJdbc = Objects.requireNonNull(platformCostJdbc, "platformCostJdbc must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public CostTotalsRow totals(CostRange range, String currency, String tenantId) {
        return platformCostJdbc.totals(range, currency, tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public List<CostBucketRow> buckets(
            CostRange range, PlatformCostGroupBy groupBy, String currency, String tenantId, int limit, int offset) {
        return platformCostJdbc.buckets(range, groupBy, currency, tenantId, limit, offset);
    }

    /** {@inheritDoc} */
    @Override
    public long bucketCount(CostRange range, PlatformCostGroupBy groupBy, String tenantId) {
        return platformCostJdbc.bucketCount(range, groupBy, tenantId);
    }
}
