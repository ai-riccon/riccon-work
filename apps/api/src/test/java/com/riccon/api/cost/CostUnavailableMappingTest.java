package com.riccon.api.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.admin.cost.repository.PlatformCostQueryRepository;
import com.riccon.api.admin.cost.service.PlatformCostService;
import com.riccon.api.cost.domain.CostBucketRow;
import com.riccon.api.cost.domain.CostInvocationRow;
import com.riccon.api.cost.domain.CostQuery;
import com.riccon.api.cost.domain.CostRange;
import com.riccon.api.cost.domain.CostTotalsRow;
import com.riccon.api.cost.domain.CostUnavailableException;
import com.riccon.api.cost.domain.PlatformCostGroupBy;
import com.riccon.api.cost.domain.TenantCostGroupBy;
import com.riccon.api.cost.repository.CostQueryRepository;
import com.riccon.api.cost.service.CostMetrics;
import com.riccon.api.cost.service.CostQueryService;
import com.riccon.api.model.catalog.BillingProperties;
import com.riccon.platform.common.tenant.TenantId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;

/**
 * 成本查询超时/失败 → 503 的边界（ADR-0022 §2.5）。
 *
 * <p>不启动 Spring：用手写端口实现模拟 MySQL 执行超时，断言两类服务都映射为
 * {@code RICCON-COST-503}，不会返回部分结果。
 */
class CostUnavailableMappingTest {

    /** 固定时钟；默认范围可预测。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC);

    /**
     * 租户报告查询超时返回 503。
     */
    @Test
    @DisplayName("租户成本查询超时映射为 RICCON-COST-503")
    void tenantReport_timeoutMapsTo503() {
        CostQueryService service = new CostQueryService(
                new TimeoutTenantRepository(), billing(), new CostMetrics(new SimpleMeterRegistry()), CLOCK);

        assertThatThrownBy(() ->
                        service.report(TenantId.of("tenant-a"), new CostQuery(null, null, "DAY", 1, 20, null, null)))
                .isInstanceOfSatisfying(CostUnavailableException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("RICCON-COST-503");
                });
    }

    /**
     * 租户明细查询超时返回 503。
     */
    @Test
    @DisplayName("租户成本明细超时映射为 RICCON-COST-503")
    void tenantInvocations_timeoutMapsTo503() {
        CostQueryService service = new CostQueryService(
                new TimeoutTenantRepository(), billing(), new CostMetrics(new SimpleMeterRegistry()), CLOCK);

        assertThatThrownBy(() -> service.invocations(
                        TenantId.of("tenant-a"), new CostQuery(null, null, null, 1, 20, null, null)))
                .isInstanceOfSatisfying(
                        CostUnavailableException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    /**
     * 平台报告查询超时返回 503。
     */
    @Test
    @DisplayName("平台成本查询超时映射为 RICCON-COST-503")
    void platformReport_timeoutMapsTo503() {
        PlatformCostService service = new PlatformCostService(
                new TimeoutPlatformRepository(), billing(), new CostMetrics(new SimpleMeterRegistry()), CLOCK);

        assertThatThrownBy(() -> service.report(new CostQuery(null, null, "TENANT", 1, 20, null, null), null))
                .isInstanceOfSatisfying(
                        CostUnavailableException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    /**
     * 构造 CNY 计费配置。
     *
     * @return 配置
     */
    private static BillingProperties billing() {
        BillingProperties properties = new BillingProperties();
        properties.setCurrency("CNY");
        properties.afterPropertiesSet();
        return properties;
    }

    /** 所有查询都模拟超时的租户端口。 */
    private static final class TimeoutTenantRepository implements CostQueryRepository {

        @Override
        public CostTotalsRow totals(TenantId tenantId, CostRange range, String currency) {
            throw new QueryTimeoutException("query timeout");
        }

        @Override
        public List<CostBucketRow> buckets(
                TenantId tenantId, CostRange range, TenantCostGroupBy groupBy, String currency, int limit, int offset) {
            throw new QueryTimeoutException("query timeout");
        }

        @Override
        public long bucketCount(TenantId tenantId, CostRange range, TenantCostGroupBy groupBy) {
            throw new QueryTimeoutException("query timeout");
        }

        @Override
        public List<CostInvocationRow> invocations(
                TenantId tenantId, CostRange range, UUID userId, UUID modelId, int limit, int offset) {
            throw new QueryTimeoutException("query timeout");
        }

        @Override
        public long invocationCount(TenantId tenantId, CostRange range, UUID userId, UUID modelId) {
            throw new QueryTimeoutException("query timeout");
        }

        @Override
        public boolean userExists(TenantId tenantId, UUID userId) {
            throw new QueryTimeoutException("query timeout");
        }
    }

    /** 所有查询都模拟超时的平台端口。 */
    private static final class TimeoutPlatformRepository implements PlatformCostQueryRepository {

        @Override
        public CostTotalsRow totals(CostRange range, String currency, String tenantId) {
            throw new QueryTimeoutException("query timeout");
        }

        @Override
        public List<CostBucketRow> buckets(
                CostRange range, PlatformCostGroupBy groupBy, String currency, String tenantId, int limit, int offset) {
            throw new QueryTimeoutException("query timeout");
        }

        @Override
        public long bucketCount(CostRange range, PlatformCostGroupBy groupBy, String tenantId) {
            throw new QueryTimeoutException("query timeout");
        }
    }
}
