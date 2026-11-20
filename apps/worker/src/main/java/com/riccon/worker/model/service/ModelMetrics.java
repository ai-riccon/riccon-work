package com.riccon.worker.model.service;

import com.riccon.platform.domain.model.ModelPurpose;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Worker 7 个模型路由/降级/key 指标（ADR-0021 §10.6）。
 *
 * <p>名称、类型与标签由 B1c 的 `M04MetricsContract` 冻结；本类只负责注册与写入，不做抓取。
 * 启动时预置零值序列：Micrometer 的带标签计数器在无流量时不会出现在抓取结果里，而
 * `scripts/ops/metrics-check` 要求 7 个指标都存在。占位标签值固定为 `none`，不引入
 * tenant/userId/runId 等高基数标签。
 */
@Component
public class ModelMetrics {

    /** 占位标签值；只用于预置零值序列。 */
    static final String NONE = "none";

    /** 模型请求总数（每次上游 HTTP 尝试一次）。 */
    private final Counter requestsPlaceholder;

    /** 降级/重试次数。 */
    private final Counter failoverPlaceholder;

    /** 单次尝试耗时。 */
    private final Timer attemptDuration;

    /** 401/403 自动失效次数。 */
    private final Counter credentialInvalidPlaceholder;

    /** 冷却次数。 */
    private final Counter credentialCooldownPlaceholder;

    /** 链为空次数。 */
    private final Counter chainEmptyChat;

    /** 链为空次数（REPORT）。 */
    private final Counter chainEmptyReport;

    /** Agent 运行并发数。 */
    private final AtomicInteger agentRunsInflight = new AtomicInteger();

    /** 指标注册表。 */
    private final MeterRegistry registry;

    /**
     * 创建并预置指标。
     *
     * @param registry Micrometer 注册表（Worker 使用 Prometheus）
     */
    public ModelMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.requestsPlaceholder = Counter.builder("riccon_model_requests")
                .tag("provider", NONE)
                .tag("model", NONE)
                .tag("outcome", NONE)
                .description("模型请求次数")
                .register(registry);
        this.failoverPlaceholder = Counter.builder("riccon_model_failover")
                .tag("from", NONE)
                .tag("to", NONE)
                .tag("reason", NONE)
                .description("模型降级/重试次数")
                .register(registry);
        this.attemptDuration = Timer.builder("riccon_model_attempt_duration")
                .description("单次模型尝试耗时")
                // ADR-0021 §10.6 约定为 histogram；Prometheus registry 只有开启分位直方图才输出 _bucket。
                .publishPercentileHistogram()
                .register(registry);
        // Micrometer/Prometheus 在 Timer 无样本时不输出 _bucket 序列，而 metrics-check 要求 histogram
        // 在无流量时也存在；记录一次 0 耗时样本是“预置 0 值”的最小代价（sum 仍为 0）。
        this.attemptDuration.record(Duration.ZERO);
        this.credentialInvalidPlaceholder = Counter.builder("riccon_model_credential_invalid")
                .tag("provider", NONE)
                .description("key 自动失效次数")
                .register(registry);
        this.credentialCooldownPlaceholder = Counter.builder("riccon_model_credential_cooldown")
                .tag("provider", NONE)
                .description("key 冷却次数")
                .register(registry);
        this.chainEmptyChat = chainEmptyCounter(ModelPurpose.CHAT);
        this.chainEmptyReport = chainEmptyCounter(ModelPurpose.REPORT);
        Gauge.builder("riccon_agent_runs_inflight", agentRunsInflight, AtomicInteger::get)
                .description("Worker 内活跃 Agent 运行数")
                .register(registry);
    }

    /**
     * 记录一次上游尝试结果。
     *
     * @param providerCode 供应商代码（非密钥）
     * @param modelKey 上游模型标识
     * @param outcome 结果：success/failure/cancelled
     */
    public void request(String providerCode, String modelKey, String outcome) {
        Counter.builder("riccon_model_requests")
                .tag("provider", safe(providerCode))
                .tag("model", safe(modelKey))
                .tag("outcome", safe(outcome))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次降级或同 key 重试。
     *
     * @param fromModelId 来源模型 ID
     * @param toModelId 目标模型 ID；同 key 重试时相同
     * @param reason 触发原因
     */
    public void failover(String fromModelId, String toModelId, String reason) {
        Counter.builder("riccon_model_failover")
                .tag("from", safe(fromModelId))
                .tag("to", safe(toModelId))
                .tag("reason", safe(reason))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次尝试耗时。
     *
     * @param duration 耗时
     */
    public void attemptDuration(Duration duration) {
        attemptDuration.record(duration == null || duration.isNegative() ? Duration.ZERO : duration);
    }

    /**
     * 记录一次 key 自动失效。
     *
     * @param providerCode 供应商代码
     */
    public void credentialInvalid(String providerCode) {
        Counter.builder("riccon_model_credential_invalid")
                .tag("provider", safe(providerCode))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次 key 冷却事件。
     *
     * <p>口径是“冷却事件近似次数”，不是唯一 key 数或冷却区间数：429 每次延长冷却都会计数，
     * 并发达到失败阈值时可能多计；告警按趋势而非精确值使用（OPS-M04-02a）。
     *
     * @param providerCode 供应商代码
     */
    public void credentialCooldown(String providerCode) {
        Counter.builder("riccon_model_credential_cooldown")
                .tag("provider", safe(providerCode))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次链为空。
     *
     * @param purpose 用途
     */
    public void chainEmpty(ModelPurpose purpose) {
        (purpose == ModelPurpose.REPORT ? chainEmptyReport : chainEmptyChat).increment();
    }

    /** Agent 运行开始，inflight +1。 */
    public void agentRunStarted() {
        agentRunsInflight.incrementAndGet();
    }

    /** Agent 运行结束，inflight -1，最低为 0。 */
    public void agentRunFinished() {
        agentRunsInflight.updateAndGet(value -> Math.max(0, value - 1));
    }

    /**
     * 返回当前 inflight 值，供测试与诊断。
     *
     * @return 活跃运行数
     */
    int agentRunsInflight() {
        return agentRunsInflight.get();
    }

    /**
     * 创建指定用途的链为空计数器（预置 0 值）。
     *
     * @param purpose 用途
     * @return 计数器
     */
    private Counter chainEmptyCounter(ModelPurpose purpose) {
        return Counter.builder("riccon_model_chain_empty")
                .tag("purpose", purpose.name())
                .description("模型链为空次数")
                .register(registry);
    }

    /**
     * 清理标签值，避免 null 触发 Micrometer 异常。
     *
     * @param value 原始值
     * @return 非空值
     */
    private static String safe(String value) {
        return value == null || value.isBlank() ? NONE : value;
    }
}
