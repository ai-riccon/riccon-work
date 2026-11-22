package com.riccon.api.cost.service;

import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostInvocationRow;
import com.riccon.api.cost.domain.CostPage;
import com.riccon.api.cost.domain.CostQuery;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.CostUnavailableException;
import com.riccon.api.cost.domain.TenantCostGroupBy;
import com.riccon.api.cost.dto.CostInvocation;
import com.riccon.api.cost.dto.CostInvocationPage;
import com.riccon.api.cost.dto.CostTotals;
import com.riccon.api.cost.dto.TenantCostBucket;
import com.riccon.api.cost.dto.TenantCostReport;
import com.riccon.api.cost.repository.CostQueryRepository;
import com.riccon.api.error.NotFoundException;
import com.riccon.api.model.catalog.BillingProperties;
import com.riccon.platform.common.tenant.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户成本报告与调用明细（{@code cost.read}）。
 *
 * <p>只返回本租户数据；租户来自会话主体，不读取查询参数中的租户。金额只读调用时的快照列，
 * 不 join 当前模型价格，因此价格更新不会改写历史金额（ADR-0022 §2.1）。
 *
 * <p>查询保护：默认最近 30 天、最大 180 天，page 1..200、size 1..100；数据库超时或失败统一
 * 映射为 503，不返回部分结果。成本查看不写审计（ADR-0022 §2.4）。
 */
@Service
public class CostQueryService {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CostQueryService.class);

    /** 成本查询端口。 */
    private final CostQueryRepository costRepository;

    /** 部署币种。 */
    private final BillingProperties billingProperties;

    /** 指标。 */
    private final CostMetrics costMetrics;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建租户成本服务。
     *
     * @param costRepository 成本查询端口
     * @param billingProperties 部署币种
     * @param costMetrics 指标
     * @param clock UTC 时钟
     */
    public CostQueryService(
            CostQueryRepository costRepository,
            BillingProperties billingProperties,
            CostMetrics costMetrics,
            Clock clock) {
        this.costRepository = Objects.requireNonNull(costRepository, "costRepository must not be null");
        this.billingProperties = Objects.requireNonNull(billingProperties, "billingProperties must not be null");
        this.costMetrics = Objects.requireNonNull(costMetrics, "costMetrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 查询本租户成本聚合。
     *
     * @param tenantId 当前租户
     * @param query 原始查询参数
     * @return 租户成本报告
     */
    @Transactional(readOnly = true)
    public TenantCostReport report(TenantId tenantId, CostQuery query) {
        CostRange range = CostRange.parse(query.from(), query.to(), clock);
        TenantCostGroupBy groupBy = TenantCostGroupBy.parse(query.groupBy());
        CostPage page = CostPage.of(query.page(), query.size());
        String currency = billingProperties.currency();
        costMetrics.recordReport(groupBy.name());
        try {
            CostTotalsRow totals = costRepository.totals(tenantId, range, currency);
            long total = costRepository.bucketCount(tenantId, range, groupBy);
            List<CostBucketRow> rows =
                    costRepository.buckets(tenantId, range, groupBy, currency, page.size(), page.offset());
            return new TenantCostReport(
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
                    rows.stream().map(row -> toBucket(tenantId, row)).toList());
        } catch (DataAccessException exception) {
            LOG.error("cost.report.store_failure tenantId={} groupBy={}", tenantId.value(), groupBy, exception);
            throw new CostUnavailableException();
        }
    }

    /**
     * 查询本租户调用成本明细。
     *
     * @param tenantId 当前租户
     * @param query 原始查询参数
     * @return 明细分页
     */
    @Transactional(readOnly = true)
    public CostInvocationPage invocations(TenantId tenantId, CostQuery query) {
        CostRange range = CostRange.parse(query.from(), query.to(), clock);
        CostPage page = CostPage.of(query.page(), query.size());
        try {
            if (query.userId() != null && !costRepository.userExists(tenantId, query.userId())) {
                // 不区分“用户不存在”和“属于其他租户”，与普通 404 同构（ADR-0009 §5）。
                throw new NotFoundException("cost user");
            }
            long total = costRepository.invocationCount(tenantId, range, query.userId(), query.modelId());
            List<CostInvocationRow> rows = costRepository.invocations(
                    tenantId, range, query.userId(), query.modelId(), page.size(), page.offset());
            return new CostInvocationPage(
                    page.page(),
                    page.size(),
                    total,
                    page.totalPages(total),
                    rows.stream().map(this::toInvocation).toList());
        } catch (DataAccessException exception) {
            LOG.error("cost.invocations.store_failure tenantId={}", tenantId.value(), exception);
            throw new CostUnavailableException();
        }
    }

    /**
     * 组装租户桶；租户字段始终为本租户。
     *
     * @param tenantId 当前租户
     * @param row 分桶行
     * @return 租户成本桶
     */
    private static TenantCostBucket toBucket(TenantId tenantId, CostBucketRow row) {
        return new TenantCostBucket(
                row.key(),
                tenantId.value(),
                tenantId.value(),
                row.modelId(),
                row.modelDisplayName(),
                row.userId(),
                row.userDisplayName(),
                row.day(),
                CostFormat.amount(row.amount()),
                row.pricedCalls(),
                row.unpricedCalls(),
                row.usageUnknownCalls());
    }

    /**
     * 组装调用明细；金额与价格按 6 位小数字符串输出。
     *
     * @param row 明细行
     * @return 明细 DTO
     */
    private CostInvocation toInvocation(CostInvocationRow row) {
        return new CostInvocation(
                row.id().toString(),
                row.modelId().toString(),
                row.modelDisplayName(),
                row.userId() == null ? null : row.userId().toString(),
                row.userDisplayName(),
                CostFormat.timestamp(row.startedAt()),
                row.promptTokens(),
                row.completionTokens(),
                CostFormat.price(row.inputPricePer1M()),
                CostFormat.price(row.outputPricePer1M()),
                row.priceCurrency(),
                row.amount() == null ? null : CostFormat.amount(row.amount()),
                row.pricingStatus());
    }
}
