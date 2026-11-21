package com.riccon.api.quota.service;

import com.riccon.api.quota.domain.QuotaScope;
import com.riccon.api.quota.domain.QuotaWindow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 配额指标登记（ADR-0022 第 1.3/1.7 节）。
 *
 * <p>指标标签只允许 {@code scope} 与 {@code window}，禁止加入租户或用户标签，避免高基数与跨租户泄漏。
 * {@code riccon_quota_usage_ratio} 表示本实例观察到的最大用量比（进程启动后单调不减），用于容量告警的
 * 保守上界；不按租户下钻，也不是精确的实时全局比例。
 */
@Component
public class QuotaMetrics {

    /** 拒绝次数计数器名；Prometheus 输出 {@code riccon_quota_rejections_total}。 */
    public static final String REJECTIONS = "riccon.quota.rejections";

    /** 用量比 Gauge 名；Prometheus 输出 {@code riccon_quota_usage_ratio}。 */
    public static final String USAGE_RATIO = "riccon.quota.usage.ratio";

    /** 关闭执行时观察到的超限量计数器。 */
    public static final String OVER_LIMIT_OBSERVED = "riccon.quota.over.limit.observed";

    /** 存储故障计数器名；Prometheus 输出 {@code riccon_quota_store_failure_total}（ADR-0022、ADR-0021 §10.6）。 */
    public static final String STORE_FAILURE = "riccon.quota.store.failure";

    /** 每个维度/窗口的最大观察比。 */
    private final Map<QuotaScope, Map<QuotaWindow, AtomicReference<Double>>> ratios = new EnumMap<>(QuotaScope.class);

    /** 指标注册表。 */
    private final MeterRegistry registry;

    /**
     * 注册固定标签组合的 Gauge。
     *
     * @param registry Micrometer 注册表
     */
    public QuotaMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        for (QuotaScope scope : QuotaScope.values()) {
            Map<QuotaWindow, AtomicReference<Double>> byWindow = new EnumMap<>(QuotaWindow.class);
            for (QuotaWindow window : QuotaWindow.values()) {
                AtomicReference<Double> value = new AtomicReference<>(0.0d);
                Gauge.builder(USAGE_RATIO, value, AtomicReference::get)
                        .tags("scope", scope.name(), "window", window.name())
                        .description("本实例观察到的最大配额用量比（0 表示不限或未使用）")
                        .register(registry);
                byWindow.put(window, value);
            }
            ratios.put(scope, byWindow);
        }
    }

    /**
     * 记录一次准入后的用量比。
     *
     * @param scope 维度
     * @param window 窗口
     * @param used 累加后的已用量
     * @param limit 生效限额；{@code null} 表示不限
     */
    public void recordAdmission(QuotaScope scope, QuotaWindow window, int used, Integer limit) {
        if (limit == null || limit <= 0) {
            return;
        }
        double ratio = (double) used / (double) limit;
        ratios.get(scope).get(window).updateAndGet(previous -> Math.max(previous, ratio));
    }

    /**
     * 记录一次拒绝。
     *
     * @param scope 维度
     * @param window 窗口
     */
    public void recordRejection(QuotaScope scope, QuotaWindow window) {
        Counter.builder(REJECTIONS)
                .tags("scope", scope.name(), "window", window.name())
                .description("配额拒绝次数")
                .register(registry)
                .increment();
    }

    /**
     * 记录关闭执行时观察到的超限。
     *
     * @param scope 维度
     * @param window 窗口
     */
    public void recordOverLimitObserved(QuotaScope scope, QuotaWindow window) {
        Counter.builder(OVER_LIMIT_OBSERVED)
                .tags("scope", scope.name(), "window", window.name())
                .description("仅计量模式下观察到的超限次数")
                .register(registry)
                .increment();
    }

    /**
     * 记录配额存储故障。
     *
     * @param reason deadlock、lock_wait 或 store
     */
    public void recordStoreFailure(String reason) {
        Counter.builder(STORE_FAILURE)
                .tags("reason", reason)
                .description("配额存储故障次数")
                .register(registry)
                .increment();
    }
}
