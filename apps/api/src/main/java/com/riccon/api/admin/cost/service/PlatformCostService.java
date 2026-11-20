package com.riccon.api.admin.cost.service;

import com.riccon.api.admin.cost.dto.PlatformCostBucket;
import com.riccon.api.admin.cost.dto.PlatformCostReport;
import com.riccon.api.admin.cost.repository.PlatformCostQueryRepository;
import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostInvalidRequestException;
import com.riccon.api.cost.domain.CostPage;
import com.riccon.api.cost.domain.CostQuery;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.CostUnavailableException;
import com.riccon.api.cost.domain.PlatformCostGroupBy;
import com.riccon.api.cost.dto.CostTotals;
import com.riccon.api.cost.service.CostFormat;
import com.riccon.api.cost.service.CostMetrics;
import com.riccon.api.model.catalog.BillingProperties;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台跨租户成本聚合（{@code platform.cost.read}）。
 *
 * <p>只返回 TENANT/MODEL/DAY 聚合，不提供用户明细；可选 {@code tenantId} 过滤仍只返回聚合。
 * 金额只读快照列，口径与租户报告一致。查询保护与 503 映射同租户端（ADR-0022 §2.4/§2.5）。
 */
@Service
public class PlatformCostService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PlatformCostService.class);

    /** 平台成本查询端口。 */
    private final PlatformCostQueryRepository costRepository;

    /** 部署币种。 */
    private final BillingProperties billingProperties;

    /** 指标。 */
    private final CostMetrics costMetrics;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建平台成本服务。
     *
     * @param costRepository 平台成本查询端口
     * @param billingProperties 部署币种
     * @param costMetrics 指标
     * @param clock UTC 时钟
     */
    public PlatformCostService(
            PlatformCostQueryRepository costRepository,
            BillingProperties billingProperties,
            CostMetrics costMetrics,
            Clock clock) {
        this.costRepository = Objects.requireNonNull(costRepository, "costRepository must not be null");
        this.billingProperties = Objects.requireNonNull(billingProperties, "billingProperties must not be null");
        this.costMetrics = Objects.requireNonNull(costMetrics, "costMetrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 查询跨租户成本聚合。
     *
     * @param query 原始查询参数
     * @param tenantId 可选租户过滤
     * @return 平台成本报告
     */
    @Transactional(readOnly = true)
    public PlatformCostReport report(CostQuery query, String tenantId) {
        CostRange range = CostRange.parse(query.from(), query.to(), clock);
        PlatformCostGroupBy groupBy = PlatformCostGroupBy.parse(query.groupBy());
        CostPage page = CostPage.of(query.page(), query.size());
        String filterTenant = normalizeTenantFilter(tenantId);
        String currency = billingProperties.currency();
        costMetrics.recordReport(groupBy.name());
        try {
            CostTotalsRow totals = costRepository.totals(range, currency, filterTenant);
            long total = costRepository.bucketCount(range, groupBy, filterTenant);
            List<CostBucketRow> rows =
                    costRepository.buckets(range, groupBy, currency, filterTenant, page.size(), page.offset());
            return new PlatformCostReport(
                    page.page(),
                    page.size(),
                    total,
                    page.totalPages(total),
                    CostFormat.date(range.from()),
                    CostFormat.date(range.to()),
                    groupBy.name(),
                    currency,
                    CostFormat.currencyMinorUnit(currency),
                    CostFormat.DISCLAIMER,
                    new CostTotals(
                            currency,
                            CostFormat.amount(totals.amount()),
                            totals.pricedCalls(),
                            totals.unpricedCalls(),
                            totals.usageUnknownCalls(),
                            totals.currencyMismatchCalls(),
                            totals.unbucketedCalls()),
                    rows.stream().map(PlatformCostService::toBucket).toList());
        } catch (DataAccessException exception) {
            LOG.error("platform.cost.report.store_failure groupBy={}", groupBy, exception);
            throw new CostUnavailableException();
        }
    }

    /**
     * 校验租户过滤；长度与 OpenAPI {@code tenantId} 一致。
     *
     * @param tenantId 原始值
     * @return 规范化后的值或 null
     */
    private static String normalizeTenantFilter(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        String normalized = tenantId.strip();
        if (normalized.length() > 64) {
            throw new CostInvalidRequestException("tenantId must not exceed 64 characters");
        }
        return normalized;
    }

    /**
     * 组装平台桶；DTO 不含用户字段。
     *
     * @param row 分桶行
     * @return 平台成本桶
     */
    private static PlatformCostBucket toBucket(CostBucketRow row) {
        return new PlatformCostBucket(
                row.key(),
                row.tenantId(),
                row.tenantId(),
                row.modelId(),
                row.modelDisplayName(),
                row.day(),
                CostFormat.amount(row.amount()),
                row.pricedCalls(),
                row.unpricedCalls(),
                row.usageUnknownCalls());
    }
}
