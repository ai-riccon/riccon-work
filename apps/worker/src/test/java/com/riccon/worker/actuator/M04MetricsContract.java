package com.riccon.worker.actuator;

import java.util.List;
import java.util.Set;

/**
 * M04 Worker 7 个路由/降级/key 指标的冻结约定（ADR-0021 §10.6、威胁模型 TM-54/B-20）。
 *
 * <p>B1c（TASK-0102）只冻结名称与标签，不注册指标；注册由 B4（TASK-0105）在 Worker 内完成，
 * 抓取期断言由 {@code scripts/ops/metrics-check} 执行。ADR、本类与脚本三处清单必须一致：
 * 任何改动先改 ADR-0021 §10.6，再同步本类与脚本，{@link M04MetricsContractTest} 会拦住漂移。
 *
 * <p>标签纪律：禁止 tenant/userId/runId 等高基数标签（ADR-0008、ADR-0021 §10.6）；按租户排查走
 * 日志、审计或 DB 查询，不走指标。
 */
final class M04MetricsContract {

    /** 禁止出现在 M04 指标上的高基数标签；名字含变体，防止不同命名风格绕过检查。 */
    static final Set<String> FORBIDDEN_LABELS =
            Set.of("tenant", "tenantId", "tenant_id", "userId", "user_id", "runId", "run_id", "sessionId");

    /** 7 个 M04 Worker 指标，顺序与 ADR-0021 §10.6 一致。 */
    static final List<Metric> METRICS = List.of(
            new Metric("riccon_model_requests_total", Type.COUNTER, Set.of("provider", "model", "outcome")),
            new Metric("riccon_model_failover_total", Type.COUNTER, Set.of("from", "to", "reason")),
            new Metric("riccon_model_attempt_duration_seconds", Type.HISTOGRAM, Set.of()),
            new Metric("riccon_model_credential_invalid_total", Type.COUNTER, Set.of("provider")),
            new Metric("riccon_model_credential_cooldown_total", Type.COUNTER, Set.of("provider")),
            new Metric("riccon_model_chain_empty_total", Type.COUNTER, Set.of("purpose")),
            new Metric("riccon_agent_runs_inflight", Type.GAUGE, Set.of()));

    private M04MetricsContract() {}

    /**
     * 单个指标的定义。
     *
     * @param name 指标名（Prometheus 文本格式）
     * @param type 指标类型
     * @param requiredLabels 必须出现的标签键；空集合表示 ADR 未约定标签
     */
    record Metric(String name, Type type, Set<String> requiredLabels) {}

    /** 指标类型，只覆盖 M04 会注册的三类。 */
    enum Type {
        /** 单调递增计数器（Prometheus 文本格式带 `_total`）。 */
        COUNTER,
        /** 直方图（文本格式有 `_bucket`/`_sum`/`_count`）。 */
        HISTOGRAM,
        /** 瞬时值。 */
        GAUGE
    }
}
