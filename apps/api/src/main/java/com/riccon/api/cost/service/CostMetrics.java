package com.riccon.api.cost.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 成本查询指标。
 *
 * <p>只按 {@code group_by} 打标签（USER/MODEL/DAY/TENANT），禁止租户、用户或金额标签，
 * 避免高基数与跨租户泄漏（ADR-0008/ADR-0022 §2.4）。启动时预置 0 值，
 * 保证无流量时 Prometheus 也能看到序列。
 */
@Component
public class CostMetrics {

    /** 指标名；Prometheus 输出 {@code riccon_cost_report_queries_total}。 */
    public static final String REPORT_QUERIES = "riccon.cost.report.queries";

    /** 已注册的计数器，按 groupBy 名称索引。 */
    private final Map<String, Counter> counters;

    /**
     * 注册固定标签组合。
     *
     * @param registry Micrometer 注册表
     */
    public CostMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        Map<String, Counter> registered = new LinkedHashMap<>();
        for (String groupBy : new String[] {"USER", "MODEL", "DAY", "TENANT"}) {
            registered.put(
                    groupBy,
                    Counter.builder(REPORT_QUERIES)
                            .tag("group_by", groupBy)
                            .description("成本报告查询次数")
                            .register(registry));
        }
        this.counters = Map.copyOf(registered);
    }

    /**
     * 记录一次成本报告查询。
     *
     * @param groupBy 聚合维度名称
     */
    public void recordReport(String groupBy) {
        Counter counter = counters.get(groupBy);
        if (counter != null) {
            counter.increment();
        }
    }
}
