package com.riccon.api.quota.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 配额指标暴露名测试（ADR-0022 第 1.3/1.7 节、ADR-0021 §10.6、THREAT-MODEL B-20）。
 *
 * <p>覆盖模块评审 F-001：存储故障指标必须暴露为单数 {@code riccon_quota_store_failure_total}，
 * 且保留 {@code reason} 标签；改名必须先改 ADR，再同步本测试。
 */
class QuotaMetricsTest {

    /** ADR 冻结的存储故障指标名（Prometheus 输出名，含 counter 的 {@code _total} 后缀）。 */
    private static final String STORE_FAILURE_METRIC = "riccon_quota_store_failure_total";

    /**
     * 记录存储故障后，抓取文本出现单数指标名与 reason 标签，且不再出现旧的复数名。
     */
    @Test
    void recordStoreFailure_exposesSingularContractNameWithReasonTag() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        QuotaMetrics metrics = new QuotaMetrics(registry);

        metrics.recordStoreFailure("lock_wait");

        assertThat(registry.scrape())
                .as("ADR-0022/ADR-0021 §10.6 冻结的存储故障指标名")
                .contains(STORE_FAILURE_METRIC + "{reason=\"lock_wait\"")
                .doesNotContain("riccon_quota_store_failures_total");
        assertThat(registry.find(QuotaMetrics.STORE_FAILURE)
                        .tag("reason", "lock_wait")
                        .counter())
                .isNotNull()
                .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0d));
    }
}
